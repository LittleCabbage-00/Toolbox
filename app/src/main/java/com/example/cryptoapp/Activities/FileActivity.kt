package com.example.cryptoapp.Activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.view.GravityCompat
import com.example.cryptoapp.*
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Utils.FileUtil
import com.example.cryptoapp.Utils.StringCrypto
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_file.*
import kotlinx.android.synthetic.main.activity_file.decryptButton
import kotlinx.android.synthetic.main.activity_file.encryptButton
import kotlinx.android.synthetic.main.activity_file.passwordEditText
import kotlinx.android.synthetic.main.activity_main.drawerLayout
import kotlinx.android.synthetic.main.activity_main.navView
import kotlinx.android.synthetic.main.activity_main.toolbar
import java.io.*
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

class FileActivity : BaseActivity(), View.OnClickListener {
    private val REQUEST_CODE_FOR_LOAD_FILE = 1
    private val REQUEST_CODE_FOR_CREATE_FILE = 2
    private var fromFileUri: Uri? = null
    private var toFileUri: Uri? = null
    private val startProgress = 1
    private val finishProgress = 2
    private val cryptoFinishToastMessage = 3
    private val cryptoErrorToastMessage = 4
    private val handler = MyHandler(this)

    //防止Handler造成的内存泄露，使用内部类
    private class MyHandler(activity: FileActivity) : Handler() {
        private val mActivity: WeakReference<FileActivity> = WeakReference(activity)

        override fun handleMessage(msg: Message) {
            if (mActivity.get() == null) {
                return
            }
            val activity = mActivity.get()
            if (activity != null) {
                when (msg.what) {
                    activity.startProgress -> activity.progressBar.visibility = View.VISIBLE
                    activity.finishProgress -> activity.progressBar.visibility = View.GONE
                    activity.cryptoFinishToastMessage -> Snackbar.make(activity.fileView, "任务已完成", Snackbar.LENGTH_SHORT).show()
                    activity.cryptoErrorToastMessage -> Snackbar.make(activity.fileView, "任务发生错误", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.readFileButton -> {
                setFromFileUri()
            }
            R.id.saveFileButton -> {
                setToFileUri()
            }
            R.id.encryptButton -> {
                fileCryptoTask("ENCRYPT")
            }
            R.id.decryptButton -> {
                fileCryptoTask("DECRYPT")
            }
        }
    }

    private fun fileCryptoTask(option: String) {
        if (toFileUri != null && fromFileUri != null) {
            thread {
                val startMsg = Message()
                startMsg.what = startProgress
                handler.sendMessage(startMsg)
                val flag = fileHandle(fromFileUri!!, option)
                println(flag)
                val finishMsg = Message()
                finishMsg.what = finishProgress
                handler.sendMessage(finishMsg)
                if (flag) {
                    val cryptoFinishMsg = Message()
                    cryptoFinishMsg.what = cryptoFinishToastMessage
                    handler.sendMessage(cryptoFinishMsg)
                } else {
                    val cryptoErrorMsg = Message()
                    cryptoErrorMsg.what = cryptoErrorToastMessage
                    handler.sendMessage(cryptoErrorMsg)
                }
            }
        } else {
            Snackbar.make(fileView, "输入或输出路径错误", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setFromFileUri() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_FOR_LOAD_FILE)

    }

    private fun setToFileUri() {
        val password = passwordEditText.text.toString()
        val readName = readPathEditText.text.toString()
        // 通过输入文件名是否包含特殊后缀来判断输出文件名默认值是加密还是解密后的字符串
        val outputName = if (readName.contains(".cf", ignoreCase = true)) {
            fileNameHandle(readName.substring(0, readName.lastIndexOf('.')), password, "DECRYPT")
        } else {
            fileNameHandle(readName, password, "ENCRYPT") + ".cf"
        }

        val intent =Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_TITLE, outputName)
        startActivityForResult(intent, REQUEST_CODE_FOR_CREATE_FILE)
    }


    override fun onActivityResult(requestCode: Int,resultCode: Int, data:Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_FOR_LOAD_FILE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    data.data?.let { uri ->
                        readPathEditText.setText(FileUtil.uriToFileName(uri, this))
                        fromFileUri = uri
                    }
                }
            }
            REQUEST_CODE_FOR_CREATE_FILE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    data.data?.let { uri ->
                        savePathEditText.setText(FileUtil.uriToFileName(uri, this))
                        toFileUri = uri
                    }
                }
            }
        }
    }

    fun fileNameHandle(inputName: String, password:String, option: String): String {
        var outputName = ""
        if (option == "ENCRYPT") {
            outputName = try {
                StringCrypto(password).encrypt(inputName)
            } catch (e: Exception) {
                Log.d("Encrypt Error", "Encrypt input name exception!")
                "EncryptNameError"
            }
        } else if (option == "DECRYPT"){
            outputName = try {
                StringCrypto(password).decrypt(inputName)
            } catch (e: Exception) {
                Log.d("Decrypt Error", "Decrypt input name exception!")
                "DecryptNameError"
            }
        } else {
            outputName = ""
        }
        return outputName
    }

    private fun fileHandle(uri: Uri, option: String): Boolean {
        val inputFileResolver = contentResolver.openFileDescriptor(uri, "r")
        val outputFileResolver = contentResolver.openFileDescriptor(toFileUri!!, "w")
        val password = passwordEditText.text.toString()
        var successFlag = false
        if (outputFileResolver != null) {
            val outputFileStream = FileOutputStream(outputFileResolver.fileDescriptor)
            inputFileResolver?.fileDescriptor?.let {
                val fileInputStream = FileInputStream(it)
                if (option == "ENCRYPT") {
                    successFlag = FileCrypto(password).encrypt(fileInputStream, outputFileStream)
                } else if (option == "DECRYPT"){
                    successFlag = FileCrypto(password).decrypt(fileInputStream, outputFileStream)
                }
                fileInputStream.close()
            }
            outputFileStream.close()
        }
        inputFileResolver?.close()
        outputFileResolver?.close()
        return successFlag
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar, menu)
        return true
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
