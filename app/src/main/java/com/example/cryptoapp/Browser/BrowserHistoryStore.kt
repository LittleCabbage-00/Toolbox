package com.example.cryptoapp.Browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 浏览器历史记录的数据对象。时间戳同时作为稳定 ID，便于单条删除。 */
data class BrowserHistoryEntry(
    val id: Long,
    val title: String,
    val url: String,
    val visitedAt: Long
)

/**
 * 浏览器历史记录仓库。
 *
 * 当前数据量上限为 300 条，使用 SharedPreferences 可以保持实现轻量；所有序列化细节集中
 * 在这里，Activity 和 WebView 不直接接触 JSON，后续迁移 Room 时无需修改界面层。
 */
class BrowserHistoryStore(context: Context) {
    private val settings = context.applicationContext
        .getSharedPreferences(BrowserPreferences.CONFIG, Context.MODE_PRIVATE)
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(): List<BrowserHistoryEntry> = parse(preferences.getString(KEY_HISTORY, "[]"))

    @Synchronized
    fun search(keyword: String): List<BrowserHistoryEntry> {
        val query = keyword.trim()
        if (query.isEmpty()) return getAll()
        return getAll().filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
    }

    @Synchronized
    fun add(title: String?, url: String?) {
        if (!settings.getBoolean(BrowserPreferences.SAVE_HISTORY, true)) return
        val safeUrl = url?.trim().orEmpty()
        if (safeUrl.isEmpty() || safeUrl.startsWith("about:")) return
        val now = System.currentTimeMillis()
        val entry = BrowserHistoryEntry(now, title?.takeIf { it.isNotBlank() } ?: safeUrl, safeUrl, now)
        val updated = buildList {
            add(entry)
            addAll(getAll().filterNot { it.url == safeUrl }.take(MAX_HISTORY - 1))
        }
        save(updated)
    }

    @Synchronized
    fun delete(id: Long) = save(getAll().filterNot { it.id == id })

    @Synchronized
    fun clear() = preferences.edit().remove(KEY_HISTORY).apply()

    private fun save(entries: List<BrowserHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("title", entry.title)
                put("url", entry.url)
                put("time", entry.visitedAt)
            })
        }
        preferences.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun parse(raw: String?): List<BrowserHistoryEntry> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val time = item.optLong("time", System.currentTimeMillis())
                add(
                    BrowserHistoryEntry(
                        id = item.optLong("id", time),
                        title = item.optString("title", "网页"),
                        url = item.optString("url", ""),
                        visitedAt = time
                    )
                )
            }
        }.filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    companion object {
        private const val PREFERENCES_NAME = "browser_state"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 300
    }
}
