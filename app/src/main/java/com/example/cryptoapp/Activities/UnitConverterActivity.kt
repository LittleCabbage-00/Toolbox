package com.example.cryptoapp.Activities

import android.os.Bundle
import android.widget.ArrayAdapter
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Utils.UnitConverterEngine
import com.example.cryptoapp.databinding.ActivityUnitConverterBinding
import com.google.android.material.snackbar.Snackbar

/** 长度、质量、温度、容量、速度和面积的离线单位换算。 */
class UnitConverterActivity : BaseActivity() {
    private lateinit var binding: ActivityUnitConverterBinding
    private var categoryIndex = 0
    private var fromIndex = 0
    private var toIndex = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnitConverterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.categoryInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1,
            UnitConverterEngine.categories.map { it.name }))
        binding.categoryInput.setText(UnitConverterEngine.categories.first().name, false)
        binding.categoryInput.setOnItemClickListener { _, _, position, _ ->
            categoryIndex = position
            fromIndex = 0
            toIndex = 1
            bindUnits()
        }
        binding.fromUnitInput.setOnItemClickListener { _, _, position, _ -> fromIndex = position }
        binding.toUnitInput.setOnItemClickListener { _, _, position, _ -> toIndex = position }
        binding.swapButton.setOnClickListener {
            val oldFrom = fromIndex
            fromIndex = toIndex
            toIndex = oldFrom
            updateUnitTexts()
            convert()
        }
        binding.convertButton.setOnClickListener { convert() }
        bindUnits()
    }

    private fun bindUnits() {
        val names = UnitConverterEngine.categories[categoryIndex].units.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        binding.fromUnitInput.setAdapter(adapter)
        binding.toUnitInput.setAdapter(adapter)
        updateUnitTexts()
    }

    private fun updateUnitTexts() {
        val units = UnitConverterEngine.categories[categoryIndex].units
        binding.fromUnitInput.setText(units[fromIndex].name, false)
        binding.toUnitInput.setText(units[toIndex].name, false)
    }

    private fun convert() {
        val value = binding.valueInput.text?.toString()?.toDoubleOrNull()
            ?: return Snackbar.make(binding.root, "请输入有效数值", Snackbar.LENGTH_SHORT).show()
        runCatching { UnitConverterEngine.convert(categoryIndex, fromIndex, toIndex, value) }
            .onSuccess { binding.resultText.text = "$it ${UnitConverterEngine.categories[categoryIndex].units[toIndex].name}" }
            .onFailure { Snackbar.make(binding.root, it.message ?: "换算失败", Snackbar.LENGTH_SHORT).show() }
    }
}
