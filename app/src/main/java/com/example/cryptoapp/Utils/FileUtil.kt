package com.example.cryptoapp.Utils

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.net.toFile

object FileUtil {
    @SuppressLint("Range")
    fun uriToFileName(uri: Uri, context: Context): String {
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.toFile().name
            ContentResolver.SCHEME_CONTENT -> {
                val cursor = context.contentResolver.query(uri, null, null, null, null, null)
                cursor?.let {
                    it.moveToFirst()
                    val displayName = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                    cursor.close()
                    displayName
                } ?: "${System.currentTimeMillis()}.${
                    MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(context.contentResolver.getType(uri))
                }}"

            }
            else -> "${System.currentTimeMillis()}.${
                MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(context.contentResolver.getType(uri))
            }}"
        }
    }
}