package com.example.cryptoapp.Base

import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.cryptoapp.Activities.FileActivity
import com.example.cryptoapp.Activities.MainActivity
import com.example.cryptoapp.Activities.PicActivity
import com.example.cryptoapp.Activities.TextActivity
import com.example.cryptoapp.R

open class BaseActivity:AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("BaseActivity",javaClass.simpleName)
        ActivityCollector.addActivity(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ActivityCollector.removeActivity(this)
    }
}