package com.example.cryptoapp.Activities

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import com.example.cryptoapp.R
import com.example.cryptoapp.Utils.CheckRoot
import com.example.cryptoapp.Utils.ShellUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_fake_termianl.*
import kotlinx.android.synthetic.main.activity_file.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main.toolbar

class FakeTerminalActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_termianl)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onStart() {
        super.onStart()
        run_fake_terminal.setOnClickListener {
            if (CheckRoot.isDeviceRooted()){
                if (command_get.getText() !=null) {
                    val command_get=command_get.text.toString()
                    val command_got: ShellUtils.CommandResult =
                        ShellUtils.execCommand(command_get, false)
                    if (command_got.errorMsg!=""){
                        Snackbar.make(command_result,"出错啦",Snackbar.LENGTH_SHORT).show()
                        command_result.setText(command_got.errorMsg.toString())
                    }
                    command_result.setText(command_got.successMsg)
                }
            }else{
                if (command_get.getText() !=null) {
                    val command_get=command_get.text.toString()
                    if (command_get.indexOf("sudo")!=-1){
                        Snackbar.make(command_result,"没有root 不能用sudo",Snackbar.LENGTH_SHORT).show()
                    }else {
                        val command_got: ShellUtils.CommandResult =
                            ShellUtils.execCommand(command_get, false)
                        if (command_got.errorMsg != "") {
                            Snackbar.make(command_result, "出错啦", Snackbar.LENGTH_SHORT).show()
                            command_result.setText(command_got.errorMsg.toString())
                        }
                        command_result.setText(command_got.successMsg)
                    }
                }
            }
        }
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
}