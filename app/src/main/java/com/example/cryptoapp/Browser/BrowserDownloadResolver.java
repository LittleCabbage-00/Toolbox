package com.example.cryptoapp.Browser;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将网页下载信息整理成安全的文件名和 MIME 类型。
 *
 * 解析顺序为 Content-Disposition、URL 路径、默认名称；扩展名和 MIME 类型会互相补全。
 * 这个类不依赖 Android API，便于在本地单元测试中覆盖服务器响应不规范的情况。
 */
public final class BrowserDownloadResolver {
    private static final Pattern UTF8_FILE_NAME = Pattern.compile(
            "filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_NAME = Pattern.compile(
            "filename\\s*=\\s*\\\"?([^\\\";]+)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> MIME_TO_EXTENSION = new HashMap<>();
    private static final Map<String, String> EXTENSION_TO_MIME = new HashMap<>();

    static {
        register("application/pdf", "pdf");
        register("application/zip", "zip");
        register("application/x-7z-compressed", "7z");
        register("application/x-rar-compressed", "rar");
        register("application/gzip", "gz");
        register("application/json", "json");
        register("application/xml", "xml");
        register("application/vnd.android.package-archive", "apk");
        register("application/msword", "doc");
        register("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        register("application/vnd.ms-excel", "xls");
        register("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        register("application/vnd.ms-powerpoint", "ppt");
        register("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        register("text/plain", "txt");
        register("text/html", "html");
        register("text/csv", "csv");
        register("image/jpeg", "jpg");
        register("image/png", "png");
        register("image/gif", "gif");
        register("image/webp", "webp");
        register("image/avif", "avif");
        register("image/bmp", "bmp");
        register("image/x-icon", "ico");
        register("image/heic", "heic");
        register("image/heif", "heif");
        register("image/tiff", "tiff");
        register("image/svg+xml", "svg");
        register("audio/mpeg", "mp3");
        register("audio/mp4", "m4a");
        register("video/mp4", "mp4");
        register("video/webm", "webm");
    }

    private BrowserDownloadResolver() {}

    public static DownloadInfo resolve(String url, String contentDisposition, String mimeType) {
        String normalizedMime = normalizeMime(mimeType);
        String fileName = fileNameFromDisposition(contentDisposition);
        if (fileName.isEmpty()) fileName = fileNameFromUrl(url);
        if (fileName.isEmpty()) fileName = "download_" + System.currentTimeMillis();
        fileName = sanitize(fileName);

        String extension = extensionOf(fileName);
        if (extension.isEmpty()) {
            String inferred = MIME_TO_EXTENSION.get(normalizedMime);
            if (inferred != null) {
                fileName += "." + inferred;
                extension = inferred;
            }
        }
        if (normalizedMime.isEmpty() || "application/octet-stream".equals(normalizedMime)) {
            normalizedMime = EXTENSION_TO_MIME.getOrDefault(extension, "application/octet-stream");
        }
        return new DownloadInfo(fileName, normalizedMime);
    }

    private static void register(String mime, String extension) {
        MIME_TO_EXTENSION.put(mime, extension);
        EXTENSION_TO_MIME.put(extension, mime);
    }

    private static String normalizeMime(String value) {
        if (value == null) return "";
        int separator = value.indexOf(';');
        return (separator >= 0 ? value.substring(0, separator) : value).trim().toLowerCase(Locale.ROOT);
    }

    private static String fileNameFromDisposition(String disposition) {
        if (disposition == null) return "";
        Matcher utf8 = UTF8_FILE_NAME.matcher(disposition);
        if (utf8.find()) return decode(utf8.group(1).replace("\"", "").trim());
        Matcher plain = FILE_NAME.matcher(disposition);
        return plain.find() ? decode(plain.group(1).trim()) : "";
    }

    private static String fileNameFromUrl(String url) {
        try {
            String path = new URI(url).getPath();
            if (path == null || path.endsWith("/")) return "";
            return decode(path.substring(path.lastIndexOf('/') + 1));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    private static String sanitize(String value) {
        String clean = value.replaceAll("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]", "_").trim();
        clean = clean.replaceAll("^[. ]+|[. ]+$", "");
        if (clean.isEmpty()) return "download_" + System.currentTimeMillis();
        return clean.length() > 180 ? clean.substring(0, 180) : clean;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static final class DownloadInfo {
        private final String fileName;
        private final String mimeType;

        DownloadInfo(String fileName, String mimeType) {
            this.fileName = fileName;
            this.mimeType = mimeType;
        }

        public String getFileName() { return fileName; }
        public String getMimeType() { return mimeType; }
    }
}
