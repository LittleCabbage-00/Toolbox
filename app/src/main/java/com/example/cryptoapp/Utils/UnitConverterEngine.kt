package com.example.cryptoapp.Utils

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** 常用单位换算；先转换为分类基准单位，再转换到目标单位。 */
object UnitConverterEngine {
    data class UnitDef(val name: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double)
    data class Category(val name: String, val units: List<UnitDef>)

    private fun linear(name: String, factor: Double) = UnitDef(name, { it * factor }, { it / factor })

    val categories = listOf(
        Category("长度", listOf(linear("毫米 mm", .001), linear("厘米 cm", .01), linear("米 m", 1.0), linear("千米 km", 1000.0), linear("英寸 in", .0254), linear("英尺 ft", .3048), linear("英里 mi", 1609.344))),
        Category("质量", listOf(linear("克 g", .001), linear("千克 kg", 1.0), linear("吨 t", 1000.0), linear("盎司 oz", .028349523125), linear("磅 lb", .45359237))),
        Category("温度", listOf(
            UnitDef("摄氏度 ℃", { it }, { it }),
            UnitDef("华氏度 ℉", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 }),
            UnitDef("开尔文 K", { it - 273.15 }, { it + 273.15 })
        )),
        Category("数据容量", listOf(linear("字节 B", 1.0), linear("KB", 1024.0), linear("MB", 1024.0 * 1024), linear("GB", 1024.0 * 1024 * 1024), linear("TB", 1024.0 * 1024 * 1024 * 1024))),
        Category("速度", listOf(linear("米/秒 m/s", 1.0), linear("千米/时 km/h", 1 / 3.6), linear("英里/时 mph", .44704), linear("节 kn", .514444))),
        Category("面积", listOf(linear("平方米 m²", 1.0), linear("平方千米 km²", 1_000_000.0), linear("公顷 ha", 10_000.0), linear("平方英尺 ft²", .09290304), linear("英亩 acre", 4046.8564224)))
    )

    fun convert(categoryIndex: Int, fromIndex: Int, toIndex: Int, value: Double): String {
        val category = categories[categoryIndex]
        val base = category.units[fromIndex].toBase(value)
        val result = category.units[toIndex].fromBase(base)
        require(result.isFinite()) { "数值超出可处理范围" }
        return DecimalFormat("0.############", DecimalFormatSymbols(Locale.ROOT)).format(result)
    }
}
