package com.example.cryptoapp.Activities

import android.app.ProgressDialog.show
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import com.example.cryptoapp.Base.ActivityCollector
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.R
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.nav_header.*


class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.example.cryptoapp.R.layout.activity_main)

        setSupportActionBar(toolbar)
        supportActionBar?.let {
            it.setDisplayHomeAsUpEnabled(true)
            it.setHomeAsUpIndicator(com.example.cryptoapp.R.drawable.ic_menu)
        }
        navView.setNavigationItemSelectedListener {
            when(it.itemId) {
                com.example.cryptoapp.R.id.home->{
                }
                com.example.cryptoapp.R.id.info->{
                    AlertDialog.Builder(this).apply{
                        setTitle("关于")
                        setMessage("作者：LittleCabbage\n" +
                                "QQ号：2485535417\n"+
                                "本软件仅做为交流学习用，严禁用于其他用途")
                        setCancelable(true)
                        setPositiveButton("确定"){_,_ ->
                        }
                        show()
                    }
                }
                com.example.cryptoapp.R.id.navTextLayout -> {
                    val intent = Intent(this, TextActivity::class.java)
                    startActivity(intent)
                }
                com.example.cryptoapp.R.id.navFileLayout -> {
                    val intent = Intent(this, FileActivity::class.java)
                    startActivity(intent)
                }
                com.example.cryptoapp.R.id.navPicLayout -> {
                    val intent = Intent(this, PicActivity::class.java)

                    startActivity(intent)
                }
            }
            drawerLayout.closeDrawers()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        navView.setCheckedItem(com.example.cryptoapp.R.id.home)
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(com.example.cryptoapp.R.menu.toolbar, menu)
        //        val sysInfo = resources.getString(com.example.cryptoapp.R.string.sysInfo)
        val sysInfo= String.format(resources.getString(com.example.cryptoapp.R.string.sysInfo)
            , android.os.Build.VERSION.RELEASE,android.os.Build.VERSION.SDK)
        val sysInfo_dis:TextView=findViewById(R.id.sysInfo)
        sysInfo_dis.text = sysInfo
        return true
    }

}