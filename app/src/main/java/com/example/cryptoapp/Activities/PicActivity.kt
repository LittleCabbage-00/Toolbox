package com.example.cryptoapp.Activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.FileCrypto
import com.example.cryptoapp.Utils.CryptoEngine
import com.example.cryptoapp.Utils.FileUtil
import com.example.cryptoapp.databinding.ActivityPicBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.concurrent.Executors

/** 多算法加密图片解密预览；文件解密和 Bitmap 解析均在后台执行。 */
class PicActivity : BaseActivity() {
    private lateinit var binding: ActivityPicBinding
    private var sourceUri: Uri? = null
    private var algorithm = FileCrypto.Algorithm.AES_GCM
    private val worker = Executors.newSingleThreadExecutor()
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        sourceUri = uri
        binding.readPicEditText.text = FileUtil.uriToFileName(uri, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "加密图片解密"
        sourceUri = savedInstanceState?.getString("source_uri")?.let(Uri::parse)
        algorithm = savedInstanceState?.getString("algorithm")?.let { name -> FileCrypto.Algorithm.values().firstOrNull { it.name == name } }
            ?: FileCrypto.Algorithm.AES_GCM
        sourceUri?.let { binding.readPicEditText.text = FileUtil.uriToFileName(it, this) }
        configureAlgorithms()
        binding.readPicButton.setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        binding.generateKeyButton.setOnClickListener { generateKeyPair() }
        binding.decryptPicButton.setOnClickListener { decryptPreview() }
    }

    private fun configureAlgorithms() {
        val values = FileCrypto.Algorithm.values()
        binding.algorithmInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, values.map { it.title }))
        binding.algorithmInput.setText(algorithm.title, false)
        applyAlgorithmUi()
        binding.algorithmInput.setOnItemClickListener { _, _, position, _ -> algorithm = values[position]; applyAlgorithmUi() }
    }

    private fun applyAlgorithmUi() {
        binding.secretLayout.hint = if (algorithm.asymmetric) "解密私钥" else "密码"
        binding.secretLayout.endIconMode = if (algorithm.asymmetric) TextInputLayout.END_ICON_NONE else TextInputLayout.END_ICON_PASSWORD_TOGGLE
        binding.generateKeyButton.visibility = if (algorithm.asymmetric) View.VISIBLE else View.GONE
    }

    private fun generateKeyPair() {
        val type = if (algorithm == FileCrypto.Algorithm.RSA_OAEP) CryptoEngine.Algorithm.RSA_OAEP else CryptoEngine.Algorithm.SM2
        runCatching { CryptoEngine.generateKeyPair(type) }.onSuccess { keys ->
            val all = "公钥（加密）\n${keys.publicKey}\n\n私钥（解密）\n${keys.privateKey}"
            MaterialAlertDialogBuilder(this).setTitle("${algorithm.title} 密钥对").setMessage(all)
                .setNeutralButton("复制全部") { _, _ -> copy(all) }
                .setNegativeButton("使用私钥") { _, _ -> binding.passwordEditText.setText(keys.privateKey) }
                .setPositiveButton("关闭", null).show()
        }.onFailure { message(it.message ?: "生成密钥失败") }
    }

    private fun decryptPreview() {
        val uri = sourceUri ?: return message("请先选择加密图片")
        val secret = binding.passwordEditText.text?.toString().orEmpty()
        if (secret.isBlank()) return message(if (algorithm.asymmetric) "请粘贴解密私钥" else "密码不能为空")
        binding.decryptPicButton.isEnabled = false
        worker.execute {
            val bitmap = runCatching {
                val encrypted = File.createTempFile("toolbox-picture-", ".tbx", cacheDir)
                val decrypted = File.createTempFile("toolbox-picture-dec-", ".img", cacheDir)
                try {
                    contentResolver.openInputStream(uri)!!.use { input -> encrypted.outputStream().use(input::copyTo) }
                    require(encrypted.length() <= MAX_IMAGE_BYTES) { "文件超过 32 MB" }
                    require(FileCrypto(secret, algorithm).decrypt(encrypted, decrypted)) { "密码、私钥或文件格式不匹配" }
                    BitmapFactory.decodeFile(decrypted.absolutePath) ?: error("解密结果不是有效图片")
                } finally { encrypted.delete(); decrypted.delete() }
            }
            runOnUiThread {
                binding.decryptPicButton.isEnabled = true
                bitmap.onSuccess(binding.decryptImageView::setImageBitmap).onFailure { message(it.message ?: "解密图片失败") }
            }
        }
    }

    private fun copy(value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Toolbox key pair", value))
        message("已复制到剪贴板")
    }
    private fun message(text: String) = Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source_uri", sourceUri?.toString()); outState.putString("algorithm", algorithm.name)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    companion object { private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024 }
}
