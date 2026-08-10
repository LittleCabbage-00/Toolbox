package com.example.cryptoapp.Browser;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assume.assumeNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 真机回归：OkHttp 本机代理提供媒体，FFmpeg 必须能完整读取并重新封装。 */
@RunWith(AndroidJUnit4.class)
public class LocalMediaProxyTest {
    @Test public void forwardsCapturedWebViewHeadersToMediaCdn() throws Exception {
        byte[] playlist = "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
        ServerSocket upstream = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<String> origin = new AtomicReference<>("");
        AtomicReference<String> fetchSite = new AtomicReference<>("");
        worker.execute(() -> {
            try (Socket socket = upstream.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                reader.readLine();
                while (true) {
                    String header = reader.readLine();
                    if (header == null || header.isEmpty()) break;
                    int colon = header.indexOf(':');
                    if (colon <= 0) continue;
                    String name = header.substring(0, colon).trim();
                    String value = header.substring(colon + 1).trim();
                    if ("Origin".equalsIgnoreCase(name)) origin.set(value);
                    if ("Sec-Fetch-Site".equalsIgnoreCase(name)) fetchSite.set(value);
                }
                boolean accepted = "https://player.example".equals(origin.get())
                        && "cross-site".equals(fetchSite.get());
                String status = accepted ? "200 OK" : "400 Bad Request";
                socket.getOutputStream().write(("HTTP/1.1 " + status
                        + "\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: "
                        + playlist.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(playlist);
                socket.getOutputStream().flush();
            } catch (Exception ignored) { }
        });

        String headers = "{\"Origin\":\"https://player.example\","
                + "\"Sec-Fetch-Site\":\"cross-site\",\"Host\":\"malicious.invalid\"}";
        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(),
                "Toolbox-Test", "", "https://player.example/watch", headers)) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/playlist.m3u8";
            try (Response response = new OkHttpClient().newCall(
                    new Request.Builder().url(proxy.urlFor(remote)).build()).execute()) {
                assertTrue("媒体 CDN 没有收到 WebView 播放请求头", response.isSuccessful());
            }
            assertEquals("https://player.example", origin.get());
            assertEquals("cross-site", fetchSite.get());
        } finally {
            upstream.close();
            worker.shutdownNow();
        }
    }

    @Test public void capturedRefererOverridesFallbackReferer() throws Exception {
        byte[] playlist = "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
        ServerSocket upstream = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<String> referer = new AtomicReference<>("");
        worker.execute(() -> {
            try (Socket socket = upstream.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                reader.readLine();
                while (true) {
                    String header = reader.readLine();
                    if (header == null || header.isEmpty()) break;
                    int colon = header.indexOf(':');
                    if (colon <= 0) continue;
                    if ("Referer".equalsIgnoreCase(header.substring(0, colon).trim())) {
                        referer.set(header.substring(colon + 1).trim());
                    }
                }
                String status = "https://player.example/embed".equals(referer.get())
                        ? "200 OK" : "400 Bad Request";
                socket.getOutputStream().write(("HTTP/1.1 " + status
                        + "\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: "
                        + playlist.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(playlist);
                socket.getOutputStream().flush();
            } catch (Exception ignored) { }
        });

        String headers = "{\"Referer\":\"https://player.example/embed\"}";
        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(),
                "Toolbox-Test", "", "https://page.example/watch", headers)) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/playlist.m3u8";
            try (Response response = new OkHttpClient().newCall(
                    new Request.Builder().url(proxy.urlFor(remote)).build()).execute()) {
                assertTrue("CDN 没有收到 WebView 实际请求携带的 Referer", response.isSuccessful());
            }
            assertEquals("https://player.example/embed", referer.get());
        } finally {
            upstream.close();
            worker.shutdownNow();
        }
    }

    @Test public void retriesPlaylistWithoutRangeOnClientError() throws Exception {
        byte[] playlist = "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
        ServerSocket upstream = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger rangedRequests = new AtomicInteger();
        worker.execute(() -> {
            while (!upstream.isClosed()) {
                try (Socket socket = upstream.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    reader.readLine();
                    boolean ranged = false;
                    while (true) {
                        String header = reader.readLine();
                        if (header == null || header.isEmpty()) break;
                        int colon = header.indexOf(':');
                        if (colon <= 0) continue;
                        if ("Range".equalsIgnoreCase(header.substring(0, colon).trim())) {
                            ranged = true;
                        }
                    }
                    if (ranged) rangedRequests.incrementAndGet();
                    String status = ranged ? "400 Bad Request" : "200 OK";
                    socket.getOutputStream().write(("HTTP/1.1 " + status
                            + "\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: "
                            + playlist.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(playlist);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) { }
            }
        });

        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(), "Toolbox-Test", "", "")) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/playlist.m3u8";
            // bytes=0- 现在会被归一化为完整请求，因此这里用真实的分段 Range 验证回退路径。
            Request request = new Request.Builder()
                    .url(proxy.urlFor(remote)).header("Range", "bytes=10-").build();
            try (Response response = new OkHttpClient().newCall(request).execute()) {
                assertTrue("带 Range 被 CDN 拒绝后应回退为完整请求重试", response.isSuccessful());
            }
            assertEquals("应当发起过一次带 Range 的请求", 1, rangedRequests.get());
        } finally {
            upstream.close();
            worker.shutdownNow();
        }
    }

    @Test public void servesByteZeroRangeAsFullContentWithoutUpstreamRange() throws Exception {
        byte[] payload = new byte[256];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        ServerSocket upstream = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger rangedRequests = new AtomicInteger();
        worker.execute(() -> {
            while (!upstream.isClosed()) {
                try (Socket socket = upstream.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    reader.readLine();
                    boolean ranged = false;
                    while (true) {
                        String header = reader.readLine();
                        if (header == null || header.isEmpty()) break;
                        int colon = header.indexOf(':');
                        if (colon <= 0) continue;
                        if ("Range".equalsIgnoreCase(header.substring(0, colon).trim())) ranged = true;
                    }
                    if (ranged) rangedRequests.incrementAndGet();
                    // 模拟对 Range 一律拒绝的 CDN：带 Range 返回 471，不带 Range 返回完整内容。
                    String status = ranged ? "471 No Range" : "200 OK";
                    socket.getOutputStream().write(("HTTP/1.1 " + status
                            + "\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                            + payload.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) { }
            }
        });

        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(), "Toolbox-Test", "", "")) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/sample.bin";
            Request request = new Request.Builder()
                    .url(proxy.urlFor(remote)).header("Range", "bytes=0-").build();
            try (Response response = new OkHttpClient().newCall(request).execute()) {
                assertTrue("bytes=0- 等价于完整请求，不应被 471 拒绝", response.isSuccessful());
                assertArrayEquals("应原样返回完整内容", payload,
                        response.body() == null ? null : response.body().bytes());
            }
            assertEquals("bytes=0- 不应把 Range 透传给上游", 0, rangedRequests.get());
        } finally {
            upstream.close();
            worker.shutdownNow();
        }
    }

    @Test public void doesNotPrefetchConsumedSegmentAgainAfterPlaylistRefresh() throws Exception {
        String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n"
                + "#EXTINF:2,\nsegment-0.ts\n#EXT-X-ENDLIST\n";
        byte[] playlistBytes = playlist.getBytes(StandardCharsets.UTF_8);
        byte[] segmentBytes = new byte[188];
        AtomicInteger segmentRequests = new AtomicInteger();
        ServerSocket upstream = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        ExecutorService serverWorkers = Executors.newCachedThreadPool();
        serverWorkers.execute(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket accepted = upstream.accept();
                    serverWorkers.execute(() -> {
                        try (Socket socket = accepted) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.US_ASCII));
                            String requestLine = reader.readLine();
                            while (true) {
                                String header = reader.readLine();
                                if (header == null || header.isEmpty()) break;
                            }
                            boolean isPlaylist = requestLine != null && requestLine.contains("playlist.m3u8");
                            if (!isPlaylist) segmentRequests.incrementAndGet();
                            byte[] body = isPlaylist ? playlistBytes : segmentBytes;
                            String type = isPlaylist ? "application/vnd.apple.mpegurl" : "video/mp2t";
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + type
                                    + "\r\nContent-Length: " + body.length
                                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().write(body);
                            socket.getOutputStream().flush();
                        } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        });

        OkHttpClient client = new OkHttpClient();
        try (LocalMediaProxy proxy = new LocalMediaProxy(client, "Toolbox-Test", "", "")) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/playlist.m3u8";
            String proxyPlaylist = proxy.urlFor(remote);
            String rewritten;
            try (Response response = client.newCall(new Request.Builder().url(proxyPlaylist).build()).execute()) {
                rewritten = response.body() == null ? "" : response.body().string();
            }
            String segmentUrl = "";
            for (String line : rewritten.split("\\r?\\n")) {
                if (!line.isEmpty() && !line.startsWith("#")) segmentUrl = line;
            }
            assertFalse(segmentUrl.isEmpty());
            try (Response response = client.newCall(new Request.Builder().url(segmentUrl).build()).execute()) {
                assertEquals(segmentBytes.length, response.body() == null ? -1 : response.body().bytes().length);
            }
            try (Response ignored = client.newCall(new Request.Builder().url(proxyPlaylist).build()).execute()) {
                if (ignored.body() != null) ignored.body().bytes();
            }
            Thread.sleep(300);
            assertEquals("直播清单刷新后不应重复请求已消费的旧分片", 1, segmentRequests.get());
        } finally {
            upstream.close();
            serverWorkers.shutdownNow();
        }
    }

    @Test public void prefetchesHlsSegmentsInParallel() throws Exception {
        String playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n"
                + "#EXTINF:2,\nsegment-0.ts\n#EXTINF:2,\nsegment-1.ts\n"
                + "#EXTINF:2,\nsegment-2.ts\n#EXTINF:2,\nsegment-3.ts\n#EXT-X-ENDLIST\n";
        byte[] playlistBytes = playlist.getBytes(StandardCharsets.UTF_8);
        byte[] segmentBytes = new byte[188 * 8];
        ServerSocket upstream = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        ExecutorService serverWorkers = Executors.newCachedThreadPool();
        CountDownLatch simultaneousSegments = new CountDownLatch(4);
        AtomicInteger activeSegments = new AtomicInteger();
        AtomicInteger maxActiveSegments = new AtomicInteger();
        serverWorkers.execute(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket accepted = upstream.accept();
                    serverWorkers.execute(() -> {
                        try (Socket socket = accepted) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.US_ASCII));
                            String requestLine = reader.readLine();
                            while (true) {
                                String header = reader.readLine();
                                if (header == null || header.isEmpty()) break;
                            }
                            boolean isPlaylist = requestLine != null && requestLine.contains("playlist.m3u8");
                            if (!isPlaylist) {
                                int active = activeSegments.incrementAndGet();
                                maxActiveSegments.accumulateAndGet(active, Math::max);
                                simultaneousSegments.countDown();
                                simultaneousSegments.await(2, TimeUnit.SECONDS);
                            }
                            byte[] body = isPlaylist ? playlistBytes : segmentBytes;
                            String type = isPlaylist ? "application/vnd.apple.mpegurl" : "video/mp2t";
                            String headers = "HTTP/1.1 200 OK\r\nContent-Type: " + type
                                    + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().write(body);
                            socket.getOutputStream().flush();
                            if (!isPlaylist) activeSegments.decrementAndGet();
                        } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        });

        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(), "Toolbox-Test", "", "")) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/playlist.m3u8";
            try (Response response = new OkHttpClient().newCall(
                    new Request.Builder().url(proxy.urlFor(remote)).build()).execute()) {
                assertTrue(response.isSuccessful());
                assertTrue(response.body() != null && response.body().string().contains("segment.ts"));
            }
            assertTrue("预取窗口没有形成并发连接", simultaneousSegments.await(3, TimeUnit.SECONDS));
            assertTrue("HLS 分片没有形成四路以上并发", maxActiveSegments.get() >= 4);
        } finally {
            upstream.close();
            serverWorkers.shutdownNow();
        }
    }

    /** 可通过 instrumentation 的 mediaUrl 参数对真实站点做短时诊断；常规回归没有参数时自动跳过。 */
    @Test public void ffmpegReadsOptionalRemoteHls() throws Exception {
        String remote = InstrumentationRegistry.getArguments().getString("mediaUrl");
        assumeNotNull(remote);
        File output = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), "remote-hls-smoke.mp4");
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(), "Toolbox-Test", "", "")) {
            String command = "-y -allowed_extensions ALL -allowed_segment_extensions ALL -extension_picky 0 "
                    + "-live_start_index 0 -i '"
                    + proxy.urlFor(remote) + "' -t 2 -c copy -bsf:a aac_adtstoasc '"
                    + output.getAbsolutePath() + "'";
            FFmpegSession session = FFmpegKit.execute(command);
            assertTrue(session.getOutput(), ReturnCode.isSuccess(session.getReturnCode()));
            assertTrue(output.isFile() && output.length() > 0);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }
    }

    @Test public void rewritesRelativeHlsResources() throws Exception {
        String playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
                + "variant.m3u8\nsegment.ts\n";
        byte[] payload = playlist.getBytes(StandardCharsets.UTF_8);
        ServerSocket upstream = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> serveOnce(upstream, payload, "application/vnd.apple.mpegurl"));
        OkHttpClient client = new OkHttpClient();
        try (LocalMediaProxy proxy = new LocalMediaProxy(client, "Toolbox-Test", "", "")) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/master.m3u8";
            try (Response response = client.newCall(new Request.Builder().url(proxy.urlFor(remote)).build()).execute()) {
                String rewritten = response.body() == null ? "" : response.body().string();
                assertTrue(response.isSuccessful());
                assertTrue(rewritten.contains("http://127.0.0.1:"));
                assertTrue(rewritten.contains("/playlist.m3u8"));
                assertTrue(rewritten.contains("/segment.ts"));
                assertFalse(rewritten.contains("URI=\"key.bin\""));
                assertFalse(rewritten.contains("\nvariant.m3u8\n"));
                assertFalse(rewritten.contains("\nsegment.ts\n"));
            }
        } finally {
            upstream.close();
            worker.shutdownNow();
        }
    }

    @Test public void givesExtensionlessHlsUrisMediaTypeHints() throws Exception {
        String playlist = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio?id=1\"\n"
                + "#EXTINF:4,\nsegment?id=2\n";
        ServerSocket upstream = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> serveOnce(upstream, playlist.getBytes(StandardCharsets.UTF_8),
                "application/vnd.apple.mpegurl"));
        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(), "", "", "")) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/master.m3u8";
            try (Response response = new OkHttpClient().newCall(new Request.Builder().url(proxy.urlFor(remote)).build()).execute()) {
                String rewritten = response.body().string();
                assertTrue(rewritten.contains("/playlist.m3u8"));
                assertTrue(rewritten.contains("/segment.ts"));
                assertFalse("首次短清单不能被误判为完整视频", rewritten.contains("#EXT-X-ENDLIST"));
            }
        } finally {
            upstream.close();
            worker.shutdownNow();
        }
    }

    @Test public void ffmpegReadsMediaThroughProxy() throws Exception {
        byte[] wav = silentWav();
        ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        ExecutorService serverWorker = Executors.newSingleThreadExecutor();
        serverWorker.execute(() -> {
            while (!upstream.isClosed()) {
                try (Socket socket = upstream.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    while (true) {
                        String line = reader.readLine();
                        if (line == null || line.isEmpty()) break;
                    }
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: "
                            + wav.length + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(wav);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) { }
            }
        });

        File output = new File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir(), "proxy-smoke.wav");
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(), "Toolbox-Test", "", "")) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/sample.wav";
            String command = "-y -i '" + proxy.urlFor(remote) + "' -c copy '" + output.getAbsolutePath() + "'";
            FFmpegSession session = FFmpegKit.execute(command);
            assertTrue(session.getOutput(), ReturnCode.isSuccess(session.getReturnCode()));
            assertTrue(output.isFile() && output.length() > 44);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            upstream.close();
            serverWorker.shutdownNow();
        }
    }

    @Test public void ffmpegMergesHlsSegmentsWithPreservedProxyExtensions() throws Exception {
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File segment = new File(cache, "proxy-hls-segment.ts");
        File output = new File(cache, "proxy-hls-output.mp4");
        //noinspection ResultOfMethodCallIgnored
        segment.delete();
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        FFmpegSession generated = FFmpegKit.execute("-y -f lavfi -i testsrc2=size=128x96:rate=10 "
                + "-t 1.2 -c:v libopenh264 -g 10 -pix_fmt yuv420p -mpegts_flags resend_headers "
                + "-f mpegts '" + segment.getAbsolutePath() + "'");
        assertTrue(generated.getOutput(), ReturnCode.isSuccess(generated.getReturnCode()));
        byte[] keyBytes = "Toolbox-HLS-Key!".getBytes(StandardCharsets.US_ASCII);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(new byte[16]));
        byte[] segmentBytes = cipher.doFinal(java.nio.file.Files.readAllBytes(segment.toPath()));
        byte[] firstPlaylist = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.ts\",IV=0x00000000000000000000000000000000\n"
                + "#EXTINF:1.2,\nsegment-0.ts\n").getBytes(StandardCharsets.UTF_8);
        byte[] completedPlaylist = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.ts\",IV=0x00000000000000000000000000000000\n"
                + "#EXTINF:1.2,\nsegment-0.ts\n#EXTINF:1.2,\nsegment-1.ts\n"
                + "#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);

        ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger playlistRequests = new AtomicInteger();
        worker.execute(() -> {
            while (!upstream.isClosed()) {
                try (Socket socket = upstream.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String requestLine = reader.readLine();
                    while (true) {
                        String line = reader.readLine();
                        if (line == null || line.isEmpty()) break;
                    }
                    boolean wantsPlaylist = requestLine != null && requestLine.contains("playlist.m3u8");
                    boolean wantsKey = requestLine != null && requestLine.contains("key.ts");
                    byte[] body = wantsPlaylist
                            ? (playlistRequests.incrementAndGet() == 1 ? firstPlaylist : completedPlaylist)
                            : wantsKey ? keyBytes : segmentBytes;
                    String type = wantsPlaylist ? "application/vnd.apple.mpegurl"
                            : wantsKey ? "application/octet-stream" : "video/mp2t";
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: " + type
                            + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (Exception ignored) { }
            }
        });

        try (LocalMediaProxy proxy = new LocalMediaProxy(new OkHttpClient(), "Toolbox-Test", "", "")) {
            String remote = "http://127.0.0.1:" + upstream.getLocalPort() + "/playlist.m3u8";
            String command = "-y -allowed_extensions ALL -allowed_segment_extensions ALL -extension_picky 0 "
                    + "-live_start_index 0 -i '" + proxy.urlFor(remote)
                    + "' -c copy -bsf:a aac_adtstoasc '" + output.getAbsolutePath() + "'";
            FFmpegSession session = FFmpegKit.execute(command);
            assertTrue(session.getOutput(), ReturnCode.isSuccess(session.getReturnCode()));
            assertTrue(output.isFile() && output.length() > 0);
            assertTrue("FFmpeg 没有刷新增长中的 m3u8", playlistRequests.get() >= 2);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            segment.delete();
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            upstream.close();
            worker.shutdownNow();
        }
    }

    private static byte[] silentWav() {
        int dataSize = 8_000;
        byte[] wav = new byte[44 + dataSize];
        put(wav, 0, "RIFF"); putInt(wav, 4, 36 + dataSize); put(wav, 8, "WAVE");
        put(wav, 12, "fmt "); putInt(wav, 16, 16); putShort(wav, 20, 1);
        putShort(wav, 22, 1); putInt(wav, 24, 8_000); putInt(wav, 28, 8_000);
        putShort(wav, 32, 1); putShort(wav, 34, 8); put(wav, 36, "data");
        putInt(wav, 40, dataSize);
        return wav;
    }

    private static void serveOnce(ServerSocket server, byte[] body, String contentType) {
        try (Socket socket = server.accept()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            while (true) {
                String line = reader.readLine();
                if (line == null || line.isEmpty()) break;
            }
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType
                    + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        } catch (Exception ignored) { }
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }
    private static void putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) value; target[offset + 1] = (byte) (value >>> 8);
    }
    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value; target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16); target[offset + 3] = (byte) (value >>> 24);
    }
}
