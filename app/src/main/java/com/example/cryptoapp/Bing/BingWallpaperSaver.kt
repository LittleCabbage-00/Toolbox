package com.example.cryptoapp.Bing

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 保存结果：是否新保存、是否已存在（供调用方决定弹窗）、是否失败。 */
enum class SaveResult { SAVED, ALREADY_SAVED, FAILED }

/**
 * 把 Bing 壁纸保存到系统图片目录 Pictures/Toolbox，并处理「已保存过 → 替换/取消」去重。
 *
 * 去重/替换要点：
 * - API ≥ 29：查 MediaStore.Images（DISPLAY_NAME + RELATIVE_PATH + IS_PENDING=0）判定已存在。
 *   替换时**必须用旧行 contentUri 覆盖写**——重新 insert 同名文件会被系统改成 "name (1).jpg"，
 *   变成两个文件而非覆盖。
 * - API < 29：查 File(Pictures/Toolbox/文件名).exists()，替换用 FileOutputStream 覆盖同名文件。
 *
 * 保存写 IS_PENDING=1 的半成品，写完后清 0，失败删除半成品。
 */
object BingWallpaperSaver {
    private const val PREF_KEY_SAVED = "bing_saved"
    private const val MIME_JPEG = "image/jpeg"

    /** 目标目录：Pictures/Toolbox。 */
    private fun relativeDir() = Environment.DIRECTORY_PICTURES + "/Toolbox"

    /** 确定性保存文件名，去重与替换都以它为准。 */
    fun displayName(day: BingWallpaperDay, orientation: Orientation): String {
        val suffix = if (orientation == Orientation.LANDSCAPE) "UHD" else "Portrait"
        return "Bing_${day.startDate}_$suffix.jpg"
    }

    /**
     * 已保存判定。API≥29 查 MediaStore 完成行；API<29 查文件存在。
     * 返回已存在文件的 contentUri（API<29 为 null），不存在返回 null。
     */
    fun findExisting(context: Context, day: BingWallpaperDay, orientation: Orientation): Uri? {
        val name = displayName(day, orientation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?" +
                    " AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?" +
                    " AND ${MediaStore.Images.Media.IS_PENDING} = 0"
            val args = arrayOf(name, relativeDir() + "/")
            context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID),
                selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    return ContentUris.withAppendedId(collection, id)
                }
            }
            return null
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Toolbox")
        return if (File(dir, name).exists()) Uri.EMPTY else null
    }

    /**
     * 写入新版本。force 为 true 时直接覆盖已存在文件（替换旧版）；false 时若已存在则不写。
     * 返回是否真正写入。
     */
    fun write(context: Context, day: BingWallpaperDay, orientation: Orientation,
              source: File, force: Boolean): SaveResult {
        val name = displayName(day, orientation)
        val existing = findExisting(context, day, orientation)
        if (existing != null && !force) return SaveResult.ALREADY_SAVED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(context, name, source, existing)
        } else {
            writeLegacy(context, name, source, existing != null)
        }
    }

    private fun writeMediaStore(context: Context, name: String, source: File,
                                existing: Uri?): SaveResult {
        val resolver = context.contentResolver
        val uri: Uri = if (existing != null) {
            existing
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_JPEG)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativeDir())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return SaveResult.FAILED
        }
        return try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: return SaveResult.FAILED
            // 替换旧版本时行已存在，无需再改 IS_PENDING；新插入的行写完清 0 才对外可见。
            if (existing == null) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            SaveResult.SAVED
        } catch (e: Exception) {
            if (existing == null) {
                runCatching { resolver.delete(uri, null, null) }
            }
            SaveResult.FAILED
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(context: Context, name: String, source: File,
                            overwrite: Boolean): SaveResult {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Toolbox")
        if (!dir.exists() && !dir.mkdirs()) return SaveResult.FAILED
        val target = File(dir, name)
        if (target.exists() && !overwrite) return SaveResult.ALREADY_SAVED
        return try {
            source.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
            }
            SaveResult.SAVED
        } catch (e: IOException) {
            SaveResult.FAILED
        }
    }

    /**
     * 记录（或清除）某一天某个方向已保存到 MediaStore 的标记。
     * 用于自动保存的静默去重，避免重复查询 MediaStore。
     */
    fun markSaved(context: Context, day: BingWallpaperDay, orientation: Orientation, saved: Boolean) {
        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        val key = day.savedPrefKey(orientation)
        // getStringSet 返回的是不可持久化的引用集合，必须复制到新 HashSet 再 put 回去。
        val set = HashSet(prefs.getStringSet(PREF_KEY_SAVED, emptySet()))
        if (saved) set.add(key) else set.remove(key)
        prefs.edit().putStringSet(PREF_KEY_SAVED, set).apply()
    }

    fun isMarkedSaved(context: Context, day: BingWallpaperDay, orientation: Orientation): Boolean {
        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        return day.savedPrefKey(orientation) in (prefs.getStringSet(PREF_KEY_SAVED, emptySet()) ?: emptySet())
    }
}
