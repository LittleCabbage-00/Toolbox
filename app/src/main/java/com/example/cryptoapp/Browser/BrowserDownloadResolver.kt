package com.example.cryptoapp.Browser

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * 将网页下载信息整理成安全的文件名和 MIME 类型。
 *
 * 解析顺序为 Content-Disposition、URL 路径、默认名称；扩展名和 MIME 类型会互相补全。
 * 这个类不依赖 Android API，便于在本地单元测试中覆盖服务器响应不规范的情况。
 */
class BrowserDownloadResolver private constructor() {
    companion object {
        private val UTF8_FILE_NAME = Pattern.compile(
            "filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)", Pattern.CASE_INSENSITIVE)
        private val FILE_NAME = Pattern.compile(
            "filename\\s*=\\s*\\\"?([^\\\";]+)", Pattern.CASE_INSENSITIVE)
        private val MIME_TO_EXTENSION = HashMap<String, String>()
        private val EXTENSION_TO_MIME = HashMap<String, String>()

        init {
            register("application/pdf", "pdf")
            register("application/zip", "zip")
            register("application/x-7z-compressed", "7z")
            register("application/x-rar-compressed", "rar")
            register("application/gzip", "gz")
            register("application/json", "json")
            register("application/xml", "xml")
            register("application/vnd.android.package-archive", "apk")
            register("application/msword", "doc")
            register("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
            register("application/vnd.ms-excel", "xls")
            register("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
            register("application/vnd.ms-powerpoint", "ppt")
            register("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx")
            register("text/plain", "txt")
            register("text/html", "html")
            register("text/csv", "csv")
            register("image/jpeg", "jpg")
            register("image/png", "png")
            register("image/gif", "gif")
            register("image/webp", "webp")
            register("image/avif", "avif")
            register("image/bmp", "bmp")
            register("image/x-icon", "ico")
            register("image/heic", "heic")
            register("image/heif", "heif")
            register("image/tiff", "tiff")
            register("image/svg+xml", "svg")
            register("audio/mpeg", "mp3")
            register("audio/mp4", "m4a")
            register("video/mp4", "mp4")
            register("video/webm", "webm")
        }

        private fun register(mime: String, extension: String) {
            MIME_TO_EXTENSION[mime] = extension
            EXTENSION_TO_MIME[extension] = mime
        }

        @JvmStatic
        fun resolve(url: String, contentDisposition: String?, mimeType: String?): DownloadInfo {
            var normalizedMime = normalizeMime(mimeType)
            var fileName = fileNameFromDisposition(contentDisposition)
            if (fileName.isEmpty()) fileName = fileNameFromUrl(url)
            if (fileName.isEmpty()) fileName = "download_" + System.currentTimeMillis()
            fileName = sanitize(fileName)

            var extension = extensionOf(fileName)
            if (extension.isEmpty()) {
                val inferred = MIME_TO_EXTENSION[normalizedMime]
                if (inferred != null) {
                    fileName += "." + inferred
                    extension = inferred
                }
            }
            if (normalizedMime.isEmpty() || "application/octet-stream" == normalizedMime) {
                normalizedMime = EXTENSION_TO_MIME[extension] ?: "application/octet-stream"
            }
            return DownloadInfo(fileName, normalizedMime)
        }

        private fun normalizeMime(value: String?): String {
            if (value == null) return ""
            val separator = value.indexOf(';')
            return (if (separator >= 0) value.substring(0, separator) else value).trim()
                .lowercase(Locale.ROOT)
        }

        private fun fileNameFromDisposition(disposition: String?): String {
            if (disposition == null) return ""
            val utf8 = UTF8_FILE_NAME.matcher(disposition)
            if (utf8.find()) return decode(utf8.group(1).replace("\"", "").trim())
            val plain = FILE_NAME.matcher(disposition)
            return if (plain.find()) decode(plain.group(1).trim()) else ""
        }

        private fun fileNameFromUrl(url: String): String = try {
            val path = URI(url).path
            if (path == null || path.endsWith("/")) ""
            else decode(path.substring(path.lastIndexOf('/') + 1))
        } catch (ignored: Exception) {
            ""
        }

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (ignored: Exception) {
            value
        }

        private fun sanitize(value: String): String {
            var clean = value.replace(Regex("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]"), "_").trim()
            clean = clean.replace(Regex("^[. ]+|[. ]+$"), "")
            if (clean.isEmpty()) return "download_" + System.currentTimeMillis()
            return if (clean.length > 180) clean.substring(0, 180) else clean
        }

        private fun extensionOf(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0 || dot == fileName.length - 1) return ""
            return fileName.substring(dot + 1).lowercase(Locale.ROOT)
        }
    }

    class DownloadInfo(val fileName: String, val mimeType: String)
}
