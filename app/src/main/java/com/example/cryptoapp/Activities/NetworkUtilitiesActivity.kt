package com.example.cryptoapp.Activities

import android.os.Bundle
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.databinding.ActivityNetworkUtilitiesBinding
import com.google.android.material.snackbar.Snackbar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 网络辅助工具：URL 解析、DNS 查询、HTTPS 证书检查。 */
class NetworkUtilitiesActivity : BaseActivity() {
    private lateinit var binding: ActivityNetworkUtilitiesBinding
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkUtilitiesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.parseUrlButton.setOnClickListener { parseUrl() }
        binding.dnsButton.setOnClickListener { resolveDns() }
        binding.certButton.setOnClickListener { checkCertificate() }
        binding.traceRouteButton.setOnClickListener { traceRoute() }
        binding.copyButton.setOnClickListener {
            val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            manager.setPrimaryClip(
                android.content.ClipData.newPlainText("Toolbox network utilities", binding.resultText.text)
            )
            message("结果已复制")
        }
    }

    private fun urlInput(): String {
        val raw = binding.input.text?.toString()?.trim().orEmpty()
        require(raw.isNotBlank()) { "请输入网址或域名" }
        return if (raw.contains("://")) raw else "https://$raw"
    }

    private fun parseUrl() = runCatching {
        val input = urlInput()
        val uri = URI(input)
        binding.resultText.text = buildString {
            appendLine("【URL 解析】")
            appendLine("完整地址：$input")
            appendLine("协议：${uri.scheme.orEmpty().ifEmpty { "（无）" }}")
            val userInfo = uri.rawUserInfo
            if (userInfo.isNullOrEmpty()) {
                appendLine("用户信息：无")
            } else {
                val user = userInfo.substringBefore(':')
                val hasPassword = userInfo.contains(':')
                appendLine("用户信息：$user${if (hasPassword) ":****（密码已隐藏）" else ""}")
            }
            appendLine("主机：${uri.host.orEmpty().ifEmpty { "（无）" }}")
            val port = uri.port
            appendLine("端口：${if (port < 0) "默认（${defaultPort(uri.scheme)}）" else port}")
            appendLine("路径（编码）：${uri.rawPath.orEmpty().ifEmpty { "/" }}")
            appendLine("路径（解码）：${uri.path.orEmpty().ifEmpty { "/" }}")
            val rawQuery = uri.rawQuery
            if (rawQuery.isNullOrEmpty()) {
                appendLine("查询参数：无")
            } else {
                appendLine("查询参数（原始）：$rawQuery")
                appendLine("查询参数明细：")
                rawQuery.split("&").forEach { pair ->
                    val index = pair.indexOf('=')
                    val key = if (index >= 0) pair.substring(0, index) else pair
                    val value = if (index >= 0) pair.substring(index + 1) else ""
                    appendLine("  ${decode(key)} = ${decode(value).ifEmpty { "（空值）" }}")
                }
            }
            appendLine("片段：${uri.rawFragment.orEmpty().ifEmpty { "无" }}")
            appendLine("是否为绝对地址：${if (uri.isAbsolute) "是" else "否"}")
        }
    }.onFailure { message(it.message ?: "网址格式无效") }

    private fun defaultPort(scheme: String?): String = when (scheme?.lowercase(Locale.ROOT)) {
        "http" -> "80"
        "https" -> "443"
        "ftp" -> "21"
        "ws" -> "80"
        "wss" -> "443"
        else -> "未知"
    }

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun resolveDns() = background("正在查询 DNS…") {
        val input = urlInput()
        val host = URI(input).host ?: error("无法识别域名")
        val start = System.currentTimeMillis()
        val addresses = InetAddress.getAllByName(host)
        val elapsed = System.currentTimeMillis() - start
        val distinct = addresses.distinctBy { it.hostAddress }
        buildString {
            appendLine("【DNS 查询】")
            appendLine("域名：$host")
            appendLine("耗时：$elapsed ms")
            appendLine("解析结果：${distinct.size} 条")
            distinct.forEachIndexed { index, address ->
                val type = when (address) {
                    is Inet4Address -> "IPv4"
                    is Inet6Address -> "IPv6"
                    else -> "未知"
                }
                appendLine("  ${index + 1}. $type ${address.hostAddress}")
                val canonical = address.canonicalHostName
                if (!canonical.isNullOrEmpty() && canonical != address.hostAddress && canonical != host) {
                    appendLine("     主机名：$canonical")
                }
            }
        }.trim()
    }

    private fun checkCertificate() = background("正在检查证书…") {
        val input = urlInput()
        client.newCall(Request.Builder().url(input).head().build()).execute().use { response ->
            val handshake = response.handshake ?: error("非 HTTPS 连接，没有证书")
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val now = Date()
            buildString {
                appendLine("【HTTPS 证书检查】")
                appendLine("地址：$input")
                appendLine("状态：HTTP ${response.code}")
                appendLine("TLS 版本：${handshake.tlsVersion.javaName}")
                appendLine("加密套件：${handshake.cipherSuite.javaName}")
                handshake.peerCertificates.filterIsInstance<X509Certificate>()
                    .forEachIndexed { index, cert ->
                        appendLine("证书 ${index + 1}：")
                        appendLine("  主题：${cert.subjectX500Principal.name}")
                        appendLine("  签发者：${cert.issuerX500Principal.name}")
                        appendLine("  序列号：${cert.serialNumber}")
                        appendLine("  有效期：${format.format(cert.notBefore)} ~ ${format.format(cert.notAfter)}")
                        val expired = cert.notAfter.before(now)
                        val notYet = cert.notBefore.after(now)
                        val days = (cert.notAfter.time - now.time) / 86_400_000L
                        val state = when {
                            expired -> "已过期"
                            notYet -> "尚未生效"
                            days < 14 -> "即将过期（剩余 $days 天）"
                            else -> "有效（剩余 $days 天）"
                        }
                        appendLine("  状态：$state")
                    }
            }.trim()
        }
    }

    private fun traceRoute() {
        val input = runCatching { urlInput() }.getOrElse {
            message(it.message ?: "请输入网址或域名")
            return
        }
        val host = runCatching { URI(input).host }.getOrNull()
        if (host.isNullOrBlank()) {
            message("无法识别域名")
            return
        }
        binding.resultText.text = "【路由跟踪】\n目标：$host\n正在逐跳探测…"
        worker.execute {
            val output = StringBuilder("【路由跟踪】\n目标：$host\n")
            output.appendLine("提示：运营商或 VPN 屏蔽 ICMP 时，中间跳点显示超时属正常")
            for (ttl in 1..30) {
                if (isDestroyed || isFinishing) break
                val (line, reached) = probeHop(host, ttl)
                output.appendLine("${ttl.toString().padStart(2)}  $line")
                runOnUiThread { if (!isDestroyed && !isFinishing) binding.resultText.text = output.toString() }
                if (reached) break
            }
        }
    }

    private fun probeHop(host: String, ttl: Int): Pair<String, Boolean> {
        val process = try {
            ProcessBuilder("/system/bin/ping", "-c", "1", "-t", ttl.toString(), "-W", "1", host)
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            return "无法执行 ping：${error.message}" to false
        }
        val text = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (error: IOException) {
            process.destroy()
            return "读取输出失败：${error.message}" to false
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
        } catch (_: InterruptedException) {
            process.destroy()
        }
        val hopIp = Regex("""from ([0-9a-fA-F:.]+)""")
        val hopTime = Regex("""time=([0-9.]+)\s*ms""")
        val line = text.lineSequence().firstOrNull { hopIp.containsMatchIn(it) }
        if (line == null) {
            val hint = text.lineSequence().firstOrNull { it.contains("unknown") || it.contains("not found") }
            return (hint ?: "超时（无响应）") to false
        }
        val ip = hopIp.find(line)?.groupValues?.get(1) ?: "?"
        val time = hopTime.find(line)?.groupValues?.get(1)
        val reached = line.contains(" time=")
        val detail = time?.let { "，$it ms" } ?: ""
        return (if (reached) "$ip（到达目标$detail）" else "$ip（跳点$detail）") to reached
    }

    private fun background(progress: String, action: () -> String) {
        binding.resultText.text = progress
        worker.execute {
            val result = runCatching(action)
            runOnUiThread { binding.resultText.text = result.getOrElse { "操作失败：${it.message.orEmpty()}" } }
        }
    }

    private fun message(value: String) = Snackbar.make(binding.root, value, Snackbar.LENGTH_SHORT).show()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
