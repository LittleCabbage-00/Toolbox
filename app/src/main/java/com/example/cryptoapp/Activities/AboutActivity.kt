package com.example.cryptoapp.Activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebSettings
import android.widget.TextView
import android.widget.Toast
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.BuildConfig
import com.example.cryptoapp.R
import com.example.cryptoapp.databinding.ActivityAboutBinding

/** 关于与设备信息页。不再通过阻塞 Shell 获取基础系统信息。 */
class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "关于"

        binding.root.findViewById<android.view.View>(R.id.version_info).setOnClickListener {
            Toast.makeText(this, "当前版本：${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
        }
        binding.root.findViewById<android.view.View>(R.id.using_helper).setOnClickListener {
            startActivity(Intent(this, UsingHelperActivity::class.java))
        }
        binding.root.findViewById<android.view.View>(R.id.github_address).setOnClickListener {
            // 项目已经提供完整的内置浏览器，这类普通 HTTPS 页面不应无故打断用户并跳出应用。
            startActivity(Intent(this, SearchActivity::class.java).putExtra("web_address", GITHUB))
        }
        binding.root.findViewById<android.view.View>(R.id.email_to_author).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL")), "发送邮件"))
        }

        text(binding, R.id.android_version, getString(R.string.android_version, Build.VERSION.RELEASE))
        text(binding, R.id.android_sdk, getString(R.string.android_sdk, Build.VERSION.SDK_INT))
        text(binding, R.id.kernel_version, System.getProperty("os.version") ?: "未知")
        text(binding, R.id.cpu_name, Build.SUPPORTED_ABIS.joinToString())
        text(binding, R.id.chromium_agent, WebSettings.getDefaultUserAgent(this))
    }

    private fun text(binding: ActivityAboutBinding, id: Int, value: String) {
        binding.root.findViewById<TextView>(id).text = value
    }

    companion object {
        private const val GITHUB = "https://github.com/LittleCabbage-00/CryptoApp"
        private const val EMAIL = "jiangfy299792458@gmail.com"
    }
}
