package com.example.cryptoapp.Activities

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Browser.BrowserDownloadService
import com.example.cryptoapp.Browser.BrowserDownloadStore
import com.example.cryptoapp.R
import com.example.cryptoapp.databinding.ActivityBrowserDownloadsBinding
import com.example.cryptoapp.databinding.ItemBrowserDownloadBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

/** 应用内下载管理页：显示持久化记录，并复用通知中的暂停、继续和取消操作。 */
class BrowserDownloadsActivity : BaseActivity() {
    private lateinit var binding: ActivityBrowserDownloadsBinding
    private lateinit var store: BrowserDownloadStore
    private val adapter = DownloadAdapter(::openRecord, ::controlDownload, ::confirmDeleteRecord)
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BrowserDownloadStore(this)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.downloadList.layoutManager = LinearLayoutManager(this)
        binding.downloadList.adapter = adapter
        binding.clearFinishedButton.setOnClickListener { confirmClearFinished() }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, updateReceiver, IntentFilter(BrowserDownloadService.ACTION_DOWNLOAD_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onStop() {
        unregisterReceiver(updateReceiver)
        super.onStop()
    }

    private fun refresh() {
        val records = store.getAll()
        adapter.submitList(records)
        binding.emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        val hasFinished = records.any {
            it.status != BrowserDownloadStore.STATUS_DOWNLOADING &&
                it.status != BrowserDownloadStore.STATUS_PAUSED
        }
        binding.clearFinishedButton.visibility = if (hasFinished) View.VISIBLE else View.GONE
    }

    private fun controlDownload(record: BrowserDownloadStore.DownloadRecord) {
        val action = when (record.status) {
            BrowserDownloadStore.STATUS_DOWNLOADING -> BrowserDownloadService.ACTION_PAUSE
            BrowserDownloadStore.STATUS_PAUSED -> BrowserDownloadService.ACTION_RESUME
            else -> BrowserDownloadService.ACTION_CANCEL
        }
        startService(
            Intent(this, BrowserDownloadService::class.java).setAction(action)
                .putExtra(BrowserDownloadService.EXTRA_DOWNLOAD_ID, record.id)
        )
    }

    private fun openRecord(record: BrowserDownloadStore.DownloadRecord) {
        if (record.status != BrowserDownloadStore.STATUS_COMPLETED || record.localUri.isBlank()) return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(record.localUri), record.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, "没有应用可以打开这种文件", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearFinished() {
        MaterialAlertDialogBuilder(this).setTitle("清除已结束的下载记录？")
            .setMessage("只删除记录，不会删除 Download/Toolbox 中已经下载的文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ -> store.clearFinished(); refresh() }
            .show()
    }

    private fun confirmDeleteRecord(record: BrowserDownloadStore.DownloadRecord) {
        val checkBox = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "同时删除 Download/Toolbox 中的文件"
            setPadding(24.dp(), 8.dp(), 24.dp(), 8.dp())
            isEnabled = record.localUri.isNotBlank()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除这条下载任务？")
            .setMessage("未勾选时只删除应用内记录。")
            .setView(checkBox)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (checkBox.isChecked && record.localUri.isNotBlank()) {
                    runCatching { contentResolver.delete(Uri.parse(record.localUri), null, null) }
                }
                store.delete(record.id)
                refresh()
                Snackbar.make(binding.root,
                    if (checkBox.isChecked) "文件和记录已删除" else "记录已删除",
                    Snackbar.LENGTH_SHORT).show()
            }.show()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}

private class DownloadAdapter(
    private val onOpen: (BrowserDownloadStore.DownloadRecord) -> Unit,
    private val onControl: (BrowserDownloadStore.DownloadRecord) -> Unit,
    private val onDelete: (BrowserDownloadStore.DownloadRecord) -> Unit
) : ListAdapter<BrowserDownloadStore.DownloadRecord, DownloadAdapter.Holder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemBrowserDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemBrowserDownloadBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: BrowserDownloadStore.DownloadRecord) = with(binding) {
            fileName.text = record.fileName
            val active = record.status == BrowserDownloadStore.STATUS_DOWNLOADING ||
                record.status == BrowserDownloadStore.STATUS_PAUSED
            statusText.text = statusLabel(record)
            pauseResumeButton.visibility = if (active) View.VISIBLE else View.GONE
            cancelButton.visibility = if (active) View.VISIBLE else View.GONE
            deleteButton.visibility = if (active) View.GONE else View.VISIBLE
            pauseResumeButton.setIconResource(
                if (record.status == BrowserDownloadStore.STATUS_PAUSED) R.drawable.ic_play_24
                else R.drawable.ic_pause_24
            )
            pauseResumeButton.contentDescription =
                if (record.status == BrowserDownloadStore.STATUS_PAUSED) "继续下载" else "暂停下载"
            progressIndicator.isIndeterminate = active && record.total <= 0
            progressIndicator.progress = when {
                record.status == BrowserDownloadStore.STATUS_COMPLETED -> 100
                record.total > 0 -> (record.downloaded * 100 / record.total).toInt().coerceIn(0, 100)
                else -> 0
            }
            detailText.text = buildString {
                val ffmpegIsPreparing = active && record.downloaded == 0L && record.error.isNotBlank()
                if (!ffmpegIsPreparing) {
                    append(formatBytes(record.downloaded))
                    if (record.total > 0) append(" / ").append(formatBytes(record.total))
                    append("  ·  ").append(record.mimeType)
                }
                if (record.error.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(record.error)
                }
            }
            detailText.maxLines = if (record.status == BrowserDownloadStore.STATUS_FAILED) 8 else 2
            pauseResumeButton.setOnClickListener { onControl(record) }
            cancelButton.setOnClickListener {
                itemView.context.startService(
                    Intent(itemView.context, BrowserDownloadService::class.java)
                        .setAction(BrowserDownloadService.ACTION_CANCEL)
                        .putExtra(BrowserDownloadService.EXTRA_DOWNLOAD_ID, record.id)
                )
            }
            deleteButton.setOnClickListener { onDelete(record) }
            root.setOnClickListener { onOpen(record) }
        }

        private fun statusLabel(record: BrowserDownloadStore.DownloadRecord) = when (record.status) {
            BrowserDownloadStore.STATUS_DOWNLOADING -> "正在下载"
            BrowserDownloadStore.STATUS_PAUSED -> "已暂停"
            BrowserDownloadStore.STATUS_COMPLETED -> "下载完成 · 点击打开"
            BrowserDownloadStore.STATUS_CANCELLED -> "已取消"
            else -> "下载失败"
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            var value = bytes.toDouble()
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
            return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
        }
    }

    private object Diff : DiffUtil.ItemCallback<BrowserDownloadStore.DownloadRecord>() {
        override fun areItemsTheSame(old: BrowserDownloadStore.DownloadRecord, new: BrowserDownloadStore.DownloadRecord) = old.id == new.id
        override fun areContentsTheSame(old: BrowserDownloadStore.DownloadRecord, new: BrowserDownloadStore.DownloadRecord) =
            old.status == new.status && old.downloaded == new.downloaded && old.total == new.total &&
                old.fileName == new.fileName && old.error == new.error
    }
}
