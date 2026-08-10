package com.example.cryptoapp.Base

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.ColorUtils
import com.example.cryptoapp.R
import com.example.cryptoapp.Browser.BrowserPreferences
import com.google.android.material.shape.MaterialShapeDrawable

/**
 * 所有页面的轻量基类，只负责生命周期登记和统一的返回行为。
 * 页面专属逻辑（例如主页抽屉）由对应 Activity 自己处理，避免基类依赖某个特定布局。
 */
open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = getSharedPreferences(BrowserPreferences.CONFIG, MODE_PRIVATE)
            .getString(BrowserPreferences.THEME_MODE, "system")
        AppCompatDelegate.setDefaultNightMode(when (themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.d("BaseActivity", javaClass.simpleName)
        ActivityCollector.addActivity(this)
    }

    override fun onDestroy() {
        ActivityCollector.removeActivity(this)
        super.onDestroy()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applyEdgeToEdge(view)
        applyResponsiveWidth(view)
    }

    /**
     * 对标记为 responsive_content 的主体设置 840dp 最大宽度。手机保持满宽，
     * 横屏和大屏居中显示，避免输入框与卡片被拉成难以阅读的超长行。
     */
    private fun applyResponsiveWidth(root: View) {
        val content = root.findViewWithTag<View>("responsive_content") ?: return
        val originalWidth = content.layoutParams.width
        val originalGravity = when (val params = content.layoutParams) {
            is FrameLayout.LayoutParams -> params.gravity
            is LinearLayout.LayoutParams -> params.gravity
            else -> Gravity.NO_GRAVITY
        }
        val originallyCentered = (content.layoutParams as? RelativeLayout.LayoutParams)
            ?.rules?.get(RelativeLayout.CENTER_HORIZONTAL) != 0
        var updatePosted = false

        fun updateWidthAfterLayout() {
            if (updatePosted) return
            updatePosted = true
            // 改变 LayoutParams 会再次触发布局，延迟到当前布局过程结束，避免 requestLayout 警告和抖动。
            root.post {
                updatePosted = false
                val available = root.width
                if (available <= 0) return@post
                val threshold = (600 * resources.displayMetrics.density).toInt()
                val maxWidth = (840 * resources.displayMetrics.density).toInt()
                val horizontalPadding = (48 * resources.displayMetrics.density).toInt()
                val targetWidth = if (available >= threshold) {
                    minOf(maxWidth, (available - horizontalPadding).coerceAtLeast(1))
                } else {
                    originalWidth
                }
                if (content.layoutParams.width == targetWidth) return@post
                content.layoutParams = content.layoutParams.apply {
                    width = targetWidth
                    when (this) {
                        is FrameLayout.LayoutParams -> gravity = if (available >= threshold) {
                            Gravity.CENTER_HORIZONTAL
                        } else originalGravity
                        is LinearLayout.LayoutParams -> gravity = if (available >= threshold) {
                            Gravity.CENTER_HORIZONTAL
                        } else originalGravity
                        is RelativeLayout.LayoutParams -> if (available >= threshold || originallyCentered) {
                            addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
                        } else {
                            removeRule(RelativeLayout.CENTER_HORIZONTAL)
                        }
                    }
                }
            }
        }

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWidthAfterLayout() }
        updateWidthAfterLayout()
    }

    override fun setContentView(layoutResID: Int) {
        setContentView(layoutInflater.inflate(layoutResID, null))
    }

    /**
     * Android 15 起边到边显示默认启用。背景保持铺到系统栏下方，只给工具栏、
     * 底栏和普通页面内容增加安全间距，避免把全屏图片也整体推回安全区。
     */
    private fun applyEdgeToEdge(root: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // findViewById 是 Java 平台类型。若不显式声明为可空类型，Kotlin 可能把 Elvis
        // 右侧推断为非空 View，并在某个页面不存在备用 ID 时插入运行时空检查。
        // 不同页面本来就可以没有顶栏或底栏，因此这里必须保留真正的可空语义。
        val topBar: View? = root.findViewById<View>(R.id.toolbar)
            ?: root.findViewById<View>(R.id.browserToolbar)
        val bottomBar: View? = root.findViewById<View>(R.id.browserBottomBar)
            ?: root.findViewById<View>(R.id.nav_bottom)
        val fullScreenBackground = root.findViewById<View>(R.id.image_bing) != null

        // 状态栏透明后，图标颜色需要跟随顶栏底色；主页透明顶栏默认使用亮色图标。
        topBar?.post {
            val color = when (val background = topBar.background) {
                is ColorDrawable -> background.color
                is MaterialShapeDrawable -> background.fillColor?.defaultColor ?: Color.TRANSPARENT
                else -> Color.TRANSPARENT
            }
            val lightBackground = Color.alpha(color) > 0 && ColorUtils.calculateLuminance(color) > 0.5
            ViewCompat.getWindowInsetsController(root)?.isAppearanceLightStatusBars = lightBackground
        }

        val rootLeft = root.paddingLeft
        val rootTop = root.paddingTop
        val rootRight = root.paddingRight
        val rootBottom = root.paddingBottom
        val topPadding = topBar?.paddingTop ?: 0
        val topHeight = topBar?.layoutParams?.height ?: 0
        val bottomPadding = bottomBar?.paddingBottom ?: 0
        val bottomHeight = bottomBar?.layoutParams?.height ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val left = maxOf(bars.left, cutout.left)
            val top = maxOf(bars.top, cutout.top)
            val right = maxOf(bars.right, cutout.right)
            val bottom = maxOf(bars.bottom, cutout.bottom)

            topBar?.let { bar ->
                bar.setPadding(bar.paddingLeft, topPadding + top, bar.paddingRight, bar.paddingBottom)
                if (topHeight > 0) bar.layoutParams = bar.layoutParams.apply { height = topHeight + top }
            }
            bottomBar?.let { bar ->
                bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bottomPadding + bottom)
                if (bottomHeight > 0) bar.layoutParams = bar.layoutParams.apply { height = bottomHeight + bottom }
            }
            if (!fullScreenBackground) {
                root.setPadding(rootLeft + left, rootTop + if (topBar == null) top else 0, rootRight + right,
                    if (bottomBar == null) rootBottom + bottom else rootBottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
