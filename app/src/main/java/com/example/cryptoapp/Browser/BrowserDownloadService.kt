package com.example.cryptoapp.Browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.cryptoapp.Activities.BrowserDownloadsActivity
import com.example.cryptoapp.R
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * OkHttp 前台下载服务。支持通知进度、暂停、Range 续传和取消，并保证未完成文件不可见。
 * 当前采用单任务模型，新的下载会在当前任务结束后才能开始，避免多个大文件争抢内存与带宽。
 */
class BrowserDownloadService : Service() {
    private val lock = Any()
    private var task: DownloadTask? = null
    private var activeCall: Call? = null
    private var activeSession: FFmpegSession? = null
    private var activeProxy: LocalMediaProxy? = null
    private var activeImportThread: Thread? = null
    private lateinit var store: BrowserDownloadStore
    /** 最近一次交给 onStartCommand 的 startId，用于避免旧任务结束时误停刚启动的新任务。 */
    @Volatile
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "网页文件下载", NotificationManager.IMPORTANCE_LOW)
        channel.setDescription("显示网页文件的下载进度与控制按钮")
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        store = BrowserDownloadStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action
        if (ACTION_START == action) {
            // 每一次 startForegroundService() 都必须尽快确认前台状态。即使当前已有任务，
            // 也不能在调用 startForeground() 之前直接返回，否则部分 ROM 会在超时后杀死进程。
            startForeground(NOTIFICATION_ID,
                buildNotification("正在准备下载…", 0, true, false))
            startNewTask(intent!!)
        } else {
            val current = task
            if (current == null) {
                stopSelfResult(startId)
            } else if (intent == null) {
                // START_NOT_STICKY 正常不会重投递；个别系统仍可能传入空 Intent，保留当前任务即可。
                Log.w(TAG, "ignore null service intent while task is active")
            } else if (!matchesTask(intent)) {
                // 过期通知发出的控制命令不得停止当前下载服务。
                Log.w(TAG, "ignore stale control action=" + action)
            } else if (ACTION_PAUSE == action) {
                pauseTask()
            } else if (ACTION_RESUME == action) {
                resumeTask()
            } else if (ACTION_CANCEL == action) {
                cancelTask()
            }
        }
        return START_NOT_STICKY
    }

    private fun startNewTask(intent: Intent) {
        synchronized(lock) {
            val existing = task
            if (existing != null && !existing.finished) {
                deleteValidatedWebImage(value(intent, EXTRA_LOCAL_SOURCE_PATH))
                notifyMessage("已有文件正在下载，请先完成或取消当前任务")
                // 恢复当前任务的真实进度，避免上面的前台确认通知长期显示“正在准备”。
                updateNotification(buildNotification(
                    (if (existing.paused) "已暂停 · " else "正在下载 · ") + displayName(existing),
                    progress(existing), existing.total <= 0, existing.paused))
                return
            }
            task = DownloadTask(
                intent.getLongExtra(EXTRA_DOWNLOAD_ID, System.currentTimeMillis()),
                value(intent, EXTRA_URL), value(intent, EXTRA_USER_AGENT),
                value(intent, EXTRA_COOKIES), value(intent, EXTRA_DISPOSITION), value(intent, EXTRA_MIME),
                value(intent, EXTRA_FILE_NAME), value(intent, EXTRA_RESOLVED_MIME),
                intent.getBooleanExtra(EXTRA_AUTO_CONVERT, false), value(intent, EXTRA_OUTPUT_FORMAT),
                value(intent, EXTRA_REFERER), value(intent, EXTRA_LOCAL_SOURCE_PATH),
                value(intent, EXTRA_REQUEST_HEADERS),
                intent.getStringArrayExtra(EXTRA_FALLBACK_URLS))
            val started = task!!
            Log.i(TAG, "start id=" + started.id + " source=" + safeEndpoint(started.url))
            updateRecord(started, BrowserDownloadStore.STATUS_DOWNLOADING, "", "")
            startForeground(NOTIFICATION_ID, buildNotification("准备下载…", 0, false, false))
            if (started.autoConvert) startFfmpegTask(started)
            else if (started.localSourcePath.isNotEmpty()) startLocalImport(started)
            else startRequest(started)
        }
    }

    /** 将 WebView 中提取出的 blob/data 图片纳入统一下载记录并写入 Download/Toolbox。 */
    private fun startLocalImport(target: DownloadTask) {
        target.paused = false
        activeImportThread = Thread(Runnable {
            val source = validatedWebImage(target.localSourcePath)
            if (source == null || !source.isFile()) {
                synchronized(lock) { if (target == task) failTask(target, "网页临时图片已经失效") }
                return@Runnable
            }
            try {
                target.total = source.length()
                if (target.contentUri == null && target.partialFile == null) createDestination(target)
                FileInputStream(source).use { input ->
                    var skipped = 0L
                    while (skipped < target.downloaded) {
                        val count = input.skip(target.downloaded - skipped)
                        if (count <= 0) break
                        skipped += count
                    }
                    openOutput(target, target.downloaded > 0).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastUpdate = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (target.paused || target.cancelled || target != task) return@Runnable
                            output.write(buffer, 0, count)
                            target.downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 300) {
                                lastUpdate = now
                                updateProgress(target)
                            }
                        }
                        output.flush()
                    }
                }
                synchronized(lock) {
                    if (!target.paused && !target.cancelled && target == task) completeTask(target)
                }
                source.delete()
            } catch (error: Exception) {
                synchronized(lock) {
                    if (!target.paused && !target.cancelled && target == task) {
                        failTask(target, error.localizedMessage ?: "网页图片保存失败")
                    }
                }
            } finally {
                activeImportThread = null
            }
        }, "web-image-import")
        activeImportThread!!.start()
    }

    /** 嗅探媒体由 FFmpeg 直接读取远端资源，适用于包含大量分片的 m3u8。 */
    private fun startFfmpegTask(target: DownloadTask) {
        target.paused = false
        target.downloaded = 0
        target.total = -1
        if (target.conversionFile != null && target.conversionFile!!.exists()) {
            // 暂停后继续会从清单开头重新封装，先清理上一次未完成的临时输出。
            target.conversionFile!!.delete()
        }
        val output: File = try {
            File.createTempFile("toolbox-sniff-", "." + target.outputFormat, cacheDir)
        } catch (error: IOException) {
            failTask(target, "无法创建转换缓存文件")
            return
        }
        target.conversionFile = output
        updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "", "FFmpeg 正在下载并转换")
        updateNotification(buildNotification("FFmpeg 正在下载并转换 · " + displayName(target), 0, true, false))
        try {
            closeActiveProxy()
            activeProxy = LocalMediaProxy(CLIENT, target.userAgent, target.cookies,
                target.referer, target.requestHeaders)
            val proxyInput = activeProxy!!.urlFor(target.url)
            activeSession = FFmpegKit.executeAsync(
                mediaCommand(target, output, proxyInput),
                { session ->
                    synchronized(lock) {
                        closeActiveProxy()
                        if (target.paused || target.cancelled || target != task || session != activeSession) {
                            output.delete()
                        } else if (!ReturnCode.isSuccess(session.returnCode)) {
                            val log = session.output
                            val detail = if (log.isNullOrEmpty()) "FFmpeg 没有返回错误详情" else tail(log, 3000)
                            // 失败时记录完整 URL 与转发的请求头，便于核对 CDN 签名是否过期、请求是否被改写。
                            Log.e(TAG, "FFmpeg failed, returnCode=" + session.returnCode
                                + " url=" + target.url
                                + " referer=" + target.referer
                                + " headers=" + (if (target.requestHeaders.isEmpty()) "(empty)" else target.requestHeaders)
                                + "\n" + sanitizeLog(detail))
                            if (target.fallbackUrls.isNotEmpty()) {
                                // 源（常见为 HLS 分片）被 CDN 风控拒绝时，回退到页面里已捕获的就绪直链媒体，
                                // 由普通 OkHttp 直连下载（自带 Referer/签名降级重试），不再经过 FFmpeg 组装。
                                output.delete()
                                target.url = target.fallbackUrls[0]
                                Log.w(TAG, "HLS failed, fallback to direct url=" + safeEndpoint(target.url))
                                updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "",
                                    "HLS 源下载失败，已改用直链媒体下载")
                                startRequest(target)
                            } else {
                                failTask(target, describeFfmpegFailure(detail))
                            }
                        } else {
                            try {
                                createDestination(target)
                                FileInputStream(output).use { input ->
                                    openOutput(target, false).use { saved ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count == -1) break
                                            saved.write(buffer, 0, count)
                                        }
                                    }
                                }
                                target.downloaded = output.length()
                                target.total = output.length()
                                completeTask(target)
                            } catch (error: Exception) {
                                failTask(target, error.localizedMessage ?: "转换文件保存失败")
                            } finally {
                                output.delete()
                            }
                        }
                    }
                },
                { _ -> },
                { statistics ->
                    if (!target.paused && !target.cancelled) {
                        val seconds = maxOf(0L, (statistics.time / 1000.0).toLong())
                        updateNotification(
                            buildNotification("已处理 " + seconds + " 秒 · " + displayName(target), 0, true, false))
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - target.lastConversionRecordUpdate >= 1_500L) {
                            target.lastConversionRecordUpdate = now
                            // FFmpeg 直接写应用缓存，远端没有可靠总字节数；临时 MP4 的实时大小
                            // 至少能明确告诉用户解密与封装正在产出，而不是长期显示误导性的 0 B。
                            target.downloaded = output.length()
                            updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "",
                                "已处理媒体 " + seconds + " 秒 · 正在解密并合并")
                        }
                    }
                })
        } catch (error: Throwable) {
            // 原生库加载或命令初始化异常不能拖垮浏览器所在进程。
            closeActiveProxy()
            failTask(target, "FFmpeg 初始化失败：" + (error.localizedMessage ?: error.javaClass.simpleName))
        }
    }

    private fun mediaCommand(target: DownloadTask, output: File, inputUrl: String): String {
        val command = StringBuilder("-y ")
        // HTTPS、Cookie、Referer 和 Range 均由本机 OkHttp 代理处理；FFmpeg 只读取环回 HTTP。
        // 部分站点把 AES 密钥伪装成 .ts，FFmpeg 8 会把它套入更严格的 HLS 分片后缀检查。
        // 播放列表中的所有 URI 已由 LocalMediaProxy 改写并限制在本机代理内，因此可安全放宽该检查。
        if (isHlsUrl(target.url)) {
            // 即使站点把点播清单标成直播，也必须从清单首段开始；FFmpeg 默认的 -3
            // 会从直播尾部倒数三段起步，最终得到一个能播放但缺少开头的不完整 MP4。
            command.append("-allowed_extensions ALL -allowed_segment_extensions ALL -extension_picky 0 -live_start_index 0 ")
        }
        command.append("-i ").append(quote(inputUrl)).append(' ')
        when (target.outputFormat) {
            "mkv" -> command.append("-map 0 -c copy ")
            "mp3" -> command.append("-vn -c:a libmp3lame -q:a 2 ")
            "m4a" -> command.append("-vn -c:a aac -b:a 192k ")
            "flac" -> command.append("-vn -c:a flac ")
            else -> command.append("-map 0 -c copy -bsf:a aac_adtstoasc -movflags +faststart ")
        }
        return command.append(quote(output.absolutePath)).toString()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun tail(value: String, length: Int): String =
        if (value.length <= length) value else value.substring(value.length - length)

    private fun isHlsUrl(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        val query = lower.indexOf('?')
        return (if (query < 0) lower else lower.substring(0, query)).endsWith(".m3u8")
    }

    /** 下载记录可以展示完整错误，但必须隐藏本机代理 token，避免间接暴露原始鉴权 URL。 */
    private fun sanitizeLog(value: String): String =
        value.replace(Regex("http://127\\.0\\.0\\.1:\\d+/media/[A-Za-z0-9_-]+"), "<本机媒体代理>")

    /** 把 CDN 常见的 HTTP 4xx 拒绝直接翻译成用户能看懂的原因，而不是只展示 FFmpeg 内部日志。 */
    private fun describeFfmpegFailure(detail: String): String {
        val matcher = Pattern.compile("HTTP error (\\d{3})").matcher(detail)
        return if (matcher.find()) {
            "视频源返回 HTTP " + matcher.group(1) + "，链接可能已失效或缺少访问请求头\n" + sanitizeLog(detail)
        } else {
            "FFmpeg 下载或转换失败\n" + sanitizeLog(detail)
        }
    }

    private fun startRequest(target: DownloadTask) {
        val builder = Request.Builder().url(target.url)
        if (target.userAgent.isNotEmpty()) builder.header("User-Agent", target.userAgent)
        if (target.headerRetryStep < 2 && target.cookies.isNotEmpty()) builder.header("Cookie", target.cookies)
        // 网页自身能显示但直接请求返回 403/占位图时，通常是图片 CDN 的 Referer 防盗链。
        if (target.headerRetryStep == 0 && target.referer.isNotEmpty()) builder.header("Referer", target.referer)
        if (target.resolvedMime != null && target.resolvedMime!!.startsWith("image/")) {
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        }
        applyCapturedHeaders(builder, target)
        if (target.downloaded > 0) builder.header("Range", "bytes=" + target.downloaded + "-")
        target.paused = false
        activeCall = CLIENT.newCall(builder.build())
        activeCall!!.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                synchronized(lock) {
                    if (target.paused || target.cancelled || target != task) return
                    failTask(target, error.localizedMessage ?: "网络连接失败")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (shouldRetryWithoutPageContext(target, response)) {
                    response.close()
                    target.headerRetryStep++
                    Log.w(TAG, "retry id=" + target.id + " step=" + target.headerRetryStep
                        + " http=" + response.code + " source=" + safeEndpoint(target.url))
                    updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "",
                        if (target.headerRetryStep == 1) "图片服务器拒绝页面来源，正在无 Referer 重试"
                        else "正在使用纯净直链重试")
                    startRequest(target)
                    return
                }
                handleResponse(target, response)
            }
        })
    }

    private fun applyCapturedHeaders(request: Request.Builder, target: DownloadTask) {
        if (target.requestHeaders.isEmpty()) return
        try {
            val headers = JSONObject(target.requestHeaders)
            val names = headers.keys()
            while (names.hasNext()) {
                val name = names.next()
                val lower = name.lowercase(Locale.ROOT)
                val basic = "accept" == lower || "accept-language" == lower
                    || "referer" == lower
                val pageContext = "origin" == lower || lower.startsWith("sec-fetch-")
                    || "x-requested-with" == lower || "purpose" == lower
                    || "dpr" == lower || "width" == lower
                    || "viewport-width" == lower || "save-data" == lower
                if (!(basic || (target.headerRetryStep == 0 && pageContext))) continue
                val value = headers.optString(name, "")
                if (value.isNotEmpty() && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
                    request.header(name, value)
                }
            }
        } catch (ignored: Exception) { }
    }

    private fun shouldRetryWithoutPageContext(target: DownloadTask, response: Response): Boolean {
        if (target.downloaded > 0 || target.headerRetryStep >= 2) return false
        val code = response.code
        if (code == 401 || code == 403) return true
        val expected = target.resolvedMime ?: ""
        val actual = (response.header("Content-Type") ?: "").lowercase(Locale.ROOT)
        // 一些图床用 200 + HTML 防盗链提示页伪装成功响应，不能把它保存成损坏图片。
        return expected.startsWith("image/") && actual.startsWith("text/html")
    }

    private fun handleResponse(target: DownloadTask, response: Response) {
        response.use {
            if (!response.isSuccessful) throw IOException("服务器返回 HTTP " + response.code)
            val body = response.body
            if (body == null) throw IOException("服务器没有返回文件内容")
            val resumed = target.downloaded > 0 && response.code == 206
            if (target.downloaded > 0 && !resumed) {
                // 服务器忽略 Range 时从头覆盖，防止将完整响应追加到半个文件后造成损坏。
                target.downloaded = 0
            }
            if (target.contentUri == null && target.partialFile == null) {
                val info = BrowserDownloadResolver.resolve(
                    response.request.url.toString(),
                    response.header("Content-Disposition", target.contentDisposition),
                    response.header("Content-Type", target.mimeType))
                target.fileName = info.fileName
                target.resolvedMime = info.mimeType
                createDestination(target)
                updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "", "")
            }
            val remaining = body.contentLength()
            target.total = if (remaining > 0) target.downloaded + remaining else -1
            body.byteStream().use { input ->
                openOutput(target, resumed).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (target.paused || target.cancelled) return
                        output.write(buffer, 0, count)
                        target.downloaded += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 500) {
                            lastUpdate = now
                            updateProgress(target)
                        }
                    }
                    output.flush()
                }
            }
            synchronized(lock) {
                if (!target.paused && !target.cancelled && target == task) completeTask(target)
            }
        }
    }

    private fun pauseTask() {
        synchronized(lock) {
            val current = task ?: return
            if (current.finished || current.paused) return
            current.paused = true
            activeCall?.cancel()
            activeSession?.cancel()
            closeActiveProxy()
            updateRecord(current, BrowserDownloadStore.STATUS_PAUSED, "", "")
            updateNotification(buildNotification("已暂停 · " + displayName(current), progress(current), true, true))
        }
    }

    private fun resumeTask() {
        synchronized(lock) {
            val current = task ?: return
            if (current.finished || !current.paused) return
            updateRecord(current, BrowserDownloadStore.STATUS_DOWNLOADING, "", "")
            updateNotification(buildNotification("正在继续 · " + displayName(current), progress(current), false, false))
            if (current.autoConvert) startFfmpegTask(current)
            else if (current.localSourcePath.isNotEmpty()) startLocalImport(current)
            else startRequest(current)
        }
    }

    private fun cancelTask() {
        synchronized(lock) {
            val current = task ?: return
            current.cancelled = true
            activeCall?.cancel()
            activeSession?.cancel()
            activeImportThread?.interrupt()
            closeActiveProxy()
            deletePartial(current)
            current.finished = true
            updateRecord(current, BrowserDownloadStore.STATUS_CANCELLED, "", "")
            updateNotification(NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24).setContentTitle("下载已取消")
                .setContentText(displayName(current)).setAutoCancel(true).build())
            stopServiceAfterTask()
        }
    }

    private fun completeTask(target: DownloadTask) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ready = ContentValues()
            ready.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(target.contentUri!!, ready, null, null)
        } else if (target.partialFile != null && target.finalFile != null
            && !target.partialFile!!.renameTo(target.finalFile!!)) {
            throw IOException("无法完成文件重命名")
        }
        target.finished = true
        Log.i(TAG, "completed id=" + target.id + " source=" + safeEndpoint(target.url)
            + " bytes=" + target.downloaded)
        val localUri: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            localUri = target.contentUri.toString()
        } else {
            localUri = FileProvider.getUriForFile(this, packageName + ".files", target.finalFile!!).toString()
        }
        updateRecord(target, BrowserDownloadStore.STATUS_COMPLETED, localUri, "")
        val done = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_24)
            .setContentTitle("下载完成")
            .setContentText("Download/Toolbox/" + displayName(target))
            .setContentIntent(downloadsIntent())
            .setAutoCancel(true)
        updateNotification(done.build())
        stopServiceAfterTask()
    }

    private fun failTask(target: DownloadTask, reason: String) {
        Log.e(TAG, "failed id=" + target.id + " source=" + safeEndpoint(target.url) + " reason=" + reason)
        closeActiveProxy()
        deletePartial(target)
        target.finished = true
        updateRecord(target, BrowserDownloadStore.STATUS_FAILED, "", reason)
        val failed = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_24)
            .setContentTitle("下载失败")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
        updateNotification(failed.build())
        stopServiceAfterTask()
    }

    /**
     * 只处理到当前最新启动命令时才停止服务。若此刻已有更新的启动命令到达，
     * stopSelfResult 会保留服务，从而避免旧任务的完成回调把新下载一起终止。
     */
    private fun stopServiceAfterTask() {
        stopForeground(false)
        stopSelfResult(latestStartId)
    }

    private fun safeEndpoint(value: String): String = try {
        val uri = Uri.parse(value)
        uri.scheme + "://" + (uri.host ?: "local")
    } catch (ignored: Exception) {
        "unknown"
    }

    private fun createDestination(target: DownloadTask) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.Downloads.DISPLAY_NAME, target.fileName)
            values.put(MediaStore.Downloads.MIME_TYPE, target.resolvedMime)
            values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIRECTORY)
            values.put(MediaStore.Downloads.IS_PENDING, 1)
            target.contentUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (target.contentUri == null) throw IOException("无法在系统下载目录创建文件")
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "Toolbox")
            if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建 Download/Toolbox 文件夹")
            target.finalFile = uniqueFile(directory, target.fileName)
            target.fileName = target.finalFile!!.name
            target.partialFile = File(directory, target.fileName + ".part")
        }
    }

    private fun openOutput(target: DownloadTask, append: Boolean): OutputStream {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val output = contentResolver.openOutputStream(target.contentUri!!, if (append) "wa" else "w")
            if (output == null) throw IOException("无法打开下载文件")
            return output
        }
        return FileOutputStream(target.partialFile, append)
    }

    private fun deletePartial(target: DownloadTask) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.contentUri != null) {
            contentResolver.delete(target.contentUri!!, null, null)
        } else if (target.partialFile != null && target.partialFile!!.exists()) {
            target.partialFile!!.delete()
        }
        if (target.conversionFile != null && target.conversionFile!!.exists()) {
            target.conversionFile!!.delete()
        }
        deleteValidatedWebImage(target.localSourcePath)
    }

    private fun validatedWebImage(path: String?): File? {
        if (path.isNullOrEmpty()) return null
        return try {
            val file = File(path).canonicalFile
            val cache = cacheDir.canonicalFile
            if (file.parentFile != cache || !file.name.startsWith("toolbox-web-image-")) null
            else file
        } catch (ignored: IOException) {
            null
        }
    }

    private fun deleteValidatedWebImage(path: String?) {
        val file = validatedWebImage(path)
        if (file != null && file.exists()) {
            file.delete()
        }
    }

    private fun updateProgress(target: DownloadTask) {
        val percent = progress(target)
        updateRecord(target, BrowserDownloadStore.STATUS_DOWNLOADING, "", "")
        val text = if (target.total > 0) "$percent% · " + displayName(target)
            else "正在下载 · " + displayName(target)
        updateNotification(buildNotification(text, percent, target.total <= 0, false))
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean, paused: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_24)
            .setContentTitle("Toolbox 网页下载")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(downloadsIntent())
            .setProgress(100, percent, indeterminate)
            .addAction(if (paused) R.drawable.ic_play_24 else R.drawable.ic_pause_24,
                if (paused) "继续" else "暂停",
                serviceIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(R.drawable.ic_stop_24, "取消", serviceIntent(ACTION_CANCEL, 2))
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BrowserDownloadService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun downloadsIntent(): PendingIntent {
        val intent = Intent(this, BrowserDownloadsActivity::class.java)
        return PendingIntent.getActivity(this, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun notifyMessage(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24).setContentTitle("Toolbox 网页下载")
                .setContentText(text).setAutoCancel(true).build())
    }

    private fun progress(target: DownloadTask): Int =
        if (target.total > 0) (target.downloaded * 100 / target.total).toInt().coerceAtMost(100) else 0

    private fun displayName(target: DownloadTask): String = target.fileName

    private fun value(intent: Intent, key: String): String = intent.getStringExtra(key) ?: ""

    private fun matchesTask(intent: Intent): Boolean {
        val requested = intent.getLongExtra(EXTRA_DOWNLOAD_ID, task!!.id)
        return requested == task!!.id
    }

    private fun updateRecord(target: DownloadTask, status: String, localUri: String, error: String) {
        val now = System.currentTimeMillis()
        store.upsert(BrowserDownloadStore.DownloadRecord(
            target.id, displayName(target), target.url,
            if (target.resolvedMime.isNullOrEmpty()) "application/octet-stream" else target.resolvedMime!!,
            status, target.downloaded, target.total, localUri, error, target.createdAt, now))
        sendBroadcast(Intent(ACTION_DOWNLOAD_UPDATED).setPackage(packageName))
    }

    private fun uniqueFile(directory: File, original: String): File {
        var target = File(directory, original)
        if (!target.exists() && !File(directory, original + ".part").exists()) return target
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val extension = if (dot > 0) original.substring(dot) else ""
        for (index in 1 until 10_000) {
            target = File(directory, base + " (" + index + ")" + extension)
            if (!target.exists() && !File(target.path + ".part").exists()) return target
        }
        return File(directory, base + "_" + System.currentTimeMillis() + extension)
    }

    override fun onDestroy() {
        activeCall?.cancel()
        activeSession?.cancel()
        activeImportThread?.interrupt()
        closeActiveProxy()
        synchronized(lock) {
            val current = task
            if (current != null && !current.finished && !current.cancelled) {
                updateRecord(current, BrowserDownloadStore.STATUS_FAILED, "", "下载服务已停止")
            }
        }
        super.onDestroy()
    }

    private fun closeActiveProxy() {
        if (activeProxy == null) return
        activeProxy!!.close()
        activeProxy = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 当前下载任务状态；字段由 Service 线程持有，跨线程读取靠 volatile。 */
    private class DownloadTask(
        id: Long,
        initialUrl: String,
        val userAgent: String,
        val cookies: String,
        val contentDisposition: String,
        val mimeType: String,
        fileName: String,
        initialResolvedMime: String?,
        val autoConvert: Boolean,
        outputFormat: String,
        val referer: String,
        val localSourcePath: String,
        val requestHeaders: String,
        fallbackUrls: Array<String>?
    ) {
        val id: Long = id
        val createdAt = System.currentTimeMillis()
        // HLS 失败后回退到直链时会被替换，因此不是 val。
        var url: String = initialUrl
        @Volatile
        var paused = false
        @Volatile
        var cancelled = false
        var finished = false
        var downloaded = 0L
        var total = -1L
        var fileName: String = if (fileName.isEmpty()) "网页文件" else fileName
        var resolvedMime: String? = initialResolvedMime
        var contentUri: Uri? = null
        var finalFile: File? = null
        var partialFile: File? = null
        var conversionFile: File? = null
        @Volatile
        var lastConversionRecordUpdate = 0L
        val outputFormat: String = if (outputFormat.isEmpty()) "mp4" else outputFormat
        val fallbackUrls: Array<String> = fallbackUrls ?: arrayOf()
        var headerRetryStep = 0
    }

    companion object {
        const val ACTION_START = "com.example.cryptoapp.download.START"
        const val ACTION_PAUSE = "com.example.cryptoapp.download.PAUSE"
        const val ACTION_RESUME = "com.example.cryptoapp.download.RESUME"
        const val ACTION_CANCEL = "com.example.cryptoapp.download.CANCEL"
        const val ACTION_DOWNLOAD_UPDATED = "com.example.cryptoapp.download.UPDATED"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_URL = "url"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_DISPOSITION = "content_disposition"
        const val EXTRA_MIME = "mime_type"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_RESOLVED_MIME = "resolved_mime"
        const val EXTRA_AUTO_CONVERT = "auto_convert"
        const val EXTRA_OUTPUT_FORMAT = "output_format"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_LOCAL_SOURCE_PATH = "local_source_path"
        const val EXTRA_REQUEST_HEADERS = "request_headers"
        /** 当前资源（通常是 HLS）下载失败时，可回退的就绪直链媒体 URL 列表。 */
        const val EXTRA_FALLBACK_URLS = "fallback_urls"

        private const val CHANNEL_ID = "browser_downloads"
        private const val TAG = "ToolboxDownload"
        private const val NOTIFICATION_ID = 3107
        private val RELATIVE_DIRECTORY = Environment.DIRECTORY_DOWNLOADS + "/Toolbox"
        private val CLIENT = OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true).build()
    }
}
