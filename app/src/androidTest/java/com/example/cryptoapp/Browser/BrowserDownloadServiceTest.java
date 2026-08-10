package com.example.cryptoapp.Browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** WebView blob/data 图片提取完成后，必须能进入统一下载服务并写入公共下载目录。 */
@RunWith(AndroidJUnit4.class)
public class BrowserDownloadServiceTest {
    @Test public void retriesDirectImageWithoutRejectedReferer() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long id = System.currentTimeMillis();
        byte[] image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean cleanRetry = new AtomicBoolean();
        worker.execute(() -> {
            while (!server.isClosed() && requests.get() < 2) {
                try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    reader.readLine();
                    boolean hasReferer = false;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("referer:")) hasReferer = true;
                    }
                    int attempt = requests.incrementAndGet();
                    if (attempt == 1 && hasReferer) {
                        byte[] body = "forbidden".getBytes(StandardCharsets.US_ASCII);
                        writeResponse(socket, 403, "text/plain", body);
                    } else {
                        cleanRetry.set(!hasReferer);
                        writeResponse(socket, 200, "image/png", image);
                    }
                } catch (Exception ignored) { }
            }
        });

        Intent service = new Intent(context, BrowserDownloadService.class)
                .setAction(BrowserDownloadService.ACTION_START)
                .putExtra(BrowserDownloadService.EXTRA_DOWNLOAD_ID, id)
                .putExtra(BrowserDownloadService.EXTRA_URL,
                        "http://127.0.0.1:" + server.getLocalPort() + "/direct.png")
                .putExtra(BrowserDownloadService.EXTRA_FILE_NAME, "toolbox-http-test-" + id + ".png")
                .putExtra(BrowserDownloadService.EXTRA_MIME, "image/png")
                .putExtra(BrowserDownloadService.EXTRA_RESOLVED_MIME, "image/png")
                .putExtra(BrowserDownloadService.EXTRA_REFERER, "https://blocked.example/page");
        ContextCompat.startForegroundService(context, service);
        BrowserDownloadStore store = new BrowserDownloadStore(context);
        BrowserDownloadStore.DownloadRecord found = waitForCompleted(store, id);
        assertNotNull(found);
        assertEquals(2, requests.get());
        assertTrue(cleanRetry.get());
        cleanup(context, store, found);
        server.close();
        worker.shutdownNow();
    }

    @Test public void importsExtractedWebImage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long id = System.currentTimeMillis();
        byte[] image = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        File source = File.createTempFile("toolbox-web-image-", ".part", context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(source)) { output.write(image); }

        Intent service = new Intent(context, BrowserDownloadService.class)
                .setAction(BrowserDownloadService.ACTION_START)
                .putExtra(BrowserDownloadService.EXTRA_DOWNLOAD_ID, id)
                .putExtra(BrowserDownloadService.EXTRA_URL, "blob:https://example.test/image")
                .putExtra(BrowserDownloadService.EXTRA_FILE_NAME, "toolbox-blob-test-" + id + ".png")
                .putExtra(BrowserDownloadService.EXTRA_MIME, "image/png")
                .putExtra(BrowserDownloadService.EXTRA_RESOLVED_MIME, "image/png")
                .putExtra(BrowserDownloadService.EXTRA_LOCAL_SOURCE_PATH, source.getAbsolutePath());
        ContextCompat.startForegroundService(context, service);

        BrowserDownloadStore store = new BrowserDownloadStore(context);
        BrowserDownloadStore.DownloadRecord found = waitForCompleted(store, id);
        assertNotNull(found);
        assertEquals(image.length, found.getDownloaded());
        cleanup(context, store, found);
        //noinspection ResultOfMethodCallIgnored
        source.delete();
    }

    private static BrowserDownloadStore.DownloadRecord waitForCompleted(
            BrowserDownloadStore store, long id) throws InterruptedException {
        BrowserDownloadStore.DownloadRecord found = null;
        for (int attempt = 0; attempt < 50 && found == null; attempt++) {
            Thread.sleep(100);
            for (BrowserDownloadStore.DownloadRecord record : store.getAll()) {
                if (record.getId() == id && BrowserDownloadStore.STATUS_COMPLETED.equals(record.getStatus())) {
                    found = record;
                    break;
                }
            }
        }
        return found;
    }

    private static void cleanup(Context context, BrowserDownloadStore store,
                                BrowserDownloadStore.DownloadRecord found) {
        if (!found.getLocalUri().isEmpty()) {
            context.getContentResolver().delete(Uri.parse(found.getLocalUri()), null, null);
        }
        store.delete(found.getId());
        context.stopService(new Intent(context, BrowserDownloadService.class));
    }

    private static void writeResponse(Socket socket, int code, String type, byte[] body) throws Exception {
        String message = code == 200 ? "OK" : "Forbidden";
        String headers = "HTTP/1.1 " + code + " " + message + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }
}
