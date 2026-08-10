package com.example.cryptoapp.Utils

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 无状态文本密码学入口。密文自带算法、盐和 IV，可经剪贴板或二维码传递，
 * 不依赖应用内部的隐藏状态；密钥和密码也不会由该类持久化。
 */
object CryptoEngine {
    private const val PREFIX = "TBX1"
    private val random = SecureRandom()

    enum class Algorithm(val title: String, val needsSecret: Boolean, val canDecrypt: Boolean) {
        AES_GCM("AES-256-GCM", true, true),
        RSA_OAEP("RSA-2048 OAEP", true, true),
        SM2("SM2", true, true),
        SM3("SM3 摘要", false, false),
        SM4_GCM("SM4-GCM", true, true),
        SHA256("SHA-256 摘要", false, false)
    }

    data class KeyPairText(val publicKey: String, val privateKey: String)

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    fun encrypt(algorithm: Algorithm, input: String, secret: String): String = when (algorithm) {
        Algorithm.AES_GCM -> symmetricEncrypt("AES", 32, algorithm, input, secret)
        Algorithm.SM4_GCM -> symmetricEncrypt("SM4", 16, algorithm, input, secret)
        Algorithm.RSA_OAEP -> asymmetricEncrypt(
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "RSA", algorithm, input, secret
        )
        Algorithm.SM2 -> asymmetricEncrypt("SM2", "EC", algorithm, input, secret, "BC")
        Algorithm.SM3 -> digest("SM3", input, "BC")
        Algorithm.SHA256 -> digest("SHA-256", input)
    }

    fun decrypt(algorithm: Algorithm, input: String, secret: String): String = when (algorithm) {
        Algorithm.AES_GCM -> symmetricDecrypt("AES", 32, algorithm, input, secret)
        Algorithm.SM4_GCM -> symmetricDecrypt("SM4", 16, algorithm, input, secret)
        Algorithm.RSA_OAEP -> asymmetricDecrypt(
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "RSA", algorithm, input, secret
        )
        Algorithm.SM2 -> asymmetricDecrypt("SM2", "EC", algorithm, input, secret, "BC")
        Algorithm.SM3, Algorithm.SHA256 -> error("摘要算法不可逆，不能解密")
    }

    fun generateKeyPair(algorithm: Algorithm): KeyPairText {
        val generator = when (algorithm) {
            Algorithm.RSA_OAEP -> KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            Algorithm.SM2 -> KeyPairGenerator.getInstance("EC", "BC").apply {
                initialize(ECNamedCurveGenParameterSpec("sm2p256v1"), random)
            }
            else -> error("${algorithm.title} 不使用非对称密钥对")
        }
        val pair = generator.generateKeyPair()
        return KeyPairText(
            toPem("PUBLIC KEY", pair.public.encoded),
            toPem("PRIVATE KEY", pair.private.encoded)
        )
    }

    private fun symmetricEncrypt(
        keyAlgorithm: String,
        keySize: Int,
        algorithm: Algorithm,
        input: String,
        password: String
    ): String {
        require(password.isNotBlank()) { "请输入密码" }
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val key = deriveKey(password, salt, keySize, keyAlgorithm)
        val cipher = cipher("$keyAlgorithm/GCM/NoPadding", symmetricProvider(keyAlgorithm))
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv), random)
        val encrypted = cipher.doFinal(input.toByteArray(StandardCharsets.UTF_8))
        return listOf(PREFIX, algorithm.name, b64(salt), b64(iv), b64(encrypted)).joinToString("|")
    }

    private fun symmetricDecrypt(
        keyAlgorithm: String,
        keySize: Int,
        algorithm: Algorithm,
        input: String,
        password: String
    ): String {
        require(password.isNotBlank()) { "请输入密码" }
        val parts = input.trim().split('|')
        require(parts.size == 5 && parts[0] == PREFIX && parts[1] == algorithm.name) {
            "密文格式或所选算法不匹配"
        }
        val key = deriveKey(password, b64Decode(parts[2]), keySize, keyAlgorithm)
        val cipher = cipher("$keyAlgorithm/GCM/NoPadding", symmetricProvider(keyAlgorithm))
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64Decode(parts[3])))
        return String(cipher.doFinal(b64Decode(parts[4])), StandardCharsets.UTF_8)
    }

    private fun asymmetricEncrypt(
        transformation: String,
        keyAlgorithm: String,
        algorithm: Algorithm,
        input: String,
        publicKeyPem: String,
        provider: String? = null
    ): String {
        require(publicKeyPem.isNotBlank()) { "请粘贴公钥" }
        val factory = keyFactory(keyAlgorithm, provider)
        val key = factory.generatePublic(X509EncodedKeySpec(fromPem(publicKeyPem)))
        val cipher = cipher(transformation, provider)
        cipher.init(Cipher.ENCRYPT_MODE, key, random)
        val encrypted = cipher.doFinal(input.toByteArray(StandardCharsets.UTF_8))
        return listOf(PREFIX, algorithm.name, b64(encrypted)).joinToString("|")
    }

    private fun asymmetricDecrypt(
        transformation: String,
        keyAlgorithm: String,
        algorithm: Algorithm,
        input: String,
        privateKeyPem: String,
        provider: String? = null
    ): String {
        require(privateKeyPem.isNotBlank()) { "请粘贴私钥" }
        val parts = input.trim().split('|')
        require(parts.size == 3 && parts[0] == PREFIX && parts[1] == algorithm.name) {
            "密文格式或所选算法不匹配"
        }
        val factory = keyFactory(keyAlgorithm, provider)
        val key = factory.generatePrivate(PKCS8EncodedKeySpec(fromPem(privateKeyPem)))
        val cipher = cipher(transformation, provider)
        cipher.init(Cipher.DECRYPT_MODE, key)
        return String(cipher.doFinal(b64Decode(parts[2])), StandardCharsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray, size: Int, algorithm: String): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, size * 8)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, algorithm)
    }

    private fun digest(name: String, input: String, provider: String? = null): String {
        val md = if (provider == null) MessageDigest.getInstance(name)
        else MessageDigest.getInstance(name, provider)
        return md.digest(input.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun cipher(transformation: String, provider: String? = null): Cipher =
        if (provider == null) Cipher.getInstance(transformation) else Cipher.getInstance(transformation, provider)

    private fun keyFactory(algorithm: String, provider: String?) =
        if (provider == null) KeyFactory.getInstance(algorithm) else KeyFactory.getInstance(algorithm, provider)

    /** AES 使用系统实现；Android 系统通常不含 SM4，因此由 Bouncy Castle 提供。 */
    private fun symmetricProvider(keyAlgorithm: String): String? =
        if (keyAlgorithm == "SM4") BouncyCastleProvider.PROVIDER_NAME else null

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun b64Decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun toPem(label: String, bytes: ByteArray): String =
        "-----BEGIN $label-----\n" + Base64.getEncoder().encodeToString(bytes) + "\n-----END $label-----"

    private fun fromPem(value: String): ByteArray {
        val content = value.replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s"), "")
        return Base64.getDecoder().decode(content)
    }
}
