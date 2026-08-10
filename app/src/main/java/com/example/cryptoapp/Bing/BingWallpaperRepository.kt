package com.example.cryptoapp.Bing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bing 每日壁纸的单例数据仓库。负责：
 * - 14 天元数据抓取（两次请求 idx=0 与 idx=7，按 startdate 去重）
 * - 今天的 UHD 优先下载（横屏原生 4K，竖屏由横屏中心裁剪放大 2160×3840）
 * - 后台慢速预取其余 13 天，并清理超过 14 天的缓存
 * - 采样解码（供首页/图库/详情显示，避免 4K 大图吃满内存）
 * - 保存入口（委托 BingWallpaperSaver，含去重/替换回调）
 *
 * 线程模型（全部 app 级单例内部）：
 * - prefetchExecutor 单线程 MIN_PRIORITY：13 天慢速下载 + 清理
 * - ioExecutor 单线程：竖屏生成、MediaStore 保存
 * - decodeExecutor 固定 2 线程：采样解码
 * 今天的 UHD 用 OkHttp enqueue 异步下载，不被预取线程阻塞。
 */
class BingWallpaperRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    val cacheDir: File = File(context.filesDir, "bing_wallpaper").apply { mkdirs() }

    // 首次启动/大图下载需要更宽的超时：4K 原图（8-15MB）在默认 10s 读超时下极易
    // 失败，这正是"第一次打开壁纸加载不出来"的主因之一。
    /** 设备屏幕实际宽高比（height/width），如 19.5:9 ≈ 2.1667。 */
    private val screenAspect: Float = run {
        val dm = appContext.resources.displayMetrics
        maxOf(dm.widthPixels, dm.heightPixels).toFloat() / minOf(dm.widthPixels, dm.heightPixels)
    }
    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefetchExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "bing-prefetch").apply { priority = Thread.MIN_PRIORITY } }
    private val ioExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "bing-io") }
    private val decodeExecutor: ExecutorService =
        Executors.newFixedThreadPool(2) { Thread(it, "bing-decode") }

    @Volatile private var inMemoryDays: List<BingWallpaperDay>? = null
    @Volatile private var prefetchListener: ((String) -> Unit)? = null
    private val fetchInFlight = AtomicBoolean(false)
    private val prefetchRunning = AtomicBoolean(false)
    private val downloadGuard = ConcurrentHashMap.newKeySet<String>()
    private val previewCache = object : LinkedHashMap<String, Bitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 12
    }

    companion object {
        @Volatile private var instance: BingWallpaperRepository? = null
        @JvmStatic fun get(context: Context): BingWallpaperRepository {
            return instance ?: synchronized(this) {
                instance ?: BingWallpaperRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    // ------------------------------------------------------------------ public API

    /** 今天已缓存的记录（横屏 UHD 文件存在才算），否则 null。 */
    fun todayCached(): BingWallpaperDay? {
        val days = inMemoryDays ?: return null
        return days.firstOrNull()
    }

    /** 当前内存中的 14 天列表；未抓取过为 null。 */
    fun allDays(): List<BingWallpaperDay>? = inMemoryDays

    /** 单飞抓取 14 天元数据，完成后回调主线程。已有内存结果立即回调。 */
    fun fetchDays(onReady: (List<BingWallpaperDay>) -> Unit) {
        inMemoryDays?.let { mainHandler.post { onReady(it) }; return }
        if (!fetchInFlight.compareAndSet(false, true)) return
        prefetchExecutor.execute {
            val days = try { fetchDaysBlocking() } catch (e: Exception) { null }
            fetchInFlight.set(false)
            if (days != null) {
                inMemoryDays = days
                mainHandler.post { onReady(days) }
            }
        }
    }

    /**
     * 确保今天的 UHD 已缓存：下载横屏 4K（缺失时），需要竖屏则生成竖屏，然后回调主线程。
     * 无论方向如何，横屏 UHD 下载完成即镜像到 legacy wallpaper.jpg/.date，保证首帧秒显。
     */
    fun ensureTodayUhd(onReady: (BingWallpaperDay) -> Unit, onError: (Exception) -> Unit) {
        val request = BingUrls.request(BingUrls.api(0, 1))
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // OkHttp 回调线程，统一切回主线程再交给调用方，避免其 onError 直接操作 UI。
                mainHandler.post { onError(e) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        if (!response.isSuccessful || response.body == null) throw IOException("HTTP ${response.code}")
                        val image = JSONObject(response.body!!.string())
                            .getJSONArray("images").getJSONObject(0)
                        val day = parseDay(image)
                        // 在 OkHttp 回调线程执行下载，绝不阻塞主线程：首次打开无缓存时
                        // 要下载 3.5MB+ 的 4K 原图，若 post 回主线程同步下载，慢网络下
                        // 主线程会长时间冻结（甚至 ANR），表现为"首屏壁纸加载不出来"。
                        onTodayReady(day, onReady, onError)
                    } catch (e: Exception) {
                        mainHandler.post { onError(e) }
                    }
                }
            }
        })
    }

    /** 后台慢速预取 14 天横屏 UHD，并清理超过 14 天的缓存。幂等。 */
    fun startBackgroundPrefetch() {
        if (!prefetchRunning.compareAndSet(false, true)) return
        prefetchExecutor.execute {
            try {
                val days = fetchDaysBlocking()
                inMemoryDays = days
                if (days.isNotEmpty()) pruneOlderThan(days.first().startDate, 14)
                for (day in days) {
                    val file = day.landscapeUhdFile(cacheDir)
                    if (!file.exists() && downloadGuard.add(day.startDate)) {
                        try {
                            downloadFile(day.landscapeUhdUrl, file)
                        } catch (ignored: Exception) { } finally {
                            downloadGuard.remove(day.startDate)
                        }
                        try { Thread.sleep(1500) } catch (ignored: InterruptedException) { return@execute }
                    }
                    if (file.exists()) mainHandler.post { prefetchListener?.invoke(day.startDate) }
                }
            } catch (ignored: Exception) { } finally {
                prefetchRunning.set(false)
            }
        }
    }

    /** 图库监听：某天横屏 UHD 缓存完成后回调其 startDate（主线程）。 */
    fun setPrefetchListener(listener: ((String) -> Unit)?) { prefetchListener = listener }

    /** 查找某天记录。 */
    fun cachedDay(startDate: String): BingWallpaperDay? {
        return inMemoryDays?.firstOrNull { it.startDate == startDate }
    }

    /** 确保竖屏 4K 已生成，回调主线程（可能为 null = 生成失败）。 */
    fun ensurePortrait(day: BingWallpaperDay, onReady: (File?) -> Unit) {
        val out = day.portraitUhdFile(cacheDir)
        if (out.exists()) { mainHandler.post { onReady(out) }; return }
        ioExecutor.execute {
            val generated = generatePortrait(day)
            mainHandler.post { onReady(generated) }
        }
    }

    /** 采样解码（后台线程，回调主线程）。可带 LRU 缓存 key。 */
    fun decodePreviewAsync(day: BingWallpaperDay, file: File, maxDim: Int, onReady: (Bitmap?) -> Unit) {
        decodeExecutor.execute {
            val key = file.name
            val cached = previewCache[key]
            if (cached != null) { mainHandler.post { onReady(cached) }; return@execute }
            val bitmap = decodeSampled(file, maxDim)
            if (bitmap != null) previewCache[key] = bitmap
            mainHandler.post { onReady(bitmap) }
        }
    }

    /** 同步采样解码。 */
    fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 保存某天某方向的 UHD。存在重复时回调 onDuplicate（主线程），由调用方决定：
     * - 手动保存 → 弹「已保存过，是否替换」对话框，替换→force=true 覆盖，取消→不操作
     * - 自动保存 → 静默跳过（onDuplicate 里什么都不做即不替换）
     */
    fun saveDayWithDedup(day: BingWallpaperDay, orientation: Orientation,
                         onDuplicate: () -> Unit, onResult: (SaveResult) -> Unit) {
        ioExecutor.execute {
            val source = when (orientation) {
                Orientation.LANDSCAPE -> day.landscapeUhdFile(cacheDir)
                Orientation.PORTRAIT -> generatePortrait(day)
            }
            if (source == null || !source.exists()) {
                mainHandler.post { onResult(SaveResult.FAILED) }
                return@execute
            }
            if (BingWallpaperSaver.isMarkedSaved(appContext, day, orientation)) {
                mainHandler.post { onDuplicate() }
                return@execute
            }
            val existing = BingWallpaperSaver.findExisting(appContext, day, orientation)
            if (existing != null) {
                mainHandler.post { onDuplicate() }
                return@execute
            }
            val result = BingWallpaperSaver.write(appContext, day, orientation, source, force = false)
            if (result == SaveResult.SAVED) {
                BingWallpaperSaver.markSaved(appContext, day, orientation, saved = true)
            }
            mainHandler.post { onResult(result) }
        }
    }

    /** 保存某天某方向，允许强制替换（替换已存在的旧版）。 */
    fun saveDayForce(day: BingWallpaperDay, orientation: Orientation, onResult: (SaveResult) -> Unit) {
        ioExecutor.execute {
            val source = when (orientation) {
                Orientation.LANDSCAPE -> day.landscapeUhdFile(cacheDir)
                Orientation.PORTRAIT -> generatePortrait(day)
            }
            if (source == null || !source.exists()) {
                mainHandler.post { onResult(SaveResult.FAILED) }
                return@execute
            }
            val result = BingWallpaperSaver.write(appContext, day, orientation, source, force = true)
            if (result == SaveResult.SAVED) {
                BingWallpaperSaver.markSaved(appContext, day, orientation, saved = true)
            }
            mainHandler.post { onResult(result) }
        }
    }

    /** 自动保存今天（静默去重）：有自动保存标记或 MediaStore 已存在则跳过。 */
    fun autoSaveToday(includePortrait: Boolean, onResult: (SaveResult) -> Unit) {
        ensureTodayUhd({ day ->
            saveDayWithDedup(day, Orientation.LANDSCAPE, onDuplicate = { /* 静默跳过 */ },
                onResult = { onResult(it) })
            if (includePortrait) {
                saveDayWithDedup(day, Orientation.PORTRAIT, onDuplicate = { /* 静默跳过 */ },
                    onResult = { })
            }
        }, onError = { onResult(SaveResult.FAILED) })
    }

    // ------------------------------------------------------------------ internals

    private fun parseDay(image: JSONObject): BingWallpaperDay {
        val startDate = image.getString("startdate")
        val path = image.getString("url")
        return BingWallpaperDay(
            startDate,
            image.optString("title"),
            image.optString("copyright"),
            image.optString("copyrightlink"),
            BingUrls.landscapeUhd(path),
            BingUrls.absolute(path),
            BingUrls.portraitPreview(path)
        )
    }

    private fun fetchDaysBlocking(): List<BingWallpaperDay> {
        val map = LinkedHashMap<String, BingWallpaperDay>()
        for (idx in intArrayOf(0, 7)) {
            val json = httpGetJson(BingUrls.api(idx, 8))
            val images = json.getJSONArray("images")
            for (i in 0 until images.length()) {
                val image = images.getJSONObject(i)
                val startDate = image.getString("startdate")
                if (map.containsKey(startDate)) continue   // 去重 idx=7 的重叠日
                map[startDate] = parseDay(image)
            }
        }
        return map.values.toList()
    }

    private fun httpGetJson(url: String): JSONObject {
        val call = httpClient.newCall(BingUrls.request(url))
        call.execute().use { response ->
            if (!response.isSuccessful || response.body == null) throw IOException("HTTP ${response.code}")
            return JSONObject(response.body!!.string())
        }
    }

    /** 轮询等待文件出现且非空（另一并发任务正在写入它）。 */
    private fun waitForFile(file: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.length() > 0) return true
            try {
                Thread.sleep(200)
            } catch (ignored: InterruptedException) {
                return false
            }
        }
        return file.exists() && file.length() > 0
    }

    /**
     * 流式落盘并最多重试 2 次。4K 原图（8-15MB）用 bytes() 全量读入内存，既吃内存又
     * 容易触发读超时；改为 byteStream 边下边写，失败时删掉半成品再重试一次。
     * 返回是否成功；异常在内部处理，调用方无需再 try/catch。
     */
    private fun downloadFile(url: String, target: File): Boolean {
        for (attempt in 1..2) {
            try {
                val call = httpClient.newCall(BingUrls.request(url))
                call.execute().use { response ->
                    if (!response.isSuccessful || response.body == null) throw IOException("HTTP ${response.code}")
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
                if (target.length() > 0) return true
                target.delete()
            } catch (e: Exception) {
                target.delete()
            }
            if (attempt == 1) {
                try { Thread.sleep(800) } catch (ignored: InterruptedException) { return false }
            }
        }
        return false
    }

    private fun onTodayReady(day: BingWallpaperDay, onReady: (BingWallpaperDay) -> Unit, onError: (Exception) -> Unit) {
        val file = day.landscapeUhdFile(cacheDir)
        if (!file.exists()) {
            if (downloadGuard.add(day.startDate)) {
                var ok = false
                try {
                    ok = downloadFile(day.landscapeUhdUrl, file)
                } finally {
                    downloadGuard.remove(day.startDate)
                }
                if (!ok) {
                    // 下载失败不再静默 return：明确回调 onError，调用方才能感知并重试。
                    mainHandler.post { onError(IOException("UHD 下载失败")) }
                    return
                }
            } else {
                // 后台预取/自动保存正在下载同一天，持锁者把文件写入后我们直接复用。
                // 若这里静默 return，onReady 永不触发，首屏即使图片已缓存也不显示。
                if (!waitForFile(file, 60_000)) {
                    mainHandler.post { onError(IOException("UHD 等待超时")) }
                    return
                }
            }
        }
        if (!file.exists()) {
            mainHandler.post { onError(IOException("UHD 文件缺失")) }
            return
        }
        // 镜像到 legacy 单图，保证 showCachedWallpaper() 首帧秒显。
        copyFile(file, File(cacheDir, "wallpaper.jpg"))
        writeText(File(cacheDir, "wallpaper.date"), day.startDate)
        // 竖屏屏 或 开启竖屏保存时，先生成竖屏再回调（保证 onReady 时两种文件都可用）。
        if (isPortraitScreen() || wantsPortraitSave()) {
            ensurePortrait(day) { mainHandler.post { onReady(day) } }
        } else {
            mainHandler.post { onReady(day) }
        }
    }

    private fun isPortraitScreen(): Boolean {
        return appContext.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
    }

    private fun wantsPortraitSave(): Boolean {
        return appContext.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getBoolean("bing_save_portrait", false)
    }

    /**
     * 竖屏 4K 生成：按设备屏幕实际宽高比（如 19.5:9 ≈ 2.17）从横屏 UHD 中心裁剪，
     * 再放大到该比例。原图 3840×2160，竖屏要宽 = 高(2160) × aspect ≈ 4680，超出原图
     * 宽度，因此先取中心区域，若不足该宽度则放大填满。低内存：只解码一次 region。
     */
    fun generatePortrait(day: BingWallpaperDay): File? {
        val src = day.landscapeUhdFile(cacheDir)
        val out = day.portraitUhdFile(cacheDir)
        if (!src.exists()) return null
        val targetAspect = screenAspect.coerceIn(0.8f, 3.0f)
        // 若缓存存在且比例已是屏幕比例则直接复用；旧版本 9:16 缓存比例不符，需要重建。
        if (out.exists() && isAspectMatch(out, targetAspect)) return out
        return try {
            val decoder = BitmapRegionDecoder.newInstance(src.absolutePath, false)
            val srcW = decoder.width
            val srcH = decoder.height
            // 竖屏目标宽高比 = 屏幕高/宽。宽取原图中心可容纳的最大竖屏比例。
            // 目标竖屏宽度 = 高度 × aspect；若超原图宽则用原图全宽（比例接近，centerCrop 可兜底）。
            val cropW = minOf(srcW, (srcH * targetAspect).toInt())
            val cropH = srcH
            val offX = (srcW - cropW) / 2
            val cropped = decoder.decodeRegion(
                Rect(offX, 0, offX + cropW, cropH),
                BitmapFactory.Options().apply { inSampleSize = 1 })
            decoder.recycle()
            if (cropped == null) return null
            // 输出为 1080 × (1080 × aspect)，等比放大，保持纵横比。
            val outH = 3840
            val outW = (outH * cropped.width / cropped.height.toFloat()).toInt()
            val portrait = Bitmap.createScaledBitmap(cropped, outW, outH, true)
            if (cropped !== portrait) cropped.recycle()
            FileOutputStream(out).use { portrait.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            portrait.recycle()
            out
        } catch (e: Exception) {
            null
        }
    }

    /** 检查图片宽高比是否接近目标 aspect（±2% 视为匹配）。 */
    private fun isAspectMatch(file: File, targetAspect: Float): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val actual = bounds.outWidth.toFloat() / bounds.outHeight
            kotlin.math.abs(actual - targetAspect) / targetAspect < 0.02f
        } catch (e: Exception) {
            false
        }
    }

    /** 只清理 app 缓存中超过 keepDays 的日期文件；绝不删 MediaStore 保存。 */
    fun pruneOlderThan(todayStart: String, keepDays: Int) {
        val today = try {
            LocalDate.parse(todayStart, DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: Exception) {
            return
        }
        val pattern = Regex("^(\\d{8})_(UHD|portrait)\\.jpg$")
        cacheDir.listFiles()?.forEach { file ->
            val match = pattern.find(file.name) ?: return@forEach
            val date = try {
                LocalDate.parse(match.groupValues[1], DateTimeFormatter.BASIC_ISO_DATE)
            } catch (e: Exception) {
                return@forEach
            }
            if (ChronoUnit.DAYS.between(date, today) > keepDays) file.delete()
        }
    }

    private fun copyFile(from: File, to: File) {
        try {
            from.inputStream().use { input -> FileOutputStream(to).use { input.copyTo(it) } }
        } catch (ignored: IOException) { }
    }

    private fun writeText(file: File, text: String) {
        try {
            FileOutputStream(file).use { it.write(text.toByteArray()) }
        } catch (ignored: IOException) { }
    }
}
