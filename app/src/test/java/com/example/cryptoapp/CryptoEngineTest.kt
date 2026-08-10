package com.example.cryptoapp

import com.example.cryptoapp.Utils.CryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 覆盖全部可逆文本算法、摘要算法和错误密码校验。 */
class CryptoEngineTest {
    private val plaintext = "工具箱 crypto test 你好"

    @Test fun aesGcmRoundTrip() = assertSymmetricRoundTrip(CryptoEngine.Algorithm.AES_GCM)

    @Test fun sm4GcmRoundTrip() = assertSymmetricRoundTrip(CryptoEngine.Algorithm.SM4_GCM)

    @Test fun rsaRoundTrip() = assertAsymmetricRoundTrip(CryptoEngine.Algorithm.RSA_OAEP)

    @Test fun sm2RoundTrip() = assertAsymmetricRoundTrip(CryptoEngine.Algorithm.SM2)

    @Test fun digestsAreStableAndDifferent() {
        val sha = CryptoEngine.encrypt(CryptoEngine.Algorithm.SHA256, "abc", "")
        val sm3 = CryptoEngine.encrypt(CryptoEngine.Algorithm.SM3, "abc", "")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha)
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", sm3)
        assertNotEquals(sha, sm3)
    }

    @Test fun wrongPasswordCannotDecryptAuthenticatedCiphertext() {
        val encrypted = CryptoEngine.encrypt(CryptoEngine.Algorithm.AES_GCM, plaintext, "correct")
        assertThrows(Exception::class.java) {
            CryptoEngine.decrypt(CryptoEngine.Algorithm.AES_GCM, encrypted, "wrong")
        }
    }

    private fun assertSymmetricRoundTrip(algorithm: CryptoEngine.Algorithm) {
        val encrypted = CryptoEngine.encrypt(algorithm, plaintext, "可靠密码-123")
        assertNotEquals(plaintext, encrypted)
        assertEquals(plaintext, CryptoEngine.decrypt(algorithm, encrypted, "可靠密码-123"))
    }

    private fun assertAsymmetricRoundTrip(algorithm: CryptoEngine.Algorithm) {
        val keys = CryptoEngine.generateKeyPair(algorithm)
        val encrypted = CryptoEngine.encrypt(algorithm, plaintext, keys.publicKey)
        assertEquals(plaintext, CryptoEngine.decrypt(algorithm, encrypted, keys.privateKey))
    }
}
