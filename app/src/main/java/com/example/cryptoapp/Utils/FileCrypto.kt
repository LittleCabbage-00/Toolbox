package com.example.cryptoapp

import android.util.Log
import com.example.cryptoapp.Utils.MessageDigestUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class FileCrypto(_password: String, _useMD5:Boolean=true, _useUrlSafe:Boolean=true) {
    private val useUrlSafe = _useUrlSafe
    //根据参数选择是否转为MD5值
    private val passByteArray = if (_useMD5) MessageDigestUtils.md5(_password) else _password.toByteArray()
    //初始化:加密/解密
    private val keySpec: SecretKeySpec = SecretKeySpec(passByteArray,"AES")
    //创建cipher对象
    private val cipher = Cipher.getInstance("AES")

    //文件处理
    private fun fileHandler(inputFileStream:FileInputStream, outputFileStream: FileOutputStream,
                            eachBlockHandler:(ByteArray)-> ByteArray,
                            endBlockHandler:(ByteArray)-> ByteArray,
                            blockSize:Int=10*1024*1024): Boolean {

        try {
            var len = -1
            val buffer = ByteArray(blockSize)
            while (((inputFileStream.read(buffer)).also { len = it }) != -1) {
                if (len == blockSize) {
                    outputFileStream.write(eachBlockHandler(buffer))
                } else {
                    outputFileStream.write(endBlockHandler(buffer.sliceArray(0 until len)))
                }
            }
        } catch (e: Exception) {
            Log.d("FileCrypto Error", "FileCrypto exception")
            return false
        }
        return true
    }

    private fun update(fileBytes: ByteArray):ByteArray {
        return cipher.update(fileBytes)
    }

    private fun doFinal(fileBytes: ByteArray):ByteArray {
        return cipher.doFinal(fileBytes)
    }

    //从文件路径加密
    fun encrypt(inputPath:String, outputPath:String): Boolean {
        val inputFile = File(inputPath)
        val outputFile = File(outputPath)
        return encrypt(inputFile, outputFile)
    }

    //从文件路径解密
    fun decrypt(inputPath:String, outputPath:String): Boolean {
        val inputFile = File(inputPath)
        val outputFile = File(outputPath)
        return decrypt(inputFile, outputFile)
    }

    //从File对象加密
    fun encrypt(inputFile:File, outputFile:File): Boolean {
        val inputFileStream = FileInputStream(inputFile)
        val outputFileStream = FileOutputStream(outputFile)
        val result = encrypt(inputFileStream, outputFileStream)
        inputFileStream.close()
        outputFileStream.close()
        return result
    }

    //从File对象解密
    fun decrypt(inputFile:File, outputFile:File): Boolean {
        val inputFileStream = FileInputStream(inputFile)
        val outputFileStream = FileOutputStream(outputFile)
        val result = decrypt(inputFileStream, outputFileStream)
        inputFileStream.close()
        outputFileStream.close()
        return result
    }

    // 从FileInputStream对象加密
    fun encrypt(inputFileStream:FileInputStream, outputFileStream: FileOutputStream): Boolean {
        cipher.init(Cipher.ENCRYPT_MODE,keySpec)
        return fileHandler(inputFileStream, outputFileStream, ::update, ::doFinal)
    }

    // 从FileInputStream对象解密
    fun decrypt(inputFileStream:FileInputStream, outputFileStream: FileOutputStream): Boolean {
        cipher.init(Cipher.DECRYPT_MODE,keySpec)
        return fileHandler(inputFileStream, outputFileStream, ::update, ::doFinal)
    }
}


// 字节数组加密解密
class BytesCrypto(_password: String, _useMD5:Boolean=true, _useUrlSafe:Boolean=true) {
    private val useUrlSafe = _useUrlSafe

    //根据参数选择是否转为MD5值
    private val passByteArray =
        if (_useMD5) MessageDigestUtils.md5(_password) else _password.toByteArray()

    //初始化:加密/解密
    private val keySpec: SecretKeySpec = SecretKeySpec(passByteArray, "AES")

    fun encrypt(inputBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE,keySpec)
        return cipher.doFinal(inputBytes)
    }

    fun decrypt(inputBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE,keySpec)
        return cipher.doFinal(inputBytes)
    }

}