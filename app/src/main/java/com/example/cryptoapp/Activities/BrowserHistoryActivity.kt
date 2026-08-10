package com.example.cryptoapp.Activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Browser.BrowserHistoryEntry
import com.example.cryptoapp.Browser.BrowserHistoryStore
import com.example.cryptoapp.databinding.ActivityBrowserHistoryBinding
import com.example.cryptoapp.databinding.ItemBrowserHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.util.Date

/** 浏览器历史记录独立页面：支持搜索、打开、单条删除和清空。 */
class BrowserHistoryActivity : BaseActivity() {
    private lateinit var binding: ActivityBrowserHistoryBinding
    private lateinit var store: BrowserHistoryStore
    private val adapter = HistoryAdapter(::openEntry, ::deleteEntry)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BrowserHistoryStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        // RecyclerView 没有 LayoutManager 时即使仓库有数据也不会创建或显示任何条目。
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.historyList.setHasFixedSize(true)
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.clearAllButton.setOnClickListener { confirmClearAll() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val entries = store.search(binding.searchInput.text?.toString().orEmpty())
        adapter.submitList(entries)
        binding.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.clearAllButton.isEnabled = store.getAll().isNotEmpty()
    }

    private fun openEntry(entry: BrowserHistoryEntry) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_URL, entry.url))
        finish()
    }

    private fun deleteEntry(entry: BrowserHistoryEntry) {
        store.delete(entry.id)
        refresh()
        Snackbar.make(binding.root, "已删除一条历史记录", Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空历史记录？")
            .setMessage("此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                store.clear()
                refresh()
            }
            .show()
    }

    companion object {
        const val EXTRA_SELECTED_URL = "selected_history_url"
    }
}

private class HistoryAdapter(
    private val onOpen: (BrowserHistoryEntry) -> Unit,
    private val onDelete: (BrowserHistoryEntry) -> Unit
) : ListAdapter<BrowserHistoryEntry, HistoryAdapter.ViewHolder>(DiffCallback) {
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBrowserHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemBrowserHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: BrowserHistoryEntry) {
            binding.titleText.text = entry.title
            binding.urlText.text = entry.url
            binding.timeText.text = dateFormat.format(Date(entry.visitedAt))
            binding.root.setOnClickListener { onOpen(entry) }
            binding.deleteButton.setOnClickListener { onDelete(entry) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<BrowserHistoryEntry>() {
        override fun areItemsTheSame(oldItem: BrowserHistoryEntry, newItem: BrowserHistoryEntry) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: BrowserHistoryEntry, newItem: BrowserHistoryEntry) =
            oldItem == newItem
    }
}
