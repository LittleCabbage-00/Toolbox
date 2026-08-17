package com.example.cryptoapp.Activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Bing.BingWallpaperDay
import com.example.cryptoapp.Bing.BingWallpaperRepository
import com.example.cryptoapp.Bing.Orientation
import com.example.cryptoapp.Bing.SaveResult
import com.example.cryptoapp.Browser.BrowserHistoryStore
import com.example.cryptoapp.Browser.BrowserPreferences
import com.example.cryptoapp.Browser.AdBlockRuleStore
import com.example.cryptoapp.databinding.SettingsActivityBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.util.Date

/** 软件设置页。每个选项都直接对应浏览器或主页中的实际行为。 */
class SettingsActivity : BaseActivity() {
    private lateinit var binding: SettingsActivityBinding
    private val preferences by lazy {
        getSharedPreferences(BrowserPreferences.CONFIG, Context.MODE_PRIVATE)
    }
    private val adBlockRules by lazy { AdBlockRuleStore.get(this) }

    /** API ≤ 28 保存到公共目录前需要 WRITE_EXTERNAL_STORAGE 运行时权限；API ≥ 29 MediaStore 无需权限。 */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) saveTodayWallpaper() else message("未授予存储权限，无法保存壁纸") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bindCurrentValues()
        binding.wallpaperSwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.BING_WALLPAPER, value) }
        binding.saveTodayWallpaperButton.setOnClickListener { saveTodayWallpaper() }
        binding.openWallpaperGalleryButton.setOnClickListener {
            startActivity(Intent(this, BingWallpaperGalleryActivity::class.java))
        }
        binding.themeModeRow.setOnClickListener { showThemePicker() }
        binding.saveHistorySwitch.setOnCheckedChangeListener { _, value ->
            save(BrowserPreferences.SAVE_HISTORY, value)
        }
        binding.showSearchHistorySwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.SHOW_SEARCH_HISTORY, value) }
        binding.showBookmarkBarSwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.SHOW_BOOKMARK_BAR, value) }
        binding.javascriptSwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.JAVASCRIPT_ENABLED, value) }
        binding.blockThirdPartyCookiesSwitch.setOnCheckedChangeListener { _, value -> save(BrowserPreferences.BLOCK_THIRD_PARTY_COOKIES, value) }
        binding.adBlockSwitch.setOnCheckedChangeListener { _, value ->
            adBlockRules.setEnabled(value)
            bindAdBlockStatus()
        }
        binding.updateAdBlockRulesButton.setOnClickListener { updateAdBlockRules() }
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
        showBookmarkBarSwitch.isChecked = preferences.getBoolean(BrowserPreferences.SHOW_BOOKMARK_BAR, false)
        javascriptSwitch.isChecked = preferences.getBoolean(BrowserPreferences.JAVASCRIPT_ENABLED, true)
        blockThirdPartyCookiesSwitch.isChecked = preferences.getBoolean(BrowserPreferences.BLOCK_THIRD_PARTY_COOKIES, true)
        adBlockSwitch.isChecked = adBlockRules.isEnabled()
        bindAdBlockStatus()
        videoFormatValue.text = preferences.getString(BrowserPreferences.SNIFF_VIDEO_FORMAT, "mp4")?.uppercase()
        audioFormatValue.text = preferences.getString(BrowserPreferences.SNIFF_AUDIO_FORMAT, "mp3")?.uppercase()
        searchEngineValue.text = preferences.getString(BrowserPreferences.METHOD_NAME, "必应")
        homepageValue.text = preferences.getString(BrowserPreferences.HOME_URL, "https://cn.bing.com")
        clearHistoryButton.isEnabled = BrowserHistoryStore(this@SettingsActivity).getAll().isNotEmpty()
    }

    private fun bindAdBlockStatus() {
        val status = adBlockRules.status()
        val time = if (status.updatedAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(status.updatedAt)) else "随应用内置"
        binding.adBlockStatusValue.text = "${status.source} · ${status.count} 条网络规则 · $time"
    }

    private fun updateAdBlockRules() {
        binding.updateAdBlockRulesButton.isEnabled = false
        binding.updateAdBlockRulesButton.text = "正在更新规则…"
        Thread {
            val result = adBlockRules.update()
            runOnUiThread {
                binding.updateAdBlockRulesButton.isEnabled = true
                binding.updateAdBlockRulesButton.text = "更新广告拦截规则"
                bindAdBlockStatus()
                message(result.message)
            }
        }.start()
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

    /** 手动保存今天的壁纸（横屏 UHD；开启竖屏时同时保存竖屏）。已存在时弹窗询问替换。 */
    private fun saveTodayWallpaper() {
        // API ≤ 28 写公共图片目录需要运行时权限；API ≥ 29 走 MediaStore 无需权限。
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
            && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val repo = BingWallpaperRepository.get(this)
        val includePortrait = false
        repo.ensureTodayUhd({ day ->
            saveDayWithDedup(repo, day, Orientation.LANDSCAPE)
            if (includePortrait) saveDayWithDedup(repo, day, Orientation.PORTRAIT)
        }, { message("保存失败，请检查网络") })
    }

    private fun saveDayWithDedup(repo: BingWallpaperRepository, day: BingWallpaperDay, orientation: Orientation) {
        repo.saveDayWithDedup(day, orientation,
            onDuplicate = { showReplaceDialog(repo, day, orientation) },
            onResult = { result -> message(result.toLabel()) })
    }

    private fun showReplaceDialog(repo: BingWallpaperRepository, day: BingWallpaperDay, orientation: Orientation) {
        MaterialAlertDialogBuilder(this)
            .setTitle("图片已保存过")
            .setMessage("该壁纸（${day.startDate}）已经保存到 Pictures/Toolbox，是否替换为新版本？")
            .setNegativeButton("取消", null)
            .setPositiveButton("替换") { _, _ ->
                repo.saveDayForce(day, orientation) { result -> message(result.toLabel()) }
            }
            .show()
    }

    private fun SaveResult.toLabel(): String = when (this) {
        SaveResult.SAVED -> "已保存到 Pictures/Toolbox"
        SaveResult.ALREADY_SAVED -> "已保存过"
        SaveResult.FAILED -> "保存失败"
    }

    private data class SearchEngine(val name: String, val homeUrl: String, val searchUrl: String)
}
