package com.example.cryptoapp.Browser;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 将 FFmpeg 的本机 HTTP 请求转发为 OkHttp HTTPS 请求。
 *
 * maintained FFmpegKit 8.1.7 的 full 制品没有编入 TLS 协议，但完整音视频编码能力
 * 仍在。这个仅绑定 127.0.0.1 的短生命周期代理让网络、Cookie、Range 和证书校验
 * 统一交给 OkHttp，同时重写 HLS 播放列表中的分片、密钥和子清单地址。
 */
public final class LocalMediaProxy implements Closeable {
    private static final String TAG = "ToolboxMediaProxy";
    private static final Pattern HLS_URI = Pattern.compile("URI=\\\"([^\\\"]+)\\\"");
    /**
     * HLS 分片本身必须按播放列表顺序交给 FFmpeg，但网络下载可以提前并发进行。
     * 4 路能覆盖常见移动网络的高延迟，同时不会像一次性提交整张播放列表那样占满内存和连接。
     */
    private static final int PREFETCH_AHEAD = 8;
    /** 与 OkHttp 媒体调度器保持一致，防止异常页面用大量并发连接耗尽应用线程。 */
    private static final int MAX_PROXY_CONNECTIONS = 16;
    private static final int MAX_PREFETCH_SEGMENT_BYTES = 16 * 1024 * 1024;
    /** 伪直播点播清单在尾分片至少稳定 30 秒后，才允许把当前快照视为完整视频。 */
    private static final long PLAYLIST_STABLE_NANOS = 30_000_000_000L;
    private final OkHttpClient client;
    private final OkHttpClient prefetchClient;
    private final String userAgent;
    private final String cookies;
    private final String referer;
    private final Map<String, String> capturedHeaders = new ConcurrentHashMap<>();
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newFixedThreadPool(MAX_PROXY_CONNECTIONS);
    private final ExecutorService prefetchWorkers = Executors.newFixedThreadPool(PREFETCH_AHEAD);
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<PrefetchedResponse>> prefetched = new ConcurrentHashMap<>();
    private final Map<String, SegmentPosition> segmentPositions = new ConcurrentHashMap<>();
    private final Map<String, PlaylistState> playlistStates = new ConcurrentHashMap<>();
    /** 已交付给 FFmpeg 的分片不再因直播清单刷新而重复预取。 */
    private final Set<String> consumedSegments = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public LocalMediaProxy(OkHttpClient client, String userAgent, String cookies, String referer) throws IOException {
        this(client, userAgent, cookies, referer, "");
    }

    public LocalMediaProxy(OkHttpClient client, String userAgent, String cookies,
                           String referer, String requestHeaders) throws IOException {
        // OkHttp 默认每个域名只允许 5 个并发请求，会让 8 路预取实际上仍被限流。
        // 使用独立调度器保留原客户端的连接池、超时与证书配置，同时放宽媒体域名并发数。
        Dispatcher mediaDispatcher = new Dispatcher();
        mediaDispatcher.setMaxRequests(16);
        mediaDispatcher.setMaxRequestsPerHost(12);
        this.client = client.newBuilder().dispatcher(mediaDispatcher).build();
        // 预取只是加速手段，不能反过来阻塞 FFmpeg。站点偶尔会让某条并发连接长时间无响应，
        // 因此预取使用独立的短调用超时；失败后当前分片会退回不受该限制的正常流式请求。
        this.prefetchClient = this.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build();
        this.userAgent = userAgent == null ? "" : userAgent;
        this.cookies = cookies == null ? "" : cookies;
        this.referer = referer == null ? "" : referer;
        parseCapturedHeaders(requestHeaders);
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        workers.execute(this::acceptLoop);
    }

    public String urlFor(String remoteUrl) {
        return urlFor(remoteUrl, null);
    }

    private String urlFor(String remoteUrl, String fallbackHint) {
        String token = Base64.encodeToString(remoteUrl.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        // FFmpeg 的 HLS demuxer 会校验分片扩展名。代理路径必须保留一个安全的类型后缀，
        // 否则编码后的 token 会被当成“无扩展名分片”并触发 allowed_segment_extensions 拒绝。
        return "http://127.0.0.1:" + server.getLocalPort() + "/media/" + token
                + "/" + safeFileHint(remoteUrl, fallbackHint);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                workers.execute(() -> handle(socket));
            } catch (IOException error) {
                if (!closed) close();
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             InputStream rawInput = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(rawInput, StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length < 2 || !("GET".equals(requestParts[0]) || "HEAD".equals(requestParts[0]))) {
                writeSimple(output, 405, "Method Not Allowed");
                return;
            }
            String range = "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    range = line.substring(colon + 1).trim();
                }
            }
            String remoteUrl = decodePath(requestParts[1]);
            if (remoteUrl == null) {
                writeSimple(output, 400, "Bad Request");
                return;
            }
            boolean head = "HEAD".equals(requestParts[0]);
            if (!head && range.isEmpty()) {
                PrefetchedResponse cached = awaitPrefetch(remoteUrl);
                if (cached != null) {
                    relay(cached, output);
                    markSegmentConsumed(remoteUrl);
                    scheduleAfter(remoteUrl);
                    return;
                }
            }
            Call call = client.newCall(buildRequest(remoteUrl, head, range));
            calls.add(call);
            try {
                Response response = call.execute();
                try {
                    if (!head && !range.isEmpty()
                            && (response.code() == 400 || response.code() == 403 || response.code() == 416)) {
                        // FFmpeg 默认会对输入携带 Range: bytes=0-，部分 CDN 对 m3u8 清单
                        // 直接拒绝带 Range 的请求；播放列表很小，回退为完整请求重试一次没有成本。
                        response.close();
                        Call fallback = client.newCall(buildRequest(remoteUrl, head, ""));
                        calls.add(fallback);
                        try {
                            response = fallback.execute();
                        } finally {
                            calls.remove(fallback);
                        }
                    }
                    if (!response.isSuccessful()) {
                        // 失败时记录完整上游 URL（可能含 CDN 鉴权参数），用于核对签名是否过期或请求是否被改写。
                        Log.w(TAG, "upstream " + response.code() + " " + remoteUrl
                                + (range.isEmpty() ? "" : " range=" + range));
                    }
                    relay(response, output, head, remoteUrl);
                    if (!head && response.isSuccessful()) markSegmentConsumed(remoteUrl);
                } finally {
                    response.close();
                }
            } finally {
                calls.remove(call);
            }
            if (!head && range.isEmpty()) scheduleAfter(remoteUrl);
        } catch (Exception ignored) {
            // FFmpeg 取消、暂停或主动断开连接时会触发 IOException，无需污染应用日志。
        }
    }

    private void relay(Response response, OutputStream output, boolean head, String playlistStateKey) throws IOException {
        ResponseBody body = response.body();
        String contentType = response.header("Content-Type", "application/octet-stream");
        boolean playlist = isPlaylist(response.request().url(), contentType);
        byte[] rewritten = null;
        if (!head && playlist && body != null) {
            String source = body.string();
            rewritten = rewritePlaylist(source, response.request().url(), playlistStateKey)
                    .getBytes(StandardCharsets.UTF_8);
            contentType = "application/vnd.apple.mpegurl";
        }
        long length = rewritten != null ? rewritten.length : body == null ? 0 : body.contentLength();
        writeAscii(output, "HTTP/1.1 " + response.code() + " " + response.message() + "\r\n");
        writeAscii(output, "Content-Type: " + contentType + "\r\n");
        if (length >= 0) writeAscii(output, "Content-Length: " + length + "\r\n");
        copyHeader(response, output, "Accept-Ranges");
        copyHeader(response, output, "Content-Range");
        writeAscii(output, "Connection: close\r\n\r\n");
        if (head || body == null) { output.flush(); return; }
        if (rewritten != null) output.write(rewritten);
        else try (InputStream input = body.byteStream()) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while (!closed && (count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        output.flush();
    }

    private String rewritePlaylist(String source, HttpUrl baseUrl, String playlistStateKey) {
        StringBuilder result = new StringBuilder(source.length() + 256);
        String[] lines = source.split("\\r?\\n", -1);
        List<String> mediaSegments = new ArrayList<>();
        boolean nextUriIsPlaylist = false;
        boolean hasCompleteSegments = false;
        boolean hasEndList = false;
        for (String line : lines) {
            String rewritten = line;
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF")) hasCompleteSegments = true;
                if (line.startsWith("#EXT-X-ENDLIST")) hasEndList = true;
                Matcher matcher = HLS_URI.matcher(line);
                StringBuffer buffer = new StringBuffer();
                while (matcher.find()) {
                    String resolved = resolve(baseUrl, matcher.group(1), hintForTag(line));
                    matcher.appendReplacement(buffer, "URI=\\\"" + Matcher.quoteReplacement(resolved) + "\\\"");
                }
                matcher.appendTail(buffer);
                rewritten = buffer.toString();
                nextUriIsPlaylist = line.startsWith("#EXT-X-STREAM-INF");
            } else if (!line.trim().isEmpty()) {
                // HLS 地址经常把真实类型藏在查询参数中。无后缀 URI 若统一伪装成 .bin，
                // FFmpeg 会在读取任何字节前按分片扩展名策略直接拒绝，表现为进度始终为 0。
                HttpUrl remote = baseUrl.resolve(line.trim());
                if (!nextUriIsPlaylist && remote != null) mediaSegments.add(remote.toString());
                rewritten = resolve(baseUrl, line.trim(), nextUriIsPlaylist ? "playlist.m3u8" : "segment.ts");
                nextUriIsPlaylist = false;
            }
            result.append(rewritten).append('\n');
        }
        // 不能在第一次看到无 ENDLIST 的清单时立刻封口：不少点播站只先返回约 30～60 秒，
        // 随后才逐步追加分片。首次快照强制结束会得到“下载成功但只有几十秒”的残缺视频。
        // 保持清单可刷新，直到服务端明确结束，或同一个尾分片连续稳定一段时间后才冻结。
        if (hasCompleteSegments && !hasEndList
                && shouldCloseStablePlaylist(playlistStateKey, mediaSegments)) {
            result.append("#EXT-X-ENDLIST\n");
        }
        if (!mediaSegments.isEmpty()) registerSegments(mediaSegments);
        return result.toString();
    }

    private boolean shouldCloseStablePlaylist(String playlistStateKey, List<String> mediaSegments) {
        if (mediaSegments.isEmpty()) return false;
        String lastSegment = segmentIdentity(mediaSegments.get(mediaSegments.size() - 1));
        PlaylistState state = playlistStates.computeIfAbsent(playlistStateKey, ignored -> new PlaylistState());
        return state.observe(lastSegment, mediaSegments.size(), System.nanoTime());
    }

    /** CDN 经常只刷新鉴权查询参数；判断清单是否增长时应比较稳定的分片路径。 */
    private String segmentIdentity(String value) {
        HttpUrl parsed = HttpUrl.parse(value);
        return parsed == null ? value : parsed.encodedPath();
    }

    /** 记录媒体分片顺序，并只启动一个有界的前瞻窗口。 */
    private void registerSegments(List<String> segments) {
        // 不依赖 Android 10 以后才完整提供的 List.copyOf，保持 minSdk 26 可运行。
        List<String> immutable = Collections.unmodifiableList(new ArrayList<>(segments));
        for (int index = 0; index < immutable.size(); index++) {
            segmentPositions.put(immutable.get(index), new SegmentPosition(immutable, index));
        }
        scheduleWindow(immutable, 0);
    }

    private void scheduleAfter(String remoteUrl) {
        SegmentPosition position = segmentPositions.get(remoteUrl);
        if (position != null) scheduleWindow(position.segments, position.index + 1);
    }

    private void scheduleWindow(List<String> segments, int start) {
        int end = Math.min(segments.size(), start + PREFETCH_AHEAD);
        for (int index = Math.max(0, start); index < end && !closed; index++) {
            String url = segments.get(index);
            if (consumedSegments.contains(url)) continue;
            prefetched.computeIfAbsent(url, ignored -> CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchForPrefetch(url);
                } catch (IOException error) {
                    throw new CompletionException(error);
                }
            }, prefetchWorkers));
        }
    }

    private PrefetchedResponse awaitPrefetch(String remoteUrl) {
        CompletableFuture<PrefetchedResponse> future = prefetched.remove(remoteUrl);
        if (future == null) return null;
        try {
            return future.get();
        } catch (Exception ignored) {
            // 预取失败不影响正常下载，当前请求会立即退回普通 OkHttp 流式请求。
            return null;
        }
    }

    private void markSegmentConsumed(String remoteUrl) {
        if (!segmentPositions.containsKey(remoteUrl)) return;
        consumedSegments.add(remoteUrl);
        // 发生直接回源与预取竞态时，及时取消并释放那份不再需要的缓存结果。
        CompletableFuture<PrefetchedResponse> duplicate = prefetched.remove(remoteUrl);
        if (duplicate != null && !duplicate.isDone()) duplicate.cancel(true);
    }

    private PrefetchedResponse fetchForPrefetch(String remoteUrl) throws IOException {
        Call call = prefetchClient.newCall(buildRequest(remoteUrl, false, ""));
        calls.add(call);
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HLS prefetch HTTP " + response.code());
            }
            long declaredLength = body.contentLength();
            if (declaredLength > MAX_PREFETCH_SEGMENT_BYTES) {
                throw new IOException("HLS segment is too large to prefetch");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    declaredLength > 0 ? (int) declaredLength : 64 * 1024);
            try (InputStream input = body.byteStream()) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                int total = 0;
                while (!closed && (count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_PREFETCH_SEGMENT_BYTES) {
                        throw new IOException("HLS segment exceeded prefetch limit");
                    }
                    bytes.write(buffer, 0, count);
                }
            }
            return new PrefetchedResponse(response.code(), response.message(),
                    response.header("Content-Type", "application/octet-stream"),
                    response.header("Accept-Ranges"), response.header("Content-Range"), bytes.toByteArray());
        } finally {
            calls.remove(call);
        }
    }

    private Request buildRequest(String remoteUrl, boolean head, String range) {
        Request.Builder request = new Request.Builder().url(remoteUrl);
        if (head) request.head();
        for (Map.Entry<String, String> header : capturedHeaders.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        if (!userAgent.isEmpty()) request.header("User-Agent", userAgent);
        if (!cookies.isEmpty()) request.header("Cookie", cookies);
        if (!referer.isEmpty() && !hasCapturedHeader("Referer")) request.header("Referer", referer);
        if (range != null && !range.isEmpty()) request.header("Range", range);
        return request.build();
    }

    private boolean hasCapturedHeader(String name) {
        for (String key : capturedHeaders.keySet()) {
            if (key.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * 复用 WebView 真正播放清单时的安全请求头。Host、Cookie、Range 等由 OkHttp 或专用字段管理，
     * 防止页面伪造连接级请求头；Origin、客户端提示和 Fetch Metadata 则是部分视频 CDN 的必要校验。
     */
    private void parseCapturedHeaders(String serialized) {
        if (serialized == null || serialized.isEmpty()) return;
        try {
            JSONObject headers = new JSONObject(serialized);
            Iterator<String> names = headers.keys();
            while (names.hasNext()) {
                String name = names.next();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean allowed = "accept".equals(lower) || "accept-language".equals(lower)
                        || "origin".equals(lower) || lower.startsWith("sec-fetch-")
                        || lower.startsWith("sec-ch-ua") || "x-requested-with".equals(lower)
                        || "purpose".equals(lower) || "dpr".equals(lower)
                        || "width".equals(lower) || "viewport-width".equals(lower)
                        || "save-data".equals(lower) || "referer".equals(lower);
                String value = headers.optString(name, "");
                if (allowed && !value.isEmpty() && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
                    capturedHeaders.put(name, value);
                }
            }
        } catch (Exception ignored) { }
    }

    private void relay(PrefetchedResponse response, OutputStream output) throws IOException {
        writeAscii(output, "HTTP/1.1 " + response.code + " " + response.message + "\r\n");
        writeAscii(output, "Content-Type: " + response.contentType + "\r\n");
        writeAscii(output, "Content-Length: " + response.body.length + "\r\n");
        if (response.acceptRanges != null) writeAscii(output, "Accept-Ranges: " + response.acceptRanges + "\r\n");
        if (response.contentRange != null) writeAscii(output, "Content-Range: " + response.contentRange + "\r\n");
        writeAscii(output, "Connection: close\r\n\r\n");
        output.write(response.body);
        output.flush();
    }

    private static final class SegmentPosition {
        final List<String> segments;
        final int index;

        SegmentPosition(List<String> segments, int index) {
            this.segments = segments;
            this.index = index;
        }
    }

    private static final class PlaylistState {
        private String lastSegment = "";
        private int largestSegmentCount;
        private int observations;
        private long lastGrowthNanos;

        synchronized boolean observe(String currentLastSegment, int segmentCount, long nowNanos) {
            observations++;
            boolean grew = !currentLastSegment.equals(lastSegment) || segmentCount > largestSegmentCount;
            if (grew) {
                lastSegment = currentLastSegment;
                largestSegmentCount = Math.max(largestSegmentCount, segmentCount);
                lastGrowthNanos = nowNanos;
                return false;
            }
            // 至少观察三次，避免一次偶发的 CDN 缓存响应被误判为视频结尾。
            return observations >= 3 && lastGrowthNanos > 0
                    && nowNanos - lastGrowthNanos >= PLAYLIST_STABLE_NANOS;
        }
    }

    private static final class PrefetchedResponse {
        final int code;
        final String message;
        final String contentType;
        final String acceptRanges;
        final String contentRange;
        final byte[] body;

        PrefetchedResponse(int code, String message, String contentType,
                           String acceptRanges, String contentRange, byte[] body) {
            this.code = code;
            this.message = message;
            this.contentType = contentType;
            this.acceptRanges = acceptRanges;
            this.contentRange = contentRange;
            this.body = body;
        }
    }

    private String hintForTag(String line) {
        if (line.startsWith("#EXT-X-MEDIA") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
            return "playlist.m3u8";
        }
        if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY")) return "key.key";
        if (line.startsWith("#EXT-X-MAP")) return "init.mp4";
        if (line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT")) return "fragment.m4s";
        return "resource.bin";
    }

    private String resolve(HttpUrl baseUrl, String value, String fallbackHint) {
        HttpUrl resolved = baseUrl.resolve(value);
        if (resolved != null) return urlFor(resolved.toString(), fallbackHint);
        // 不把 file:、content: 等非 HTTP URI 原样交给 FFmpeg；即使 HLS 后缀策略被放宽，
        // 子资源也只能经过本机代理访问，不能借恶意播放列表读取应用或系统本地文件。
        return "http://127.0.0.1:" + server.getLocalPort() + "/invalid/"
                + (fallbackHint == null ? "resource.bin" : fallbackHint);
    }

    private boolean isPlaylist(HttpUrl url, String type) {
        return url.encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")
                || type.toLowerCase(Locale.ROOT).contains("mpegurl");
    }

    private String decodePath(String path) {
        int index = path.indexOf("/media/");
        if (index < 0) return null;
        String encoded = path.substring(index + 7).split("\\?", 2)[0];
        int suffix = encoded.indexOf('/');
        String token = suffix >= 0 ? encoded.substring(0, suffix) : encoded;
        try {
            return new String(Base64.decode(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 只暴露通用文件名提示，不把远端路径或鉴权参数泄漏到本地代理 URL。 */
    private String safeFileHint(String remoteUrl, String fallbackHint) {
        HttpUrl parsed = HttpUrl.parse(remoteUrl);
        if (parsed == null) return fallbackHint == null ? "resource.bin" : fallbackHint;
        String path = parsed.encodedPath().toLowerCase(Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return fallbackHint == null ? "resource.bin" : fallbackHint;
        String extension = name.substring(dot + 1).replaceAll("[^a-z0-9]", "");
        if (extension.isEmpty() || extension.length() > 10) return fallbackHint == null ? "resource.bin" : fallbackHint;
        if ("m3u8".equals(extension)) return "playlist.m3u8";
        if ("ts".equals(extension) || "m2ts".equals(extension)) return "segment." + extension;
        if ("m4s".equals(extension) || "cmfv".equals(extension) || "cmfa".equals(extension)) {
            return "fragment." + extension;
        }
        return "resource." + extension;
    }

    private void copyHeader(Response response, OutputStream output, String name) throws IOException {
        String value = response.header(name);
        if (value != null) writeAscii(output, name + ": " + value + "\r\n");
    }

    private void writeSimple(OutputStream output, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        writeAscii(output, "HTTP/1.1 " + code + " " + message + "\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n");
        output.write(body);
        output.flush();
    }

    private void writeAscii(OutputStream output, @NonNull String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Call call : calls) call.cancel();
        calls.clear();
        prefetched.clear();
        segmentPositions.clear();
        playlistStates.clear();
        consumedSegments.clear();
        try { server.close(); } catch (IOException ignored) { }
        prefetchWorkers.shutdownNow();
        workers.shutdownNow();
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdownNow();
    }
}
