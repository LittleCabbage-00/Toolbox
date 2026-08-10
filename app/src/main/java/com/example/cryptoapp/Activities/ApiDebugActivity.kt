package com.example.cryptoapp.Activities

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.R
import com.example.cryptoapp.databinding.ActivityApiDebugBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 面向后端的 HTTP 接口调试工具：自定义方法/请求头/请求体，输出耗时、TLS、响应头和响应体，附带路由跟踪。 */
class ApiDebugActivity : BaseActivity() {
    private companion object {
        const val PREVIEW_LIMIT = 256 * 1024
        val METHODS = arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        val JSON_TYPES = arrayOf("文本", "数字", "布尔", "JSON")
        private const val PREFS_API_DEBUG = "api_debug_state"
        private const val SAVED_REQUESTS_KEY = "saved_requests"
    }

    private lateinit var binding: ActivityApiDebugBinding
    private val jsonRows = ArrayList<JsonRow>()
    private val headerRows = ArrayList<HeaderRow>()
    private val savedRequests = mutableListOf<SavedRequest>()
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApiDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.methodInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, METHODS))
        binding.methodInput.setText("GET", false)
        binding.sendButton.setOnClickListener { sendRequest() }
        binding.addFieldButton.setOnClickListener { addJsonRow() }
        binding.generateButton.setOnClickListener { generateAndSend() }
        binding.addHeaderButton.setOnClickListener { addHeaderRow() }
        binding.headerModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.headerModeForm) {
                syncRawToForm()
                binding.headerFormPanel.visibility = View.VISIBLE
                binding.rawHeaderPanel.visibility = View.GONE
            } else {
                syncFormToRaw()
                binding.headerFormPanel.visibility = View.GONE
                binding.rawHeaderPanel.visibility = View.VISIBLE
            }
        }
        binding.saveRequestButton.setOnClickListener { saveCurrentRequest() }
        binding.historyRequestsButton.setOnClickListener { showSavedRequests() }
        binding.bodyModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val form = checkedId == R.id.modeForm
            binding.jsonBodyPanel.visibility = if (form) View.VISIBLE else View.GONE
            binding.rawBodyPanel.visibility = if (form) View.GONE else View.VISIBLE
        }
        binding.copyButton.setOnClickListener {
            val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            manager.setPrimaryClip(
                android.content.ClipData.newPlainText("Toolbox network result", binding.resultText.text)
            )
            message("结果已复制")
        }
        addJsonRow()
        addJsonRow()
        addHeaderRow()
        loadSavedRequests()
    }

    private data class JsonRow(val key: EditText, val value: EditText, val type: Spinner)
    private data class HeaderRow(val key: EditText, val value: EditText)
    private data class SavedRequest(
        val id: String, val name: String, val url: String,
        val method: String, val headers: String, val body: String
    )

    private fun saveCurrentRequest() {
        val url = runCatching { urlInput() }.getOrNull()
        if (url.isNullOrBlank()) {
            message("目标地址不能为空")
            return
        }
        val nameInput = EditText(this).apply {
            hint = "请求名称"
            setText(runCatching { URI(url).host }.getOrNull().orEmpty().ifEmpty { "请求 ${savedRequests.size + 1}" })
            inputType = InputType.TYPE_CLASS_TEXT
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存当前请求")
            .setView(nameInput)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty().ifEmpty { url }
                savedRequests.removeAll { it.name == name }
                savedRequests.add(
                    0, SavedRequest(
                        UUID.randomUUID().toString(), name, url, requestMethod(),
                        currentHeadersText(),
                        binding.bodyInput.text?.toString().orEmpty()
                    )
                )
                persistSavedRequests()
                message("已保存：$name")
            }
            .show()
    }

    private fun showSavedRequests() {
        if (savedRequests.isEmpty()) {
            message("暂无已保存的请求")
            return
        }
        val entries = ArrayList(savedRequests)
        var dialog: AlertDialog? = null
        lateinit var adapter: SavedRequestAdapter
        adapter = SavedRequestAdapter(
            entries,
            onSelect = { item ->
                binding.input.setText(item.url)
                binding.methodInput.setText(item.method, false)
                binding.headersInput.setText(item.headers)
                if (item.headers.isNotBlank()) {
                    binding.headerModeForm.isChecked = true
                    syncRawToForm()
                }
                binding.bodyInput.setText(item.body)
                if (item.body.isNotBlank()) binding.modeRaw.isChecked = true
                dialog?.dismiss()
            },
            onDelete = { item ->
                savedRequests.removeAll { it.id == item.id }
                persistSavedRequests()
                entries.removeAll { it.id == item.id }
                adapter.notifyDataSetChanged()
                if (entries.isEmpty()) dialog?.dismiss()
            }
        )
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ApiDebugActivity)
            this.adapter = adapter
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, dp(320))
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("已保存的请求（${entries.size}）")
            .setView(list, dp(24), dp(8), dp(24), dp(8))
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空全部") { _, _ -> confirmClearSavedRequests() }
            .show()
    }

    private fun confirmClearSavedRequests() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空全部已保存请求？")
            .setMessage("此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                savedRequests.clear()
                persistSavedRequests()
            }
            .show()
    }

    private fun persistSavedRequests() {
        val array = JSONArray()
        savedRequests.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id); put("name", item.name); put("url", item.url)
                    put("method", item.method); put("headers", item.headers); put("body", item.body)
                }
            )
        }
        getSharedPreferences(PREFS_API_DEBUG, MODE_PRIVATE)
            .edit().putString(SAVED_REQUESTS_KEY, array.toString()).apply()
    }

    private fun loadSavedRequests() {
        val raw = getSharedPreferences(PREFS_API_DEBUG, MODE_PRIVATE)
            .getString(SAVED_REQUESTS_KEY, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            savedRequests.clear()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                savedRequests.add(
                    SavedRequest(
                        item.getString("id"), item.getString("name"), item.getString("url"),
                        item.getString("method"), item.getString("headers"), item.getString("body")
                    )
                )
            }
        }
    }

    private class SavedRequestAdapter(
        private val entries: MutableList<SavedRequest>,
        private val onSelect: (SavedRequest) -> Unit,
        private val onDelete: (SavedRequest) -> Unit
    ) : RecyclerView.Adapter<SavedRequestAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_saved_request, parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = entries[position]
            holder.name.text = item.name
            holder.detail.text = "${item.method} ${item.url}"
            holder.itemView.setOnClickListener { onSelect(item) }
            holder.delete.setOnClickListener { onDelete(item) }
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.savedRequestName)
            val detail: TextView = itemView.findViewById(R.id.savedRequestDetail)
            val delete: ImageButton = itemView.findViewById(R.id.savedRequestDelete)
        }
    }

    private fun addJsonRow() {
        val key = EditText(this).apply {
            hint = "字段名"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val value = EditText(this).apply {
            hint = "值"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@ApiDebugActivity, android.R.layout.simple_spinner_item, JSON_TYPES).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        val remove = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close_24)
            background = null
            contentDescription = "删除字段"
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(key, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
            addView(type, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(remove, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        remove.setOnClickListener {
            binding.jsonRowsContainer.removeView(row)
            jsonRows.removeIf { it.key === key }
        }
        binding.jsonRowsContainer.addView(row)
        jsonRows.add(JsonRow(key, value, type))
    }

    private fun generateAndSend() {
        val json = JSONObject()
        val seen = HashSet<String>()
        jsonRows.forEachIndexed { index, row ->
            val key = row.key.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                message("第 ${index + 1} 行的字段名为空")
                return
            }
            if (!seen.add(key)) {
                message("字段名重复：$key")
                return
            }
            val value = row.value.text?.toString()?.trim().orEmpty()
            when (row.type.selectedItem.toString()) {
                "文本" -> json.put(key, value)
                "数字" -> {
                    val number = runCatching {
                        if (value.contains('.')) value.toDouble() else value.toLong()
                    }.getOrElse {
                        message("字段 $key 不是合法数字")
                        return
                    }
                    json.put(key, number)
                }
                "布尔" -> {
                    val parsed = when (value) {
                        "true", "1", "是" -> true
                        "false", "0", "否" -> false
                        else -> {
                            message("字段 $key 应为 true/false/1/0")
                            return
                        }
                    }
                    json.put(key, parsed)
                }
                "JSON" -> {
                    if (value.isEmpty()) {
                        message("字段 $key 的 JSON 为空")
                        return
                    }
                    val parsed = runCatching { JSONTokener(value).nextValue() }.getOrElse {
                        message("字段 $key 的 JSON 不合法")
                        return
                    }
                    json.put(key, parsed)
                }
            }
        }
        if (json.length() == 0) {
            message("请先填写字段")
            return
        }
        binding.bodyInput.setText(json.toString(2))
        binding.modeRaw.isChecked = true
        if (requestMethod() == "GET") binding.methodInput.setText("POST", false)
        sendRequest()
    }

    private fun urlInput(): String {
        val raw = binding.input.text?.toString()?.trim().orEmpty()
        require(raw.isNotBlank()) { "请输入网址或域名" }
        return if (raw.contains("://")) raw else "https://$raw"
    }

    private fun requestMethod(): String =
        binding.methodInput.text?.toString()?.trim()?.ifEmpty { "GET" } ?: "GET"

    private fun parseHeaders(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()
        val result = ArrayList<Pair<String, String>>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val colon = trimmed.indexOf(':')
            if (colon <= 0) error("请求头格式错误：$trimmed（应为 Key: Value）")
            val key = trimmed.substring(0, colon).trim()
            val value = trimmed.substring(colon + 1).trim()
            if (key.isEmpty() || key.contains('\n') || key.contains('\r')
                || value.contains('\n') || value.contains('\r')) {
                error("请求头格式错误：$trimmed")
            }
            result.add(key to value)
        }
        return result
    }

    private fun currentHeadersText(): String {
        if (binding.headerModeRaw.isChecked) {
            return binding.headersInput.text?.toString().orEmpty()
        }
        return buildString {
            headerRows.forEach { row ->
                val key = row.key.text?.toString()?.trim().orEmpty()
                val value = row.value.text?.toString()?.trim().orEmpty()
                if (key.isNotEmpty()) appendLine("$key: $value")
            }
        }.trim()
    }

    private fun syncFormToRaw() {
        binding.headersInput.setText(currentHeadersText())
    }

    private fun syncRawToForm() {
        val pairs = binding.headersInput.text?.toString().orEmpty().lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val colon = trimmed.indexOf(':')
                if (colon <= 0) null else trimmed.substring(0, colon).trim() to trimmed.substring(colon + 1).trim()
            }.toList()
        clearHeaderRows()
        if (pairs.isEmpty()) addHeaderRow()
        else pairs.forEach { (key, value) -> addHeaderRow(key, value) }
    }

    private fun clearHeaderRows() {
        binding.headerRowsContainer.removeAllViews()
        headerRows.clear()
    }

    private fun addHeaderRow(key: String = "", value: String = "") {
        val keyInput = EditText(this).apply {
            hint = "Key"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(key)
        }
        val valueInput = EditText(this).apply {
            hint = "Value"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(value)
        }
        val remove = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close_24)
            background = null
            contentDescription = "删除请求头"
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(keyInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
            addView(remove, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        remove.setOnClickListener {
            binding.headerRowsContainer.removeView(row)
            headerRows.removeIf { it.key === keyInput }
        }
        binding.headerRowsContainer.addView(row)
        headerRows.add(HeaderRow(keyInput, valueInput))
    }

    private fun sendRequest() = background("正在发送请求…") {
        val input = urlInput()
        val method = requestMethod()
        val headers = parseHeaders(currentHeadersText())
        val builder = Request.Builder().url(input)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val bodyText = binding.bodyInput.text?.toString().orEmpty()
        val bodySupported = method != "GET" && method != "HEAD"
        if (bodySupported) {
            val mediaType = if (headers.any { it.first.equals("Content-Type", true) }) null
            else if (bodyText.trimStart().startsWith("{") || bodyText.trimStart().startsWith("["))
                "application/json; charset=utf-8"
            else "text/plain; charset=utf-8"
            builder.method(method, bodyText.toByteArray(Charsets.UTF_8).toRequestBody(mediaType?.toMediaType()))
        } else {
            builder.method(method, null)
        }
        val request = builder.build()
        val collector = EventCollector()
        val httpClient = client.newBuilder().eventListener(collector).build()
        val requestStart = System.nanoTime()
        httpClient.newCall(request).execute().use { response ->
            val ttfbMs = (System.nanoTime() - requestStart) / 1_000_000
            val body = response.body
            val declared = body?.contentLength() ?: -1
            val previewBytes = readPreview(body?.byteStream())
            val bodyMs = (System.nanoTime() - requestStart) / 1_000_000 - ttfbMs
            val contentType = response.header("Content-Type") ?: ""
            val textLike = contentType.isEmpty() || contentType.startsWith("text/")
                || contentType.contains("json") || contentType.contains("xml")
                || contentType.contains("javascript") || contentType.contains("x-www-form-urlencoded")
            buildString {
                appendLine("【HTTP 请求诊断】")
                appendLine("请求：$method $input")
                appendLine("状态：HTTP ${response.code} ${response.message}")
                appendLine("协议：${response.protocol}")
                appendLine("总耗时：${response.receivedResponseAtMillis - response.sentRequestAtMillis} ms")
                appendLine("首字节耗时（TTFB）：$ttfbMs ms")
                appendLine("响应体下载：$bodyMs ms / ${formatBytes(previewBytes.size.toLong())}")
                val hops = redirectHops(response)
                if (hops.size > 1) {
                    appendLine("跳转链路（${hops.size - 1} 次）：")
                    hops.forEachIndexed { index, hop ->
                        appendLine("  ${index + 1}. HTTP ${hop.first} → ${hop.second}")
                    }
                } else {
                    appendLine("跳转链路：无")
                }
                appendLine("发送的请求头（${request.headers.size} 条）：")
                request.headers.forEach { (name, value) -> appendLine("  $name: $value") }
                appendTls(response, this)
                appendLine("事件明细：")
                val base = collector.events.firstOrNull()?.atNanos
                if (base == null) {
                    appendLine("  （无事件记录）")
                } else {
                    collector.events.forEach { event ->
                        val millis = (event.atNanos - base) / 1_000_000
                        appendLine(
                            "  ${millis.toString().padStart(6)} ms  ${event.name}" +
                                (if (event.detail.isEmpty()) "" else "：${event.detail}")
                        )
                    }
                }
                appendLine("响应头（${response.headers.size} 条）：")
                if (response.headers.size == 0) appendLine("  （无）")
                response.headers.forEach { (name, value) -> appendLine("  $name: $value") }
                appendLine("响应体：")
                if (previewBytes.isEmpty()) {
                    appendLine("  （无响应体）")
                } else if (textLike) {
                    val preview = String(previewBytes, Charsets.UTF_8)
                    appendLine("  $preview")
                    if (declared > previewBytes.size || previewBytes.size >= PREVIEW_LIMIT) {
                        appendLine("  …（预览已截断，完整大小${if (declared >= 0) " ${formatBytes(declared)}" else "未知"}）")
                    }
                } else {
                    appendLine("  （二进制内容 ${formatBytes(previewBytes.size.toLong())}，跳过预览）")
                }
            }.trim()
        }
    }

    private fun readPreview(stream: java.io.InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        val output = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (total < PREVIEW_LIMIT) {
                val count = input.read(buffer, 0, minOf(buffer.size, (PREVIEW_LIMIT - total).toInt()))
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
        }
        return output.toByteArray()
    }

    private fun appendTls(response: Response, builder: StringBuilder) {
        val handshake = response.handshake
        if (handshake == null) {
            builder.appendLine("TLS 信息：无（非 HTTPS 连接）")
            return
        }
        builder.appendLine("TLS 信息：")
        builder.appendLine("  版本：${handshake.tlsVersion.javaName}")
        builder.appendLine("  加密套件：${handshake.cipherSuite.javaName}")
        val now = Date()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        handshake.peerCertificates.filterIsInstance<X509Certificate>().forEachIndexed { index, cert ->
            builder.appendLine("  证书 ${index + 1}：")
            builder.appendLine("    主题：${cert.subjectX500Principal.name}")
            builder.appendLine("    签发者：${cert.issuerX500Principal.name}")
            builder.appendLine("    有效期：${format.format(cert.notBefore)} ~ ${format.format(cert.notAfter)}")
            val valid = !cert.notAfter.before(now) && !cert.notBefore.after(now)
            builder.appendLine("    当前是否有效：${if (valid) "是" else "否（已过期或未生效）"}")
        }
    }

    private fun redirectHops(response: Response): List<Pair<Int, String>> {
        val hops = ArrayList<Pair<Int, String>>()
        var current: Response? = response
        while (current != null) {
            hops.add(current.code to current.request.url.toString())
            current = current.priorResponse
        }
        hops.reverse()
        return hops
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB")
        var size = value.toDouble() / 1024
        var unit = 0
        while (size >= 1024 && unit < units.size - 1) {
            size /= 1024
            unit++
        }
        return String.format(Locale.getDefault(), "%.1f %s", size, units[unit])
    }

    private class EventCollector : EventListener() {
        val events = ArrayList<NetEvent>()

        private fun add(name: String, detail: String = "") {
            events.add(NetEvent(name, detail, System.nanoTime()))
        }

        override fun dnsStart(call: Call, domainName: String) { add("DNS 开始", domainName) }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            add("DNS 结束", inetAddressList.joinToString(", ") { it.hostAddress ?: "" })
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            add("TCP 连接开始", "${inetSocketAddress.hostString}:${inetSocketAddress.port}（代理：$proxy）")
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            add("TCP 连接完成", protocol?.toString().orEmpty())
        }
        override fun secureConnectStart(call: Call) { add("TLS 握手开始") }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            add("TLS 握手完成", handshake?.tlsVersion?.javaName.orEmpty())
        }
        override fun connectionAcquired(call: Call, connection: Connection) {
            add("连接已建立", "协议：${connection.protocol()}")
        }
        override fun requestHeadersStart(call: Call) { add("发送请求头开始") }
        override fun requestHeadersEnd(call: Call, request: Request) {
            add("请求头发送完成", "${request.method} ${request.url}")
        }
        override fun responseHeadersStart(call: Call) { add("等待响应头") }
        override fun responseHeadersEnd(call: Call, response: Response) {
            add("收到响应头", "HTTP ${response.code}")
        }
        override fun responseBodyStart(call: Call) { add("响应体开始") }
        override fun responseBodyEnd(call: Call, byteCount: Long) { add("响应体结束", "$byteCount 字节") }
        override fun callEnd(call: Call) { add("请求结束") }
        override fun callFailed(call: Call, ioe: IOException) { add("请求失败", ioe.toString()) }
    }

    private data class NetEvent(val name: String, val detail: String, val atNanos: Long)

    private fun background(progress: String, action: () -> String) {
        binding.resultText.text = progress
        worker.execute {
            val result = runCatching(action)
            runOnUiThread { binding.resultText.text = result.getOrElse { "操作失败：${it.message.orEmpty()}" } }
        }
    }

    private fun message(value: String) = Snackbar.make(binding.root, value, Snackbar.LENGTH_SHORT).show()

    private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
