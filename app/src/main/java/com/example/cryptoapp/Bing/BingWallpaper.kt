package com.example.cryptoapp.Bing

import java.io.File

/** 壁纸方向：横屏（原生 16:9 UHD）与竖屏（由横屏中心裁剪放大生成的 9:16）。 */
enum class Orientation { LANDSCAPE, PORTRAIT }

/**
 * Bing 每日一图的单日记录。所有 URL 与本地文件名都由 API 字段派生，不单独存储。
 * startDate 形如 YYYYMMDD，是去重 key 与缓存文件名主干。
 */
data class BingWallpaperDay(
    val startDate: String,
    val title: String,
    val copyright: String,
    val copyrightLink: String,
    val landscapeUhdUrl: String,
    val landscapePreviewUrl: String,
    val portraitPreviewUrl: String
) {
    /** 横屏 4K 缓存：3840×2160 原图。 */
    fun landscapeUhdFile(dir: File) = File(dir, "${startDate}_UHD.jpg")

    /** 竖屏 4K 缓存：由横屏中心裁剪放大生成的 2160×3840。 */
    fun portraitUhdFile(dir: File) = File(dir, "${startDate}_portrait.jpg")

    /** 已保存去重 key（写入 config prefs）。 */
    fun savedPrefKey(orientation: Orientation) = "${startDate}_${orientation.name}"
}

/** Bing 图片服务 URL 构建。请求需带 User-Agent 避免 403。 */
object BingUrls {
    private const val HOST = "https://cn.bing.com"
    private const val API = "https://cn.bing.com/HPImageArchive.aspx?format=js&idx=%d&n=%d&mkt=zh-CN"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** 官方 JSON 元数据接口；idx=0 为今天，n=8 一次最多 8 天。 */
    fun api(idx: Int, n: Int) = String.format(API, idx, n)

    /** 每个请求都带 UA 的 Request 便捷构造。 */
    fun request(url: String) = okhttp3.Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .build()

    /** 相对路径（含 1920x1080）补全为绝对 URL（默认 1920×1080 预览）。 */
    fun absolute(path: String) = HOST + path

    /** 横屏 UHD：1920x1080 → UHD，得到 3840×2160 原图。 */
    fun landscapeUhd(path: String) = HOST + path.replace("1920x1080", "UHD")

    /** 竖屏预览（Bing 原生最大竖屏 1080×1920）。 */
    fun portraitPreview(path: String) = HOST + path.replace("1920x1080", "1080x1920")
}
