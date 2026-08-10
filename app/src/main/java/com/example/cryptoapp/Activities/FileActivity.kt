package com.example.cryptoapp.Activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.FileCrypto
import com.example.cryptoapp.Utils.CryptoEngine
import com.example.cryptoapp.Utils.FileUtil
import com.example.cryptoapp.databinding.ActivityFileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** 基于 Storage Access Framework 的流式多算法文件加解密页面。 */
class FileActivity : BaseActivity() {
    private lateinit var binding: ActivityFileBinding
    private var sourceUri: Uri? = null
    private var destinationUri: Uri? = null
    private var algorithm = FileCrypto.Algorithm.AES_GCM
    private val worker = Executors.newSingleThreadExecutor()

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        sourceUri = uri
        binding.readPathEditText.text = FileUtil.uriToFileName(uri, this)
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@registerForActivityResult
        destinationUri = uri
        binding.savePathEditText.text = FileUtil.uriToFileName(uri, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "文件加密与解密"

        restoreState(savedInstanceState)
        configureAlgorithmPicker()
        binding.readFile.setOnClickListener { openDocument.launch(arrayOf("*/*")) }
        binding.saveFile.setOnClickListener { createDocument.launch(suggestedOutputName()) }
        binding.generateKeyButton.setOnClickListener { generateKeyPair() }
        binding.encryptButton.setOnClickListener { runCrypto(encrypt = true) }
        binding.decryptButton.setOnClickListener { runCrypto(encrypt = false) }
    }

    private fun configureAlgorithmPicker() {
        val algorithms = FileCrypto.Algorithm.values()
        binding.algorithmInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, algorithms.map { it.title })
        )
        binding.algorithmInput.setText(algorithm.title, false)
        applyAlgorithmUi()
        binding.algorithmInput.setOnItemClickListener { _, _, position, _ ->
            algorithm = algorithms[position]
            applyAlgorithmUi()
        }
    }

    private fun applyAlgorithmUi() {
        binding.secretLayout.hint = if (algorithm.asymmetric) "加密粘贴公钥，解密粘贴私钥" else "密码"
        binding.secretLayout.endIconMode = if (algorithm.asymmetric) TextInputLayout.END_ICON_NONE
        else TextInputLayout.END_ICON_PASSWORD_TOGGLE
        binding.generateKeyButton.visibility = if (algorithm.asymmetric) View.VISIBLE else View.GONE
    }

    private fun generateKeyPair() {
        val textAlgorithm = if (algorithm == FileCrypto.Algorithm.RSA_OAEP) {
            CryptoEngine.Algorithm.RSA_OAEP
        } else CryptoEngine.Algorithm.SM2
        runCatching { CryptoEngine.generateKeyPair(textAlgorithm) }
            .onSuccess { keys ->
                val all = "公钥（加密）\n${keys.publicKey}\n\n私钥（解密，请妥善保管）\n${keys.privateKey}"
                MaterialAlertDialogBuilder(this)
                    .setTitle("${algorithm.title} 密钥对")
                    .setMessage(all)
                    .setNeutralButton("复制全部") { _, _ -> copy(all) }
                    .setNegativeButton("使用私钥") { _, _ -> binding.secretInput.setText(keys.privateKey) }
                    .setPositiveButton("使用公钥") { _, _ -> binding.secretInput.setText(keys.publicKey) }
                    .show()
            }
            .onFailure { message(it.message ?: "生成密钥失败") }
    }

    private fun suggestedOutputName(): String {
        val input = binding.readPathEditText.text.toString().ifBlank { "toolbox-file" }
        return if (input.endsWith(".tbx", true)) input.dropLast(4).ifBlank { "decrypted-file" } else "$input.tbx"
    }

    private fun runCrypto(encrypt: Boolean) {
        val input = sourceUri
        val output = destinationUri
        val secret = binding.secretInput.text?.toString().orEmpty()
        if (input == null || output == null) return message("请先选择源文件和目标文件")
        if (secret.isBlank()) return message(if (algorithm.asymmetric) "请粘贴对应的公钥或私钥" else "密码不能为空")
        setBusy(true)
        worker.execute {
            val success = runCatching {
                contentResolver.openFileDescriptor(input, "r")?.use { inputFd ->
                    contentResolver.openFileDescriptor(output, "w")?.use { outputFd ->
                        FileInputStream(inputFd.fileDescriptor).use { from ->
                            FileOutputStream(outputFd.fileDescriptor).use { to ->
                                val crypto = FileCrypto(secret, algorithm)
                                if (encrypt) crypto.encrypt(from, to) else crypto.decrypt(from, to)
                            }
                        }
                    } ?: false
                } ?: false
            }.getOrDefault(false)
            runOnUiThread {
                setBusy(false)
                message(if (success) "任务已完成" else "任务失败，请检查密码、密钥和文件格式")
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.encryptButton.isEnabled = !busy
        binding.decryptButton.isEnabled = !busy
    }

    private fun restoreState(state: Bundle?) {
        sourceUri = state?.getString(STATE_SOURCE)?.let(Uri::parse)
        destinationUri = state?.getString(STATE_DESTINATION)?.let(Uri::parse)
        algorithm = state?.getString(STATE_ALGORITHM)?.let { name ->
            FileCrypto.Algorithm.values().firstOrNull { it.name == name }
        } ?: FileCrypto.Algorithm.AES_GCM
        sourceUri?.let { binding.readPathEditText.text = FileUtil.uriToFileName(it, this) }
        destinationUri?.let { binding.savePathEditText.text = FileUtil.uriToFileName(it, this) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SOURCE, sourceUri?.toString())
        outState.putString(STATE_DESTINATION, destinationUri?.toString())
        outState.putString(STATE_ALGORITHM, algorithm.name)
        super.onSaveInstanceState(outState)
    }

    private fun copy(value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Toolbox key pair", value))
        message("已复制到剪贴板")
    }

    private fun message(text: String) = Snackbar.make(binding.fileView, text, Snackbar.LENGTH_LONG).show()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val STATE_SOURCE = "source_uri"
        private const val STATE_DESTINATION = "destination_uri"
        private const val STATE_ALGORITHM = "file_algorithm"
    }
}
