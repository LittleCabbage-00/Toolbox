package com.example.cryptoapp.Browser;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.example.cryptoapp.Activities.BrowserDownloadsActivity;
import com.example.cryptoapp.R;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp 前台下载服务。支持通知进度、暂停、Range 续传和取消，并保证未完成文件不可见。
 * 当前采用单任务模型，新的下载会在当前任务结束后才能开始，避免多个大文件争抢内存与带宽。
 */
public final class BrowserDownloadService extends Service {
    public static final String ACTION_START = "com.example.cryptoapp.download.START";
    public static final String ACTION_PAUSE = "com.example.cryptoapp.download.PAUSE";
    public static final String ACTION_RESUME = "com.example.cryptoapp.download.RESUME";
    public static final String ACTION_CANCEL = "com.example.cryptoapp.download.CANCEL";
    public static final String ACTION_DOWNLOAD_UPDATED = "com.example.cryptoapp.download.UPDATED";
    public static final String EXTRA_DOWNLOAD_ID = "download_id";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_USER_AGENT = "user_agent";
    public static final String EXTRA_COOKIES = "cookies";
    public static final String EXTRA_DISPOSITION = "content_disposition";
    public static final String EXTRA_MIME = "mime_type";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_RESOLVED_MIME = "resolved_mime";
    public static final String EXTRA_AUTO_CONVERT = "auto_convert";
    public static final String EXTRA_OUTPUT_FORMAT = "output_format";
    public static final String EXTRA_REFERER = "referer";
    public static final String EXTRA_LOCAL_SOURCE_PATH = "local_source_path";
    public static final String EXTRA_REQUEST_HEADERS = "request_headers";

    private static final String CHANNEL_ID = "browser_downloads";
    private static final String TAG = "ToolboxDownload";
    private static final int NOTIFICATION_ID = 3107;
    private static final String RELATIVE_DIRECTORY = Environment.DIRECTORY_DOWNLOADS + "/Toolbox";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true).build();

    private final Object lock = new Object();
    private DownloadTask task;
    private Call activeCall;
    private FFmpegSession activeSession;
    private LocalMediaProxy activeProxy;
    private Thread activeImportThread;
    private BrowserDownloadStore store;
    /** 最近一次交给 onStartCommand 的 startId，用于避免旧任务结束时误停刚启动的新任务。 */
    private volatile int latestStartId;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "网页文件下载", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示网页文件的下载进度与控制按钮");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        store = new BrowserDownloadStore(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            // 每一次 startForegroundService() 都必须尽快确认前台状态。即使当前已有任务，
            // 也不能在调用 startForeground() 之前直接返回，否则部分 ROM 会在超时后杀死进程。
            startForeground(NOTIFICATION_ID,
                    buildNotification("正在准备下载…", 0, true, false));
            startNewTask(intent);
        } else {
            DownloadTask current = task;
            if (current == null) {
                stopSelfResult(startId);
            } else if (intent == null) {
                // START_NOT_STICKY 正常不会重投递；个别系统仍可能传入空 Intent，保留当前任务即可。
                Log.w(TAG, "ignore null service intent while task is active");
            } else if (!matchesTask(intent)) {
                // 过期通知发出的控制命令不得停止当前下载服务。
                Log.w(TAG, "ignore stale control action=" + action);
            } else if (ACTION_PAUSE.equals(action)) {
                pauseTask();
            } else if (ACTION_RESUME.equals(action)) {
                resumeTask();
            } else if (ACTION_CANCEL.equals(action)) {
                cancelTask();
            }
        }
        return START_NOT_STICKY;
    }

    private void startNewTask(Intent intent) {
        synchronized (lock) {
            if (task != null && !task.finished) {
                deleteValidatedWebImage(value(intent, EXTRA_LOCAL_SOURCE_PATH));
                notifyMessage("已有文件正在下载，请先完成或取消当前任务");
                // 恢复当前任务的真实进度，避免上面的前台确认通知长期显示“正在准备”。
                updateNotification(buildNotification(
                        (task.paused ? "已暂停 · " : "正在下载 · ") + displayName(task),
                        progress(task), task.total <= 0, task.paused));
                return;
            }
            task = new DownloadTask(
                    intent.getLongExtra(EXTRA_DOWNLOAD_ID, System.currentTimeMillis()),
                    value(intent, EXTRA_URL), value(intent, EXTRA_USER_AGENT),
                    value(intent, EXTRA_COOKIES), value(intent, EXTRA_DISPOSITION), value(intent, EXTRA_MIME),
                    value(intent, EXTRA_FILE_NAME), value(intent, EXTRA_RESOLVED_MIME),
                    intent.getBooleanExtra(EXTRA_AUTO_CONVERT, false), value(intent, EXTRA_OUTPUT_FORMAT),
                    value(intent, EXTRA_REFERER), value(intent, EXTRA_LOCAL_SOURCE_PATH),
                    value(intent, EXTRA_REQUEST_HEADERS));
            Log.i(TAG, "start id=" + task.id + " source=" + safeEndpoint(task.url));
            updateRecord(task, BrowserDownloadStore.STATUS_DOWNLOADING, "", "");
            startForeground(NOTIFICATION_ID, buildNotification("准备下载…", 0, false, false));
            if (task.autoConvert) startFfmpegTask(task);
            else if (!task.localSourcePath.isEmpty()) startLocalImport(task);
            else startRequest(task);
        }
    }

    /** 将 WebView 中提取出的 blob/data 图片纳入统一下载记录并写入 Download/Toolbox。 */
    private void startLocalImport(DownloadTask target) {
        target.paused = false;
        activeImportThread = new Thread(() -> {
            File source = validatedWebImage(target.localSourcePath);
            if (source == null || !source.isFile()) {
                synchronized (lock) { if (target == task) failTask(target, "网页临时图片已经失效"); }
                return;
            }
            try {
                target.total = source.length();
                if (target.contentUri == null && target.partialFile == null) createDestination(target);
                try (FileInputStream input = new FileInputStream(source)) {
                    long skipped = 0;
                    while (skipped < target.downloaded) {
                        long count = input.skip(target.downloaded - skipped);
                        if (count <= 0) break;
                        skipped += count;
                    }
                    try (OutputStream output = openOutput(target, target.downloaded > 0)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        long lastUpdate = 0;
                        while ((count = input.read(buffer)) != -1) {
                            if (target.paused || target.cancelled || target != task) return;
                            output.write(buffer, 0, count);
                            target.downloaded += count;
                            long now = System.currentTimeMillis();
                            if (now - lastUpdate >= 300) { lastUpdate = now; updateProgress(target); }
                        }
                        output.flush();
                    }
                }
                synchronized (lock) {
                    if (!target.paused && !target.cancelled && target == task) completeTask(target);
                }
                //noinspection ResultOfMethodCallIgnored
                source.delete();
            } catch (Exception error) {
                synchronized (lock) {
                    if (!target.paused && !target.cancelled && target == task) {
                        failTask(target, error.getLocalizedMessage() == null ? "网页图片保存失败" : error.getLocalizedMessage());
                    }
                }
            } finally {
                activeImportThread = null;
            }
        }, "web-image-import");
        activeImportThread.start();
    }

    /** 嗅探媒体由 FFmpeg 直接读取远端资源，适用于包含大量分片的 m3u8。 */
    private void startFfmpegTask(DownloadTask target) {
        target.paused = false;
        target.downloaded = 0;
        target.total = -1;
        if (target.conversionFile != null && target.conversionFile.exists()) {
            // 暂停后继续会从清单开头重新封装，先清理上一次未完成的临时输出。
            //noinspection ResultOfMethodCallIgnored
            target.conversionFile.delete();
        }
        final File output;
        try {
            output = File.createTempFile("toolbox-sniff-", "." + target.outputFormat, getCacheDir());
        } catch (IOException error) {
            failTask(target, "无法创建转换缓存文件");
            return;
        }
        target.conversionFile = output;
        updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "", "FFmpeg 正在下载并转换");
        updateNotification(buildNotification("FFmpeg 正在下载并转换 · " + displayName(target), 0, true, false));
        try {
            closeActiveProxy();
            activeProxy = new LocalMediaProxy(CLIENT, target.userAgent, target.cookies,
                    target.referer, target.requestHeaders);
            String proxyInput = activeProxy.urlFor(target.url);
            activeSession = FFmpegKit.executeAsync(mediaCommand(target, output, proxyInput), session -> {
            synchronized (lock) {
                closeActiveProxy();
                if (target.paused || target.cancelled || target != task || session != activeSession) {
                    //noinspection ResultOfMethodCallIgnored
                    output.delete();
                    return;
                }
                if (!ReturnCode.isSuccess(session.getReturnCode())) {
                    String log = session.getOutput();
                    String detail = log == null || log.isEmpty() ? "FFmpeg 没有返回错误详情" : tail(log, 3000);
                    // 失败时记录完整 URL 与转发的请求头，便于核对 CDN 签名是否过期、请求是否被改写。
                    Log.e(TAG, "FFmpeg failed, returnCode=" + session.getReturnCode()
                            + " url=" + target.url
                            + " referer=" + target.referer
                            + " headers=" + (target.requestHeaders.isEmpty() ? "(empty)" : target.requestHeaders)
                            + "\n" + sanitizeLog(detail));
                    failTask(target, describeFfmpegFailure(detail));
                    return;
                }
                try {
                    createDestination(target);
                    try (OutputStream saved = openOutput(target, false);
                         var input = new java.io.FileInputStream(output)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) saved.write(buffer, 0, count);
                    }
                    target.downloaded = output.length();
                    target.total = output.length();
                    completeTask(target);
                } catch (Exception error) {
                    failTask(target, error.getLocalizedMessage() == null ? "转换文件保存失败" : error.getLocalizedMessage());
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    output.delete();
                }
            }
            }, log -> { }, statistics -> {
                if (target.paused || target.cancelled) return;
                long seconds = Math.max(0L, (long) (statistics.getTime() / 1000.0));
                updateNotification(buildNotification("已处理 " + seconds + " 秒 · " + displayName(target), 0, true, false));
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - target.lastConversionRecordUpdate >= 1_500L) {
                    target.lastConversionRecordUpdate = now;
                    // FFmpeg 直接写应用缓存，远端没有可靠总字节数；临时 MP4 的实时大小
                    // 至少能明确告诉用户解密与封装正在产出，而不是长期显示误导性的 0 B。
                    target.downloaded = output.length();
                    updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "",
                            "已处理媒体 " + seconds + " 秒 · 正在解密并合并");
                }
            });
        } catch (Throwable error) {
            // 原生库加载或命令初始化异常不能拖垮浏览器所在进程。
            closeActiveProxy();
            failTask(target, "FFmpeg 初始化失败：" + (error.getLocalizedMessage() == null ? error.getClass().getSimpleName() : error.getLocalizedMessage()));
        }
    }

    private String mediaCommand(DownloadTask target, File output, String inputUrl) {
        StringBuilder command = new StringBuilder("-y ");
        // HTTPS、Cookie、Referer 和 Range 均由本机 OkHttp 代理处理；FFmpeg 只读取环回 HTTP。
        // 部分站点把 AES 密钥伪装成 .ts，FFmpeg 8 会把它套入更严格的 HLS 分片后缀检查。
        // 播放列表中的所有 URI 已由 LocalMediaProxy 改写并限制在本机代理内，因此可安全放宽该检查。
        if (isHlsUrl(target.url)) {
            // 即使站点把点播清单标成直播，也必须从清单首段开始；FFmpeg 默认的 -3
            // 会从直播尾部倒数三段起步，最终得到一个能播放但缺少开头的不完整 MP4。
            command.append("-allowed_extensions ALL -allowed_segment_extensions ALL "
                    + "-extension_picky 0 -live_start_index 0 ");
        }
        command.append("-i ").append(quote(inputUrl)).append(' ');
        switch (target.outputFormat) {
            case "mkv": command.append("-map 0 -c copy "); break;
            case "mp3": command.append("-vn -c:a libmp3lame -q:a 2 "); break;
            case "m4a": command.append("-vn -c:a aac -b:a 192k "); break;
            case "flac": command.append("-vn -c:a flac "); break;
            default: command.append("-map 0 -c copy -bsf:a aac_adtstoasc -movflags +faststart ");
        }
        return command.append(quote(output.getAbsolutePath())).toString();
    }

    private String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private String tail(String value, int length) { return value.length() <= length ? value : value.substring(value.length() - length); }
    private boolean isHlsUrl(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        int query = lower.indexOf('?');
        return (query < 0 ? lower : lower.substring(0, query)).endsWith(".m3u8");
    }

    /** 下载记录可以展示完整错误，但必须隐藏本机代理 token，避免间接暴露原始鉴权 URL。 */
    private String sanitizeLog(String value) {
        return value.replaceAll("http://127\\.0\\.0\\.1:\\d+/media/[A-Za-z0-9_-]+",
                "<本机媒体代理>");
    }

    /** 把 CDN 常见的 HTTP 4xx 拒绝直接翻译成用户能看懂的原因，而不是只展示 FFmpeg 内部日志。*/
    private String describeFfmpegFailure(String detail) {
        Matcher matcher = Pattern.compile("HTTP error (\\d{3})").matcher(detail);
        if (matcher.find()) {
            return "视频源返回 HTTP " + matcher.group(1)
                    + "，链接可能已失效或缺少访问请求头\n" + sanitizeLog(detail);
        }
        return "FFmpeg 下载或转换失败\n" + sanitizeLog(detail);
    }

    private void startRequest(DownloadTask target) {
        Request.Builder builder = new Request.Builder().url(target.url);
        if (!target.userAgent.isEmpty()) builder.header("User-Agent", target.userAgent);
        if (target.headerRetryStep < 2 && !target.cookies.isEmpty()) builder.header("Cookie", target.cookies);
        // 网页自身能显示但直接请求返回 403/占位图时，通常是图片 CDN 的 Referer 防盗链。
        if (target.headerRetryStep == 0 && !target.referer.isEmpty()) builder.header("Referer", target.referer);
        if (target.resolvedMime != null && target.resolvedMime.startsWith("image/")) {
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
        }
        applyCapturedHeaders(builder, target);
        if (target.downloaded > 0) builder.header("Range", "bytes=" + target.downloaded + "-");
        target.paused = false;
        activeCall = CLIENT.newCall(builder.build());
        activeCall.enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException error) {
                synchronized (lock) {
                    if (target.paused || target.cancelled || target != task) return;
                    failTask(target, error.getLocalizedMessage() == null ? "网络连接失败" : error.getLocalizedMessage());
                }
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (shouldRetryWithoutPageContext(target, response)) {
                    response.close();
                    target.headerRetryStep++;
                    Log.w(TAG, "retry id=" + target.id + " step=" + target.headerRetryStep
                            + " http=" + response.code() + " source=" + safeEndpoint(target.url));
                    updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "",
                            target.headerRetryStep == 1 ? "图片服务器拒绝页面来源，正在无 Referer 重试"
                                    : "正在使用纯净直链重试");
                    startRequest(target);
                    return;
                }
                handleResponse(target, response);
            }
        });
    }

    private void applyCapturedHeaders(Request.Builder request, DownloadTask target) {
        if (target.requestHeaders.isEmpty()) return;
        try {
            JSONObject headers = new JSONObject(target.requestHeaders);
            Iterator<String> names = headers.keys();
            while (names.hasNext()) {
                String name = names.next();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                boolean basic = "accept".equals(lower) || "accept-language".equals(lower)
                        || "referer".equals(lower);
                boolean pageContext = "origin".equals(lower) || lower.startsWith("sec-fetch-")
                        || "x-requested-with".equals(lower) || "purpose".equals(lower)
                        || "dpr".equals(lower) || "width".equals(lower)
                        || "viewport-width".equals(lower) || "save-data".equals(lower);
                if (!(basic || (target.headerRetryStep == 0 && pageContext))) continue;
                String value = headers.optString(name, "");
                if (!value.isEmpty() && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
                    request.header(name, value);
                }
            }
        } catch (Exception ignored) { }
    }

    private boolean shouldRetryWithoutPageContext(DownloadTask target, Response response) {
        if (target.downloaded > 0 || target.headerRetryStep >= 2) return false;
        int code = response.code();
        if (code == 401 || code == 403) return true;
        String expected = target.resolvedMime == null ? "" : target.resolvedMime;
        String actual = response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT);
        // 一些图床用 200 + HTML 防盗链提示页伪装成功响应，不能把它保存成损坏图片。
        return expected.startsWith("image/") && actual.startsWith("text/html");
    }

    private void handleResponse(DownloadTask target, Response response) {
        try (response) {
            if (!response.isSuccessful()) throw new IOException("服务器返回 HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("服务器没有返回文件内容");
            boolean resumed = target.downloaded > 0 && response.code() == 206;
            if (target.downloaded > 0 && !resumed) {
                // 服务器忽略 Range 时从头覆盖，防止将完整响应追加到半个文件后造成损坏。
                target.downloaded = 0;
            }
            if (target.contentUri == null && target.partialFile == null) {
                BrowserDownloadResolver.DownloadInfo info = BrowserDownloadResolver.resolve(
                        response.request().url().toString(),
                        response.header("Content-Disposition", target.contentDisposition),
                        response.header("Content-Type", target.mimeType));
                target.fileName = info.getFileName();
                target.resolvedMime = info.getMimeType();
                createDestination(target);
                updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "", "");
            }
            long remaining = body.contentLength();
            target.total = remaining > 0 ? target.downloaded + remaining : -1;
            try (OutputStream output = openOutput(target, resumed);
                 var input = body.byteStream()) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                long lastUpdate = 0;
                while ((count = input.read(buffer)) != -1) {
                    if (target.paused || target.cancelled) return;
                    output.write(buffer, 0, count);
                    target.downloaded += count;
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate >= 500) {
                        lastUpdate = now;
                        updateProgress(target);
                    }
                }
                output.flush();
            }
            synchronized (lock) {
                if (!target.paused && !target.cancelled && target == task) completeTask(target);
            }
        } catch (Exception error) {
            synchronized (lock) {
                if (!target.paused && !target.cancelled && target == task) {
                    failTask(target, error.getLocalizedMessage() == null ? "文件保存失败" : error.getLocalizedMessage());
                }
            }
        }
    }

    private void pauseTask() {
        synchronized (lock) {
            if (task == null || task.finished || task.paused) return;
            task.paused = true;
            if (activeCall != null) activeCall.cancel();
            if (activeSession != null) activeSession.cancel();
            closeActiveProxy();
            updateRecord(task, BrowserDownloadStore.STATUS_PAUSED, "", "");
            updateNotification(buildNotification("已暂停 · " + displayName(task), progress(task), true, true));
        }
    }

    private void resumeTask() {
        synchronized (lock) {
            if (task == null || task.finished || !task.paused) return;
            updateRecord(task, BrowserDownloadStore.STATUS_DOWNLOADING, "", "");
            updateNotification(buildNotification("正在继续 · " + displayName(task), progress(task), false, false));
            if (task.autoConvert) startFfmpegTask(task);
            else if (!task.localSourcePath.isEmpty()) startLocalImport(task);
            else startRequest(task);
        }
    }

    private void cancelTask() {
        synchronized (lock) {
            if (task == null) return;
            task.cancelled = true;
            if (activeCall != null) activeCall.cancel();
            if (activeSession != null) activeSession.cancel();
            if (activeImportThread != null) activeImportThread.interrupt();
            closeActiveProxy();
            deletePartial(task);
            task.finished = true;
            updateRecord(task, BrowserDownloadStore.STATUS_CANCELLED, "", "");
            updateNotification(new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download_24).setContentTitle("下载已取消")
                    .setContentText(displayName(task)).setAutoCancel(true).build());
            stopServiceAfterTask();
        }
    }

    private void completeTask(DownloadTask target) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(target.contentUri, ready, null, null);
        } else if (target.partialFile != null && target.finalFile != null
                && !target.partialFile.renameTo(target.finalFile)) {
            throw new IOException("无法完成文件重命名");
        }
        target.finished = true;
        Log.i(TAG, "completed id=" + target.id + " source=" + safeEndpoint(target.url)
                + " bytes=" + target.downloaded);
        String localUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            localUri = target.contentUri.toString();
        } else {
            localUri = FileProvider.getUriForFile(this, getPackageName() + ".files", target.finalFile).toString();
        }
        updateRecord(target, BrowserDownloadStore.STATUS_COMPLETED, localUri, "");
        NotificationCompat.Builder done = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle("下载完成")
                .setContentText("Download/Toolbox/" + displayName(target))
                .setContentIntent(downloadsIntent())
                .setAutoCancel(true);
        updateNotification(done.build());
        stopServiceAfterTask();
    }

    private void failTask(DownloadTask target, String reason) {
        Log.e(TAG, "failed id=" + target.id + " source=" + safeEndpoint(target.url) + " reason=" + reason);
        closeActiveProxy();
        deletePartial(target);
        target.finished = true;
        updateRecord(target, BrowserDownloadStore.STATUS_FAILED, "", reason);
        NotificationCompat.Builder failed = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle("下载失败")
                .setContentText(reason)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reason))
                .setAutoCancel(true);
        updateNotification(failed.build());
        stopServiceAfterTask();
    }

    /**
     * 只处理到当前最新启动命令时才停止服务。若此刻已有更新的启动命令到达，
     * stopSelfResult 会保留服务，从而避免旧任务的完成回调把新下载一起终止。
     */
    private void stopServiceAfterTask() {
        stopForeground(false);
        stopSelfResult(latestStartId);
    }

    private String safeEndpoint(String value) {
        try {
            Uri uri = Uri.parse(value);
            return uri.getScheme() + "://" + (uri.getHost() == null ? "local" : uri.getHost());
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private void createDestination(DownloadTask target) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, target.fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, target.resolvedMime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIRECTORY);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            target.contentUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target.contentUri == null) throw new IOException("无法在系统下载目录创建文件");
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "Toolbox");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建 Download/Toolbox 文件夹");
            target.finalFile = uniqueFile(directory, target.fileName);
            target.fileName = target.finalFile.getName();
            target.partialFile = new File(directory, target.fileName + ".part");
        }
    }

    private OutputStream openOutput(DownloadTask target, boolean append) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            OutputStream output = getContentResolver().openOutputStream(target.contentUri, append ? "wa" : "w");
            if (output == null) throw new IOException("无法打开下载文件");
            return output;
        }
        return new FileOutputStream(target.partialFile, append);
    }

    private void deletePartial(DownloadTask target) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.contentUri != null) {
            getContentResolver().delete(target.contentUri, null, null);
        } else if (target.partialFile != null && target.partialFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.partialFile.delete();
        }
        if (target.conversionFile != null && target.conversionFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.conversionFile.delete();
        }
        deleteValidatedWebImage(target.localSourcePath);
    }

    private File validatedWebImage(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            File file = new File(path).getCanonicalFile();
            File cache = getCacheDir().getCanonicalFile();
            if (!file.getParentFile().equals(cache) || !file.getName().startsWith("toolbox-web-image-")) return null;
            return file;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void deleteValidatedWebImage(String path) {
        File file = validatedWebImage(path);
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void updateProgress(DownloadTask target) {
        int percent = progress(target);
        updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "", "");
        String text = target.total > 0 ? percent + "% · " + displayName(target) : "正在下载 · " + displayName(target);
        updateNotification(buildNotification(text, percent, target.total <= 0, false));
    }

    private android.app.Notification buildNotification(String text, int percent, boolean indeterminate, boolean paused) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle("Toolbox 网页下载")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(downloadsIntent())
                .setProgress(100, percent, indeterminate)
                .addAction(paused ? R.drawable.ic_play_24 : R.drawable.ic_pause_24,
                        paused ? "继续" : "暂停", serviceIntent(paused ? ACTION_RESUME : ACTION_PAUSE, 1))
                .addAction(R.drawable.ic_stop_24, "取消", serviceIntent(ACTION_CANCEL, 2));
        return builder.build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, BrowserDownloadService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent downloadsIntent() {
        Intent intent = new Intent(this, BrowserDownloadsActivity.class);
        return PendingIntent.getActivity(this, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification(android.app.Notification notification) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
    }

    private void notifyMessage(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID + 1,
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_download_24).setContentTitle("Toolbox 网页下载")
                        .setContentText(text).setAutoCancel(true).build());
    }

    private int progress(DownloadTask target) {
        return target.total > 0 ? (int) Math.min(100, target.downloaded * 100 / target.total) : 0;
    }

    private String displayName(DownloadTask target) {
        return target.fileName == null ? "网页文件" : target.fileName;
    }

    private String value(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value;
    }

    private boolean matchesTask(Intent intent) {
        long requested = intent.getLongExtra(EXTRA_DOWNLOAD_ID, task.id);
        return requested == task.id;
    }

    private void updateRecord(DownloadTask target, String status, String localUri, String error) {
        long now = System.currentTimeMillis();
        store.upsert(new BrowserDownloadStore.DownloadRecord(
                target.id, displayName(target), target.url,
                target.resolvedMime == null || target.resolvedMime.isEmpty() ? "application/octet-stream" : target.resolvedMime,
                status, target.downloaded, target.total, localUri, error, target.createdAt, now));
        sendBroadcast(new Intent(ACTION_DOWNLOAD_UPDATED).setPackage(getPackageName()));
    }

    private File uniqueFile(File directory, String original) {
        File target = new File(directory, original);
        if (!target.exists() && !new File(directory, original + ".part").exists()) return target;
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        String extension = dot > 0 ? original.substring(dot) : "";
        for (int index = 1; index < 10_000; index++) {
            target = new File(directory, base + " (" + index + ")" + extension);
            if (!target.exists() && !new File(target.getPath() + ".part").exists()) return target;
        }
        return new File(directory, base + "_" + System.currentTimeMillis() + extension);
    }

    @Override public void onDestroy() {
        if (activeCall != null) activeCall.cancel();
        if (activeSession != null) activeSession.cancel();
        if (activeImportThread != null) activeImportThread.interrupt();
        closeActiveProxy();
        synchronized (lock) {
            if (task != null && !task.finished && !task.cancelled) {
                updateRecord(task, BrowserDownloadStore.STATUS_FAILED, "", "下载服务已停止");
            }
        }
        super.onDestroy();
    }

    private void closeActiveProxy() {
        if (activeProxy == null) return;
        activeProxy.close();
        activeProxy = null;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static final class DownloadTask {
        final long id;
        final long createdAt = System.currentTimeMillis();
        final String url;
        final String userAgent;
        final String cookies;
        final String contentDisposition;
        final String mimeType;
        volatile boolean paused;
        volatile boolean cancelled;
        boolean finished;
        long downloaded;
        long total = -1;
        String fileName;
        String resolvedMime;
        Uri contentUri;
        File finalFile;
        File partialFile;
        File conversionFile;
        volatile long lastConversionRecordUpdate;
        final boolean autoConvert;
        final String outputFormat;
        final String referer;
        final String localSourcePath;
        final String requestHeaders;
        int headerRetryStep;

        DownloadTask(long id, String url, String userAgent, String cookies, String contentDisposition,
                     String mimeType, String fileName, String resolvedMime, boolean autoConvert,
                     String outputFormat, String referer, String localSourcePath, String requestHeaders) {
            this.id = id;
            this.url = url;
            this.userAgent = userAgent;
            this.cookies = cookies;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.fileName = fileName.isEmpty() ? "网页文件" : fileName;
            this.resolvedMime = resolvedMime;
            this.autoConvert = autoConvert;
            this.outputFormat = outputFormat.isEmpty() ? "mp4" : outputFormat;
            this.referer = referer;
            this.localSourcePath = localSourcePath;
            this.requestHeaders = requestHeaders;
        }
    }
}
