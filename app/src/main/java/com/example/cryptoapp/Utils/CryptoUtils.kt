package com.example.cryptoapp.Utils

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 文件 MD5 校验工具。 */
object CryptoUtils {
    fun cmpFile(f1: File, f2: File): Boolean {
        val md5Digest = MessageDigest.getInstance("MD5")
        val checksum1 = getFileChecksum(md5Digest, f1)
        val checksum2 = getFileChecksum(md5Digest, f2)
        return checksum1 == checksum2
    }

    private fun getFileChecksum(digest: MessageDigest, file: File): String {
        FileInputStream(file).use { fis ->
            val byteArray = ByteArray(1024)
            var bytesCount = fis.read(byteArray)
            while (bytesCount != -1) {
                digest.update(byteArray, 0, bytesCount)
                bytesCount = fis.read(byteArray)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder()
        for (aByte in bytes) {
            sb.append(Integer.toString((aByte.toInt() and 0xff) + 0x100, 16).substring(1))
        }
        return sb.toString()
    }
}
