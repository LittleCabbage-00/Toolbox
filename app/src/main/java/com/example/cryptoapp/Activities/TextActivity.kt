package com.example.cryptoapp.Activities

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.R
import com.example.cryptoapp.Utils.CryptoEngine
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.activity.CaptureActivity
import com.google.zxing.common.BitMatrix

class TextActivity : BaseActivity() {
    private lateinit var root: View
    private lateinit var algorithmInput: AutoCompleteTextView
    private lateinit var secretLayout: TextInputLayout
    private lateinit var secretInput: TextInputEditText
    private lateinit var input: TextInputEditText
    private lateinit var output: TextInputEditText
    private lateinit var generateKeyButton: View
    private var algorithm = CryptoEngine.Algorithm.AES_GCM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text)
        root = findViewById(R.id.textRoot)
        algorithmInput = findViewById(R.id.algorithmInput)
        secretLayout = findViewById(R.id.secretLayout)
        secretInput = findViewById(R.id.secretInput)
        input = findViewById(R.id.inputEditText)
        output = findViewById(R.id.outputEditText)
        generateKeyButton = findViewById(R.id.generateKeyButton)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        configureAlgorithmPicker()

        findViewById<View>(R.id.encryptButton).setOnClickListener { transform(true) }
        findViewById<View>(R.id.decryptButton).setOnClickListener { transform(false) }
        findViewById<View>(R.id.copyOutputButton).setOnClickListener { copy(output.text.toString()) }
        findViewById<View>(R.id.scanInputButton).setOnClickListener {
            startActivityForResult(
                Intent(this, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_RETURN_RESULT, true),
                REQUEST_SCAN
            )
        }
        findViewById<View>(R.id.showQrButton).setOnClickListener { showQr(output.text.toString()) }
        generateKeyButton.setOnClickListener { generateKeyPair() }
    }

    private fun configureAlgorithmPicker() {
        val algorithms = CryptoEngine.Algorithm.values()
        algorithmInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, algorithms.map { it.title }))
        algorithmInput.setText(algorithm.title, false)
        algorithmInput.setOnItemClickListener { _, _, position, _ ->
            algorithm = algorithms[position]
            val asymmetric = algorithm == CryptoEngine.Algorithm.RSA_OAEP || algorithm == CryptoEngine.Algorithm.SM2
            secretLayout.visibility = if (algorithm.needsSecret) View.VISIBLE else View.GONE
            secretLayout.hint = if (asymmetric) "加密粘贴公钥，解密粘贴私钥" else "密码"
            secretLayout.endIconMode = if (asymmetric) TextInputLayout.END_ICON_NONE else TextInputLayout.END_ICON_PASSWORD_TOGGLE
            generateKeyButton.visibility = if (asymmetric) View.VISIBLE else View.GONE
            findViewById<View>(R.id.decryptButton).isEnabled = algorithm.canDecrypt
        }
    }

    private fun transform(encrypt: Boolean) {
        try {
            val source = input.text?.toString().orEmpty()
            require(source.isNotEmpty()) { "请输入需要处理的内容" }
            output.setText(
                if (encrypt) CryptoEngine.encrypt(algorithm, source, secretInput.text?.toString().orEmpty())
                else CryptoEngine.decrypt(algorithm, source, secretInput.text?.toString().orEmpty())
            )
        } catch (e: Exception) {
            output.setText("")
            Snackbar.make(root, e.message ?: "处理失败，请检查输入和密钥", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun generateKeyPair() {
        try {
            val keys = CryptoEngine.generateKeyPair(algorithm)
            val all = "公钥（用于加密）\n${keys.publicKey}\n\n私钥（用于解密，请妥善保管）\n${keys.privateKey}"
            MaterialAlertDialogBuilder(this)
                .setTitle("${algorithm.title} 密钥对")
                .setMessage(all)
                .setNeutralButton("复制全部") { _, _ -> copy(all) }
                .setNegativeButton("使用私钥") { _, _ -> secretInput.setText(keys.privateKey) }
                .setPositiveButton("使用公钥") { _, _ -> secretInput.setText(keys.publicKey) }
                .show()
        } catch (e: Exception) {
            Snackbar.make(root, e.message ?: "生成密钥失败", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showQr(value: String) {
        if (value.isBlank()) {
            Snackbar.make(root, "请先生成输出结果", Snackbar.LENGTH_SHORT).show()
            return
        }
        try {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 900, 900)
            val bitmap = matrix.toBitmap()
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                setPadding(32, 16, 32, 16)
            }
            MaterialAlertDialogBuilder(this).setTitle("扫描以传递结果").setView(image)
                .setPositiveButton("关闭", null).show()
        } catch (e: Exception) {
            Snackbar.make(root, "内容过长，无法生成二维码", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun BitMatrix.toBitmap(): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = if (get(x, y)) 0xff111111.toInt() else 0xffffffff.toInt()
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun copy(value: String) {
        if (value.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Toolbox", value))
        Snackbar.make(root, "已复制到剪贴板", Snackbar.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCAN && resultCode == Activity.RESULT_OK) {
            input.setText(data?.getStringExtra(CaptureActivity.EXTRA_SCAN_RESULT).orEmpty())
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object { private const val REQUEST_SCAN = 2101 }
}
