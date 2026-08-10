package com.example.cryptoapp.Activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Utils.DataToolsEngine
import com.example.cryptoapp.databinding.ActivityDataToolsBinding
import com.google.android.material.snackbar.Snackbar

/** 编码、JSON、时间戳和随机数据等常用离线开发工具。 */
class DataToolsActivity : BaseActivity() {
    private lateinit var binding: ActivityDataToolsBinding
    private val operations = DataToolsEngine.Operation.entries
    private var operation = operations.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.operationInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, operations.map { it.title }))
        binding.operationInput.setText(operation.title, false)
        binding.operationInput.setOnItemClickListener { _, _, position, _ ->
            operation = operations[position]
            updateDescription()
        }
        binding.processButton.setOnClickListener { process() }
        binding.copyButton.setOnClickListener { copyResult() }
        binding.useResultButton.setOnClickListener {
            binding.sourceInput.setText(binding.resultOutput.text)
            binding.resultOutput.setText("")
        }
        updateDescription()
    }

    private fun updateDescription() {
        binding.descriptionText.text = buildString {
            append(operation.hint)
            if (operation.name.startsWith("BASE64")) append("。Base64 是编码，不是加密")
        }
        val needsInput = operation != DataToolsEngine.Operation.UUID_GENERATE
        binding.sourceInput.isEnabled = needsInput
        if (!needsInput) binding.sourceInput.setText("")
    }

    private fun process() {
        val result = runCatching { DataToolsEngine.transform(operation, binding.sourceInput.text?.toString().orEmpty()) }
        result.onSuccess { binding.resultOutput.setText(it) }
            .onFailure { message(it.message ?: "转换失败，请检查输入格式") }
    }

    private fun copyResult() {
        val value = binding.resultOutput.text?.toString().orEmpty()
        if (value.isBlank()) return message("暂无可复制结果")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("工具结果", value))
        message("结果已复制")
    }

    private fun message(value: String) = Snackbar.make(binding.root, value, Snackbar.LENGTH_SHORT).show()
}
