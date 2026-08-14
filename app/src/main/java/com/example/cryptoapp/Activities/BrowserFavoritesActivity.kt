package com.example.cryptoapp.Activities

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Browser.BrowserFavorite
import com.example.cryptoapp.Browser.BrowserFavoriteFolder
import com.example.cryptoapp.Browser.BrowserFavoriteStore
import com.example.cryptoapp.databinding.ActivityBrowserHistoryBinding
import com.example.cryptoapp.databinding.ItemBrowserHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.button.MaterialButton

/** 支持编辑、文件夹归类与搜索的网页收藏夹。 */
class BrowserFavoritesActivity : BaseActivity() {
    private lateinit var binding: ActivityBrowserHistoryBinding
    private lateinit var store: BrowserFavoriteStore
    private val adapter = FavoriteAdapter(::open, ::edit, ::delete) { orderedIds -> store.reorder(orderedIds) }
    private lateinit var folderBar: LinearLayout
    private var selectedFolderId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = BrowserFavoriteStore(this)
        binding.toolbar.title = "网页收藏夹"
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.menu.add("新建文件夹").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        binding.toolbar.setOnMenuItemClickListener { createFolder(); true }
        binding.searchInput.hint = "搜索收藏或网址"
        addFolderNavigation()
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(view: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
                adapter.move(source.bindingAdapterPosition, target.bindingAdapterPosition)
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(holder, actionState)
                holder?.itemView?.alpha = if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 0.76f else 1f
            }
            override fun clearView(view: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(view, holder)
                holder.itemView.alpha = 1f
                adapter.persistOrder()
            }
            override fun isLongPressDragEnabled() = true
        }).attachToRecyclerView(binding.historyList)
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
        binding.historyList.post { message("长按书签后上下拖动即可排序") }
    }

    private fun refresh() {
        val values = store.search(binding.searchInput.text?.toString().orEmpty())
            .filter { it.folderId == selectedFolderId }
        adapter.submitList(values)
        binding.emptyView.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyView.text = if (selectedFolderId == null) "书签栏还没有收藏网页" else "此文件夹还没有收藏"
        binding.clearAllButton.isEnabled = store.getAll().isNotEmpty()
    }

    /** Chrome 式书签层级导航：根目录与每个文件夹是平级入口，列表只展示当前层。 */
    private fun addFolderNavigation() {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        }
        folderBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        scroll.addView(folderBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        (binding.root as LinearLayout).addView(scroll, 2)
        renderFolderNavigation()
    }

    private fun renderFolderNavigation() {
        if (!::folderBar.isInitialized) return
        folderBar.removeAllViews()
        addFolderTab("书签栏", null)
        store.getFolders().forEach { folder -> addFolderTab(folder.name, folder.id) }
    }

    private fun addFolderTab(name: String, folderId: Long?) {
        val selected = folderId == selectedFolderId
        val tab = MaterialButton(this).apply {
            text = name; isCheckable = true; isChecked = selected; minWidth = 0
            setIconResource(if (folderId == null) com.example.cryptoapp.R.drawable.ic_bookmark_24 else com.example.cryptoapp.R.drawable.file_folder)
            iconSize = dp(18); iconPadding = dp(4)
            contentDescription = if (folderId == null) "书签栏根目录" else "书签文件夹：$name"
            setOnClickListener { selectedFolderId = folderId; renderFolderNavigation(); refresh() }
        }
        folderBar.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginEnd = dp(6)
        })
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "例如：常用网站"; setSingleLine() }
        MaterialAlertDialogBuilder(this).setTitle("新建书签文件夹").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("创建") { _, _ ->
                val folder = store.addFolder(input.text?.toString().orEmpty())
                if (folder == null) message("文件夹名称为空或已存在") else {
                    selectedFolderId = folder.id
                    renderFolderNavigation(); refresh(); message("已创建文件夹")
                }
            }.show()
    }

    private fun edit(item: BrowserFavorite) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        val title = EditText(this).apply { hint = "名称"; setText(item.title); setSingleLine() }
        val url = EditText(this).apply { hint = "网址"; setText(item.url); setSingleLine() }
        val folders = listOf<BrowserFavoriteFolder?>(null) + store.getFolders()
        val folderNames = folders.map { it?.name ?: "不归类（根目录）" }
        val folder = Spinner(this).apply { adapter = ArrayAdapter(this@BrowserFavoritesActivity, android.R.layout.simple_spinner_dropdown_item, folderNames) }
        folder.setSelection(folders.indexOfFirst { it?.id == item.folderId }.coerceAtLeast(0))
        content.addView(title); content.addView(url); content.addView(folder)
        MaterialAlertDialogBuilder(this).setTitle("编辑收藏") .setView(content)
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                runCatching { store.update(item.id, title.text.toString(), url.text.toString(), folders[folder.selectedItemPosition]?.id) }
                    .onSuccess { refresh(); message("收藏已更新") }.onFailure { message(it.message ?: "保存失败") }
            }.show()
    }

    private fun open(item: BrowserFavorite) {
        // 不使用 Activity Result：部分国产 ROM 在底部抽屉关闭与结果分发相邻时会提前恢复父页面。
        // singleTop 将已有浏览器带到前台，并把网址交给 onNewIntent 直接加载。
        startActivity(Intent(this, SearchActivity::class.java)
            .putExtra("web_address", item.url)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    private fun delete(item: BrowserFavorite) { store.delete(item.id); refresh() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun message(value: String) = Snackbar.make(binding.root, value, Snackbar.LENGTH_SHORT).show()
}

private class FavoriteAdapter(
    private val onOpen: (BrowserFavorite) -> Unit, private val onEdit: (BrowserFavorite) -> Unit,
    private val onDelete: (BrowserFavorite) -> Unit,
    private val onOrderChanged: (List<Long>) -> Unit
) : ListAdapter<BrowserFavorite, FavoriteAdapter.Holder>(object : DiffUtil.ItemCallback<BrowserFavorite>() {
    override fun areItemsTheSame(a: BrowserFavorite, b: BrowserFavorite) = a.id == b.id
    override fun areContentsTheSame(a: BrowserFavorite, b: BrowserFavorite) = a == b
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemBrowserHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    fun move(from: Int, to: Int): Boolean {
        if (from !in currentList.indices || to !in currentList.indices || from == to) return false
        val reordered = currentList.toMutableList()
        val item = reordered.removeAt(from); reordered.add(to, item)
        submitList(reordered)
        return true
    }
    fun persistOrder() = onOrderChanged(currentList.map { it.id })
    inner class Holder(private val binding: ItemBrowserHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BrowserFavorite) = with(binding) {
            titleText.text = item.title; urlText.text = item.url
            timeText.text = "${if (item.folderId == null) "根目录" else "文件夹内收藏"} · 收藏"
            editButton.visibility = View.VISIBLE; root.setOnClickListener { onOpen(item) }
            editButton.setOnClickListener { onEdit(item) }; deleteButton.setOnClickListener { onDelete(item) }
        }
    }
}
