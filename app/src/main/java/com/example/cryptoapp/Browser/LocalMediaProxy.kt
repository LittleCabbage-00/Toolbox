package com.example.cryptoapp.Browser

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * 将 FFmpeg 的本机 HTTP 请求转发为 OkHttp HTTPS 请求。
 *
 * maintained FFmpegKit 8.1.7 的 full 制品没有编入 TLS 协议，但完整音视频编码能力
 * 仍在。这个仅绑定 127.0.0.1 的短生命周期代理让网络、Cookie、Range 和证书校验
 * 统一交给 OkHttp，同时重写 HLS 播放列表中的分片、密钥和子清单地址。
 */
class LocalMediaProxy @Throws(IOException::class) constructor(
    client: OkHttpClient,
    userAgent: String,
    cookies: String,
    referer: String,
    requestHeaders: String = ""
) : Closeable {
    @Throws(IOException::class)
    constructor(client: OkHttpClient, userAgent: String, cookies: String, referer: String) :
        this(client, userAgent, cookies, referer, "")

    private val client: OkHttpClient
    private val prefetchClient: OkHttpClient
    private val userAgent: String
    private val cookies: String
    private val referer: String
    private val capturedHeaders = ConcurrentHashMap<String, String>()
    private val server: ServerSocket
    private val workers: ExecutorService = Executors.newFixedThreadPool(MAX_PROXY_CONNECTIONS)
    private val prefetchWorkers: ExecutorService = Executors.newFixedThreadPool(PREFETCH_AHEAD)
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val prefetched = ConcurrentHashMap<String, CompletableFuture<PrefetchedResponse>>()
    private val segmentPositions = ConcurrentHashMap<String, SegmentPosition>()
    private val playlistStates = ConcurrentHashMap<String, PlaylistState>()
    /** 已交付给 FFmpeg 的分片不再因直播清单刷新而重复预取。 */
    private val consumedSegments: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var closed = false

    init {
        // OkHttp 默认每个域名只允许 5 个并发请求，会让 8 路预取实际上仍被限流。
        // 使用独立调度器保留原客户端的连接池、超时与证书配置，同时放宽媒体域名并发数。
        val mediaDispatcher = Dispatcher()
        mediaDispatcher.maxRequests = 16
        mediaDispatcher.maxRequestsPerHost = 12
        this.client = client.newBuilder().dispatcher(mediaDispatcher).build()
        // 预取只是加速手段，不能反过来阻塞 FFmpeg。站点偶尔会让某条并发连接长时间无响应，
        // 因此预取使用独立的短调用超时；失败后当前分片会退回不受该限制的正常流式请求。
        this.prefetchClient = this.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        this.userAgent = userAgent ?: ""
        this.cookies = cookies ?: ""
        this.referer = referer ?: ""
        parseCapturedHeaders(requestHeaders)
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        workers.execute { acceptLoop() }
    }

    fun urlFor(remoteUrl: String): String = urlFor(remoteUrl, null)

    private fun urlFor(remoteUrl: String, fallbackHint: String?): String {
        val token = Base64.encodeToString(remoteUrl.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        // FFmpeg 的 HLS demuxer 会校验分片扩展名。代理路径必须保留一个安全的类型后缀，
        // 否则编码后的 token 会被当成“无扩展名分片”并触发 allowed_segment_extensions 拒绝。
        return "http://127.0.0.1:" + server.localPort + "/media/" + token +
            "/" + safeFileHint(remoteUrl, fallbackHint)
    }

    private fun acceptLoop() {
        while (!closed) {
            try {
                val socket = server.accept()
                workers.execute { handle(socket) }
            } catch (error: IOException) {
                if (!closed) close()
                return
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use {
                BufferedInputStream(socket.inputStream).use { rawInput ->
                    BufferedOutputStream(socket.outputStream).use { output ->
                        val reader = BufferedReader(InputStreamReader(rawInput, StandardCharsets.US_ASCII))
                        val requestLine = reader.readLine()
                        if (requestLine == null) return
                        val requestParts = requestLine.split(" ", limit = 3)
                        if (requestParts.size < 2 || (requestParts[0] != "GET" && requestParts[0] != "HEAD")) {
                            writeSimple(output, 405, "Method Not Allowed")
                            return
                        }
                        var range = ""
                        var line: String?
                        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                            val colon = line!!.indexOf(':')
                            if (colon > 0 && "range".equals(line!!.substring(0, colon).trim(), ignoreCase = true)) {
                                range = line!!.substring(colon + 1).trim()
                            }
                        }
                        // FFmpeg 打开可分页输入时总会带 Range: bytes=0-，语义就是"从第 0 字节读到结尾"，
                        // 等价于完整内容。归一化为空后：(1) 分片预取缓存可以命中，8 路预取不再白跑；
                        // (2) 上游请求不再带 Range，绕过部分 CDN 用 4xx（如 471）拒绝 Range 的问题。
                        if ("bytes=0-".equals(range, ignoreCase = true)) range = ""
                        val remoteUrl = decodePath(requestParts[1])
                        if (remoteUrl == null) {
                            writeSimple(output, 400, "Bad Request")
                            return
                        }
                        val head = requestParts[0] == "HEAD"
                        if (!head && range.isEmpty()) {
                            val cached = awaitPrefetch(remoteUrl)
                            if (cached != null) {
                                relay(cached, output)
                                markSegmentConsumed(remoteUrl)
                                scheduleAfter(remoteUrl)
                                return
                            }
                        }
                        var call = client.newCall(buildRequest(remoteUrl, head, range, false))
                        calls.add(call)
                        try {
                            var response = call.execute()
                            try {
                                if (!head && range.isNotEmpty()
                                    && (response.code == 400 || response.code == 403 || response.code == 416
                                    || response.code == 471)) {
                                    // FFmpeg 默认会对输入携带 Range: bytes=0-，部分 CDN 对 m3u8 清单
                                    // 直接拒绝带 Range 的请求；播放列表很小，回退为完整请求重试一次没有成本。
                                    response.close()
                                    val fallback = client.newCall(buildRequest(remoteUrl, head, "", false))
                                    calls.add(fallback)
                                    try {
                                        response = fallback.execute()
                                    } finally {
                                        calls.remove(fallback)
                                    }
                                }
                                if (!response.isSuccessful && capturedHeaders.isNotEmpty()
                                    && (response.code == 400 || response.code == 403 || response.code == 471)) {
                                    // 部分视频 CDN 会按请求特征校验分片，携带 m3u8 的 Origin/sec-fetch-* 等捕获头
                                    // 反而会被拒绝（常见 471）。回退为只带 UA/Cookie/Referer 的干净请求重试一次。
                                    response.close()
                                    val cleanRetry = client.newCall(buildRequest(remoteUrl, head, range, true))
                                    calls.add(cleanRetry)
                                    try {
                                        response = cleanRetry.execute()
                                    } finally {
                                        calls.remove(cleanRetry)
                                    }
                                }
                                if (!response.isSuccessful) {
                                    // 失败时记录完整上游 URL 与实际发送的每个请求头，便于与 WebView 成功
                                    // 播放时的请求头逐项对比，定位 CDN 拒绝分片的差异（UA / Cookie / Referer / Origin）。
                                    val detail = StringBuilder("upstream ")
                                        .append(response.code).append(" ").append(remoteUrl)
                                    if (range.isNotEmpty()) detail.append(" range=").append(range)
                                    detail.append(" sentHeaders=")
                                    val sent = response.request
                                    for (i in 0 until sent.headers.size) {
                                        var name = sent.headers.name(i)
                                        var value = sent.headers.value(i)
                                        if ("cookie".equals(name, ignoreCase = true) && value.length > 120) {
                                            value = value.substring(0, 120) + "…"
                                        }
                                        detail.append(name).append('=').append(value).append(';')
                                    }
                                    Log.w(TAG, detail.toString())
                                }
                                relay(response, output, head, remoteUrl)
                                if (!head && response.isSuccessful) markSegmentConsumed(remoteUrl)
                            } finally {
                                response.close()
                            }
                        } finally {
                            calls.remove(call)
                        }
                        if (!head && range.isEmpty()) scheduleAfter(remoteUrl)
                    }
                }
            }
        } catch (ignored: Exception) {
            // FFmpeg 取消、暂停或主动断开连接时会触发 IOException，无需污染应用日志。
        }
    }

    private fun relay(response: Response, output: OutputStream, head: Boolean, playlistStateKey: String) {
        val body = response.body
        var contentType = response.header("Content-Type") ?: "application/octet-stream"
        val playlist = isPlaylist(response.request.url, contentType)
        var rewritten: ByteArray? = null
        if (!head && playlist && body != null) {
            val source = body.string()
            rewritten = rewritePlaylist(source, response.request.url, playlistStateKey)
                .toByteArray(StandardCharsets.UTF_8)
            contentType = "application/vnd.apple.mpegurl"
        }
        val length = if (rewritten != null) rewritten.size.toLong()
            else if (body == null) 0 else body.contentLength()
        writeAscii(output, "HTTP/1.1 " + response.code + " " + response.message + "\r\n")
        writeAscii(output, "Content-Type: " + contentType + "\r\n")
        if (length >= 0) writeAscii(output, "Content-Length: " + length + "\r\n")
        copyHeader(response, output, "Accept-Ranges")
        copyHeader(response, output, "Content-Range")
        writeAscii(output, "Connection: close\r\n\r\n")
        if (head || body == null) {
            output.flush()
            return
        }
        if (rewritten != null) {
            output.write(rewritten)
        } else {
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (!closed) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
            }
        }
        output.flush()
    }

    private fun rewritePlaylist(source: String, baseUrl: HttpUrl, playlistStateKey: String): String {
        val result = StringBuilder(source.length + 256)
        // 保持 Java split(regex, -1) 语义：保留末尾空行，改写后输出与原始清单行数一致。
        val lines = Pattern.compile("\\r?\\n").split(source, -1)
        val mediaSegments = ArrayList<String>()
        var nextUriIsPlaylist = false
        var hasCompleteSegments = false
        var hasEndList = false
        for (line in lines) {
            var rewritten = line
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF")) hasCompleteSegments = true
                if (line.startsWith("#EXT-X-ENDLIST")) hasEndList = true
                val matcher = HLS_URI.matcher(line)
                val buffer = StringBuffer()
                while (matcher.find()) {
                    val resolved = resolve(baseUrl, matcher.group(1), hintForTag(line))
                    matcher.appendReplacement(buffer, "URI=\\\"" + Matcher.quoteReplacement(resolved) + "\\\"")
                }
                matcher.appendTail(buffer)
                rewritten = buffer.toString()
                nextUriIsPlaylist = line.startsWith("#EXT-X-STREAM-INF")
            } else if (line.trim().isNotEmpty()) {
                // HLS 地址经常把真实类型藏在查询参数中。无后缀 URI 若统一伪装成 .bin，
                // FFmpeg 会在读取任何字节前按分片扩展名策略直接拒绝，表现为进度始终为 0。
                val remote = baseUrl.resolve(line.trim())
                if (!nextUriIsPlaylist && remote != null) mediaSegments.add(remote.toString())
                rewritten = resolve(baseUrl, line.trim(),
                    if (nextUriIsPlaylist) "playlist.m3u8" else "segment.ts")
                nextUriIsPlaylist = false
            }
            result.append(rewritten).append('\n')
        }
        // 不能在第一次看到无 ENDLIST 的清单时立刻封口：不少点播站只先返回约 30～60 秒，
        // 随后才逐步追加分片。首次快照强制结束会得到“下载成功但只有几十秒”的残缺视频。
        // 保持清单可刷新，直到服务端明确结束，或同一个尾分片连续稳定一段时间后才冻结。
        if (hasCompleteSegments && !hasEndList
            && shouldCloseStablePlaylist(playlistStateKey, mediaSegments)) {
            result.append("#EXT-X-ENDLIST\n")
        }
        if (mediaSegments.isNotEmpty()) registerSegments(mediaSegments)
        return result.toString()
    }

    private fun shouldCloseStablePlaylist(playlistStateKey: String, mediaSegments: List<String>): Boolean {
        if (mediaSegments.isEmpty()) return false
        val lastSegment = segmentIdentity(mediaSegments[mediaSegments.size - 1])
        val state = playlistStates.computeIfAbsent(playlistStateKey) { PlaylistState() }
        return state.observe(lastSegment, mediaSegments.size, System.nanoTime())
    }

    /** CDN 经常只刷新鉴权查询参数；判断清单是否增长时应比较稳定的分片路径。 */
    private fun segmentIdentity(value: String): String {
        val parsed = value.toHttpUrlOrNull()
        return parsed?.encodedPath ?: value
    }

    /** 记录媒体分片顺序，并只启动一个有界的前瞻窗口。 */
    private fun registerSegments(segments: List<String>) {
        // 不依赖 Android 10 以后才完整提供的 List.copyOf，保持 minSdk 26 可运行。
        val immutable = java.util.Collections.unmodifiableList(ArrayList(segments))
        for (index in immutable.indices) {
            segmentPositions[immutable[index]] = SegmentPosition(immutable, index)
        }
        scheduleWindow(immutable, 0)
    }

    private fun scheduleAfter(remoteUrl: String) {
        val position = segmentPositions[remoteUrl]
        if (position != null) scheduleWindow(position.segments, position.index + 1)
    }

    private fun scheduleWindow(segments: List<String>, start: Int) {
        val end = minOf(segments.size, start + PREFETCH_AHEAD)
        for (index in maxOf(0, start) until end) {
            if (closed) break
            val url = segments[index]
            if (consumedSegments.contains(url)) continue
            prefetched.computeIfAbsent(url) {
                CompletableFuture.supplyAsync({
                    try {
                        fetchForPrefetch(url)
                    } catch (error: IOException) {
                        throw CompletionException(error)
                    }
                }, prefetchWorkers)
            }
        }
    }

    private fun awaitPrefetch(remoteUrl: String): PrefetchedResponse? {
        val future = prefetched.remove(remoteUrl) ?: return null
        return try {
            future.get()
        } catch (ignored: Exception) {
            // 预取失败不影响正常下载，当前请求会立即退回普通 OkHttp 流式请求。
            null
        }
    }

    private fun markSegmentConsumed(remoteUrl: String) {
        if (!segmentPositions.containsKey(remoteUrl)) return
        consumedSegments.add(remoteUrl)
        // 发生直接回源与预取竞态时，及时取消并释放那份不再需要的缓存结果。
        val duplicate = prefetched.remove(remoteUrl)
        if (duplicate != null && !duplicate.isDone) duplicate.cancel(true)
    }

    private fun fetchForPrefetch(remoteUrl: String): PrefetchedResponse {
        val call = prefetchClient.newCall(buildRequest(remoteUrl, false, "", false))
        calls.add(call)
        try {
            call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    throw IOException("HLS prefetch HTTP " + response.code)
                }
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_PREFETCH_SEGMENT_BYTES) {
                    throw IOException("HLS segment is too large to prefetch")
                }
                val bytes = ByteArrayOutputStream(if (declaredLength > 0) declaredLength.toInt() else 64 * 1024)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (!closed) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > MAX_PREFETCH_SEGMENT_BYTES) {
                            throw IOException("HLS segment exceeded prefetch limit")
                        }
                        bytes.write(buffer, 0, count)
                    }
                }
                return PrefetchedResponse(response.code, response.message,
                    response.header("Content-Type") ?: "application/octet-stream",
                    response.header("Accept-Ranges"), response.header("Content-Range"), bytes.toByteArray())
            }
        } finally {
            calls.remove(call)
        }
    }

    private fun buildRequest(remoteUrl: String, head: Boolean, range: String, skipCapturedHeaders: Boolean): Request {
        val request = Request.Builder().url(remoteUrl)
        if (head) request.head()
        if (!skipCapturedHeaders) {
            for ((key, value) in capturedHeaders) {
                // Referer 统一用下载时传入的完整页面 URL（浏览器播放时就是页面完整地址），
                // 捕获头里的裸域 Referer（如 https://cn.pornhub.com/）会破坏 CDN 校验。
                if ("Referer".equals(key, ignoreCase = true)) continue
                request.header(key, value)
            }
        }
        if (userAgent.isNotEmpty()) request.header("User-Agent", userAgent)
        if (cookies.isNotEmpty()) request.header("Cookie", cookies)
        // 完整页面 Referer 总是设置，不被捕获头的裸域 Referer 覆盖。
        if (referer.isNotEmpty()) request.header("Referer", referer)
        if (range.isNotEmpty()) request.header("Range", range)
        return request.build()
    }

    /**
     * 复用 WebView 真正播放清单时的安全请求头。Host、Cookie、Range 等由 OkHttp 或专用字段管理，
     * 防止页面伪造连接级请求头；Origin、客户端提示和 Fetch Metadata 则是部分视频 CDN 的必要校验。
     */
    private fun parseCapturedHeaders(serialized: String?) {
        if (serialized.isNullOrEmpty()) return
        try {
            val headers = JSONObject(serialized)
            val names = headers.keys()
            while (names.hasNext()) {
                val name = names.next()
                val lower = name.lowercase(Locale.ROOT)
                val allowed = "accept" == lower || "accept-language" == lower
                    || "origin" == lower || lower.startsWith("sec-fetch-")
                    || lower.startsWith("sec-ch-ua") || "x-requested-with" == lower
                    || "purpose" == lower || "dpr" == lower
                    || "width" == lower || "viewport-width" == lower
                    || "save-data" == lower || "referer" == lower
                val value = headers.optString(name, "")
                if (allowed && value.isNotEmpty() && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
                    capturedHeaders[name] = value
                }
            }
        } catch (ignored: Exception) { }
    }

    private fun relay(response: PrefetchedResponse, output: OutputStream) {
        writeAscii(output, "HTTP/1.1 " + response.code + " " + response.message + "\r\n")
        writeAscii(output, "Content-Type: " + response.contentType + "\r\n")
        writeAscii(output, "Content-Length: " + response.body.size + "\r\n")
        if (response.acceptRanges != null) writeAscii(output, "Accept-Ranges: " + response.acceptRanges + "\r\n")
        if (response.contentRange != null) writeAscii(output, "Content-Range: " + response.contentRange + "\r\n")
        writeAscii(output, "Connection: close\r\n\r\n")
        output.write(response.body)
        output.flush()
    }

    private class SegmentPosition(val segments: List<String>, val index: Int)

    private class PlaylistState {
        private var lastSegment = ""
        private var largestSegmentCount = 0
        private var observations = 0
        private var lastGrowthNanos = 0L

        @Synchronized
        fun observe(currentLastSegment: String, segmentCount: Int, nowNanos: Long): Boolean {
            observations++
            val grew = currentLastSegment != lastSegment || segmentCount > largestSegmentCount
            if (grew) {
                lastSegment = currentLastSegment
                largestSegmentCount = maxOf(largestSegmentCount, segmentCount)
                lastGrowthNanos = nowNanos
                return false
            }
            // 至少观察三次，避免一次偶发的 CDN 缓存响应被误判为视频结尾。
            return observations >= 3 && lastGrowthNanos > 0
                && nowNanos - lastGrowthNanos >= PLAYLIST_STABLE_NANOS
        }
    }

    private class PrefetchedResponse(
        val code: Int,
        val message: String,
        val contentType: String,
        val acceptRanges: String?,
        val contentRange: String?,
        val body: ByteArray
    )

    private fun hintForTag(line: String): String {
        if (line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
            return "playlist.m3u8"
        }
        if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY")) return "key.key"
        if (line.startsWith("#EXT-X-MAP")) return "init.mp4"
        if (line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT")) return "fragment.m4s"
        return "resource.bin"
    }

    private fun resolve(baseUrl: HttpUrl, value: String, fallbackHint: String?): String {
        val resolved = baseUrl.resolve(value)
        if (resolved != null) return urlFor(resolved.toString(), fallbackHint)
        // 不把 file:、content: 等非 HTTP URI 原样交给 FFmpeg；即使 HLS 后缀策略被放宽，
        // 子资源也只能经过本机代理访问，不能借恶意播放列表读取应用或系统本地文件。
        return "http://127.0.0.1:" + server.localPort + "/invalid/" +
            (fallbackHint ?: "resource.bin")
    }

    private fun isPlaylist(url: HttpUrl, type: String): Boolean =
        url.encodedPath.lowercase(Locale.ROOT).endsWith(".m3u8")
            || type.lowercase(Locale.ROOT).contains("mpegurl")

    private fun decodePath(path: String): String? {
        val index = path.indexOf("/media/")
        if (index < 0) return null
        val encoded = path.substring(index + 7).split("\\?", limit = 2)[0]
        val suffix = encoded.indexOf('/')
        val token = if (suffix >= 0) encoded.substring(0, suffix) else encoded
        return try {
            String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                StandardCharsets.UTF_8)
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /** 只暴露通用文件名提示，不把远端路径或鉴权参数泄漏到本地代理 URL。 */
    private fun safeFileHint(remoteUrl: String, fallbackHint: String?): String {
        val parsed = remoteUrl.toHttpUrlOrNull() ?: return fallbackHint ?: "resource.bin"
        val path = parsed.encodedPath.lowercase(Locale.ROOT)
        val slash = path.lastIndexOf('/')
        val name = if (slash >= 0) path.substring(slash + 1) else path
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return fallbackHint ?: "resource.bin"
        val extension = name.substring(dot + 1).replace(Regex("[^a-z0-9]"), "")
        if (extension.isEmpty() || extension.length > 10) return fallbackHint ?: "resource.bin"
        if ("m3u8" == extension) return "playlist.m3u8"
        if ("ts" == extension || "m2ts" == extension) return "segment." + extension
        if ("m4s" == extension || "cmfv" == extension || "cmfa" == extension) return "fragment." + extension
        return "resource." + extension
    }

    private fun copyHeader(response: Response, output: OutputStream, name: String) {
        val value = response.header(name)
        if (value != null) writeAscii(output, name + ": " + value + "\r\n")
    }

    private fun writeSimple(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        writeAscii(output, "HTTP/1.1 " + code + " " + message + "\r\nContent-Length: "
            + body.size + "\r\nConnection: close\r\n\r\n")
        output.write(body)
        output.flush()
    }

    private fun writeAscii(output: OutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    override fun close() {
        if (closed) return
        closed = true
        for (call in calls) call.cancel()
        calls.clear()
        prefetched.clear()
        segmentPositions.clear()
        playlistStates.clear()
        consumedSegments.clear()
        try {
            server.close()
        } catch (ignored: IOException) { }
        prefetchWorkers.shutdownNow()
        workers.shutdownNow()
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
    }

    companion object {
        private const val TAG = "ToolboxMediaProxy"
        private val HLS_URI = Pattern.compile("URI=\\\"([^\\\"]+)\\\"")
        /**
         * HLS 分片本身必须按播放列表顺序交给 FFmpeg，但网络下载可以提前并发进行。
         * 4 路能覆盖常见移动网络的高延迟，同时不会像一次性提交整张播放列表那样占满内存和连接。
         */
        private const val PREFETCH_AHEAD = 8
        /** 与 OkHttp 媒体调度器保持一致，防止异常页面用大量并发连接耗尽应用线程。 */
        private const val MAX_PROXY_CONNECTIONS = 16
        private const val MAX_PREFETCH_SEGMENT_BYTES = 16 * 1024 * 1024
        /** 伪直播点播清单在尾分片至少稳定 30 秒后，才允许把当前快照视为完整视频。 */
        private const val PLAYLIST_STABLE_NANOS = 30_000_000_000L
    }
}
