package com.example.cryptoapp.Activities

import android.os.Bundle
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.databinding.ActivityUsingHelperBinding

/** 应用内使用说明页面，正文统一来自资源文件，便于后续维护和本地化。 */
class UsingHelperActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityUsingHelperBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "使用帮助"
    }
}
