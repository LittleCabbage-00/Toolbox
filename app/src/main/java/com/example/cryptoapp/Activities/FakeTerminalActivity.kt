package com.example.cryptoapp.Activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.R
import com.example.cryptoapp.Utils.CheckRoot
import com.example.cryptoapp.Utils.ShellCommandExecutor
import com.example.cryptoapp.Utils.ShellExecutionMode
import com.example.cryptoapp.Utils.ShizukuShellClient
import com.example.cryptoapp.databinding.ActivityFakeTermianlBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 可维护命令模板，并支持应用沙箱、Root、Shizuku 三种执行身份的简易终端。 */
class FakeTerminalActivity : BaseActivity() {
    private lateinit var binding: ActivityFakeTermianlBinding
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var runningTask: Future<*>? = null
    private val commandHistory = mutableListOf<String>()
    private val templates = mutableListOf<CommandTemplate>()
    private var executionMode = ShellExecutionMode.APP

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
            runOnUiThread {
                updateShizukuStatus()
                val message = if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 授权成功" else "Shizuku 授权被拒绝"
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { updateShizukuStatus() } }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread { updateShizukuStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFakeTermianlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        registerShizukuListeners()
        setupExecutionModes()
        loadHistory()
        loadTemplates()
        renderTemplates()

        binding.manageTemplatesButton.setOnClickListener { showTemplateManager() }
        binding.historyButton.setOnClickListener { showCommandHistory() }
        binding.shizukuAuthorizeButton.setOnClickListener { requestShizukuAuthorization() }
        binding.runButton.setOnClickListener { runCommand() }
        binding.cancelButton.setOnClickListener { cancelCommand() }
        binding.clearButton.setOnClickListener { binding.outputText.text = "$ 等待输入命令" }
        binding.copyButton.setOnClickListener { copyOutput() }
        binding.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { runCommand(); true } else false
        }
    }

    private fun setupExecutionModes() {
        val saved = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(MODE_KEY, ShellExecutionMode.APP.name)
        executionMode = runCatching { ShellExecutionMode.valueOf(saved.orEmpty()) }.getOrDefault(ShellExecutionMode.APP)
        val selectedId = when (executionMode) {
            ShellExecutionMode.APP -> R.id.appModeButton
            ShellExecutionMode.ROOT -> R.id.rootModeButton
            ShellExecutionMode.SHIZUKU -> R.id.shizukuModeButton
        }
        binding.executionModeGroup.check(selectedId)
        binding.executionModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            executionMode = when (checkedId) {
                R.id.rootModeButton -> ShellExecutionMode.ROOT
                R.id.shizukuModeButton -> ShellExecutionMode.SHIZUKU
                else -> ShellExecutionMode.APP
            }
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(MODE_KEY, executionMode.name).apply()
            binding.shizukuPanel.visibility = if (executionMode == ShellExecutionMode.SHIZUKU) View.VISIBLE else View.GONE
            updateShizukuStatus()
        }
        binding.shizukuPanel.visibility = if (executionMode == ShellExecutionMode.SHIZUKU) View.VISIBLE else View.GONE
        updateShizukuStatus()
    }

    private fun registerShizukuListeners() {
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        }
    }

    private fun updateShizukuStatus() {
        if (!::binding.isInitialized) return
        val running = ShizukuShellClient.isRunning()
        val authorized = ShizukuShellClient.isAuthorized()
        binding.shizukuStatus.text = when {
            !running -> "Shizuku 未运行"
            !authorized -> "Shizuku 已运行，等待授权"
            else -> {
                val uid = runCatching { Shizuku.getUid() }.getOrDefault(2000)
                "已授权 · ${if (uid == 0) "Root/Sui" else "ADB Shell"} (uid=$uid)"
            }
        }
        binding.shizukuAuthorizeButton.text = if (authorized) "已授权" else "申请授权"
        binding.shizukuAuthorizeButton.isEnabled = !authorized
    }

    private fun requestShizukuAuthorization() {
        if (!ShizukuShellClient.isRunning()) {
            Snackbar.make(binding.root, "请先安装并启动 Shizuku", Snackbar.LENGTH_LONG)
                .setAction("查看官网") {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
                }.show()
            return
        }
        runCatching {
            when {
                Shizuku.isPreV11() -> error("Shizuku 版本过旧，请升级到 v11 以上")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> updateShizukuStatus()
                Shizuku.shouldShowRequestPermissionRationale() -> error("授权已被永久拒绝，请在 Shizuku 的“已授权应用”中开启")
                else -> Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }
        }.onFailure { Snackbar.make(binding.root, it.message ?: "无法申请 Shizuku 授权", Snackbar.LENGTH_LONG).show() }
    }

    private fun renderTemplates() {
        binding.quickCommands.removeAllViews()
        templates.forEach { template ->
            binding.quickCommands.addView(Chip(this).apply {
                text = template.title
                contentDescription = "命令模板：${template.title}，长按编辑"
                isCheckable = false
                setOnClickListener { binding.commandInput.setText(template.command, false) }
                setOnLongClickListener { editTemplate(template); true }
            })
        }
        binding.quickCommands.addView(Chip(this).apply {
            text = "+ 新增"
            isCheckable = false
            setOnClickListener { editTemplate(null) }
        })
    }

    private fun showTemplateManager() {
        val items = templates.map { "${it.title}\n${it.command}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("管理命令模板")
            .setItems(items) { _, index -> editTemplate(templates[index]) }
            .setNeutralButton("恢复默认") { _, _ -> confirmRestoreDefaults() }
            .setNegativeButton("关闭", null)
            .setPositiveButton("新增") { _, _ -> editTemplate(null) }
            .show()
    }

    private fun editTemplate(existing: CommandTemplate?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_terminal_template, null)
        val titleInput = view.findViewById<TextInputEditText>(R.id.templateTitleInput)
        val commandInput = view.findViewById<TextInputEditText>(R.id.templateCommandInput)
        titleInput.setText(existing?.title.orEmpty())
        commandInput.setText(existing?.command.orEmpty())
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "新增命令模板" else "编辑命令模板")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val title = titleInput.text?.toString()?.trim().orEmpty()
                val command = commandInput.text?.toString()?.trim().orEmpty()
                if (title.isBlank() || command.isBlank()) {
                    Snackbar.make(binding.root, "模板名称和命令不能为空", Snackbar.LENGTH_LONG).show()
                } else {
                    if (existing == null) templates.add(CommandTemplate(UUID.randomUUID().toString(), title, command))
                    else {
                        val index = templates.indexOfFirst { it.id == existing.id }
                        if (index >= 0) templates[index] = existing.copy(title = title, command = command)
                    }
                    saveTemplates(); renderTemplates()
                }
            }
        if (existing != null) builder.setNeutralButton("删除") { _, _ ->
            templates.removeAll { it.id == existing.id }; saveTemplates(); renderTemplates()
        }
        builder.show()
    }

    private fun confirmRestoreDefaults() {
        MaterialAlertDialogBuilder(this).setTitle("恢复默认模板？")
            .setMessage("现有自定义模板会被替换。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                templates.clear(); templates.addAll(defaultTemplates()); saveTemplates(); renderTemplates()
            }.show()
    }

    private fun runCommand() {
        val command = binding.commandInput.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) {
            Snackbar.make(binding.root, "请输入命令", Snackbar.LENGTH_SHORT).show(); return
        }
        if (executionMode == ShellExecutionMode.ROOT && !CheckRoot.isDeviceRooted()) {
            Snackbar.make(binding.root, "设备未检测到可用的 su", Snackbar.LENGTH_LONG).show(); return
        }
        if (executionMode == ShellExecutionMode.SHIZUKU && !ShizukuShellClient.isAuthorized()) {
            requestShizukuAuthorization(); return
        }
        remember(command)
        setRunning(true)
        binding.outputText.text = "[${executionMode.displayName}] $ $command\n\n运行中…"
        runningTask = worker.submit {
            val rendered = runCatching {
                val result = ShellCommandExecutor.execute(applicationContext, command, executionMode)
                buildString {
                    append('[').append(executionMode.displayName).append("] $ ").append(command).append("\n\n")
                    if (result.standardOutput.isNotBlank()) append(result.standardOutput).append('\n')
                    if (result.errorOutput.isNotBlank()) append("\n[stderr]\n").append(result.errorOutput).append('\n')
                    append("\n[退出码 ").append(result.exitCode)
                    if (result.timedOut) append("，已超时终止")
                    append("，耗时 ").append(result.durationMillis).append(" ms]")
                }
            }.getOrElse { "[${executionMode.displayName}] $ $command\n\n执行失败：${it.message ?: it.javaClass.simpleName}" }
            runOnUiThread {
                binding.outputText.animate().alpha(0f).setDuration(90).withEndAction {
                    binding.outputText.text = rendered
                    binding.outputText.animate().alpha(1f).setDuration(180).start()
                }.start()
                setRunning(false)
            }
        }
    }

    private fun cancelCommand() {
        runningTask?.cancel(true)
        binding.outputText.append("\n\n[用户已请求停止]")
        setRunning(false)
    }

    private fun setRunning(running: Boolean) {
        binding.runButton.isEnabled = !running
        binding.cancelButton.isEnabled = running
        binding.commandInput.isEnabled = !running
        for (index in 0 until binding.executionModeGroup.childCount) {
            binding.executionModeGroup.getChildAt(index).isEnabled = !running
        }
    }

    private fun remember(command: String) {
        commandHistory.remove(command); commandHistory.add(0, command)
        while (commandHistory.size > MAX_HISTORY) commandHistory.removeAt(commandHistory.lastIndex)
        persistHistory()
    }

    private fun loadHistory() {
        val raw = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(HISTORY_KEY, "").orEmpty()
        commandHistory.clear(); commandHistory.addAll(raw.split(SEPARATOR).filter { it.isNotBlank() })
        updateHistoryAdapter()
    }

    private fun persistHistory() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putString(HISTORY_KEY, commandHistory.joinToString(SEPARATOR)).apply()
        updateHistoryAdapter()
    }

    private fun updateHistoryAdapter() {
        binding.commandInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, commandHistory))
    }

    private fun showCommandHistory() {
        if (commandHistory.isEmpty()) {
            Snackbar.make(binding.root, "暂无历史命令", Snackbar.LENGTH_SHORT).show()
            return
        }
        val entries = ArrayList(commandHistory)
        var dialog: AlertDialog? = null
        lateinit var adapter: CommandHistoryAdapter
        adapter = CommandHistoryAdapter(
            entries,
            onSelect = { command ->
                binding.commandInput.setText(command, false)
                dialog?.dismiss()
            },
            onDelete = { command ->
                commandHistory.removeAll { it == command }
                persistHistory()
                entries.removeAll { it == command }
                adapter.notifyDataSetChanged()
            }
        )
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FakeTerminalActivity)
            this.adapter = adapter
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, dp(320)
            )
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("命令历史（${entries.size}）")
            .setView(list, dp(24), dp(8), dp(24), dp(8))
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空全部") { _, _ -> confirmClearCommandHistory() }
            .show()
    }

    private fun confirmClearCommandHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空全部命令历史？")
            .setMessage("此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                commandHistory.clear()
                persistHistory()
            }
            .show()
    }

    private class CommandHistoryAdapter(
        private val entries: MutableList<String>,
        private val onSelect: (String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<CommandHistoryAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_terminal_history, parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val command = entries[position]
            holder.command.text = command
            holder.itemView.setOnClickListener { onSelect(command) }
            holder.delete.setOnClickListener { onDelete(command) }
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val command: TextView = itemView.findViewById(R.id.historyCommand)
            val delete: ImageButton = itemView.findViewById(R.id.historyDelete)
        }
    }

    private fun loadTemplates() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val raw = preferences.getString(TEMPLATES_KEY, null)
        templates.clear()
        if (raw == null) {
            templates.addAll(defaultTemplates()); saveTemplates(); return
        }
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                templates.add(CommandTemplate(item.getString("id"), item.getString("title"), item.getString("command")))
            }
        }.onFailure { templates.addAll(defaultTemplates()); saveTemplates() }
    }

    private fun saveTemplates() {
        val array = JSONArray()
        templates.forEach { template -> array.put(JSONObject().apply {
            put("id", template.id); put("title", template.title); put("command", template.command)
        }) }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(TEMPLATES_KEY, array.toString()).apply()
    }

    private fun defaultTemplates() = listOf(
        CommandTemplate("id", "当前身份", "id"),
        CommandTemplate("device", "设备型号", "getprop ro.product.manufacturer; getprop ro.product.model"),
        CommandTemplate("android", "Android 版本", "getprop ro.build.version.release; getprop ro.build.version.sdk"),
        CommandTemplate("disk", "存储空间", "df -h /data /storage/emulated/0"),
        CommandTemplate("packages", "第三方应用", "pm list packages -3"),
        CommandTemplate("scene", "激活 Scene（ADB/Shizuku）", "sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh"),
        CommandTemplate("scene_root", "激活 Scene（Root）", "sh /data/user/0/com.omarea.vtools/files/up.sh")
    )

    private fun copyOutput() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText("终端输出", binding.outputText.text)
        )
        Snackbar.make(binding.root, "输出已复制", Snackbar.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)

    override fun onDestroy() {
        runningTask?.cancel(true); worker.shutdownNow()
        runCatching {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        }
        super.onDestroy()
    }

    private data class CommandTemplate(val id: String, val title: String, val command: String)

    companion object {
        private const val PREFERENCES = "terminal_state"
        private const val HISTORY_KEY = "command_history"
        private const val TEMPLATES_KEY = "command_templates"
        private const val MODE_KEY = "execution_mode"
        private const val SEPARATOR = "\u001F"
        private const val MAX_HISTORY = 20
        private const val SHIZUKU_PERMISSION_REQUEST = 4101
    }
}
