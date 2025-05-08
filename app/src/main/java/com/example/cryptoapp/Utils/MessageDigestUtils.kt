package com.example.cryptoapp.Utils

import java.security.MessageDigest

object MessageDigestUtils {
    /**
     * md5加密字符串
     */
    fun md5(str: String): ByteArray? {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(str.toByteArray())
    }
}