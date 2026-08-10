package com.example.cryptoapp.Utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/** 常用文本与开发数据工具。这里只做编码和格式转换，不宣称提供加密能力。 */
object DataToolsEngine {
    enum class Operation(val title: String, val hint: String) {
        BASE64_ENCODE("Base64 编码", "输入普通文本"),
        BASE64_DECODE("Base64 解码", "输入 Base64 文本"),
        URL_ENCODE("URL 参数编码", "输入需要放入 URL 参数的文本"),
        URL_DECODE("URL 参数解码", "输入已编码的 URL 参数"),
        HEX_ENCODE("文本转 Hex", "输入普通文本"),
        HEX_DECODE("Hex 转文本", "输入十六进制字节，例如 e4bda0e5a5bd"),
        JSON_FORMAT("JSON 格式化", "输入 JSON 对象或数组"),
        JSON_MINIFY("JSON 压缩", "输入 JSON 对象或数组"),
        TIMESTAMP_TO_DATE("时间戳转日期", "输入秒或毫秒时间戳"),
        DATE_TO_TIMESTAMP("日期转时间戳", "格式：yyyy-MM-dd HH:mm:ss"),
        UUID_GENERATE("生成 UUID", "无需输入"),
        PASSWORD_GENERATE("生成安全密码", "输入长度，默认 20，范围 8–128")
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val secureRandom = SecureRandom()
    private val passwordGroups = listOf(
        "ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", "!@#%+-_="
    )
    private val passwordChars = passwordGroups.joinToString("")

    fun transform(operation: Operation, input: String, zoneId: ZoneId = ZoneId.systemDefault()): String = when (operation) {
        Operation.BASE64_ENCODE -> Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
        Operation.BASE64_DECODE -> String(Base64.getDecoder().decode(input.trim()), Charsets.UTF_8)
        Operation.URL_ENCODE -> URLEncoder.encode(input, Charsets.UTF_8.name())
        Operation.URL_DECODE -> URLDecoder.decode(input, Charsets.UTF_8.name())
        Operation.HEX_ENCODE -> input.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        Operation.HEX_DECODE -> decodeHex(input)
        Operation.JSON_FORMAT -> json(input, 2)
        Operation.JSON_MINIFY -> json(input, 0)
        Operation.TIMESTAMP_TO_DATE -> {
            val raw = input.trim().toLong()
            val millis = if (kotlin.math.abs(raw) < 100_000_000_000L) raw * 1000 else raw
            dateFormat.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId))
        }
        Operation.DATE_TO_TIMESTAMP -> LocalDateTime.parse(input.trim(), dateFormat)
            .atZone(zoneId).toInstant().toEpochMilli().toString()
        Operation.UUID_GENERATE -> UUID.randomUUID().toString()
        Operation.PASSWORD_GENERATE -> generatePassword(input.trim().toIntOrNull() ?: 20)
    }

    private fun decodeHex(value: String): String {
        val clean = value.replace(Regex("\\s|0x"), "")
        require(clean.length % 2 == 0 && clean.matches(Regex("[0-9a-fA-F]*"))) { "Hex 必须由偶数个十六进制字符组成" }
        val bytes = ByteArray(clean.length / 2) { index -> clean.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return String(bytes, Charsets.UTF_8)
    }

    private fun json(value: String, indent: Int): String = value.trim().let {
        when {
            it.startsWith("{") -> JSONObject(it).toString(indent)
            it.startsWith("[") -> JSONArray(it).toString(indent)
            else -> error("请输入 JSON 对象或数组")
        }
    }

    private fun generatePassword(length: Int): String {
        require(length in 8..128) { "密码长度必须在 8–128 之间" }
        val characters = mutableListOf<Char>()
        passwordGroups.forEach { group -> characters += group[secureRandom.nextInt(group.length)] }
        repeat(length - characters.size) { characters += passwordChars[secureRandom.nextInt(passwordChars.length)] }
        // Fisher–Yates 洗牌，避免四类必选字符总出现在固定位置。
        for (index in characters.lastIndex downTo 1) {
            val other = secureRandom.nextInt(index + 1)
            val temporary = characters[index]
            characters[index] = characters[other]
            characters[other] = temporary
        }
        return characters.joinToString("")
    }
}
