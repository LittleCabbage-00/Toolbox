package com.example.cryptoapp.Base

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import com.example.cryptoapp.R
import com.king.zxing.util.LogUtils
import kotlinx.android.synthetic.main.activity_main.*

open class BaseActivity:AppCompatActivity(){
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("BaseActivity",javaClass.simpleName)
        ActivityCollector.addActivity(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ActivityCollector.removeActivity(this)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> drawerLayout.openDrawer(GravityCompat.START)
            R.id.exit->ActivityCollector.finishAll()
            R.id.toQQ->{
                try{
                    val package_name = "com.tencent.mobileqq"
                    val packageManager: PackageManager = this.getPackageManager()
                    val it: Intent? = packageManager.getLaunchIntentForPackage(package_name)
                    startActivity(it)
                }catch (e:java.lang.Exception){
                    AlertDialog.Builder(this).apply {
                        setTitle("出错了 >_<")
                        setMessage("检测到您没有安装QQ\n是否前去安装？")
                        setCancelable(false)
                        setPositiveButton("确认"){_,_->
                            val intent_downloadQQ=Intent(Intent.ACTION_VIEW)
                            intent_downloadQQ.data= Uri.parse("https://im.qq.com/mobileqq")
                            startActivity(intent_downloadQQ)
                        }
                        setNegativeButton("取消"){_,_->
                        }
                        show()
                    }
                }
            }
        }
        return true
    }
}