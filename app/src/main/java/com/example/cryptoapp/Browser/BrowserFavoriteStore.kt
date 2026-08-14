package com.example.cryptoapp.Browser

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class BrowserFavorite(
    val id: Long,
    val title: String,
    val url: String,
    val createdAt: Long,
    val folderId: Long? = null,
    val icon: String? = null
)

data class BrowserFavoriteFolder(val id: Long, val name: String, val createdAt: Long)

/** 轻量收藏夹仓库：收藏、文件夹和 favicon 均保存在应用私有偏好中。 */
class BrowserFavoriteStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("browser_state", Context.MODE_PRIVATE)

    @Synchronized fun getAll(): List<BrowserFavorite> = parseFavorites(preferences.getString(KEY, "[]"))
    @Synchronized fun getFolders(): List<BrowserFavoriteFolder> = parseFolders(preferences.getString(FOLDERS_KEY, "[]"))
    @Synchronized fun search(keyword: String): List<BrowserFavorite> {
        val query = keyword.trim()
        return if (query.isEmpty()) getAll() else getAll().filter {
            it.title.contains(query, true) || it.url.contains(query, true)
        }
    }

    @Synchronized fun add(title: String?, url: String?, folderId: Long? = null): Boolean {
        val safeUrl = url?.trim().orEmpty()
        if (safeUrl.isEmpty() || safeUrl.startsWith("about:")) return false
        val existed = getAll().firstOrNull { it.url == safeUrl }
        val now = System.currentTimeMillis()
        val item = BrowserFavorite(existed?.id ?: now, title?.trim().takeUnless { it.isNullOrEmpty() } ?: safeUrl,
            safeUrl, existed?.createdAt ?: now, folderId ?: existed?.folderId, existed?.icon)
        saveFavorites(listOf(item) + getAll().filterNot { it.url == safeUrl })
        return existed == null
    }

    @Synchronized fun update(id: Long, title: String, url: String, folderId: Long?) {
        val safeUrl = url.trim()
        require(safeUrl.isNotEmpty()) { "网址不能为空" }
        saveFavorites(getAll().map { old -> if (old.id == id) old.copy(
            title = title.trim().ifEmpty { safeUrl }, url = safeUrl, folderId = folderId
        ) else old })
    }

    @Synchronized fun addFolder(name: String): BrowserFavoriteFolder? {
        val safeName = name.trim()
        if (safeName.isEmpty() || getFolders().any { it.name.equals(safeName, true) }) return null
        val folder = BrowserFavoriteFolder(System.currentTimeMillis(), safeName, System.currentTimeMillis())
        saveFolders(listOf(folder) + getFolders())
        return folder
    }

    @Synchronized fun deleteFolder(id: Long) {
        saveFolders(getFolders().filterNot { it.id == id })
        saveFavorites(getAll().map { if (it.folderId == id) it.copy(folderId = null) else it })
    }
    @Synchronized fun delete(id: Long) = saveFavorites(getAll().filterNot { it.id == id })
    @Synchronized fun clear() = preferences.edit().remove(KEY).apply()

    /** 仅重排传入书签在原列表中的相对顺序，不改变其他文件夹条目及其位置。 */
    @Synchronized fun reorder(ids: List<Long>) {
        if (ids.size < 2) return
        val current = getAll()
        val ordered = ids.mapNotNull { id -> current.firstOrNull { it.id == id } }.toMutableList()
        if (ordered.size != ids.distinct().size) return
        saveFavorites(current.map { item -> if (item.id in ids) ordered.removeAt(0) else item })
    }

    /** WebChromeClient 收到网站图标后更新对应收藏；压缩后的 PNG 仅保存小图标。 */
    @Synchronized fun updateIcon(url: String?, bitmap: Bitmap?) {
        val target = url?.trim().orEmpty()
        if (target.isEmpty() || bitmap == null || getAll().none { it.url == target }) return
        val stream = ByteArrayOutputStream()
        val scaled = if (bitmap.width > 64 || bitmap.height > 64) Bitmap.createScaledBitmap(bitmap, 64, 64, true) else bitmap
        scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
        val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        saveFavorites(getAll().map { if (it.url == target) it.copy(icon = encoded) else it })
    }

    private fun saveFavorites(items: List<BrowserFavorite>) = preferences.edit().putString(KEY, JSONArray().apply {
        items.forEach { put(JSONObject().apply {
            put("id", it.id); put("title", it.title); put("url", it.url); put("time", it.createdAt)
            it.folderId?.let { folder -> put("folder", folder) }; it.icon?.let { value -> put("icon", value) }
        }) }
    }.toString()).apply()

    private fun saveFolders(items: List<BrowserFavoriteFolder>) = preferences.edit().putString(FOLDERS_KEY, JSONArray().apply {
        items.forEach { put(JSONObject().apply { put("id", it.id); put("name", it.name); put("time", it.createdAt) }) }
    }.toString()).apply()

    private fun parseFavorites(raw: String?): List<BrowserFavorite> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index -> array.optJSONObject(index) }.mapNotNull { item ->
            item ?: return@mapNotNull null
            val time = item.optLong("time", System.currentTimeMillis()); val url = item.optString("url", "")
            if (url.isBlank()) null else BrowserFavorite(item.optLong("id", time), item.optString("title", url), url,
                time, if (item.has("folder")) item.optLong("folder") else null, item.optString("icon").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    private fun parseFolders(raw: String?): List<BrowserFavoriteFolder> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index -> array.optJSONObject(index) }.mapNotNull { item ->
            item?.let { BrowserFavoriteFolder(it.optLong("id"), it.optString("name"), it.optLong("time")) }
        }
    }.getOrDefault(emptyList())

    companion object { private const val KEY = "favorites"; private const val FOLDERS_KEY = "favorite_folders" }
}
