package com.example.cryptoapp.Utils

import android.os.Build
import androidx.annotation.RequiresApi
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class StringCrypto(_password: String, _useMD5:Boolean=true, _useUrlSafe:Boolean=true) {
    private val useUrlSafe = _useUrlSafe
    //根据参数选择是否转为MD5值
    private val passByteArray = if (_useMD5) MessageDigestUtils.md5(_password) else _password.toByteArray()
    //初始化:加密/解密
    private val keySpec: SecretKeySpec = SecretKeySpec(passByteArray,"AES")

    //加密
    @RequiresApi(Build.VERSION_CODES.O)
    fun encrypt(input:String): String{
        //创建cipher对象
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE,keySpec)
        //加密
        val encrypt = cipher.doFinal(input.toByteArray())
        val encoder = if (useUrlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
        return encoder.encodeToString(encrypt)
    }

    //解密
    @RequiresApi(Build.VERSION_CODES.O)
    fun decrypt(input:String): String {
        //创建cipher对象
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE,keySpec)
        //因为传过来的是Base64加密后的字符串，所以先Base64解密
        val decoder = if (useUrlSafe) Base64.getUrlDecoder() else Base64.getDecoder()
        val encrypt = cipher.doFinal(decoder.decode(input))
        return String(encrypt)
    }
}