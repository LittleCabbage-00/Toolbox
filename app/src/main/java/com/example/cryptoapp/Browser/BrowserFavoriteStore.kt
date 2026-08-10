package com.example.cryptoapp.Browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BrowserFavorite(val id: Long, val title: String, val url: String, val createdAt: Long)

/** 轻量收藏夹仓库，统一处理去重、搜索和 JSON 持久化。 */
class BrowserFavoriteStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("browser_state", Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(): List<BrowserFavorite> = parse(preferences.getString(KEY, "[]"))

    @Synchronized
    fun search(keyword: String): List<BrowserFavorite> {
        val query = keyword.trim()
        return if (query.isEmpty()) getAll() else getAll().filter {
            it.title.contains(query, true) || it.url.contains(query, true)
        }
    }

    @Synchronized
    fun add(title: String?, url: String?): Boolean {
        val safeUrl = url?.trim().orEmpty()
        if (safeUrl.isEmpty() || safeUrl.startsWith("about:")) return false
        val existed = getAll().any { it.url == safeUrl }
        val now = System.currentTimeMillis()
        val updated = buildList {
            add(BrowserFavorite(now, title?.takeIf(String::isNotBlank) ?: safeUrl, safeUrl, now))
            addAll(getAll().filterNot { it.url == safeUrl })
        }
        save(updated)
        return !existed
    }

    @Synchronized fun delete(id: Long) = save(getAll().filterNot { it.id == id })
    @Synchronized fun clear() = preferences.edit().remove(KEY).apply()

    private fun save(items: List<BrowserFavorite>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().apply {
            put("id", item.id); put("title", item.title); put("url", item.url); put("time", item.createdAt)
        }) }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private fun parse(raw: String?): List<BrowserFavorite> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val time = item.optLong("time", System.currentTimeMillis())
                val url = item.optString("url", "")
                if (url.isNotBlank()) add(BrowserFavorite(
                    item.optLong("id", time), item.optString("title", url), url, time
                ))
            }
        }
    }.getOrDefault(emptyList())

    companion object { private const val KEY = "favorites" }
}
