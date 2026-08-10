package com.example.cryptoapp.Browser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 下载记录仓库：服务和下载管理页共享同一份持久化状态。 */
public final class BrowserDownloadStore {
    public static final String STATUS_DOWNLOADING = "downloading";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    private static final String PREFERENCES = "browser_downloads";
    private static final String KEY_RECORDS = "records";
    private static final int MAX_RECORDS = 100;

    private final android.content.SharedPreferences preferences;

    public BrowserDownloadStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public synchronized List<DownloadRecord> getAll() {
        ArrayList<DownloadRecord> records = parse(preferences.getString(KEY_RECORDS, "[]"));
        records.sort(Comparator.comparingLong(DownloadRecord::getUpdatedAt).reversed());
        return records;
    }

    public synchronized void upsert(DownloadRecord record) {
        ArrayList<DownloadRecord> records = parse(preferences.getString(KEY_RECORDS, "[]"));
        records.removeIf(item -> item.id == record.id);
        records.add(0, record);
        while (records.size() > MAX_RECORDS) records.remove(records.size() - 1);
        save(records);
    }

    public synchronized void clearFinished() {
        ArrayList<DownloadRecord> records = parse(preferences.getString(KEY_RECORDS, "[]"));
        records.removeIf(item -> !STATUS_DOWNLOADING.equals(item.status) && !STATUS_PAUSED.equals(item.status));
        save(records);
    }

    public synchronized void delete(long id) {
        ArrayList<DownloadRecord> records = parse(preferences.getString(KEY_RECORDS, "[]"));
        records.removeIf(item -> item.id == id);
        save(records);
    }

    private void save(List<DownloadRecord> records) {
        JSONArray array = new JSONArray();
        try {
            for (DownloadRecord record : records) {
                array.put(new JSONObject()
                        .put("id", record.id).put("name", record.fileName).put("url", record.url)
                        .put("mime", record.mimeType).put("status", record.status)
                        .put("downloaded", record.downloaded).put("total", record.total)
                        .put("uri", record.localUri).put("error", record.error)
                        .put("created", record.createdAt).put("updated", record.updatedAt));
            }
        } catch (Exception ignored) {
            return;
        }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply();
    }

    private ArrayList<DownloadRecord> parse(String raw) {
        ArrayList<DownloadRecord> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                result.add(new DownloadRecord(item.optLong("id"), item.optString("name", "网页文件"),
                        item.optString("url"), item.optString("mime", "application/octet-stream"),
                        item.optString("status", STATUS_FAILED), item.optLong("downloaded"),
                        item.optLong("total", -1), item.optString("uri"), item.optString("error"),
                        item.optLong("created"), item.optLong("updated")));
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static final class DownloadRecord {
        private final long id;
        private final String fileName;
        private final String url;
        private final String mimeType;
        private final String status;
        private final long downloaded;
        private final long total;
        private final String localUri;
        private final String error;
        private final long createdAt;
        private final long updatedAt;

        public DownloadRecord(long id, String fileName, String url, String mimeType, String status,
                              long downloaded, long total, String localUri, String error,
                              long createdAt, long updatedAt) {
            this.id = id; this.fileName = fileName; this.url = url; this.mimeType = mimeType;
            this.status = status; this.downloaded = downloaded; this.total = total;
            this.localUri = localUri; this.error = error; this.createdAt = createdAt; this.updatedAt = updatedAt;
        }

        public long getId() { return id; }
        public String getFileName() { return fileName; }
        public String getUrl() { return url; }
        public String getMimeType() { return mimeType; }
        public String getStatus() { return status; }
        public long getDownloaded() { return downloaded; }
        public long getTotal() { return total; }
        public String getLocalUri() { return localUri; }
        public String getError() { return error; }
        public long getCreatedAt() { return createdAt; }
        public long getUpdatedAt() { return updatedAt; }
    }
}
