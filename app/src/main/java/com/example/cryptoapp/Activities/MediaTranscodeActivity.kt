package com.example.cryptoapp.Activities

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.example.cryptoapp.Base.BaseActivity
import com.example.cryptoapp.Browser.LocalMediaProxy
import com.example.cryptoapp.Utils.FileUtil
import com.example.cryptoapp.databinding.ActivityMediaTranscodeBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.concurrent.Executors
import okhttp3.OkHttpClient

/**
 * FFmpeg 音视频转换工具。SAF 文件先复制到应用缓存，转换完成后再写回用户选择的 URI；
 * 网页 m3u8/媒体 URL 由 FFmpeg 直接读取，避免把整段流预先载入内存。
 */
class MediaTranscodeActivity : BaseActivity() {
    private lateinit var binding: ActivityMediaTranscodeBinding
    private var sourceUri: Uri? = null
    private var sourceUrl: String? = null
    private var referer: String? = null
    private var destinationUri: Uri? = null
    private var format = OutputFormat.MP4
    private var activeSession: FFmpegSession? = null
    private var activeProxy: LocalMediaProxy? = null
    private val ioWorker = Executors.newSingleThreadExecutor()

    private val openSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        sourceUri = uri; sourceUrl = null
        binding.sourceValue.text = FileUtil.uriToFileName(uri, this)
    }
    private val createOutput = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@registerForActivityResult
        destinationUri = uri
        startConversion()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaTranscodeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)
        referer = intent.getStringExtra(EXTRA_REFERER)
        binding.sourceValue.text = sourceUrl ?: "尚未选择媒体文件"
        configureFormats()
        binding.chooseSourceButton.setOnClickListener { openSource.launch(arrayOf("video/*", "audio/*", "application/vnd.apple.mpegurl", "*/*")) }
        binding.convertButton.setOnClickListener {
            if (sourceUri == null && sourceUrl.isNullOrBlank()) return@setOnClickListener message("请先选择文件或从浏览器资源嗅探传入网址")
            createOutput.launch("toolbox-${System.currentTimeMillis()}.${format.extension}")
        }
        binding.cancelButton.setOnClickListener {
            activeSession?.cancel()
            activeProxy?.close(); activeProxy = null
            setBusy(false)
            message("已请求停止转换")
        }
    }

    private fun configureFormats() {
        val formats = OutputFormat.values()
        binding.formatInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, formats.map { it.title }))
        binding.formatInput.setText(format.title, false)
        binding.formatInput.setOnItemClickListener { _, _, position, _ -> format = formats[position] }
    }

    private fun startConversion() {
        val outputUri = destinationUri ?: return
        setBusy(true)
        binding.logText.text = "正在准备输入…"
        ioWorker.execute {
            val remoteUrl = sourceUrl
            val input = if (remoteUrl != null) runCatching {
                LocalMediaProxy(HTTP_CLIENT, "Mozilla/5.0 (Android) Toolbox/3.0", "", referer.orEmpty())
                    .also { activeProxy = it }.urlFor(remoteUrl)
            }.getOrNull() else copySourceToCache(sourceUri ?: return@execute)?.absolutePath
            if (input == null) {
                runOnUiThread { setBusy(false); message("无法读取源文件") }
                return@execute
            }
            val output = File.createTempFile("toolbox-media-", ".${format.extension}", cacheDir)
            val command = buildCommand(input, output.absolutePath)
            runOnUiThread { binding.logText.text = "FFmpeg 命令已启动\n输出格式：${format.title}" }
            activeSession = FFmpegKit.executeAsync(command, { session ->
                activeProxy?.close(); activeProxy = null
                val converted = ReturnCode.isSuccess(session.returnCode)
                val saved = if (converted) runCatching {
                    contentResolver.openOutputStream(outputUri, "w")!!.use { target -> output.inputStream().use { it.copyTo(target) } }
                }.isSuccess else false
                output.delete()
                if (sourceUrl == null) File(input).delete()
                runOnUiThread {
                    setBusy(false)
                    binding.logText.text = if (saved) "转换完成，文件已保存" else
                        "转换失败\n${session.failStackTrace.orEmpty()}\n${session.output.orEmpty().takeLast(3000)}"
                    message(if (saved) "转换完成" else "转换或保存失败，请查看日志")
                }
            }, { log -> runOnUiThread { binding.logText.text = log.message.takeLast(3000) } }, { statistics ->
                runOnUiThread { binding.progressText.text = "已处理 ${statistics.time / 1000.0} 秒" }
            })
        }
    }

    private fun copySourceToCache(uri: Uri): File? = runCatching {
        File.createTempFile("toolbox-source-", ".media", cacheDir).also { file ->
            contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use(input::copyTo) }
        }
    }.getOrNull()

    private fun buildCommand(input: String, output: String): String {
        val codecs = when (format) {
            OutputFormat.MP4 -> "-map 0 -c copy -bsf:a aac_adtstoasc -movflags +faststart"
            OutputFormat.MKV -> "-map 0 -c copy"
            OutputFormat.MP3 -> "-vn -c:a libmp3lame -q:a 2"
            OutputFormat.M4A -> "-vn -c:a aac -b:a 192k"
            OutputFormat.FLAC -> "-vn -c:a flac"
        }
        return "-y -i ${q(input)} $codecs ${q(output)}"
    }

    private fun q(value: String) = "'${value.replace("'", "'\\''")}'"
    private fun setBusy(value: Boolean) {
        binding.progressBar.visibility = if (value) View.VISIBLE else View.GONE
        binding.cancelButton.visibility = if (value) View.VISIBLE else View.GONE
        binding.convertButton.isEnabled = !value
        binding.chooseSourceButton.isEnabled = !value
    }
    private fun message(value: String) = Snackbar.make(binding.root, value, Snackbar.LENGTH_LONG).show()

    override fun onDestroy() {
        activeSession?.cancel()
        activeProxy?.close()
        ioWorker.shutdownNow()
        super.onDestroy()
    }

    enum class OutputFormat(val title: String, val extension: String) {
        MP4("视频 MP4（优先无损封装）", "mp4"), MKV("视频 MKV（优先无损封装）", "mkv"),
        MP3("音频 MP3", "mp3"), M4A("音频 M4A / AAC", "m4a"), FLAC("音频 FLAC", "flac")
    }

    companion object {
        private val HTTP_CLIENT = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
        const val EXTRA_SOURCE_URL = "source_url"
        const val EXTRA_REFERER = "referer"
    }
}
