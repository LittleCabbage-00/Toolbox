package com.example.cryptoapp.Activities

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Bing.BingWallpaperDay
import com.example.cryptoapp.Bing.BingWallpaperRepository
import com.example.cryptoapp.Bing.Orientation
import com.example.cryptoapp.Bing.SaveResult
import com.example.cryptoapp.databinding.ActivityBingWallpaperDetailBinding
import com.example.cryptoapp.databinding.ItemBingWallpaperPageBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.io.File

/** Bing 壁纸详情：ViewPager2 左右滑动横屏 / 竖屏两张 UHD，每页右下角保存按钮。 */
class BingWallpaperDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityBingWallpaperDetailBinding
    private val repo by lazy { BingWallpaperRepository.get(this) }
    private var day: BingWallpaperDay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBingWallpaperDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val date = intent.getStringExtra(EXTRA_START_DATE)
        val current = date?.let { repo.cachedDay(it) }
        if (current == null) { finish(); return }
        day = current
        binding.toolbar.title = current.copyright.ifBlank { current.startDate }
        binding.detailPager.offscreenPageLimit = 1
        binding.detailPager.adapter = PageAdapter(current)
    }

    private fun screenMaxDim(): Int = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

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

    /** ViewPager2 页面适配器：0 = 横屏 UHD，1 = 竖屏 UHD。 */
    private inner class PageAdapter(private val data: BingWallpaperDay) :
        RecyclerView.Adapter<PageAdapter.PageHolder>() {

        override fun getItemCount() = 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            return PageHolder(
                ItemBingWallpaperPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(position)
        }

        inner class PageHolder(private val binding: ItemBingWallpaperPageBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(position: Int) {
                val orientation = if (position == 0) Orientation.LANDSCAPE else Orientation.PORTRAIT
                val file = if (orientation == Orientation.LANDSCAPE) {
                    data.landscapeUhdFile(repo.cacheDir)
                } else {
                    data.portraitUhdFile(repo.cacheDir)
                }

                binding.pageProgress.visibility = View.VISIBLE
                binding.pageImage.setImageDrawable(null)
                binding.pageSaveButton.text =
                    if (orientation == Orientation.LANDSCAPE) "保存横屏 UHD" else "保存竖屏 UHD"

                binding.pageSaveButton.setOnClickListener {
                    repo.saveDayWithDedup(data, orientation,
                        onDuplicate = { showReplaceDialog(data, orientation) },
                        onResult = { result -> toastSaveResult(result) })
                }

                if (file.exists()) {
                    loadImage(file, binding.pageImage, binding.pageProgress, this)
                } else if (orientation == Orientation.PORTRAIT) {
                    // 竖屏 4K 未生成：先生成再解码显示。
                    repo.ensurePortrait(data) { generated ->
                        if (generated != null && generated.exists() && !isDestroyed) {
                            loadImage(generated, binding.pageImage, binding.pageProgress, this)
                        } else {
                            binding.pageProgress.visibility = View.GONE
                        }
                    }
                } else {
                    binding.pageProgress.visibility = View.GONE
                }
            }
        }
    }

    private fun loadImage(file: File, image: ImageView, progress: LinearProgressIndicator, holder: PageAdapter.PageHolder? = null) {
        repo.decodePreviewAsync(day!!, file, screenMaxDim()) { bitmap ->
            if (holder != null) {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@decodePreviewAsync
            }
            progress.visibility = View.GONE
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
    }

    companion object {
        const val EXTRA_START_DATE = "start_date"
    }
}
