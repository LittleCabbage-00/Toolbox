package com.example.cryptoapp.Activities

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.cryptoapp.R
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_main.toolbar
import kotlinx.android.synthetic.main.setting_browser.*
import kotlinx.android.synthetic.main.setting_browser.view.*
import kotlinx.android.synthetic.main.settings_activity.*

class SettingsActivity :  AppCompatActivity(){

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        //建立读取配置文件的对象
        val config_get=getSharedPreferences("config",Context.MODE_PRIVATE)
        //设置当前搜索引擎名称
        browser_method_now.setText(config_get.getString("method_name",""))

        //bing每日一图开关
        bing_pic_check.isChecked=config_get.getBoolean("bing_pic_check",true)
        bing_pic_check.setOnCheckedChangeListener { buttonView, bing_pic_checked ->
            if (bing_pic_checked){
                val config=getSharedPreferences("config",Context.MODE_PRIVATE).edit()
                config.apply{
                    putBoolean("bing_pic_check",true)
                    apply()
                }
            }else{
                val config=getSharedPreferences("config",Context.MODE_PRIVATE).edit()
                config.apply {
                    putBoolean("bing_pic_check", false)
                    apply()
                }
            }
        }

        change_browser_method.setOnClickListener {
            selectBrowserMethodSingle()
        }
    }

    override fun onStart() {
        super.onStart()
        setTitle("设置")
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

    fun selectBrowserMethodSingle() {

        val setting_config=getSharedPreferences("config",Context.MODE_PRIVATE).edit()
        val config_get=getSharedPreferences("config",Context.MODE_PRIVATE)

        val browser_avl = arrayOf("必应", "搜狗", "百度")

        AlertDialog.Builder(this).apply {
            setTitle("搜索引擎设置")
            setSingleChoiceItems(
                browser_avl, config_get.getInt("method_num",0)
            ) { dialogInterface, i ->
                when (i) {
                    0 -> {
                        setting_config.apply {
                            putString("home_url", "https://cn.bing.com")
                            putString("search_method", "https://cn.bing.com/search?q=")
                            putString("method_name", "必应")
                            putInt("method_num",0)
                        }
                    }
                    1 -> {
                        setting_config.apply {
                            putString("home_url", "https://www.sogou.com")
                            putString("search_method", "https://www.sogou.com/web?query=")
                            putString("method_name", "搜狗")
                            putInt("method_num",1)
                        }
                    }
                    2 -> {
                        setting_config.apply {
                            putString("home_url", "https://www.baidu.com")
                            putString("search_method", "https://www.baidu.com/s?wd=")
                            putString("method_name", "百度")
                            putInt("method_num",2)
                        }
                    }
                }
            }
            setPositiveButton("确定") { dialogInterface, i ->
                setting_config.apply()
                browser_method_now.setText(config_get.getString("method_name",""))
                Snackbar.make(snackBar_setting_text,"你选择的是 ${config_get.getString("method_name","")}",Snackbar.LENGTH_SHORT).show()
            }
            setNegativeButton("取消") { dialogInterface, i ->
                Snackbar.make(snackBar_setting_text,"你选择的是 ${config_get.getString("method_name","")}",Snackbar.LENGTH_SHORT).show()
            }
            show()
        }
    }

}