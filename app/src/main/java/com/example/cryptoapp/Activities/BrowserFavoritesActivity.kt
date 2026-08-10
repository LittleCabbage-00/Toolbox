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
import com.example.cryptoapp.Browser.BrowserFavorite
import com.example.cryptoapp.Browser.BrowserFavoriteStore
import com.example.cryptoapp.databinding.ActivityBrowserHistoryBinding
import com.example.cryptoapp.databinding.ItemBrowserHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 收藏夹页面复用历史记录的可搜索 Material 列表结构。 */
class BrowserFavoritesActivity : BaseActivity() {
    private lateinit var binding: ActivityBrowserHistoryBinding
    private lateinit var store: BrowserFavoriteStore
    private val adapter = FavoriteAdapter(::open, ::delete)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BrowserFavoriteStore(this)
        binding.toolbar.title = "网页收藏夹"
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.searchInput.hint = "搜索收藏"
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.clearAllButton.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("清空全部收藏？")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> store.clear(); refresh() }.show()
        }
        refresh()
    }

    private fun refresh() {
        val values = store.search(binding.searchInput.text?.toString().orEmpty())
        adapter.submitList(values)
        binding.emptyView.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyView.text = "还没有收藏网页"
        binding.clearAllButton.isEnabled = store.getAll().isNotEmpty()
    }

    private fun open(item: BrowserFavorite) {
        setResult(Activity.RESULT_OK, Intent().putExtra(BrowserHistoryActivity.EXTRA_SELECTED_URL, item.url)); finish()
    }
    private fun delete(item: BrowserFavorite) { store.delete(item.id); refresh() }
}

private class FavoriteAdapter(
    private val onOpen: (BrowserFavorite) -> Unit,
    private val onDelete: (BrowserFavorite) -> Unit
) : ListAdapter<BrowserFavorite, FavoriteAdapter.Holder>(object : DiffUtil.ItemCallback<BrowserFavorite>() {
    override fun areItemsTheSame(a: BrowserFavorite, b: BrowserFavorite) = a.id == b.id
    override fun areContentsTheSame(a: BrowserFavorite, b: BrowserFavorite) = a == b
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemBrowserHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    inner class Holder(private val binding: ItemBrowserHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BrowserFavorite) = with(binding) {
            titleText.text = item.title; urlText.text = item.url; timeText.text = "收藏"
            root.setOnClickListener { onOpen(item) }; deleteButton.setOnClickListener { onDelete(item) }
        }
    }
}
