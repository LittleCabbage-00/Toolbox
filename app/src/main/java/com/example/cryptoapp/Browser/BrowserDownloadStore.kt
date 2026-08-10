package com.example.cryptoapp.Browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 下载记录仓库：服务和下载管理页共享同一份持久化状态。 */
class BrowserDownloadStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(): List<DownloadRecord> {
        val records = parse(preferences.getString(KEY_RECORDS, "[]"))
        records.sortByDescending { it.updatedAt }
        return records
    }

    @Synchronized
    fun upsert(record: DownloadRecord) {
        val records = parse(preferences.getString(KEY_RECORDS, "[]"))
        records.removeAll { it.id == record.id }
        records.add(0, record)
        while (records.size > MAX_RECORDS) records.removeAt(records.size - 1)
        save(records)
    }

    @Synchronized
    fun clearFinished() {
        val records = parse(preferences.getString(KEY_RECORDS, "[]"))
        records.removeAll { it.status != STATUS_DOWNLOADING && it.status != STATUS_PAUSED }
        save(records)
    }

    @Synchronized
    fun delete(id: Long) {
        val records = parse(preferences.getString(KEY_RECORDS, "[]"))
        records.removeAll { it.id == id }
        save(records)
    }

    private fun save(records: List<DownloadRecord>) {
        val array = JSONArray()
        try {
            for (record in records) {
                array.put(JSONObject()
                    .put("id", record.id).put("name", record.fileName).put("url", record.url)
                    .put("mime", record.mimeType).put("status", record.status)
                    .put("downloaded", record.downloaded).put("total", record.total)
                    .put("uri", record.localUri).put("error", record.error)
                    .put("created", record.createdAt).put("updated", record.updatedAt))
            }
        } catch (ignored: Exception) {
            return
        }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun parse(raw: String?): ArrayList<DownloadRecord> {
        val result = ArrayList<DownloadRecord>()
        try {
            val array = JSONArray(raw ?: "[]")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                result.add(DownloadRecord(
                    item.optLong("id"), item.optString("name", "网页文件"),
                    item.optString("url"), item.optString("mime", "application/octet-stream"),
                    item.optString("status", STATUS_FAILED), item.optLong("downloaded"),
                    item.optLong("total", -1), item.optString("uri"), item.optString("error"),
                    item.optLong("created"), item.optLong("updated")))
            }
        } catch (ignored: Exception) { }
        return result
    }

    data class DownloadRecord(
        val id: Long,
        val fileName: String,
        val url: String,
        val mimeType: String,
        val status: String,
        val downloaded: Long,
        val total: Long,
        val localUri: String,
        val error: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    companion object {
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        private const val PREFERENCES = "browser_downloads"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 100
    }
}
