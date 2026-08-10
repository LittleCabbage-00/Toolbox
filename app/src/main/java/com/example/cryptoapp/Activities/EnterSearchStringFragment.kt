package com.example.cryptoapp.Activities

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Browser.BrowserHistoryEntry
import com.example.cryptoapp.Browser.BrowserHistoryStore
import com.example.cryptoapp.Browser.BrowserPreferences
import com.example.cryptoapp.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 带共享元素感动画和可删除历史建议的搜索入口。 */
class EnterSearchStringFragment : BaseActivity() {
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val historyAdapter = SearchHistoryAdapter()
    private lateinit var searchCard: MaterialCardView
    private lateinit var historyCard: MaterialCardView
    private lateinit var searchInput: EditText
    private lateinit var scrim: View
    private lateinit var historyStore: BrowserHistoryStore
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enter_search_string)
        searchCard = findViewById(R.id.input_layout)
        historyCard = findViewById(R.id.searchHistoryCard)
        searchInput = findViewById(R.id.search_text_tv)
        scrim = findViewById(R.id.scrim)
        // 首帧遮罩透明：共享元素过渡期间由 playEnterAnimation 淡入，避免先闪一下不透明背景。
        scrim.alpha = 0f
        applyImmersiveStatusBar()
        extendScrimToStatusBar()
        historyStore = BrowserHistoryStore(this)
        val historyList = findViewById<RecyclerView>(R.id.searchHistoryList)
        // RecyclerView 不会自行排列子项；缺少 LayoutManager 时即使仓库中有记录也只会跳过绘制。
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = historyAdapter
        findViewById<View>(R.id.cancelSearch).setOnClickListener { closeWithAnimation() }
        findViewById<View>(R.id.clearSearchHistory).setOnClickListener { confirmClearHistory() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeWithAnimation()
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, event -> onEditorAction(actionId, event) }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshHistory()
            }
            override fun afterTextChanged(s: Editable?) { }
        })
        refreshHistory()
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        // 窗口已 attach 后坐标才可靠；post 确保第一帧布局完成后执行，避免透明窗口下坐标错乱导致闪现。
        searchCard.post { playEnterAnimation() }
    }

    private fun refreshHistory() {
        val settings = getSharedPreferences(BrowserPreferences.CONFIG, MODE_PRIVATE)
        val visible = settings.getBoolean(BrowserPreferences.SHOW_SEARCH_HISTORY, true)
        var entries: List<BrowserHistoryEntry> = if (visible)
            historyStore.search(searchInput.text.toString())
            else ArrayList()
        if (entries.size > 6) entries = entries.subList(0, 6)
        historyAdapter.setEntries(entries)
        historyCard.visibility = if (visible && entries.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this).setTitle("删除全部历史记录？")
            .setMessage("此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("全部删除") { _, _ ->
                historyStore.clear()
                refreshHistory()
            }
            .show()
    }

    private fun openHistory(entry: BrowserHistoryEntry) {
        startActivity(Intent(this, SearchActivity::class.java).putExtra("web_address", entry.url))
        finish()
    }

    /** 透明窗口：状态栏透明由 scrim 覆盖归搜索页，图标颜色随主题（浅色→深图标，深色→浅图标）。 */
    private fun applyImmersiveStatusBar() {
        window.statusBarColor = Color.TRANSPARENT
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val dark = nightMode == Configuration.UI_MODE_NIGHT_YES
        ViewCompat.getWindowInsetsController(window.decorView)
            ?.setAppearanceLightStatusBars(!dark)
    }

    /** 让遮罩延伸到状态栏后：透明窗口下 BaseActivity 给 root 加顶部 padding 压缩 scrim，用负 topMargin 补齐，状态栏被 scrim 覆盖。 */
    private fun extendScrimToStatusBar() {
        scrim.post {
            if (isFinishing || isDestroyed) return@post
            val statusBarHeight = getStatusBarHeight()
            val params = scrim.layoutParams as android.widget.FrameLayout.LayoutParams
            params.height = scrim.height + statusBarHeight
            params.topMargin = -statusBarHeight
            scrim.layoutParams = params
        }
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            val px = resources.getDimensionPixelSize(resourceId)
            if (px > 0) return px
        }
        // 回退：读取 insets。
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        return if (insets == null) (24 * resources.displayMetrics.density).toInt() else insets.top
    }

    private fun playEnterAnimation() {
        // 输入框从屏幕下方固定距离滑入，背景遮罩淡入。不依赖任何窗口坐标，避免透明窗口下坐标错乱。
        val slideIn = resources.displayMetrics.heightPixels / 4f
        searchCard.translationY = slideIn
        scrim.alpha = 0f
        historyCard.alpha = 0f
        searchCard.animate().translationY(0f).setDuration(DURATION).setInterpolator(emphasized).start()
        scrim.animate().alpha(1f).setDuration(DURATION).setInterpolator(emphasized).start()
        historyCard.animate().alpha(1f).setStartDelay(100L).setDuration(220L).start()
        searchInput.requestFocus()
        searchInput.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }, 180L)
    }

    private fun onEditorAction(actionId: Int, event: KeyEvent?): Boolean {
        val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            && event.action == KeyEvent.ACTION_DOWN
        if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || enter) {
            val query = searchInput.text.toString().trim()
            if (query.isNotEmpty()) {
                startActivity(Intent(this, SearchActivity::class.java).putExtra("web_address", query))
                finish()
            }
            return true
        }
        return false
    }

    private fun closeWithAnimation() {
        if (finishing) return
        finishing = true
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
        val slideOut = resources.displayMetrics.heightPixels / 4f
        searchCard.animate().translationY(slideOut).setDuration(DURATION)
            .setInterpolator(emphasized).withEndAction { finish() }.start()
        scrim.animate().alpha(0f).setDuration(DURATION).setInterpolator(emphasized).start()
        historyCard.animate().alpha(0f).setDuration(140L).start()
    }

    private inner class SearchHistoryAdapter : RecyclerView.Adapter<SearchHistoryAdapter.Holder>() {
        private val entries = ArrayList<BrowserHistoryEntry>()

        fun setEntries(values: List<BrowserHistoryEntry>) {
            entries.clear()
            entries.addAll(values)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_history, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.historyTitle)
            private val url: TextView = itemView.findViewById(R.id.historyUrl)

            fun bind(entry: BrowserHistoryEntry) {
                title.text = entry.title
                url.text = entry.url
                itemView.setOnClickListener { openHistory(entry) }
                itemView.findViewById<View>(R.id.deleteHistory).setOnClickListener {
                    historyStore.delete(entry.id)
                    refreshHistory()
                }
            }
        }
    }

    companion object {
        private const val DURATION = 280L
    }
}
