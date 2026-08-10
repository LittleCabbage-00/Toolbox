package com.example.cryptoapp.Browser

import java.net.URI
import java.util.LinkedHashMap
import java.util.Locale

/**
 * 将 WebView 捕获到的 HLS 播放列表和大量媒体分片归并成可操作的资源。
 * 同一条流只展示一次；下载时优先把 master/index.m3u8 交给 FFmpeg，由其按清单顺序合并全部分片。
 */
class MediaResourceAggregator private constructor() {
    companion object {
        private val GENERIC_NAMES = HashSet<String>()
        private val QUALITY_NAMES = HashSet<String>()

        init {
            GENERIC_NAMES.addAll(listOf("master", "index", "playlist", "manifest", "media",
                "video", "stream", "chunklist", "prog_index", "live"))
            QUALITY_NAMES.addAll(listOf("144", "240", "360", "480", "540", "576", "720",
                "1080", "1440", "2160", "4320", "144p", "240p", "360p", "480p", "540p",
                "576p", "720p", "1080p", "1440p", "2160p", "4320p"))
        }

        // 预编译正则：streamKey 在每次媒体请求与嗅探收集时都会被调用，若在方法内临时
        // new Regex(...) 会反复触发 Pattern.compile 的原生编译与对象分配——这正是日志里
        // GC 压力与主线程卡顿的主要来源之一。提升为伴生常量后每次调用零分配。
        private val VARIANT_SUFFIX =
            Regex("(?i)(?:[-_](?:144|240|360|480|540|576|720|1080|1440|2160|4320)p?)$")
        private val DIGITS_ONLY = Regex("\\d{9,}")
        private val UUID_LIKE = Regex("(?i)[0-9a-f]{8}-[0-9a-f-]{27,}")
        private val RANDOM_TOKEN = Regex("(?i)[a-z0-9_-]+")

        @JvmStatic
        fun aggregate(source: Collection<String>): List<Resource> {
            val hlsGroups = LinkedHashMap<String, HlsGroup>()
            val direct = LinkedHashMap<String, Resource>()
            for (url in source) {
                if (url.isNullOrBlank()) continue
                val isManifest = isHlsManifest(url)
                if (isManifest || isHlsSegment(url)) {
                    val key = streamKey(url)
                    val group = hlsGroups.getOrPut(key) { HlsGroup() }
                    if (isManifest) group.addManifest(url)
                    else group.segments.add(url)
                } else {
                    direct.putIfAbsent(url, Resource(url, false, 0, 0, "媒体文件"))
                }
            }

            val result = ArrayList<Resource>()
            for (group in hlsGroups.values) {
                val manifest = group.bestManifest
                if (manifest != null) {
                    result.add(Resource(manifest, true, group.segments.size, group.manifestCount,
                        "HLS 视频流"))
                } else if (group.segments.isNotEmpty()) {
                    // 没捕获到播放列表时仍合并成一个提示项，避免数百个分片淹没界面。
                    result.add(Resource(null, true, group.segments.size, 0,
                        "HLS 分片组（等待播放列表）"))
                }
            }
            result.sortWith { a, b ->
                val ha = if (a.hls) 0 else 1
                val hb = if (b.hls) 0 else 1
                if (ha != hb) ha - hb
                else {
                    val ra = if (a.isReady()) 0 else 1
                    val rb = if (b.isReady()) 0 else 1
                    if (ra != rb) ra - rb
                    else b.segmentCount - a.segmentCount
                }
            }
            result.addAll(direct.values)
            return result
        }

        @JvmStatic
        fun isHlsManifest(url: String): Boolean {
            val path = cleanPath(url)
            return path.endsWith(".m3u8") || path.contains(".m3u8/")
        }

        @JvmStatic
        fun isHlsSegment(url: String): Boolean {
            val path = cleanPath(url)
            return path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".cmfv")
                || path.endsWith(".cmfa") || path.endsWith(".m2ts")
        }

        private fun cleanPath(url: String): String = try {
            val path = URI(url).path
            path?.lowercase(Locale.ROOT) ?: ""
        } catch (ignored: Exception) {
            val query = url.indexOf('?')
            (if (query >= 0) url.substring(0, query) else url).lowercase(Locale.ROOT)
        }

        /**
         * 为播放器会话生成稳定流标识。算法从路径末端寻找内容 ID，跳过清晰度、时间戳、
         * CDN 临时 token 和 master/index 等通用名称，因此签名刷新或播放分片增长不会产生新条目。
         */
        @JvmStatic
        fun streamKey(url: String): String {
            return try {
                val uri = URI(url)
                val authority = (uri.scheme ?: "").lowercase(Locale.ROOT) + "://" +
                    (uri.authority ?: "").lowercase(Locale.ROOT)
                val path = uri.path ?: ""
                val raw = path.split("/")
                val parts = ArrayList<String>()
                for (part in raw) if (part.isNotEmpty()) parts.add(part)
                if (parts.isEmpty()) return authority

                val file = parts[parts.size - 1]
                var stem = stripExtension(file).lowercase(Locale.ROOT)
                if (isHlsManifest(url)) {
                    stem = stripVariant(stem)
                    if (isStableIdentity(stem)) return authority + "|media:" + stem
                }

                // 分片使用所在内容目录；通用清单也从父目录向上寻找稳定内容 ID。
                for (index in parts.size - 2 downTo 0) {
                    val candidate = parts[index].lowercase(Locale.ROOT)
                    if (isStableIdentity(candidate)) return authority + "|media:" + candidate
                }
                authority + "|path:" + (if (parts.size > 1) parts[parts.size - 2] else stem)
            } catch (ignored: Exception) {
                // 子串定位比 split(正则) 快得多，且是回退路径，不必再编译正则。
                val clean = url.substringBefore('?')
                val slash = clean.lastIndexOf('/')
                if (slash > 0) clean.substring(0, slash) else clean
            }
        }

        private fun stripExtension(value: String): String {
            val dot = value.lastIndexOf('.')
            return if (dot > 0) value.substring(0, dot) else value
        }

        private fun stripVariant(value: String): String =
            value.replaceFirst(VARIANT_SUFFIX, "")

        private fun isStableIdentity(value: String): Boolean {
            if (value.length < 2 || GENERIC_NAMES.contains(value) || QUALITY_NAMES.contains(value)) return false
            if (value.matches(DIGITS_ONLY)) return false // 时间戳或过期时间
            if (value.matches(UUID_LIKE)) return false
            // 长随机串通常是 CDN 鉴权 token；带语义分隔符的普通 slug 不受影响。
            return value.length < 20 || !value.matches(RANDOM_TOKEN)
        }

        private fun manifestScore(url: String): Int {
            val path = cleanPath(url)
            var score = 0
            if (path.contains("master")) score += 1000
            if (path.contains("index")) score += 500
            if (path.contains("playlist")) score += 300
            // 同等情况下优先上层清单，它通常包含全部清晰度和音轨。
            score -= path.split("/").size * 5
            return score
        }
    }

    private class HlsGroup {
        val segments = ArrayList<String>()
        var bestManifest: String? = null
        var bestScore = Int.MIN_VALUE
        var manifestCount = 0

        fun addManifest(url: String) {
            manifestCount++
            val score = manifestScore(url)
            // 同分时选择最后捕获的 URL，避免继续使用已经过期的 CDN 签名。
            if (bestManifest == null || score >= bestScore) {
                bestManifest = url
                bestScore = score
            }
        }
    }

    class Resource(
        @JvmField val url: String?,
        @JvmField val hls: Boolean,
        @JvmField val segmentCount: Int,
        @JvmField val manifestCount: Int,
        @JvmField val typeLabel: String
    ) {
        fun isReady(): Boolean = !url.isNullOrEmpty()
    }
}
