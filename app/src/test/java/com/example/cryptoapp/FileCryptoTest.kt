package com.example.cryptoapp

import com.example.cryptoapp.Utils.CryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileCryptoTest {
    private val source = ByteArray(160_000) { ((it * 31) and 0xff).toByte() }

    @Test
    fun symmetricAlgorithmsRoundTripStreamingFiles() {
        roundTrip(FileCrypto.Algorithm.AES_GCM, "AES 测试密码", "AES 测试密码")
        roundTrip(FileCrypto.Algorithm.SM4_GCM, "SM4 测试密码", "SM4 测试密码")
    }

    @Test
    fun asymmetricAlgorithmsUseHybridEncryption() {
        val rsa = CryptoEngine.generateKeyPair(CryptoEngine.Algorithm.RSA_OAEP)
        roundTrip(FileCrypto.Algorithm.RSA_OAEP, rsa.publicKey, rsa.privateKey)

        val sm2 = CryptoEngine.generateKeyPair(CryptoEngine.Algorithm.SM2)
        roundTrip(FileCrypto.Algorithm.SM2, sm2.publicKey, sm2.privateKey)
    }

    private fun roundTrip(algorithm: FileCrypto.Algorithm, encryptSecret: String, decryptSecret: String) {
        val input = File.createTempFile("toolbox-input-", ".bin").apply { writeBytes(source) }
        val encrypted = File.createTempFile("toolbox-encrypted-", ".tbx")
        val output = File.createTempFile("toolbox-output-", ".bin")
        try {
            assertTrue(FileCrypto(encryptSecret, algorithm).encrypt(input, encrypted))
            // 解密算法从版本化文件头读取，页面当前选择不会影响已有密文识别。
            assertTrue(FileCrypto(decryptSecret, FileCrypto.Algorithm.AES_GCM).decrypt(encrypted, output))
            assertArrayEquals(source, output.readBytes())
        } finally {
            input.delete()
            encrypted.delete()
            output.delete()
        }
    }
}
