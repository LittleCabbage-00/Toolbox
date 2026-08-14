package com.example.cryptoapp.Browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 轻量 ABP 网络规则拦截器。
 *
 * 域名型规则（例如 ||ads.example^）进入 HashSet，并按父域逐级匹配；简单 URL 通配规则
 * 则被拆成连续片段，只在域名规则未命中时再检查。这样不需要为每个子资源遍历十万条正则。
 */
class AdBlockRuleStore private constructor(private val appContext: Context) {
    private val preferences = appContext.getSharedPreferences(BrowserPreferences.CONFIG, Context.MODE_PRIVATE)
    private val state = AtomicReference<RuleSet?>(null)
    private val lock = Any()

    fun isEnabled(): Boolean = preferences.getBoolean(BrowserPreferences.BLOCK_WEB_ADS, true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(BrowserPreferences.BLOCK_WEB_ADS, enabled).apply()
    }

    fun shouldBlock(url: String): Boolean {
        if (!isEnabled()) return false
        val host = try { Uri.parse(url).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null } ?: return false
        return rules().matches(host, url.lowercase(Locale.ROOT))
    }

    fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
    )

    fun status(): Status {
        val rules = rules()
        return Status(rules.blocked.size, rules.updatedAt, rules.source)
    }

    /** 在调用方的后台线程执行。失败时保留当前/内置规则。 */
    fun update(): Result {
        val client = OkHttpClient.Builder().build()
        val texts = ArrayList<String>()
        try {
            for (source in SOURCES) {
                client.newCall(Request.Builder().url(source).header("User-Agent", "Toolbox/3.0").build()).execute().use { response ->
                    if (!response.isSuccessful) return Result(false, "规则下载失败：HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    if (!body.contains("[Adblock Plus")) return Result(false, "规则文件格式无效")
                    texts += body
                }
            }
            val combined = texts.joinToString("\n\n! Toolbox combined list\n")
            val parsed = parse(combined, System.currentTimeMillis(), "EasyList + EasyList China（已更新）")
            if (parsed.blocked.size < MIN_RULE_COUNT) return Result(false, "规则数量异常，未替换当前规则")
            val directory = File(appContext.filesDir, "adblock")
            if (!directory.exists() && !directory.mkdirs()) return Result(false, "无法创建规则目录")
            val target = File(directory, RULE_FILE)
            val temporary = File(directory, "$RULE_FILE.tmp")
            FileOutputStream(temporary).bufferedWriter(Charsets.UTF_8).use { it.write(combined) }
            if (target.exists() && !target.delete()) return Result(false, "无法替换旧规则")
            if (!temporary.renameTo(target)) return Result(false, "无法保存新规则")
            preferences.edit().putLong(BrowserPreferences.AD_BLOCK_UPDATED_AT, parsed.updatedAt).apply()
            state.set(parsed)
            return Result(true, "已更新 ${parsed.blocked.size} 条网络拦截规则")
        } catch (error: Exception) {
            return Result(false, "更新失败：${error.localizedMessage ?: "网络不可用"}")
        }
    }

    private fun rules(): RuleSet {
        state.get()?.let { return it }
        synchronized(lock) {
            state.get()?.let { return it }
            val downloaded = File(File(appContext.filesDir, "adblock"), RULE_FILE)
            val result = if (downloaded.isFile) {
                runCatching { parse(downloaded.readText(Charsets.UTF_8), downloaded.lastModified(), "本地更新规则") }.getOrNull()
            } else null
            val resolved = result?.takeIf { it.blocked.size >= MIN_RULE_COUNT } ?: loadBundled()
            state.set(resolved)
            return resolved
        }
    }

    private fun loadBundled(): RuleSet {
        val text = buildString {
            appContext.assets.open("easylist.txt").bufferedReader().use { append(it.readText()) }
            append('\n')
            appContext.assets.open("easylistchina.txt").bufferedReader().use { append(it.readText()) }
        }
        return parse(text, 0L, "内置 EasyList + EasyList China")
    }

    private fun parse(text: String, updatedAt: Long, source: String): RuleSet {
        val blocked = HashSet<String>()
        val exceptions = HashSet<String>()
        val pathRules = ArrayList<UrlPatternRule>()
        text.lineSequence().forEach { raw ->
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith('!') || line.startsWith('[') || line.contains("##")) return@forEach
            val exception = line.startsWith("@@")
            if (exception) line = line.substring(2)
            val optionsAt = line.indexOf('$')
            if (optionsAt >= 0) {
                val options = line.substring(optionsAt + 1)
                // 依赖具体页面域名、资源类型的规则不能安全地全局复用，跳过以避免误拦。
                if (options.contains("domain=", true) || options.contains("third-party", true)
                    || options.contains("~third-party", true)) return@forEach
                line = line.substring(0, optionsAt)
            }
            if (line.startsWith("||")) {
                val rule = line.substring(2)
                val host = rule.substringBefore('^').substringBefore('/').lowercase(Locale.ROOT)
                val tail = rule.substring(host.length)
                if (HOST_PATTERN.matches(host) && (tail.isEmpty() || tail == "^")) {
                    if (exception) exceptions += host else blocked += host
                }
            } else if (!exception && line.contains('/') && !line.contains("|")) {
                UrlPatternRule.from(line)?.let { pathRules += it }
            }
        }
        return RuleSet(blocked, exceptions, pathRules, updatedAt, source)
    }

    data class Status(val count: Int, val updatedAt: Long, val source: String)
    data class Result(val success: Boolean, val message: String)

    private data class RuleSet(
        val blocked: Set<String>,
        val exceptions: Set<String>,
        val pathRules: List<UrlPatternRule>,
        val updatedAt: Long,
        val source: String
    ) {
        fun matches(host: String, url: String): Boolean {
            var candidate = host
            while (true) {
                if (candidate in exceptions) return false
                if (candidate in blocked) return true
                val dot = candidate.indexOf('.')
                if (dot < 0) break
                candidate = candidate.substring(dot + 1)
            }
            return pathRules.any { it.matches(url) }
        }
    }

    /** 仅保留安全且常见的通配 URL 规则，用片段顺序匹配替代正则。 */
    private data class UrlPatternRule(private val parts: List<String>) {
        fun matches(url: String): Boolean {
            var position = 0
            for (part in parts) {
                position = url.indexOf(part, position)
                if (position < 0) return false
                position += part.length
            }
            return true
        }

        companion object {
            fun from(raw: String): UrlPatternRule? {
                val parts = raw.lowercase(Locale.ROOT).split('*').filter { it.length >= 3 }
                return if (parts.isEmpty() || parts.size > 4) null else UrlPatternRule(parts)
            }
        }
    }

    companion object {
        private const val RULE_FILE = "rules.txt"
        private const val MIN_RULE_COUNT = 1_000
        private val HOST_PATTERN = Regex("^[a-z0-9][a-z0-9.-]*[a-z0-9]$")
        private val SOURCES = listOf(
            "https://easylist.to/easylist/easylist.txt",
            // EasyList China 当前公开发布地址；easylist.to 的 chinalist.txt 历史路径已下线。
            "https://easylist-downloads.adblockplus.org/easylistchina.txt"
        )
        @Volatile private var instance: AdBlockRuleStore? = null
        fun get(context: Context): AdBlockRuleStore = instance ?: synchronized(this) {
            instance ?: AdBlockRuleStore(context.applicationContext).also { instance = it }
        }
    }
}
