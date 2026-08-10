package com.example.cryptoapp.Activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Bing.BingWallpaperDay
import com.example.cryptoapp.Bing.BingWallpaperRepository
import com.example.cryptoapp.Bing.Orientation
import com.example.cryptoapp.Bing.SaveResult
import com.example.cryptoapp.R
import com.example.cryptoapp.databinding.ActivityBingWallpaperGalleryBinding
import com.example.cryptoapp.databinding.ItemBingWallpaperBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** Bing 每日壁纸图库：近 14 天，MaterialCard 列表，预览随后台预取逐步补齐。 */
class BingWallpaperGalleryActivity : BaseActivity() {
    private lateinit var binding: ActivityBingWallpaperGalleryBinding
    private val repo by lazy { BingWallpaperRepository.get(this) }
    private val adapter = GalleryAdapter(::openDetail, ::saveCard)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBingWallpaperGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val span = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        binding.galleryList.layoutManager = GridLayoutManager(this, span)
        binding.galleryList.adapter = adapter

        // 某天 UHD 后台预取完成 → 只刷新对应卡片，不重启滚动。
        repo.setPrefetchListener { date ->
            val index = adapter.currentList.indexOfFirst { it.startDate == date }
            if (index >= 0) adapter.notifyItemChanged(index)
        }
        load()
    }

    override fun onDestroy() {
        repo.setPrefetchListener(null)
        super.onDestroy()
    }

    private fun load() {
        val days = repo.allDays()
        if (days != null) show(days)
        else repo.fetchDays { show(it) }
    }

    private fun show(days: List<BingWallpaperDay>) {
        if (isFinishing || isDestroyed) return
        adapter.submitList(days)
        binding.emptyView.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDetail(day: BingWallpaperDay) {
        startActivity(Intent(this, BingWallpaperDetailActivity::class.java)
            .putExtra(BingWallpaperDetailActivity.EXTRA_START_DATE, day.startDate))
    }

    /** 卡片"UHD"保存按钮：已保存过 → 弹窗询问替换；否则直接保存。 */
    private fun saveCard(day: BingWallpaperDay) {
        repo.saveDayWithDedup(day, Orientation.LANDSCAPE,
            onDuplicate = { showReplaceDialog(day, Orientation.LANDSCAPE) },
            onResult = { result -> toastSaveResult(result) })
    }

    private fun showReplaceDialog(day: BingWallpaperDay, orientation: Orientation) {
        MaterialAlertDialogBuilder(this)
            .setTitle("图片已保存过")
            .setMessage("该壁纸（${day.startDate}）已经保存到 Pictures/Toolbox，是否替换为新版本？")
            .setNegativeButton("取消", null)
            .setPositiveButton("替换") { _, _ ->
                repo.saveDayForce(day, orientation) { result -> toastSaveResult(result) }
            }
            .show()
    }

    private fun toastSaveResult(result: SaveResult) {
        val text = when (result) {
            SaveResult.SAVED -> "已保存到 Pictures/Toolbox"
            SaveResult.ALREADY_SAVED -> "已保存过"
            SaveResult.FAILED -> "保存失败"
        }
        if (!isFinishing && !isDestroyed) {
            Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
        }
    }

    private class GalleryAdapter(
        private val onOpen: (BingWallpaperDay) -> Unit,
        private val onSave: (BingWallpaperDay) -> Unit
    ) : ListAdapter<BingWallpaperDay, GalleryAdapter.Holder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemBingWallpaperBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val binding: ItemBingWallpaperBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(day: BingWallpaperDay) = with(binding) {
                dateText.text = formatDate(day.startDate)
                copyrightText.text = day.copyright

                val file = day.landscapeUhdFile(BingWallpaperRepository.get(itemView.context).cacheDir)
                if (file.exists()) {
                    placeholder.visibility = View.GONE
                    BingWallpaperRepository.get(itemView.context)
                        .decodePreviewAsync(day, file, 480) { bitmap ->
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION && pos == absoluteAdapterPosition && bitmap != null) {
                                preview.setImageBitmap(bitmap)
                            }
                        }
                } else {
                    placeholder.visibility = View.VISIBLE
                    preview.setImageDrawable(null)
                }

                saveUhdButton.setOnClickListener { onSave(day) }
                root.setOnClickListener { onOpen(day) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<BingWallpaperDay>() {
            override fun areItemsTheSame(old: BingWallpaperDay, new: BingWallpaperDay) =
                old.startDate == new.startDate
            override fun areContentsTheSame(old: BingWallpaperDay, new: BingWallpaperDay) =
                old == new
        }
    }

    companion object {
        private fun formatDate(startDate: String): String {
            // startDate 形如 YYYYMMDD → DD.MM.YYYY
            return if (startDate.length == 8) {
                "${startDate.takeLast(2)}.${startDate.substring(4, 6)}.${startDate.take(4)}"
            } else startDate
        }
    }
}
