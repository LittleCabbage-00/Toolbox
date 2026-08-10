package com.example.cryptoapp.Activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.util.Base64InputStream
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Browser.BrowserDownloadResolver
import com.example.cryptoapp.Browser.BrowserDownloadService
import com.example.cryptoapp.Browser.BrowserFavoriteStore
import com.example.cryptoapp.Browser.BrowserHistoryStore
import com.example.cryptoapp.Browser.BrowserPreferences
import com.example.cryptoapp.Browser.MediaResourceAggregator
import com.example.cryptoapp.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject

/** 轻量多标签浏览器：标签管理、历史、UA、页内查找、分享和隐私清理。 */
class SearchActivity : BaseActivity() {
    private val tabs = ArrayList<BrowserTab>()
    private lateinit var webContainer: FrameLayout
    private lateinit var address: EditText
    private lateinit var favicon: ImageView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var back: MaterialButton
    private lateinit var forward: MaterialButton
    private lateinit var tabButton: MaterialButton
    private lateinit var tabCount: TextView
    private var current: BrowserTab? = null
    private lateinit var browserState: SharedPreferences
    private lateinit var config: SharedPreferences
    private lateinit var historyStore: BrowserHistoryStore
    private var mediaTimelineProbeRunning = false
    private var browserResumed = false
    // 嗅探收集的解析/过滤/聚合都在这个后台线程执行，主线程只负责发起 evaluateJavascript
    // 和最终的弹窗渲染。避免数百个 URL 的 JSON 解析与 streamKey 计算阻塞 UI。
    // 用可空字段而非 by lazy：onDestroy 里只停已创建的线程，不会因访问而强制创建。
    private var sniffThread: HandlerThread? = null
    private var sniffHandler: Handler? = null
    private fun sniffHandler(): Handler {
        var handler = sniffHandler
        if (handler == null) {
            val thread = HandlerThread("ToolboxSniff")
            thread.start()
            handler = Handler(thread.looper)
            sniffThread = thread
            sniffHandler = handler
        }
        return handler
    }
    private lateinit var favoriteStore: BrowserFavoriteStore
    private var pendingDownload: PendingDownload? = null
    private val lastWebTouchPoints = WeakHashMap<WebView, FloatArray>()
    private val storagePermissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val download = pendingDownload
        if (granted && download != null) startDownloadService(download)
        else if (!granted) {
            discardPendingTemporary(download)
            pendingDownload = null
            Toast.makeText(this, "未授予存储权限，无法保存下载文件", Toast.LENGTH_LONG).show()
        }
    }
    private val notificationPermissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val download = pendingDownload
        if (granted && download != null) requestLegacyStorageOrStart(download)
        else if (!granted) {
            discardPendingTemporary(download)
            pendingDownload = null
            Toast.makeText(this, "需要通知权限才能显示下载进度及暂停、继续、取消按钮", Toast.LENGTH_LONG).show()
        }
    }
    private val historyLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val url = result.data!!.getStringExtra(BrowserHistoryActivity.EXTRA_SELECTED_URL)
            if (!url.isNullOrEmpty()) navigate(url)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        browserState = getSharedPreferences(BROWSER_STATE, MODE_PRIVATE)
        config = getSharedPreferences(BrowserPreferences.CONFIG, MODE_PRIVATE)
        historyStore = BrowserHistoryStore(this)
        favoriteStore = BrowserFavoriteStore(this)
        webContainer = findViewById(R.id.webContainer)
        address = findViewById(R.id.textUrl)
        favicon = findViewById(R.id.webIcon)
        progress = findViewById(R.id.progressBar)
        back = findViewById(R.id.goBack)
        forward = findViewById(R.id.goForward)
        tabButton = findViewById(R.id.navTabs)
        tabCount = findViewById(R.id.tabCount)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = current
                if (tab != null && tab.webView.canGoBack()) tab.webView.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        address.setOnEditorActionListener { _, actionId, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) {
                navigate(address.text.toString())
                address.clearFocus()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.btnStart).setOnClickListener {
            if (address.hasFocus()) navigate(address.text.toString())
            else if (current != null) current!!.webView.reload()
        }
        findViewById<View>(R.id.browserExit).setOnClickListener { finish() }
        bindAnimatedClick(back) { current?.let { if (it.webView.canGoBack()) it.webView.goBack() } }
        bindAnimatedClick(forward) { current?.let { if (it.webView.canGoForward()) it.webView.goForward() } }
        bindAnimatedClick(findViewById(R.id.goHome)) { navigate(homeUrl()) }
        bindAnimatedClick(tabButton) { showTabs() }
        bindAnimatedClick(findViewById(R.id.navSet)) { showMenu() }

        if (savedInstanceState != null) {
            val restored = savedInstanceState.getParcelableArrayList<Bundle>("tab_states")
            if (restored != null) for (state in restored) restoreTab(state)
            if (tabs.isNotEmpty()) switchTo(tabs[minOf(
                savedInstanceState.getInt("current_tab", tabs.size - 1), tabs.size - 1)])
        }
        if (tabs.isEmpty()) {
            val initial = intent.getStringExtra("web_address")
            newTab(if (initial.isNullOrBlank()) homeUrl() else initial)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val settings = view.settings
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        applySettings(view)
        view.webViewClient = BrowserClient()
        view.webChromeClient = BrowserChromeClient()
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            prepareDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val scale = maxOf(0.01f, view.scale)
                lastWebTouchPoints[view] = floatArrayOf(event.x / scale, event.y / scale)
            }
            false
        }
        view.setOnLongClickListener { showWebElementActions(view) }
        return view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(view: WebView) {
        view.settings.javaScriptEnabled = config.getBoolean(BrowserPreferences.JAVASCRIPT_ENABLED, true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view,
            !config.getBoolean(BrowserPreferences.BLOCK_THIRD_PARTY_COOKIES, true))
        val mode = browserState.getString(KEY_UA_MODE, UA_DEFAULT)
        var ua: String? = null
        if (UA_DESKTOP == mode || (UA_DEFAULT == mode
                && config.getBoolean(BrowserPreferences.DESKTOP_MODE_DEFAULT, false))) ua = desktopUserAgent()
        else if (UA_CUSTOM == mode) ua = browserState.getString(KEY_CUSTOM_UA, "")
        view.settings.userAgentString = ua ?: null
    }

    private fun newTab(input: String) {
        val tab = BrowserTab(createWebView())
        tabs.add(tab)
        switchTo(tab)
        navigate(input)
    }

    private fun restoreTab(state: Bundle) {
        val tab = BrowserTab(createWebView())
        tab.url = state.getString("toolbox_url", homeUrl())
        tab.title = state.getString("toolbox_title", "新标签页")
        tabs.add(tab)
        if (tab.webView.restoreState(state) == null) tab.webView.loadUrl(tab.url)
    }

    private fun switchTo(tab: BrowserTab) {
        current?.let { old ->
            old.webView.onPause()
            webContainer.removeView(old.webView)
        }
        current = tab
        webContainer.addView(tab.webView, 0)
        if (browserResumed) tab.webView.onResume() else tab.webView.onPause()
        address.setText(tab.webView.url ?: tab.url)
        updateNavigation()
    }

    private fun closeTab(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasCurrent = tab == current
        if (wasCurrent) webContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        tabs.remove(tab)
        if (tabs.isEmpty()) newTab(homeUrl())
        else if (wasCurrent) switchTo(tabs[minOf(index, tabs.size - 1)])
        updateNavigation()
    }

    private fun navigate(input: String) {
        val tab = current ?: return
        val url = normalizeUrl(input)
        tab.url = url
        tab.webView.loadUrl(url)
    }

    private fun normalizeUrl(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return homeUrl()
        if (value.matches(Regex("^https?://.*"))) return value
        if (value.matches(Regex("^[^\\s]+\\.[^\\s]+.*"))) return "https://" + value
        return config.getString(BrowserPreferences.SEARCH_METHOD, "https://cn.bing.com/search?q=") + Uri.encode(value)
    }

    private fun homeUrl(): String = config.getString(BrowserPreferences.HOME_URL, "https://cn.bing.com")!!

    /** 每个标签均提供独立关闭按钮，最后一个关闭后自动创建主页标签。 */
    private fun showTabs() {
        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val padding = dp(8)
        list.setPadding(padding, padding, padding, padding)
        scroll.addView(list)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("标签页（" + tabs.size + "）").setView(scroll)
            .setPositiveButton("新建标签") { _, _ -> newTab(homeUrl()) }
            .setNegativeButton("完成", null).create()
        for (tab in ArrayList(tabs)) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            val text = TextView(this)
            text.text = (if (tab == current) "● " else "") + safeTitle(tab) + "\n" + tab.url
            text.maxLines = 2
            text.ellipsize = TextUtils.TruncateAt.END
            text.setPadding(dp(12), dp(10), dp(8), dp(10))
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val close = MaterialButton(this)
            close.text = ""
            close.setIconResource(R.drawable.ic_close_24)
            close.iconPadding = 0
            close.contentDescription = "关闭标签 " + safeTitle(tab)
            close.minWidth = 0
            row.addView(close, LinearLayout.LayoutParams(dp(52), dp(52)))
            text.setOnClickListener { switchTo(tab); dialog.dismiss() }
            close.setOnClickListener { closeTab(tab); dialog.dismiss(); showTabs() }
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.show()
    }

    private fun safeTitle(tab: BrowserTab): String = if (tab.title.isBlank()) "新标签页" else tab.title

    private fun showMenu() {
        val actions = arrayOf(
            BrowserMenuAction("新建标签", R.drawable.ic_add_24) { newTab(homeUrl()) },
            BrowserMenuAction("关闭标签", R.drawable.ic_close_24) { current?.let { closeTab(it) } },
            BrowserMenuAction("历史记录", R.drawable.ic_history_24) {
                historyLauncher.launch(Intent(this, BrowserHistoryActivity::class.java))
            },
            BrowserMenuAction("添加收藏", R.drawable.ic_bookmark_add_24) { addCurrentFavorite() },
            BrowserMenuAction("收藏夹", R.drawable.ic_bookmark_24) {
                historyLauncher.launch(Intent(this, BrowserFavoritesActivity::class.java))
            },
            BrowserMenuAction("刷新网页", R.drawable.ic_refresh_24) { current?.webView?.reload() },
            BrowserMenuAction("页内查找", R.drawable.ic_find_in_page_24) { showFindInPage() },
            BrowserMenuAction("分享网页", R.drawable.ic_share_24) { shareCurrentPage() },
            BrowserMenuAction("复制链接", R.drawable.ic_link_24) { copyCurrentUrl() },
            BrowserMenuAction("资源嗅探", R.drawable.ic_video_search_24) { showSniffedResources() },
            BrowserMenuAction("下载管理", R.drawable.ic_download_24) {
                startActivity(Intent(this, BrowserDownloadsActivity::class.java))
            },
            BrowserMenuAction("User-Agent", R.drawable.ic_language_24) { showUserAgentSettings() },
            BrowserMenuAction("网页信息", R.drawable.ic_info_24) { showPageInfo() },
            BrowserMenuAction("清理数据", R.drawable.ic_cleaning_24) { confirmClearBrowserData() },
            BrowserMenuAction("浏览器设置", R.drawable.ic_settings_24) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            BrowserMenuAction("外部打开", R.drawable.ic_open_in_new_24) { current?.let { openExternal(it.url) } }
        )
        val dialog = BottomSheetDialog(this)
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setPadding(dp(12), dp(8), dp(12), dp(24))

        // Material 3 底部抽屉把手：弱化容器边界，让面板和网页的层级更清楚。
        val handle = View(this)
        val handleBackground = GradientDrawable()
        handleBackground.setColor(themeColor(sheet,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            R.color.browser_on_surface_variant))
        handleBackground.setCornerRadius(dp(2).toFloat())
        handle.background = handleBackground
        handle.alpha = 0.38f
        val handleParams = LinearLayout.LayoutParams(dp(32), dp(4))
        handleParams.gravity = Gravity.CENTER_HORIZONTAL
        handleParams.bottomMargin = dp(14)
        sheet.addView(handle, handleParams)

        val title = TextView(this)
        title.text = "浏览器功能"
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        title.setPadding(dp(8), 0, dp(8), dp(2))
        sheet.addView(title)

        val subtitle = TextView(this)
        subtitle.text = "快捷操作与网页工具"
        subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        subtitle.setTextColor(themeColor(subtitle,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            R.color.browser_on_surface_variant))
        subtitle.setPadding(dp(8), 0, dp(8), dp(12))
        sheet.addView(subtitle)

        // 紧凑宽度使用 4 列；横屏、平板和 HD 大屏自动扩展为 6 列。
        val menuColumns = if (resources.configuration.screenWidthDp >= 600) 6 else 4
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        sheet.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val menuRows = (actions.size + menuColumns - 1) / menuColumns
        for (rowIndex in 0 until menuRows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(94)))
            for (columnIndex in 0 until menuColumns) {
                val actionIndex = rowIndex * menuColumns + columnIndex
                val tile: View
                if (actionIndex < actions.size) {
                    val action = actions[actionIndex]
                    tile = createBrowserMenuTile(action)
                    tile.setOnClickListener { view ->
                        // 先展示触控反馈，再打开下一个页面或对话框。
                        view.postDelayed({
                            if (!isFinishing) dialog.dismiss()
                            action.command()
                        }, 90L)
                    }
                } else {
                    // 最后一行补空白占位，保证所有实际项目仍与前几行严格等宽。
                    tile = View(this)
                }
                val tileParams = LinearLayout.LayoutParams(0, dp(88), 1f)
                tileParams.setMargins(dp(2), dp(3), dp(2), dp(3))
                row.addView(tile, tileParams)
            }
        }
        dialog.setContentView(sheet)
        dialog.behavior.setSkipCollapsed(true)
        dialog.setOnShowListener {
            // 平板横屏的自动 peekHeight 通常只够显示一行，功能面板必须完整展开。
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.translationY = dp(20).toFloat()
            sheet.alpha = 0f
            sheet.animate().translationY(0f).alpha(1f).setDuration(220L).start()
        }
        dialog.show()
    }

    /**
     * 创建统一的 Material 3 快捷操作单元。48dp 色调容器保证视觉一致，
     * 但整块 88dp 单元都可点击，兼顾美观和无障碍触控面积。
     */
    private fun createBrowserMenuTile(action: BrowserMenuAction): View {
        val tile = LinearLayout(this)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER
        tile.isClickable = true
        tile.isFocusable = true
        tile.contentDescription = action.label

        val selectable = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)) {
            tile.setBackgroundResource(selectable.resourceId)
        }

        val iconContainer = MaterialCardView(this)
        iconContainer.radius = dp(16).toFloat()
        iconContainer.cardElevation = 0f
        iconContainer.strokeWidth = 0
        iconContainer.setCardBackgroundColor(themeColor(iconContainer,
            com.google.android.material.R.attr.colorSecondaryContainer,
            R.color.browser_secondary_container))
        iconContainer.isClickable = false

        val icon = AppCompatImageView(this)
        icon.setImageResource(action.icon)
        icon.imageTintList = ColorStateList.valueOf(themeColor(icon,
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            R.color.browser_on_secondary_container))
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val iconParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        iconContainer.addView(icon, iconParams)
        tile.addView(iconContainer, LinearLayout.LayoutParams(dp(48), dp(48)))

        val label = TextView(this)
        label.text = action.label
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        label.setTextColor(themeColor(label,
            com.google.android.material.R.attr.colorOnSurface,
            R.color.browser_on_surface))
        label.gravity = Gravity.CENTER
        label.setSingleLine(true)
        label.ellipsize = TextUtils.TruncateAt.END
        val labelParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        labelParams.topMargin = dp(6)
        labelParams.leftMargin = dp(2)
        labelParams.rightMargin = dp(2)
        tile.addView(label, labelParams)
        return tile
    }

    /**
     * 安全读取主题颜色。部分厂商 ROM 会叠加主题，旧 Material Components 主题也可能
     * 缺少 Material 3 令牌；使用显式回退色可避免仅因视觉属性缺失导致页面崩溃。
     */
    private fun themeColor(view: View, attribute: Int, fallbackColor: Int): Int =
        MaterialColors.getColor(view.context, attribute,
            ContextCompat.getColor(view.context, fallbackColor))

    private fun addCurrentFavorite() {
        val tab = current ?: return
        val added = favoriteStore.add(safeTitle(tab), tab.url)
        Toast.makeText(this, if (added) "已加入收藏夹" else "已更新收藏", Toast.LENGTH_SHORT).show()
    }

    private fun showSniffedResources() {
        val target = current ?: return
        if (mediaTimelineProbeRunning) {
            Toast.makeText(this, "正在模拟全时间轴请求，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        mediaTimelineProbeRunning = true
        Toast.makeText(this, "正在探测网页媒体请求，约需 2 秒…", Toast.LENGTH_LONG).show()
        // 幂等重挂网络捕获：即使页面加载后替换过 fetch/XHR，探测前也能补上，不影响既有逻辑。
        target.webView.evaluateJavascript(NET_CAPTURE_INSTALL, null)
        target.webView.evaluateJavascript(mediaTimelineProbeScript()) { started ->
            val delay = if ("true" == started) 2_200L else 0L
            Handler(Looper.getMainLooper()).postDelayed({ collectSniffedResources(target) }, delay)
        }
    }

    /**
     * 仅通过带凭据的小范围网络请求补充捕获候选清单，不创建 MediaCodec，也不改变网页播放器。
     * 硬件解码器实例数远小于设备内存容量；用隐藏 video 反复 seek/play 会直接耗尽解码器。
     */
    private fun mediaTimelineProbeScript(): String {
        return "(()=>{" +
            "if(window.__toolboxMediaProbe)return false;" +
            "const f=[];const add=u=>{try{u=new URL(String(u||''),location.href).href;" +
            "if(/^https?:/i.test(u)&&/\\.(?:m3u8|mpd|mp4|webm|mkv|flv|mp3|m4a|aac|flac|ogg)(?:[/?#]|$)/i.test(u))f.push(u)}catch(_){}};" +
            "document.querySelectorAll('video,audio,source,[data-src],[data-url]').forEach(e=>" +
            "['src','data-src','data-url'].forEach(a=>add(e.getAttribute&&e.getAttribute(a))));" +
            "performance.getEntriesByType('resource').forEach(e=>add(e.name));" +
            "const urls=Array.from(new Set(f)).slice(-12);if(!urls.length)return false;" +
            "window.__toolboxMediaProbe=true;Promise.allSettled(urls.map(u=>{const c=new AbortController();" +
            "const timer=setTimeout(()=>c.abort(),1800);return fetch(u,{credentials:'include',cache:'no-store'," +
            "headers:{Range:'bytes=0-65535'},signal:c.signal}).then(r=>r.body?r.body.cancel():null)" +
            ".catch(()=>null).finally(()=>clearTimeout(timer))})).finally(()=>{window.__toolboxMediaProbe=false});" +
            "return true})()"
    }

    private fun collectSniffedResources(target: BrowserTab) {
        if (isFinishing || isDestroyed || !tabs.contains(target) || current != target) {
            mediaTimelineProbeRunning = false
            return
        }
        target.webView.evaluateJavascript(
            // 在 JS 端就用媒体正则过滤，只把媒体 URL 序列化回 Java——之前把页面上所有
            // performance 条目和属性值（数百个）整体打包，跨桥拷贝与 JSON 解析全压在主线程，
            // 这是嗅探后卡顿的主要来源之一。
            "(()=>{const R=/\\.(?:m3u8|mpd|mp4|mkv|webm|flv|mp3|m4a|aac|flac|ogg|ts|m4s|m2ts|cmfv|cmfa)(?:[?#]|$)/i;" +
                "const norm=u=>{try{return new URL(u,location.href).href}catch(e){return ''}};" +
                "const found=[],playing=[];const add=(l,u)=>{u=norm(u);if(u&&R.test(u))l.push(u)};" +
                "document.querySelectorAll('video,audio').forEach(e=>{const c=e.currentSrc,s=e.src;" +
                "if(c)add(found,c);if(s)add(found,s);if(!e.paused){if(c)add(playing,c);if(s)add(playing,s)}});" +
                "document.querySelectorAll('source,[src],[href],[data-src],[data-url]').forEach(e=>" +
                "['src','href','data-src','data-url'].forEach(a=>{const v=e.getAttribute&&e.getAttribute(a);if(v)add(found,v)}));" +
                "performance.getEntriesByType('resource').forEach(e=>add(found,e.name));" +
                "(document.documentElement.innerHTML.match(/https?:[^\\\"'<>\\s]+?\\.m3u8(?:\\?[^\\\"'<>\\s]*)?/gi)||[]).forEach(u=>add(found,u));" +
                "return JSON.stringify({urls:Array.from(new Set(found))," +
                "playing:Array.from(new Set(playing))," +
                "net:(window.__toolboxNetCapture||[]).slice(-200)})})()",
            { value ->
                mediaTimelineProbeRunning = false
                if (current != target) return@evaluateJavascript
                val raw = (value ?: "").trim()
                if (raw.isEmpty()) {
                    runOnUiThread { if (current == target) showResourceList() }
                    return@evaluateJavascript
                }
                // 解析、去重、聚合、排序全部移到后台线程，主线程只做最后弹窗。
                sniffHandler().post {
                    try {
                        // evaluateJavascript 返回值格式随 WebView/Chromium 版本不同：
                        // - 标准行为：脚本返回字符串时会被再次 JSON 编码，得到外层带引号、内层转义的字面量；
                        // - 部分版本：直接返回 JSON 对象字面量。
                        // 两种都兼容，避免任一版本下嗅探结果全部丢失。
                        val object_ = if (raw.startsWith("{")) {
                            Log.d(TAG, "collect: object literal, prefix=" + raw.take(120))
                            JSONObject(raw)
                        } else {
                            // 与 resolveTouchedImageUrl 一致：先经 JSONArray 剥离字符串字面量，再解析对象。
                            val payload = JSONArray("[" + raw + "]").optString(0, "")
                            Log.d(TAG, "collect: string literal, prefix=" + payload.take(120))
                            JSONObject(payload)
                        }
                        val urls = object_.optJSONArray("urls")
                        var urlsCount = 0
                        var urlsMediaMatch = 0
                        var urlsManifest = 0
                        var urlsRejected = 0
                        if (urls != null) {
                            urlsCount = urls.length()
                            for (index in 0 until urls.length()) {
                                val u = urls.optString(index)
                                if (!isMediaResourceUrl(u)) {
                                    urlsRejected++
                                } else {
                                    urlsMediaMatch++
                                    if (MediaResourceAggregator.isHlsManifest(u)) urlsManifest++
                                    recordResourceTo(target, u)
                                }
                            }
                        }
                        val playing = object_.optJSONArray("playing")
                        if (playing != null && playing.length() > 0) {
                            markCurrentPlayback(target, playing.optString(0))
                        }
                        // 网络捕获到的 URL 与 DOM 扫描共用 recordResource 去重，最后统一进资源列表。
                        val net = object_.optJSONArray("net")
                        var netCount = 0
                        var netMediaMatch = 0
                        var netManifest = 0
                        var netRejected = 0
                        if (net != null) {
                            netCount = net.length()
                            for (index in 0 until net.length()) {
                                val u = net.optString(index)
                                if (!isMediaResourceUrl(u)) {
                                    netRejected++
                                } else {
                                    netMediaMatch++
                                    if (MediaResourceAggregator.isHlsManifest(u)) netManifest++
                                    recordResourceTo(target, u)
                                }
                            }
                        }
                        synchronized(target.resources) {
                            Log.d(TAG, "collect: urls=" + urlsCount + "(media=" + urlsMediaMatch +
                                " m3u8=" + urlsManifest + " rej=" + urlsRejected + ") net=" + netCount +
                                "(media=" + netMediaMatch + " m3u8=" + netManifest + " rej=" + netRejected +
                                ") resources=" + target.resources.size)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "collect parse failed, raw=" + raw.take(300), t)
                    }
                    // 聚合与排序同样放后台，仅把结果和是否正在播放标志交给主线程弹窗。
                    val resources: MutableList<MediaResourceAggregator.Resource>
                    synchronized(target.resources) {
                        resources = ArrayList(MediaResourceAggregator.aggregate(target.resources))
                    }
                    sortResourcesByPlayback(resources, target)
                    val playingFirst = resources.isNotEmpty() && hasLivePlaybackSignal(target, resources[0])
                    runOnUiThread {
                        if (isFinishing || isDestroyed || current != target) return@runOnUiThread
                        showResourceList(resources, playingFirst)
                    }
                }
            })
    }

    /** 脚本无返回值时的回退：直接按现有资源弹窗（此时页面可能尚未加载完成）。 */
    private fun showResourceList() {
        val tab = current ?: return
        val resources: MutableList<MediaResourceAggregator.Resource>
        synchronized(tab.resources) {
            resources = ArrayList(MediaResourceAggregator.aggregate(tab.resources))
        }
        showResourceList(resources, false)
    }

    /** 渲染资源列表弹窗；resources 与 playingFirst 已在后台线程准备好，这里只做 UI。 */
    private fun showResourceList(
        resources: List<MediaResourceAggregator.Resource>,
        playingFirst: Boolean
    ) {
        if (resources.isEmpty()) {
            Toast.makeText(this, "暂未嗅探到音视频资源，可先播放网页媒体后重试", Toast.LENGTH_LONG).show()
            return
        }
        val labels = arrayOfNulls<String>(resources.size)
        for (i in resources.indices) {
            val resource = resources[i]
            var value = if (resource.isReady()) resource.url!! else "尚未捕获 m3u8 播放列表"
            if (value.length > 82) value = value.substring(0, 82) + "…"
            labels[i] = if (resource.hls) {
                val details = if (resource.segmentCount > 0) " · 已识别视频分片" else ""
                resource.typeLabel + details + "\n" + value
            } else {
                resource.typeLabel + "\n" + value
            }
            if (i == 0 && playingFirst) labels[i] = "▶ 正在播放 · " + labels[i]
        }
        MaterialAlertDialogBuilder(this).setTitle("嗅探到的资源（" + resources.size + "）")
            .setItems(labels) { _, index ->
                val resource = resources[index]
                if (resource.isReady()) {
                    showResourceActions(resource.url!!, fallbackDirectUrls(resources, resource))
                } else {
                    Toast.makeText(this,
                        "已将分片合并显示；请继续播放几秒，捕获 m3u8 清单后即可完整下载并转为 MP4",
                        Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("关闭", null).show()
    }

    /**
     * 把正在播放的媒体排到列表最前。判定优先级：
     * 1) JS 探测到的未暂停 video/audio 的 src（MSE/blob 播放时拿不到真实地址，自然跳过）；
     * 2) 该流最近仍被 WebView 请求——HLS 分片/直链 range 持续拉流，预览片等一次性请求必然更旧；
     * 3) 回退到原有 HLS 优先、就绪优先、分片数优先排序。
     */
    private fun sortResourcesByPlayback(
        resources: MutableList<MediaResourceAggregator.Resource>, tab: BrowserTab) {
        val playingKey = tab.currentPlaybackKey
        val seen = HashMap<String, Long>()
        synchronized(tab.mediaLastSeen) { seen.putAll(tab.mediaLastSeen) }
        // 比较器会被调用 O(n log n) 次，若每次比较都临时算 streamKey，会重复 URI 解析与正则匹配；
        // 这里为每个资源预计算一次 key，比较时只做 HashMap 查表。
        val keys = HashMap<MediaResourceAggregator.Resource, String>(resources.size * 2)
        for (resource in resources) keys[resource] = streamKeyOf(resource)
        resources.sortWith(Comparator { a, b ->
            val ka = keys[a]!!
            val kb = keys[b]!!
            val pa = if (playingKey != null && playingKey == ka) 0 else 1
            val pb = if (playingKey != null && playingKey == kb) 0 else 1
            if (pa != pb) Integer.compare(pa, pb)
            else {
                val la = seen.getOrDefault(ka, 0L)
                val lb = seen.getOrDefault(kb, 0L)
                if (la != lb) java.lang.Long.compare(lb, la)
                else {
                    val ha = if (a.hls) 0 else 1
                    val hb = if (b.hls) 0 else 1
                    if (ha != hb) Integer.compare(ha, hb)
                    else {
                        val ra = if (a.isReady()) 0 else 1
                        val rb = if (b.isReady()) 0 else 1
                        if (ra != rb) Integer.compare(ra, rb)
                        else Integer.compare(b.segmentCount, a.segmentCount)
                    }
                }
            }
        })
    }

    private fun streamKeyOf(resource: MediaResourceAggregator.Resource): String =
        if (resource.url.isNullOrEmpty()) "" else MediaResourceAggregator.streamKey(resource.url)

    private fun hasLivePlaybackSignal(tab: BrowserTab, resource: MediaResourceAggregator.Resource): Boolean {
        val key = streamKeyOf(resource)
        if (key.isEmpty()) return false
        if (key == tab.currentPlaybackKey) return true
        val lastSeen: Long?
        synchronized(tab.mediaLastSeen) { lastSeen = tab.mediaLastSeen[key] }
        return lastSeen != null && lastSeen > 0L
    }

    /** 记录 JS 探测到的正在播放媒体；仅接受真实 HTTP(S) 地址，blob 等虚拟地址直接忽略。 */
    private fun markCurrentPlayback(tab: BrowserTab, url: String) {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return
        val key = MediaResourceAggregator.streamKey(url)
        synchronized(tab.mediaLastSeen) { tab.mediaLastSeen[key] = SystemClock.elapsedRealtime() }
        tab.currentPlaybackKey = key
    }

    /** 用户选中 HLS 流时，把同列表里已就绪的直链媒体作为失败回退候选；直链本身不再回退。 */
    private fun fallbackDirectUrls(all: List<MediaResourceAggregator.Resource>,
                                  picked: MediaResourceAggregator.Resource): Array<String> {
        if (!picked.hls) return arrayOf()
        val direct = ArrayList<String>()
        for (resource in all) {
            if (!resource.hls && resource.isReady() && !direct.contains(resource.url)) {
                direct.add(resource.url!!)
            }
        }
        return direct.toTypedArray()
    }

    private fun showResourceActions(url: String, fallbackUrls: Array<String>) {
        val audio = isAudioResource(url)
        val format = config.getString(
            if (audio) BrowserPreferences.SNIFF_AUDIO_FORMAT else BrowserPreferences.SNIFF_VIDEO_FORMAT,
            if (audio) "mp3" else "mp4")!!
        val actions = arrayOf("下载并自动转换为 " + format.uppercase(Locale.ROOT),
            "手动选择 FFmpeg 格式", "复制资源地址")
        MaterialAlertDialogBuilder(this).setTitle("资源操作").setItems(actions) { _, which ->
            if (which == 0) requestMediaDownload(url, format, fallbackUrls)
            else if (which == 1) startActivity(Intent(this, MediaTranscodeActivity::class.java)
                .putExtra(MediaTranscodeActivity.EXTRA_SOURCE_URL, url)
                .putExtra(MediaTranscodeActivity.EXTRA_REFERER, current?.url.orEmpty()))
            else {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("网页资源", url))
                Toast.makeText(this, "资源地址已复制", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun isAudioResource(url: String): Boolean {
        // split("\\?", limit=2) 在 Kotlin 是字面子串分割（按反斜杠+问号），不是正则，
        // 查询串切不掉会导致带签名的 .m3u8/.mp3 无法匹配；统一改用 substringBefore('?')。
        val clean = url.lowercase(Locale.ROOT).substringBefore('?')
        return clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac")
            || clean.endsWith(".flac") || clean.endsWith(".ogg") || clean.endsWith(".wav")
    }

    private fun requestMediaDownload(url: String, format: String, fallbackUrls: Array<String>) {
        val tab = current
        if (tab == null || tab.webView == null) {
            Toast.makeText(this, "当前网页已关闭，请重新嗅探", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "web_media_" + System.currentTimeMillis() + "." + format
        val mime = when (format) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> "audio/flac"
        }
        val download = PendingDownload(url, tab.webView.settings.userAgentString,
            "", "", name, mime, true, format, tab.url, "", capturedHeaders(url), fallbackUrls)
        MaterialAlertDialogBuilder(this).setTitle("下载并自动转换？")
            .setMessage("输出：" + name + "\n格式：" + format.uppercase(Locale.ROOT) +
                "\n位置：Download/Toolbox\n\n任务会显示在应用下载管理和通知中。")
            .setNegativeButton("取消", null).setPositiveButton("开始") { _, _ -> requestDownload(download) }
            .show()
    }

    private fun recordResource(view: WebView, url: String) {
        val tab = tabFor(view) ?: return
        recordResourceTo(tab, url)
    }

    /**
     * 只操作 tab 的同步数据结构，不触碰 WebView，可在后台线程安全调用。
     * shouldInterceptRequest 与后台嗅探收集共用，保证两条链路统一去重。
     */
    private fun recordResourceTo(tab: BrowserTab, url: String) {
        if (url.isEmpty()) return
        if (!isMediaResourceUrl(url)) return
        synchronized(tab.resources) {
            val streamKey = MediaResourceAggregator.streamKey(url)
            if (MediaResourceAggregator.isHlsManifest(url)) {
                // 同一视频只保存最后捕获的清单，CDN 刷新签名不会无限增加记录。
                tab.resources.removeAll { existing ->
                    MediaResourceAggregator.isHlsManifest(existing)
                        && streamKey == MediaResourceAggregator.streamKey(existing)
                }
            } else if (MediaResourceAggregator.isHlsSegment(url)) {
                // 分片只用于判断哪一组是正在播放的主视频，不需要保存成百上千个 URL。
                var samples = 0
                val iterator = tab.resources.iterator()
                while (iterator.hasNext()) {
                    val existing = iterator.next()
                    if (MediaResourceAggregator.isHlsSegment(existing)
                        && streamKey == MediaResourceAggregator.streamKey(existing)) {
                        samples++
                        if (samples >= 6) {
                            iterator.remove()
                            break
                        }
                    }
                }
            }
            tab.resources.add(url)
            while (tab.resources.size > 240) {
                val iterator = tab.resources.iterator()
                var removed = false
                while (iterator.hasNext()) {
                    val existing = iterator.next()
                    if (!MediaResourceAggregator.isHlsManifest(existing)) {
                        iterator.remove()
                        removed = true
                        break
                    }
                }
                if (!removed && tab.resources.isNotEmpty()) tab.resources.remove(tab.resources.iterator().next())
            }
        }
    }

    private fun isMediaResourceUrl(url: String): Boolean {
        // 关键修复：split("\\?", limit=2) 在 Kotlin 是字面子串分割（按"反斜杠+问号"），
        // 不是正则，带 ?auth_key= 的 m3u8 查询串切不掉，endsWith(".m3u8") 永远 false，
        // 这正是嗅探 HLS 一直为空、连 URL 都进不了 resources 的根因。改用 substringBefore('?')。
        val clean = url.lowercase(Locale.ROOT).substringBefore('?')
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".mp4")
            || clean.endsWith(".mkv") || clean.endsWith(".webm") || clean.endsWith(".flv")
            || clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac")
            || clean.endsWith(".flac") || clean.endsWith(".ogg") || clean.endsWith(".ts")
            || clean.endsWith(".m4s") || clean.endsWith(".m2ts") || clean.endsWith(".cmfv")
            || clean.endsWith(".cmfa")
    }

    private fun bindAnimatedClick(view: View, action: () -> Unit) {
        view.setOnClickListener { clicked -> animateTap(clicked); action() }
    }

    private fun animateTap(view: View) {
        view.animate().cancel()
        view.animate().scaleX(0.84f).scaleY(0.84f).setDuration(70).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(190)
                .setInterpolator(OvershootInterpolator(1.8f)).start()
        }.start()
    }

    private fun showFindInPage() {
        if (current == null) return
        val editor = dialogEditor("")
        editor.hint = "输入网页内文字"
        MaterialAlertDialogBuilder(this).setTitle("在网页中查找").setView(editor)
            .setNegativeButton("取消", null)
            .setPositiveButton("查找") { _, _ -> current!!.webView.findAllAsync(editor.text.toString()) }
            .show()
    }

    private fun shareCurrentPage() {
        val tab = current ?: return
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, safeTitle(tab)).putExtra(Intent.EXTRA_TEXT, tab.url)
        startActivity(Intent.createChooser(share, "分享网页"))
    }

    private fun copyCurrentUrl() {
        val tab = current ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("网页链接", tab.url))
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    private fun showPageInfo() {
        val tab = current ?: return
        MaterialAlertDialogBuilder(this).setTitle("网页信息")
            .setMessage("标题：" + safeTitle(tab) + "\n\n地址：" + tab.url +
                "\n\n连接：" + (if (tab.url.startsWith("https://")) "HTTPS" else "非 HTTPS"))
            .setPositiveButton("关闭", null).show()
    }

    private fun confirmClearBrowserData() {
        MaterialAlertDialogBuilder(this).setTitle("清理浏览数据？")
            .setMessage("将清除 Cookie、缓存和网页本地存储，不删除历史记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                for (tab in tabs) tab.webView.clearCache(true)
                Toast.makeText(this, "浏览数据已清理", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showUserAgentSettings() {
        val choices = arrayOf("跟随软件设置", "移动端", "桌面端", "自定义")
        MaterialAlertDialogBuilder(this).setTitle("User-Agent").setItems(choices) { _, which ->
            when (which) {
                0 -> applyUserAgentMode(UA_DEFAULT, null)
                1 -> applyUserAgentMode(UA_MOBILE, null)
                2 -> applyUserAgentMode(UA_DESKTOP, null)
                else -> showCustomUserAgent()
            }
        }.show()
    }

    private fun showCustomUserAgent() {
        val editor = dialogEditor(browserState.getString(KEY_CUSTOM_UA, "")!!)
        MaterialAlertDialogBuilder(this).setTitle("自定义 User-Agent").setView(editor)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                applyUserAgentMode(UA_CUSTOM, editor.text.toString().trim())
            }.show()
    }

    private fun dialogEditor(value: String): EditText {
        val editor = EditText(this)
        editor.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        editor.setText(value)
        val padding = dp(24)
        editor.setPadding(padding, padding / 2, padding, 0)
        return editor
    }

    private fun applyUserAgentMode(mode: String, custom: String?) {
        val edit = browserState.edit().putString(KEY_UA_MODE, mode)
        if (custom != null) edit.putString(KEY_CUSTOM_UA, custom)
        edit.apply()
        for (tab in tabs) {
            applySettings(tab.webView)
            tab.webView.reload()
        }
    }

    private fun desktopUserAgent(): String =
        WebSettings.getDefaultUserAgent(this).replace("; wv", "")
            .replace(" Mobile ", " ").replace("Version/4.0 ", "")

    private fun updateNavigation() {
        val tab = current
        back.isEnabled = tab != null && tab.webView.canGoBack()
        forward.isEnabled = tab != null && tab.webView.canGoForward()
        tabCount.text = tabs.size.toString()
        tabButton.contentDescription = "标签页，当前 " + tabs.size + " 个"
    }

    private fun openExternal(url: String) {
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "没有应用可以打开此链接", Toast.LENGTH_SHORT).show()
        }
    }

    /** 下载前展示解析结果，让用户确认文件名、文件类型和固定保存目录。 */
    private fun prepareDownload(
        url: String, userAgent: String, disposition: String?, mimeType: String?, length: Long) {
        val resolvedUrl = resolveDownloadUrl(url)
        if (resolvedUrl != null && resolvedUrl.startsWith(PAGE_DATA_PREFIX)) {
            if (current == null) {
                Toast.makeText(this, "网页已关闭，无法读取内嵌图片", Toast.LENGTH_LONG).show()
            } else {
                extractPageDataImage(current!!.webView, resolvedUrl.substring(PAGE_DATA_PREFIX.length), userAgent)
            }
            return
        }
        if (resolvedUrl != null && resolvedUrl.startsWith("data:")) {
            extractDataImage(resolvedUrl, userAgent)
            return
        }
        if (resolvedUrl != null && resolvedUrl.startsWith("blob:")) {
            if (current == null) {
                Toast.makeText(this, "网页已关闭，无法读取临时图片", Toast.LENGTH_LONG).show()
            } else {
                extractBlobImage(current!!.webView, resolvedUrl, userAgent)
            }
            return
        }
        if (resolvedUrl == null || !(resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://"))) {
            Toast.makeText(this, "暂不支持此类型的下载链接", Toast.LENGTH_LONG).show()
            return
        }
        val info = BrowserDownloadResolver.resolve(resolvedUrl, disposition, mimeType)
        val referer = if (current == null || current!!.webView.url == null) "" else current!!.webView.url.toString()
        val download = PendingDownload(resolvedUrl, userAgent, disposition ?: "", mimeType ?: "",
            info.fileName, info.mimeType, false, "", referer, "", capturedHeaders(resolvedUrl))
        val size = if (length > 0) readableSize(length) else "未知"
        MaterialAlertDialogBuilder(this)
            .setTitle("下载文件？")
            .setMessage("文件：" + download.fileName + "\n类型：" + download.mimeType +
                "\n大小：" + size + "\n位置：Download/Toolbox")
            .setNegativeButton("取消", null)
            .setPositiveButton("下载") { _, _ -> requestDownload(download) }
            .show()
    }

    private fun extractDataImage(dataUrl: String, userAgent: String) {
        Toast.makeText(this, "正在提取网页图片…", Toast.LENGTH_SHORT).show()
        Thread(Runnable {
            var temporary: File? = null
            try {
                val comma = dataUrl.indexOf(',')
                if (comma < 0) throw IllegalArgumentException("无效的 data 图片")
                val metadata = dataUrl.substring(5, comma)
                var mime = metadata.split(";", limit = 2)[0]
                if (mime.isEmpty()) mime = "image/png"
                val payload = dataUrl.substring(comma + 1)
                temporary = File.createTempFile("toolbox-web-image-", ".part", cacheDir)
                FileOutputStream(temporary).use { output ->
                    if (metadata.lowercase(Locale.ROOT).contains(";base64")) {
                        // 不再同时持有 Base64 字符串、ASCII 副本和完整解码字节，避免大图瞬时占用过高。
                        Base64InputStream(StringAsciiInputStream(payload), Base64.DEFAULT).use { decoded ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = decoded.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } else {
                        output.write(URLDecoder.decode(payload, "UTF-8").toByteArray(StandardCharsets.UTF_8))
                    }
                }
                val ready = temporary
                val finalMime = mime
                runOnUiThread { queueExtractedImage(ready, finalMime, userAgent, dataUrl) }
            } catch (error: Exception) {
                temporary?.delete()
                val message = error.localizedMessage
                runOnUiThread {
                    Toast.makeText(this,
                        "图片提取失败：" + (message ?: "数据格式不受支持"), Toast.LENGTH_LONG).show()
                }
            }
        }, "web-data-image").start()
    }

    /** 将保存在页面 JavaScript 状态中的大型 data URI 转成 blob，再沿用可靠的分块读取通道。 */
    private fun extractPageDataImage(view: WebView, state: String, userAgent: String) {
        Toast.makeText(this, "正在提取网页内嵌图片…", Toast.LENGTH_SHORT).show()
        val script = "(()=>{const k=" + JSONObject.quote(state) + ";const u=window[k];" +
            "const s={ready:false,error:'',mime:'',data:''};window[k]=s;" +
            "if(typeof u!=='string'||!u.startsWith('data:')){s.error='内嵌图片状态已失效';return false;}" +
            "fetch(u).then(r=>r.blob()).then(b=>{s.mime=b.type||'image/png';const f=new FileReader();" +
            "f.onload=()=>{const v=String(f.result||'');s.data=v.slice(v.indexOf(',')+1);s.ready=true};" +
            "f.onerror=()=>{s.error='读取图片失败'};f.readAsDataURL(b)}).catch(e=>{s.error=String(e&&e.message||e)});" +
            "return true})()"
        view.evaluateJavascript(script) {
            pollBlobImage(view, state, "toolbox-image://embedded", userAgent, 0)
        }
    }

    private fun extractBlobImage(view: WebView, blobUrl: String, userAgent: String) {
        Toast.makeText(this, "正在从网页提取临时图片…", Toast.LENGTH_SHORT).show()
        val state = "__toolboxImageExtract" + System.nanoTime()
        val script = "(()=>{const k=" + JSONObject.quote(state) + ";const s={ready:false,error:'',mime:'',data:''};" +
            "window[k]=s;fetch(" + JSONObject.quote(blobUrl) + ").then(r=>{if(!r.ok)throw Error('HTTP '+r.status);return r.blob()})" +
            ".then(b=>{s.mime=b.type||'image/png';const f=new FileReader();f.onload=()=>{const v=String(f.result||'');" +
            "s.data=v.slice(v.indexOf(',')+1);s.ready=true};f.onerror=()=>{s.error='读取图片失败'};f.readAsDataURL(b)})" +
            ".catch(e=>{s.error=String(e&&e.message||e)});return true})()"
        view.evaluateJavascript(script) {
            pollBlobImage(view, state, blobUrl, userAgent, 0)
        }
    }

    private fun pollBlobImage(
        view: WebView, state: String, sourceUrl: String, userAgent: String, attempt: Int) {
        if (isFinishing || isDestroyed) return
        val script = "(()=>{const s=window[" + JSONObject.quote(state) + "];return s?" +
            "{ready:s.ready,error:s.error,mime:s.mime,length:s.data.length}:{error:'提取状态已丢失'}})()"
        view.evaluateJavascript(script) { value ->
            try {
                val status = JSONObject(value)
                val error = status.optString("error")
                if (error.isNotEmpty()) throw IllegalStateException(error)
                if (status.optBoolean("ready")) {
                    val temporary = File.createTempFile("toolbox-web-image-", ".part", cacheDir)
                    val output = FileOutputStream(temporary)
                    readBlobChunk(view, state, sourceUrl, userAgent, status.optString("mime", "image/png"),
                        temporary, output, 0, status.optInt("length"))
                    return@evaluateJavascript
                }
                if (attempt >= 150) throw IllegalStateException("网页提取超时")
                Handler(Looper.getMainLooper()).postDelayed(
                    { pollBlobImage(view, state, sourceUrl, userAgent, attempt + 1) }, 200)
            } catch (error: Exception) {
                view.evaluateJavascript("delete window[" + JSONObject.quote(state) + "]", null)
                Toast.makeText(this, "图片提取失败：" + error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readBlobChunk(
        view: WebView, state: String, sourceUrl: String, userAgent: String, mime: String,
        temporary: File, output: FileOutputStream, offset: Int, total: Int) {
        if (offset >= total) {
            try {
                output.close()
            } catch (ignored: Exception) {
                temporary.delete()
                return
            }
            view.evaluateJavascript("delete window[" + JSONObject.quote(state) + "]", null)
            queueExtractedImage(temporary, mime, userAgent, sourceUrl)
            return
        }
        val end = minOf(total, offset + 256 * 1024)
        val script = "window[" + JSONObject.quote(state) + "].data.slice(" + offset + "," + end + ")"
        view.evaluateJavascript(script) { value ->
            try {
                val chunk = JSONArray("[" + value + "]").optString(0, "")
                if (chunk.isEmpty()) throw IllegalStateException("网页返回了空图片分块")
                output.write(Base64.decode(chunk, Base64.DEFAULT))
                readBlobChunk(view, state, sourceUrl, userAgent, mime, temporary, output, end, total)
            } catch (error: Exception) {
                try {
                    output.close()
                } catch (ignored: Exception) { }
                temporary.delete()
                view.evaluateJavascript("delete window[" + JSONObject.quote(state) + "]", null)
                Toast.makeText(this, "图片提取失败：" + error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queueExtractedImage(temporary: File, mime: String, userAgent: String, sourceUrl: String) {
        if (isFinishing || isDestroyed) {
            temporary.delete()
            return
        }
        val info = BrowserDownloadResolver.resolve(
            "https://local/image_" + System.currentTimeMillis(), "", mime)
        val referer = if (current == null || current!!.webView.url == null) "" else current!!.webView.url.toString()
        // 绝不能把完整 data URI 放进 Service Intent 或下载记录；大图会突破 Binder 事务上限。
        val compactSource = if (sourceUrl != null && (sourceUrl.startsWith("data:")
                || sourceUrl.startsWith(PAGE_DATA_PREFIX))) "toolbox-image://embedded" else sourceUrl
        val download = PendingDownload(compactSource, userAgent, "", mime,
            info.fileName, info.mimeType, false, "", referer, temporary.absolutePath)
        requestDownload(download)
    }

    private fun discardPendingTemporary(download: PendingDownload?) {
        if (download == null || download.localSourcePath.isEmpty()) return
        try {
            val file = File(download.localSourcePath).canonicalFile
            if (file.parentFile == cacheDir.canonicalFile && file.name.startsWith("toolbox-web-image-")) {
                file.delete()
            }
        } catch (ignored: Exception) { }
    }

    /** 将协议相对、页面相对的懒加载图片地址解析成下载服务可访问的绝对 URL。 */
    private fun resolveDownloadUrl(value: String?): String? {
        if (value == null) return null
        val clean = value.trim()
        if (clean.isEmpty() || clean.startsWith("blob:") || clean.startsWith("data:")) return clean
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        val base = if (current == null) null else current!!.webView.url
        if (base.isNullOrEmpty()) return clean
        return try {
            URI(base).resolve(clean).toString()
        } catch (ignored: Exception) {
            clean
        }
    }

    private fun capturedHeaders(url: String): String {
        val tab = current ?: return ""
        synchronized(tab.requestHeaders) {
            val headers = tab.requestHeaders[url]
            if (headers != null) return headers
        }
        synchronized(tab.mediaRequestHeaders) {
            val headers = tab.mediaRequestHeaders[MediaResourceAggregator.streamKey(url)]
            return headers ?: ""
        }
    }

    private fun requestDownload(download: PendingDownload) {
        pendingDownload = download
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestLegacyStorageOrStart(download)
    }

    private fun requestLegacyStorageOrStart(download: PendingDownload) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDownloadService(download)
    }

    private fun startDownloadService(download: PendingDownload) {
        pendingDownload = null
        // 媒体 CDN（如 em-h.phncdn.com）通常不持有登录鉴权 Cookie，它们存在页面域
        // （如 cn.pornhub.com）上。只按下载 URL 取 Cookie 会得到空值，导致分片请求被拒。
        // 同时合并页面域与媒体域的 Cookie，交给代理统一携带。
        val cookieManager = CookieManager.getInstance()
        val pageCookies = cookieManager.getCookie(if (current == null) download.url else current!!.url)
        val mediaCookies = cookieManager.getCookie(download.url)
        val cookies = mergeCookies(pageCookies, mediaCookies)
        val downloadId = System.currentTimeMillis()
        val service = Intent(this, BrowserDownloadService::class.java)
            .setAction(BrowserDownloadService.ACTION_START)
            .putExtra(BrowserDownloadService.EXTRA_DOWNLOAD_ID, downloadId)
            .putExtra(BrowserDownloadService.EXTRA_URL, download.url)
            .putExtra(BrowserDownloadService.EXTRA_USER_AGENT, download.userAgent)
            .putExtra(BrowserDownloadService.EXTRA_COOKIES, cookies)
            .putExtra(BrowserDownloadService.EXTRA_DISPOSITION, download.contentDisposition)
            .putExtra(BrowserDownloadService.EXTRA_MIME, download.originalMimeType)
            .putExtra(BrowserDownloadService.EXTRA_FILE_NAME, download.fileName)
            .putExtra(BrowserDownloadService.EXTRA_RESOLVED_MIME, download.mimeType)
            .putExtra(BrowserDownloadService.EXTRA_AUTO_CONVERT, download.autoConvert)
            .putExtra(BrowserDownloadService.EXTRA_OUTPUT_FORMAT, download.outputFormat)
            .putExtra(BrowserDownloadService.EXTRA_REFERER, download.referer)
        service.putExtra(BrowserDownloadService.EXTRA_LOCAL_SOURCE_PATH, download.localSourcePath)
        service.putExtra(BrowserDownloadService.EXTRA_REQUEST_HEADERS, download.requestHeaders)
        service.putExtra(BrowserDownloadService.EXTRA_FALLBACK_URLS, download.fallbackUrls)
        ContextCompat.startForegroundService(this, service)
        Toast.makeText(this, "已开始下载，可在通知中控制", Toast.LENGTH_SHORT).show()
    }

    /** 合并页面域与媒体域的 Cookie，媒体域为空时仍携带页面域 Cookie。 */
    private fun mergeCookies(pageCookies: String?, mediaCookies: String?): String {
        val merged = StringBuilder()
        if (!pageCookies.isNullOrEmpty()) merged.append(pageCookies)
        if (!mediaCookies.isNullOrEmpty()) {
            if (merged.isNotEmpty()) merged.append("; ")
            merged.append(mediaCookies)
        }
        return merged.toString()
    }

    /** 根据 WebView 命中类型，为图片和普通链接提供统一的下载入口。 */
    private fun showWebElementActions(view: WebView): Boolean {
        val hit = view.hitTestResult ?: return false
        val type = hit.type
        val direct = hit.extra
        if (type == WebView.HitTestResult.IMAGE_TYPE) {
            resolveTouchedImageUrl(view, direct) { resolved -> showElementMenu(view, null, resolved) }
            return true
        }
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            showElementMenu(view, direct, null)
            return true
        }
        if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            val handler = Handler(Looper.getMainLooper()) { message ->
                val data = message.data
                resolveTouchedImageUrl(view, data.getString("src") ?: direct) { resolved ->
                    showElementMenu(view, data.getString("url"), resolved)
                }
                true
            }
            val msg = handler.obtainMessage()
            view.requestFocusNodeHref(msg)
            return true
        }
        if (type == WebView.HitTestResult.UNKNOWN_TYPE) {
            // CSS background-image 不会被 WebView 标记为 IMAGE_TYPE，仍应提供图片下载菜单。
            resolveTouchedImageUrl(view, null) { resolved ->
                if (!resolved.isNullOrEmpty()) showElementMenu(view, null, resolved)
            }
            return true
        }
        return false
    }

    /**
     * HitTestResult 经常只返回透明占位图；优先读取长按节点的 currentSrc、data-src、srcset
     * 和 CSS background-image，才能拿到懒加载图片当前真正显示的地址。
     */
    private fun resolveTouchedImageUrl(view: WebView, fallback: String?, callback: (String?) -> Unit) {
        // WebView 已经给出可访问的 HTTP(S) 图片时直接使用，避免坐标换算误选到相邻节点。
        if (fallback != null && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
            callback(fallback)
            return
        }
        val point = lastWebTouchPoints[view]
        if (point == null || !view.settings.javaScriptEnabled) {
            callback(fallback)
            return
        }
        val dataState = "__toolboxTouchedData" + System.nanoTime()
        val script = "(()=>{const k=" + JSONObject.quote(dataState) +
            ";let e=document.elementFromPoint(" + point[0] + "," + point[1] + ");" +
            "if(!e)return '';let n=e.closest?e.closest('img,picture,source,[data-src],[data-original],[data-lazy-src]'):e;" +
            "if(!n)n=e;const c=[];const add=v=>{if(v)c.push(String(v).trim())};" +
            "const attrs=x=>{if(!x)return;['data-original','data-src','data-lazy-src','data-url','src'].forEach(a=>add(x.getAttribute&&x.getAttribute(a)));" +
            "add(x.currentSrc);let s=x.getAttribute&&x.getAttribute('srcset');if(s)add(s.split(',').pop().trim().split(/\\s+/)[0]);};" +
            "attrs(n);attrs(n.querySelector&&n.querySelector('img'));attrs(n.querySelector&&n.querySelector('source'));attrs(e);" +
            "for(let x=e;x&&x!==document;x=x.parentElement){let b=getComputedStyle(x).backgroundImage;" +
            "let m=b&&b.match(/url\\([\"']?(.*?)[\"']?\\)/);if(m)add(m[1]);}" +
            "let u=c.find(v=>/^(https?:)?\\/\\//i.test(v));if(!u)u=c.find(v=>v&&!/^data:|^blob:/i.test(v));" +
            "if(!u)u=c.find(v=>/^data:|^blob:/i.test(v));" +
            "try{u=u?new URL(u,location.href).href:''}catch(_){}" +
            "if(/^data:/i.test(u)){window[k]=u;return '" + PAGE_DATA_PREFIX + "'+k}return u||''})()"
        view.evaluateJavascript(script) { value ->
            var resolved = ""
            try {
                resolved = JSONArray("[" + value + "]").optString(0, "")
            } catch (ignored: Exception) { }
            callback(if (resolved.isEmpty()) fallback else resolved)
        }
    }

    private fun showElementMenu(view: WebView, linkUrl: String?, imageUrl: String?) {
        val labels = ArrayList<String>()
        val actions = ArrayList<Runnable>()
        val userAgent = view.settings.userAgentString
        if (!imageUrl.isNullOrEmpty()) {
            labels.add("下载图片")
            actions.add { prepareDownload(imageUrl, userAgent, "", "", -1) }
            labels.add("复制图片地址")
            actions.add { copyAddress(imageUrl, "图片地址已复制") }
        }
        if (!linkUrl.isNullOrEmpty()) {
            labels.add("下载链接文件")
            actions.add { prepareDownload(linkUrl, userAgent, "", "", -1) }
            labels.add("复制链接地址")
            actions.add { copyAddress(linkUrl, "链接地址已复制") }
        }
        if (labels.isEmpty()) return
        MaterialAlertDialogBuilder(this).setTitle("网页元素")
            .setItems(labels.toTypedArray()) { _, index -> actions[index].run() }
            .setNegativeButton("取消", null).show()
    }

    private fun copyAddress(value: String, message: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("网页地址", value))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun readableSize(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
        var value = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    /** 直接从 String 提供 ASCII 字节，避免为了 Base64InputStream 再复制一份完整字符串。 */
    private class StringAsciiInputStream(private val value: String) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position < value.length) value[position++].code and 0xff else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= value.length) return -1
            val count = minOf(length, value.length - position)
            for (index in 0 until count) {
                buffer[offset + index] = value[position + index].code.toByte()
            }
            position += count
            return count
        }
    }

    private class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val originalMimeType: String,
        val fileName: String,
        val mimeType: String,
        val autoConvert: Boolean = false,
        val outputFormat: String = "",
        val referer: String = "",
        val localSourcePath: String = "",
        val requestHeaders: String = "",
        val fallbackUrls: Array<String> = arrayOf()
    )

    private class BrowserMenuAction(
        val label: String,
        val icon: Int,
        val command: () -> Unit
    )

    private class BrowserTab(val webView: WebView) {
        var title = "新标签页"
        var url = ""
        val resources = LinkedHashSet<String>()
        val requestHeaders = LinkedHashMap<String, String>()
        val mediaRequestHeaders = LinkedHashMap<String, String>()
        /** streamKey -> 该流最后一次被 WebView 请求的单调时间，用于识别正在播放的媒体。 */
        val mediaLastSeen = HashMap<String, Long>()
        /** JS 探测到的当前正在播放媒体的流 key；页面用 MSE/blob 播放或暂停时可能为 null。 */
        @Volatile
        var currentPlaybackKey: String? = null
    }

    private inner class BrowserClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            recordResource(view, url)
            val tab = tabFor(view)
            if (tab != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                val serialized = JSONObject(request.requestHeaders).toString()
                synchronized(tab.requestHeaders) {
                    tab.requestHeaders[url] = serialized
                    while (tab.requestHeaders.size > 200) {
                        tab.requestHeaders.remove(tab.requestHeaders.keys.iterator().next())
                    }
                }
                if (isMediaResourceUrl(url)) {
                    // 正在播放的流（HLS 分片/清单或直链 range 请求）会持续被请求，最后活跃的即为当前播放媒体。
                    // streamKey 内含 URI 解析，每个媒体请求只算一次，避免对两个同步块各算一遍。
                    val key = MediaResourceAggregator.streamKey(url)
                    synchronized(tab.mediaLastSeen) {
                        tab.mediaLastSeen[key] = SystemClock.elapsedRealtime()
                    }
                    synchronized(tab.mediaRequestHeaders) {
                        tab.mediaRequestHeaders[key] = serialized
                        while (tab.mediaRequestHeaders.size > 32) {
                            tab.mediaRequestHeaders.remove(tab.mediaRequestHeaders.keys.iterator().next())
                        }
                    }
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) return false
            openExternal(url)
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) return false
            openExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) {
            // 页面 JS 运行前就挂上网络捕获：原生 HLS 播放器的候选/清单 fetch 发生得早，晚挂就漏了。
            view.evaluateJavascript(NET_CAPTURE_INSTALL, null)
            val tab = tabFor(view)
            if (tab != null) {
                tab.url = url
                synchronized(tab.resources) { tab.resources.clear() }
                synchronized(tab.requestHeaders) { tab.requestHeaders.clear() }
                synchronized(tab.mediaRequestHeaders) { tab.mediaRequestHeaders.clear() }
                synchronized(tab.mediaLastSeen) { tab.mediaLastSeen.clear() }
                tab.currentPlaybackKey = null
            }
            if (tab == current) {
                address.setText(url)
                progress.visibility = View.VISIBLE
                if (icon != null) favicon.setImageBitmap(icon)
                updateNavigation()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            val tab = tabFor(view)
            if (tab != null) {
                tab.url = url
                tab.title = view.title ?: ""
            }
            historyStore.add(view.title, url)
            if (tab == current) updateNavigation()
        }
    }

    private inner class BrowserChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, value: Int) {
            if (current != null && current!!.webView == view) {
                progress.setProgressCompat(value, true)
                progress.visibility = if (value >= 100) View.GONE else View.VISIBLE
            }
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            val tab = tabFor(view)
            if (tab != null) tab.title = title
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            if (current != null && current!!.webView == view) favicon.setImageBitmap(icon)
        }
    }

    private fun tabFor(view: WebView): BrowserTab? {
        for (tab in tabs) if (tab.webView == view) return tab
        return null
    }

    override fun onResume() {
        super.onResume()
        browserResumed = true
        for (tab in tabs) {
            applySettings(tab.webView)
            if (tab != current) tab.webView.onPause()
        }
        if (current != null) current!!.webView.onResume()
    }

    override fun onPause() {
        browserResumed = false
        for (tab in tabs) tab.webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val states = ArrayList<Bundle>()
        for (tab in tabs) {
            val state = Bundle()
            tab.webView.saveState(state)
            state.putString("toolbox_url", if (tab.url.isNullOrEmpty()) homeUrl() else tab.url)
            state.putString("toolbox_title", tab.title)
            states.add(state)
        }
        outState.putParcelableArrayList("tab_states", states)
        outState.putInt("current_tab", maxOf(0, tabs.indexOf(current)))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        for (tab in ArrayList(tabs)) {
            tab.webView.stopLoading()
            tab.webView.destroy()
        }
        tabs.clear()
        // 停止嗅探后台线程，避免 Activity 销毁后 HandlerThread 空转泄漏。
        sniffThread?.quitSafely()
        sniffThread = null
        sniffHandler = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ToolboxSniff"
        private const val BROWSER_STATE = "browser_state"
        private const val KEY_UA_MODE = "user_agent_mode"
        private const val KEY_CUSTOM_UA = "custom_user_agent"
        private const val UA_DEFAULT = "default"
        private const val UA_MOBILE = "mobile"
        private const val UA_DESKTOP = "desktop"
        private const val UA_CUSTOM = "custom"
        /** 大型 data URI 只在 WebView 页面内保存，Java 层通过短标识分块提取。 */
        private const val PAGE_DATA_PREFIX = "toolbox-page-data:"

        /**
         * 运行时网络捕获注入：包装 fetch / XHR，把页面 JS 内部请求的媒体 URL 记录到
         * window.__toolboxNetCapture，绕过"原生 HLS + CORS/TAO 屏蔽"站点的 DOM 盲区。
         * 幂等（不重复包装同一函数），页面替换过 fetch/XHR 后再次注入可重新挂上。
         */
        private const val NET_CAPTURE_INSTALL =
            "(function(){var L=window.__toolboxNetCapture;if(!L){L=[];window.__toolboxNetCapture=L};" +
            "var R=/\\.(?:m3u8|mpd|mp4|mkv|webm|flv|mp3|m4a|aac|flac|ogg|ts|m4s|m2ts|cmfv|cmfa)(?:[?#]|$)/i;" +
            "var A=function(u){try{if(u){u=String(u);if(R.test(u)){if(L.length>=512)L.splice(0,1);L.push(new URL(u,location.href).href)}}}catch(e){}};" +
            "var F=window.fetch;if(F&&F!==window.__tbWrapFetch){window.__tbWrapFetch=F;window.fetch=function(){try{var i=arguments[0];if(typeof i==='string')A(i);else if(i&&i.url)A(i.url)}catch(e){}return F.apply(this,arguments)}};" +
            "var X=XMLHttpRequest.prototype.open;if(X&&X!==window.__tbWrapXhr){window.__tbWrapXhr=X;XMLHttpRequest.prototype.open=function(m,u){try{A(u)}catch(e){}return X.apply(this,arguments)}};})()"
    }
}
