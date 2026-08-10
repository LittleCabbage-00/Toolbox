package com.example.cryptoapp.Activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Browser.BrowserHistoryStore
import com.example.cryptoapp.Browser.BrowserPreferences
import com.example.cryptoapp.databinding.SettingsActivityBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** 软件设置页。每个选项都直接对应浏览器或主页中的实际行为。 */
class SettingsActivity : BaseActivity() {
    private lateinit var binding: SettingsActivityBinding
    private val preferences by lazy {
        getSharedPreferences(BrowserPreferences.CONFIG, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindCurrentValues()
        binding.wallpaperSwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.BING_WALLPAPER, value) }
        binding.themeModeRow.setOnClickListener { showThemePicker() }
        binding.saveHistorySwitch.setOnCheckedChangeListener { _, value ->
            save(BrowserPreferences.SAVE_HISTORY, value)
        }
        binding.showSearchHistorySwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.SHOW_SEARCH_HISTORY, value) }
        binding.javascriptSwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.JAVASCRIPT_ENABLED, value) }
        binding.blockThirdPartyCookiesSwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.BLOCK_THIRD_PARTY_COOKIES, value) }
        binding.desktopModeSwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.DESKTOP_MODE_DEFAULT, value) }
        binding.videoFormatRow.setOnClickListener { showMediaFormatPicker(video = true) }
        binding.audioFormatRow.setOnClickListener { showMediaFormatPicker(video = false) }
        binding.searchEngineRow.setOnClickListener { showSearchEnginePicker() }
        binding.homepageRow.setOnClickListener { showHomepageEditor() }
        binding.clearHistoryButton.setOnClickListener { confirmClearHistory() }
        binding.openHistoryButton.setOnClickListener {
            startActivity(Intent(this, BrowserHistoryActivity::class.java))
        }
        binding.clearBrowserDataButton.setOnClickListener { confirmClearBrowserData() }
    }

    private fun bindCurrentValues() = with(binding) {
        wallpaperSwitch.isChecked = preferences.getBoolean(BrowserPreferences.BING_WALLPAPER, true)
        themeModeValue.text = when (preferences.getString(BrowserPreferences.THEME_MODE, "system")) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "跟随系统"
        }
        saveHistorySwitch.isChecked = preferences.getBoolean(BrowserPreferences.SAVE_HISTORY, true)
        showSearchHistorySwitch.isChecked = preferences.getBoolean(BrowserPreferences.SHOW_SEARCH_HISTORY, true)
        javascriptSwitch.isChecked = preferences.getBoolean(BrowserPreferences.JAVASCRIPT_ENABLED, true)
        blockThirdPartyCookiesSwitch.isChecked = preferences.getBoolean(BrowserPreferences.BLOCK_THIRD_PARTY_COOKIES, true)
        desktopModeSwitch.isChecked = preferences.getBoolean(BrowserPreferences.DESKTOP_MODE_DEFAULT, false)
        videoFormatValue.text = preferences.getString(BrowserPreferences.SNIFF_VIDEO_FORMAT, "mp4")?.uppercase()
        audioFormatValue.text = preferences.getString(BrowserPreferences.SNIFF_AUDIO_FORMAT, "mp3")?.uppercase()
        searchEngineValue.text = preferences.getString(BrowserPreferences.METHOD_NAME, "必应")
        homepageValue.text = preferences.getString(BrowserPreferences.HOME_URL, "https://cn.bing.com")
        clearHistoryButton.isEnabled = BrowserHistoryStore(this@SettingsActivity).getAll().isNotEmpty()
    }

    private fun showSearchEnginePicker() {
        val engines = listOf(
            SearchEngine("必应", "https://cn.bing.com", "https://cn.bing.com/search?q="),
            SearchEngine("搜狗", "https://www.sogou.com", "https://www.sogou.com/web?query="),
            SearchEngine("百度", "https://www.baidu.com", "https://www.baidu.com/s?wd="),
            SearchEngine("谷歌", "https://www.google.com", "https://www.google.com/search?q="),
            SearchEngine("Yandex", "https://yandex.com", "https://yandex.com/search/?text=")
        )
        MaterialAlertDialogBuilder(this).setTitle("默认搜索引擎")
            .setSingleChoiceItems(engines.map { it.name }.toTypedArray(), preferences.getInt(BrowserPreferences.METHOD_INDEX, 0)) { dialog, index ->
                val selected = engines[index]
                preferences.edit()
                    .putString(BrowserPreferences.HOME_URL, selected.homeUrl)
                    .putString(BrowserPreferences.SEARCH_METHOD, selected.searchUrl)
                    .putString(BrowserPreferences.METHOD_NAME, selected.name)
                    .putInt(BrowserPreferences.METHOD_INDEX, index).apply()
                binding.searchEngineValue.text = selected.name
                binding.homepageValue.text = selected.homeUrl
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun showThemePicker() {
        val values = arrayOf("system", "light", "dark")
        val labels = arrayOf("跟随系统", "浅色", "深色")
        val current = values.indexOf(preferences.getString(BrowserPreferences.THEME_MODE, "system")).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("外观主题")
            .setSingleChoiceItems(labels, current) { dialog, index ->
                preferences.edit().putString(BrowserPreferences.THEME_MODE, values[index]).apply()
                binding.themeModeValue.text = labels[index]
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMediaFormatPicker(video: Boolean) {
        val values = if (video) arrayOf("mp4", "mkv") else arrayOf("mp3", "m4a", "flac")
        val labels = values.map(String::uppercase).toTypedArray()
        val key = if (video) BrowserPreferences.SNIFF_VIDEO_FORMAT else BrowserPreferences.SNIFF_AUDIO_FORMAT
        val current = values.indexOf(preferences.getString(key, values[0])).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (video) "嗅探视频自动转换格式" else "嗅探音频自动转换格式")
            .setSingleChoiceItems(labels, current) { dialog, index ->
                preferences.edit().putString(key, values[index]).apply()
                if (video) binding.videoFormatValue.text = labels[index] else binding.audioFormatValue.text = labels[index]
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun showHomepageEditor() {
        val editor = EditText(this).apply {
            setText(preferences.getString(BrowserPreferences.HOME_URL, "https://cn.bing.com"))
            setSelectAllOnFocus(true)
        }
        MaterialAlertDialogBuilder(this).setTitle("自定义浏览器主页").setView(editor)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val value = editor.text.toString().trim()
                val url = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
                preferences.edit().putString(BrowserPreferences.HOME_URL, url).apply()
                binding.homepageValue.text = url
            }.show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this).setTitle("清空全部历史记录？")
            .setMessage("删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                BrowserHistoryStore(this).clear()
                binding.clearHistoryButton.isEnabled = false
                message("历史记录已清空")
            }.show()
    }

    private fun confirmClearBrowserData() {
        MaterialAlertDialogBuilder(this).setTitle("清理浏览数据？")
            .setMessage("将删除 Cookie、HTTP 缓存和网页本地存储，但不会删除历史记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                WebView(this).apply { clearCache(true); destroy() }
                message("浏览数据已清理")
            }.show()
    }

    private fun save(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
    private fun message(value: String) = Snackbar.make(binding.root, value, Snackbar.LENGTH_SHORT).show()
    private data class SearchEngine(val name: String, val homeUrl: String, val searchUrl: String)
}
