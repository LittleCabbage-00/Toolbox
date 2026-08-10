package com.example.cryptoapp.Activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBar
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Bing.BingWallpaperRepository
import com.example.cryptoapp.Browser.BrowserPreferences
import com.example.cryptoapp.R
import com.example.cryptoapp.Utils.SystemFileManagerLauncher
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.activity.CaptureActivity
import java.io.File

class MainActivity : BaseActivity() {
    private lateinit var drawer: DrawerLayout
    private lateinit var bingImage: ImageView
    private lateinit var toolbar: Toolbar
    private lateinit var config: SharedPreferences
    @Volatile
    private var wallpaperLoading = false
    /** 首次启动网络未就绪时的延时重试计数，最多重试 2 次避免无限循环。 */
    private var wallpaperRetryCount = 0

    /** API ≤ 28 自动保存到公共目录前需要 WRITE_EXTERNAL_STORAGE 运行时权限。 */
    private val storagePermissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) maybeAutoSaveToday()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = getSharedPreferences("config", MODE_PRIVATE)
        initializeDefaults()

        drawer = findViewById(R.id.drawerLayout)
        bingImage = findViewById(R.id.image_bing)
        toolbar = findViewById(R.id.toolbar)
        val navigation = findViewById<NavigationView>(R.id.navView)
        val searchText = findViewById<TextView>(R.id.search_text_tv)
        val searchButton = findViewById<ImageView>(R.id.search_button)
        positionSearchAtLowerThird()

        setSupportActionBar(toolbar)
        val actionBar: ActionBar? = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setHomeAsUpIndicator(R.drawable.ic_menu)
        }

        searchText.setOnClickListener { openSearch(searchText) }
        searchButton.setOnClickListener { openSearch(searchText) }
        // 首帧绘制完成后再显示提示，避免启动阶段同时测量抽屉、卡片和 Snackbar。
        searchText.postDelayed({
            if (!isFinishing && !isDestroyed) {
                Snackbar.make(searchText, "当前搜索引擎：" + config.getString("method_name", "必应"),
                    Snackbar.LENGTH_SHORT).show()
            }
        }, 350L)

        navigation.setCheckedItem(R.id.home)
        navigation.setNavigationItemSelectedListener { item ->
            handleNavigation(item.itemId)
            drawer.closeDrawer(GravityCompat.START)
            true
        }
    }

    override fun onStart() {
        super.onStart()
        if (config.getBoolean("bing_pic_check", true)) {
            // 壁纸已加载好就不再做任何事（不重载、不重交叉淡化）。仅进程被杀后重启时
            // 内存 drawable 为空，才会重新走缓存加载 + 后台换图检查。
            if (bingImage.drawable != null) return
            if (!wallpaperLoading) ensureBingWallpaper()
        } else {
            cancelWallpaperLoad()
            bingImage.setImageDrawable(null)
        }
    }

    /** 把搜索框定位到屏幕下 1/3 处：距屏幕底部高度 = 屏高/3，随屏幕尺寸相对变化。 */
    private fun positionSearchAtLowerThird() {
        val searchContainer = findViewById<View>(R.id.searchContainer) ?: return
        val params = searchContainer.layoutParams as RelativeLayout.LayoutParams
        params.bottomMargin = resources.displayMetrics.heightPixels / 3
        searchContainer.layoutParams = params
    }

    private fun openSearch(source: TextView) {
        startActivity(Intent(this, EnterSearchStringFragment::class.java))
        // 搜索页自身处理输入框滑入 + 遮罩淡入，禁用系统默认转场避免叠加。
        overridePendingTransition(0, 0)
    }

    private fun handleNavigation(id: Int) {
        when (id) {
            R.id.info -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.navTextLayout -> startActivity(Intent(this, TextActivity::class.java))
            R.id.navFileLayout -> startActivity(Intent(this, FileActivity::class.java))
            R.id.navPicLayout -> startActivity(Intent(this, PicActivity::class.java))
            R.id.fake_terminal -> startActivity(Intent(this, FakeTerminalActivity::class.java))
            R.id.data_tools -> startActivity(Intent(this, DataToolsActivity::class.java))
            R.id.unit_converter -> startActivity(Intent(this, UnitConverterActivity::class.java))
            R.id.media_converter -> startActivity(Intent(this, MediaTranscodeActivity::class.java))
            R.id.api_debug -> startActivity(Intent(this, ApiDebugActivity::class.java))
            R.id.network_utils -> startActivity(Intent(this, NetworkUtilitiesActivity::class.java))
            R.id.open_sys_file_mgr -> SystemFileManagerLauncher.open(this)
            R.id.qr_scan -> openScannerWithPermission()
        }
    }

    private fun openScannerWithPermission() {
        // 扫码页在真正创建相机预览前申请权限；即使拒绝也能使用图库识别。
        startActivity(Intent(this, CaptureActivity::class.java))
    }

    private fun initializeDefaults() {
        if (config.getBoolean("isFirstIn", true)) {
            config.edit()
                .putBoolean("bing_pic_check", true)
                .putBoolean(BrowserPreferences.BING_SAVE_AUTO, true)
                .putBoolean(BrowserPreferences.BING_SAVE_PORTRAIT, false)
                .putString("home_url", "https://cn.bing.com")
                .putString("search_method", "https://cn.bing.com/search?q=")
                .putString("method_name", "必应")
                .putInt("method_num", 0)
                .putBoolean(BrowserPreferences.SAVE_HISTORY, true)
                .putBoolean(BrowserPreferences.SHOW_SEARCH_HISTORY, true)
                .putBoolean(BrowserPreferences.JAVASCRIPT_ENABLED, true)
                .putBoolean(BrowserPreferences.BLOCK_THIRD_PARTY_COOKIES, true)
                .putBoolean(BrowserPreferences.DESKTOP_MODE_DEFAULT, false)
                .putBoolean("isFirstIn", false)
                .apply()
        }
    }

    // ------------------------------------------------------------------ Bing 壁纸

    /** 入口：先展示本地缓存避免白屏，再后台确认 Bing 是否换图、启动后台预取、自动保存。 */
    private fun ensureBingWallpaper() {
        showCachedWallpaper()
        val repo = BingWallpaperRepository.get(this)
        wallpaperLoading = true
        repo.ensureTodayUhd({ day ->
            wallpaperRetryCount = 0
            if (isFinishing || isDestroyed) {
                wallpaperLoading = false
                return@ensureTodayUhd
            }
            var display = if (isScreenPortrait(this)) day.portraitUhdFile(repo.cacheDir)
                else day.landscapeUhdFile(repo.cacheDir)
            if (!display.exists()) display = day.landscapeUhdFile(repo.cacheDir)
            if (!display.exists()) {
                wallpaperLoading = false
                return@ensureTodayUhd
            }
            val bitmap = repo.decodeSampled(display, screenMaxDim())
            if (bitmap == null) {
                wallpaperLoading = false
                return@ensureTodayUhd
            }
            wallpaperLoading = false
            crossFadeWallpaper(bitmap)
            // 过渡结束后再按新图亮度调整顶栏前景，避免动画期间忽明忽暗。
            bingImage.postDelayed({
                if (!isFinishing && !isDestroyed) applyToolbarContrast(bitmap)
            }, 650L)
        }, {
            wallpaperLoading = false
            // 首次启动网络可能尚未就绪，若首屏仍无图则限次延时重试一次完整加载流程。
            if (bingImage.drawable == null && wallpaperRetryCount < 2
                && !isFinishing && !isDestroyed) {
                wallpaperRetryCount++
                bingImage.postDelayed({
                    if (bingImage.drawable == null && !wallpaperLoading
                        && !isFinishing && !isDestroyed) {
                        ensureBingWallpaper()
                    }
                }, 3000L)
            }
        })
        repo.startBackgroundPrefetch()
        maybeAutoSaveToday()
    }

    /**
     * 两级加载本地缓存壁纸：
     * 1. 首帧主线程同步解码小缩略图（4K 采样 1/8，约 20ms）立即显示，避免露出白底；
     * 2. 后台线程解码全尺寸后交叉淡化升级到清晰图。
     * 只有首次安装（无缓存）才会短暂白屏，之后复用缓存首帧即有图。
     */
    private fun showCachedWallpaper() {
        if (bingImage.drawable != null) return
        val repo = BingWallpaperRepository.get(this)
        val image = firstCachedDisplay(repo)
        if (!image.exists()) {
            // 首次安装无缓存：先铺深色底，避免下载完成前的纯白刺眼。
            bingImage.setImageDrawable(ColorDrawable(Color.parseColor("#1A1A1A")))
            return
        }
        // 首帧占位：小图解码极快，保证第一帧就有内容。
        val placeholder = repo.decodeSampled(image, 640)
        if (placeholder != null) {
            bingImage.setImageBitmap(placeholder)
            applyToolbarContrast(placeholder)
            // 窗口 insets 可能尚未就绪，等第一帧布局完成后按占位图重算一次状态栏亮度。
            bingImage.post {
                if (!isFinishing && !isDestroyed && bingImage.drawable != null) {
                    applyToolbarContrast(placeholder)
                }
            }
        }
        // 后台升级到全尺寸：占位小图作为交叉淡化起点，过渡平滑。
        Thread(Runnable {
            val cached = repo.decodeSampled(image, screenMaxDim()) ?: return@Runnable
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    cached.recycle()
                } else {
                    crossFadeWallpaper(cached)
                    // 过渡结束后再按全尺寸图亮度调整顶栏前景。
                    bingImage.postDelayed({
                        if (!isFinishing && !isDestroyed) applyToolbarContrast(cached)
                    }, 650L)
                }
            }
        }, "bing-cache-load").start()
    }

    /** 优先按当前方向读取日期化 UHD 缓存，其次回退 legacy 单图。 */
    private fun firstCachedDisplay(repo: BingWallpaperRepository): File {
        val dir = repo.cacheDir
        val portrait = isScreenPortrait(this)
        // 读取 wallpaper.date 找对应日期文件。
        val dateFile = File(dir, "wallpaper.date")
        if (dateFile.exists()) {
            val date = readDate(dateFile)
            if (date.isNotEmpty()) {
                val portraitFile = File(dir, date + "_portrait.jpg")
                val landscapeFile = File(dir, date + "_UHD.jpg")
                if (portrait && portraitFile.exists()) return portraitFile
                if (landscapeFile.exists()) return landscapeFile
                if (portraitFile.exists()) return portraitFile
            }
        }
        return File(dir, "wallpaper.jpg")
    }

    private fun readDate(file: File): String {
        try {
            java.io.FileInputStream(file).use { input ->
                val buffer = ByteArray(minOf(64, file.length()).toInt())
                val count = input.read(buffer)
                return if (count > 0) String(buffer, 0, count).trim() else ""
            }
        } catch (ignored: Exception) {
            return ""
        }
    }

    private fun screenMaxDim(): Int =
        maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

    /** 自动保存今天的壁纸：先确保权限，静默去重不弹窗。 */
    private fun maybeAutoSaveToday() {
        if (!config.getBoolean(BrowserPreferences.BING_SAVE_AUTO, true)) return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        BingWallpaperRepository.get(this).autoSaveToday(
            config.getBoolean(BrowserPreferences.BING_SAVE_PORTRAIT, false),
            { _ -> })
    }

    /** 600ms 交叉淡化：有旧图时新旧交叉，首次加载从透明淡入，避免直接闪现。 */
    private fun crossFadeWallpaper(newBitmap: Bitmap) {
        val oldDrawable = bingImage.drawable
        val newDrawable = BitmapDrawable(resources, newBitmap)
        val transition = TransitionDrawable(arrayOf<Drawable>(
            oldDrawable ?: ColorDrawable(Color.TRANSPARENT), newDrawable))
        transition.isCrossFadeEnabled = true
        bingImage.setImageDrawable(transition)
        transition.startTransition(600)
    }

    /**
     * 分别按状态栏区域和顶栏区域的图片亮度决定前景色：
     * - 状态栏图标：只采样状态栏高度那一窄条，亮底→深色图标，暗底→浅色图标；
     * - 顶栏标题/导航图标：采样状态栏下方 toolbar 区域。
     * 保证图片深浅变化时，状态栏与顶栏前景始终与局部背景有足够对比度。
     */
    private fun applyToolbarContrast(bitmap: Bitmap) {
        if (isFinishing || isDestroyed || bingImage.width <= 0) return
        val viewW = bingImage.width
        val viewH = bingImage.height
        // centerCrop：缩放比例取大者，裁剪居中。
        val scale = maxOf(viewW / bitmap.width.toFloat(), viewH / bitmap.height.toFloat())
        val cropW = (viewW / scale).toInt()
        val cropH = (viewH / scale).toInt()
        val offX = (bitmap.width - cropW) / 2
        val offY = (bitmap.height - cropH) / 2

        // 状态栏高度（屏幕顶部透明区域），顶栏在其下方。
        var statusBarHeight = 0
        val insets = ViewCompat.getRootWindowInsets(bingImage)?.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.statusBars())
        if (insets != null) statusBarHeight = insets.top
        val toolbarTop = statusBarHeight
        val toolbarBottom = minOf(toolbarTop + toolbar.height, viewH)

        // 状态栏区域：采样图片 y ∈ [0, 状态栏高度] 的亮度。
        val statusLight = sampleRegionBrightness(bitmap, offX, offY, cropW, cropH, 0, statusBarHeight, scale)
        // 顶栏区域：采样 y ∈ [状态栏高度, 状态栏+toolbar] 的亮度。
        val toolbarLight = sampleRegionBrightness(bitmap, offX, offY, cropW, cropH, toolbarTop, toolbarBottom, scale)

        val fg = if (toolbarLight) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
        toolbar.setTitleTextColor(fg)
        val icon = toolbar.navigationIcon
        if (icon != null) DrawableCompat.setTint(icon, fg)
        // 状态栏图标：按状态栏局部亮度独立判断，亮底→深色图标（light=true）。
        ViewCompat.getWindowInsetsController(bingImage)?.setAppearanceLightStatusBars(statusLight)
    }

    /** 采样图片 crop 区域中屏幕 y∈[top,bottom) 一段的平均亮度是否偏亮。 */
    private fun sampleRegionBrightness(bitmap: Bitmap, offX: Int, offY: Int, cropW: Int, cropH: Int,
                                       top: Int, bottom: Int, scale: Float): Boolean {
        val topY = minOf(offY + (top / scale).toInt(), offY + cropH)
        val bottomY = minOf(offY + (bottom / scale).toInt(), offY + cropH)
        if (bottomY <= topY) return false
        var total = 0.0
        var count = 0
        val step = maxOf(1, cropW / 40)
        var y = topY
        while (y < bottomY) {
            var x = offX
            while (x < offX + cropW) {
                total += ColorUtils.calculateLuminance(bitmap.getPixel(x, y))
                count++
                x += step
            }
            y += step
        }
        return count > 0 && total / count > 0.5
    }

    private fun cancelWallpaperLoad() {
        wallpaperLoading = false
    }

    override fun onDestroy() {
        cancelWallpaperLoad()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            drawer.openDrawer(GravityCompat.START)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {
        private fun isScreenPortrait(context: Context): Boolean =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }
}
