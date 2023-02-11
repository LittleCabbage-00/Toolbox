package com.example.cryptoapp.Activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.R
import com.example.cryptoapp.Utils.ShellUtils
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.card_about_app.*
import kotlinx.android.synthetic.main.card_about_device.*
import kotlinx.android.synthetic.main.card_author.*
import java.io.*


class AboutActivity : BaseActivity() {

    private val GITHUB = "https://github.com/LittleCabbage-00/CryptoApp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onStart() {
        super.onStart()
        setTitle("关于")

        version_info.setOnClickListener {
            Toast.makeText(this, "别点了，没写检查版本更新的逻辑", Toast.LENGTH_SHORT).show()
        }
        using_helper.setOnClickListener {
            val intent=Intent(this,UsingHelperActivity::class.java)
            startActivity(intent)
        }
        github_address.setOnClickListener {
            openUrl(GITHUB)
        }
        email_to_author.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO)
            intent.data = Uri.parse("mailto:jiangfy299792458@gmail.com")
            intent.putExtra(Intent.EXTRA_EMAIL, "jiangfy299792458@gmail.com")
            intent.putExtra(Intent.EXTRA_SUBJECT, "ToolKit(Crypto)")
            startActivity(Intent.createChooser(intent, "E-Mail"))
        }

        //给安卓版本赋值
        val android = String.format(
            resources.getString(R.string.android_version), Build.VERSION.RELEASE
        )
        android_version.text = android
        //给API版本赋值
        val android_api = String.format(
            resources.getString(R.string.android_sdk), Build.VERSION.SDK
        )
        android_sdk.text = android_api
        //给内核版本赋值
        kernel_version.text = KernelVersion().toString()
        //给cpu型号赋值
        if(CPUInfo().toString()==""){
            cpu_name.text="不支持"
        }else {
            cpu_name.text = CPUInfo().toString()
        }

        //webview useragent
        chromium_agent.text=WVAgent()
    }
    override fun onOptionsItemSelected(item: MenuItem):Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                this.finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.data = Uri.parse(url)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(i)
    }

    //获取Android Linux内核版本信息
    fun KernelVersion(): String? {
        val result:ShellUtils.CommandResult=ShellUtils.execCommand("uname -r",false)
        return result.successMsg
    }
    //获取cpu型号
    fun CPUInfo():String{
        val result:ShellUtils.CommandResult=ShellUtils.execCommand("cat /proc/cpuinfo | grep \"Hardware\" | cut -f2 -d: ",false)
        return result.successMsg
    }
    fun WVAgent():String?{
        val userAgent= WebView(this).getSettings().getUserAgentString()
        return userAgent
    }
}