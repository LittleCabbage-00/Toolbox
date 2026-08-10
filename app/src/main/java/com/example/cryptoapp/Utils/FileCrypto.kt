package com.example.cryptoapp

import com.example.cryptoapp.Utils.MessageDigestUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 流式文件加解密。
 *
 * 对称算法由密码派生密钥；RSA/SM2 只封装随机会话密钥，文件正文仍使用带认证的
 * AES/SM4-GCM 流式处理，因此既能处理大文件，也能检测文件被篡改或密钥错误。
 * V1 AES-GCM 与更早的 AES/ECB 文件仍可解密。
 */
class FileCrypto(
    private val secret: String,
    private val algorithm: Algorithm = Algorithm.AES_GCM
) {
    enum class Algorithm(val title: String, val asymmetric: Boolean) {
        AES_GCM("AES-256-GCM", false),
        SM4_GCM("SM4-GCM", false),
        RSA_OAEP("RSA-2048 OAEP（混合加密）", true),
        SM2("SM2（混合加密）", true)
    }

    private val random = SecureRandom()

    fun encrypt(inputPath: String, outputPath: String) = encrypt(File(inputPath), File(outputPath))
    fun decrypt(inputPath: String, outputPath: String) = decrypt(File(inputPath), File(outputPath))

    fun encrypt(inputFile: File, outputFile: File): Boolean =
        FileInputStream(inputFile).use { input -> FileOutputStream(outputFile).use { output -> encrypt(input, output) } }

    fun decrypt(inputFile: File, outputFile: File): Boolean =
        FileInputStream(inputFile).use { input -> FileOutputStream(outputFile).use { output -> decrypt(input, output) } }

    fun encrypt(input: FileInputStream, output: FileOutputStream): Boolean = runCatching {
        require(secret.isNotBlank()) { if (algorithm.asymmetric) "请粘贴公钥" else "密码不能为空" }
        ensureBc()
        val salt = if (algorithm.asymmetric) byteArrayOf() else randomBytes(SALT_SIZE)
        val iv = randomBytes(IV_SIZE)
        val contentKey = when (algorithm) {
            Algorithm.AES_GCM -> deriveKey(secret, salt, 32, "AES")
            Algorithm.SM4_GCM -> deriveKey(secret, salt, 16, "SM4")
            Algorithm.RSA_OAEP -> SecretKeySpec(randomBytes(32), "AES")
            Algorithm.SM2 -> SecretKeySpec(randomBytes(16), "SM4")
        }
        val wrappedKey = when (algorithm) {
            Algorithm.RSA_OAEP -> wrapKey(contentKey.encoded, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "RSA", null)
            Algorithm.SM2 -> wrapKey(contentKey.encoded, "SM2", "EC", BouncyCastleProvider.PROVIDER_NAME)
            else -> byteArrayOf()
        }

        DataOutputStream(output).apply {
            write(MAGIC_V2)
            writeByte(algorithm.ordinal)
            writeByte(salt.size)
            writeByte(iv.size)
            writeInt(wrappedKey.size)
            write(salt)
            write(iv)
            write(wrappedKey)
            flush()
        }
        val cipher = contentCipher(algorithm, Cipher.ENCRYPT_MODE, contentKey, iv)
        CipherOutputStream(output, cipher).use { encrypted -> input.copyTo(encrypted, BUFFER_SIZE) }
    }.isSuccess

    fun decrypt(input: FileInputStream, output: FileOutputStream): Boolean = runCatching {
        require(secret.isNotBlank()) { "密码或私钥不能为空" }
        ensureBc()
        val buffered = BufferedInputStream(input)
        buffered.mark(MAX_HEADER_SIZE)
        val magic = ByteArray(MAGIC_V2.size)
        require(buffered.read(magic) == magic.size) { "文件内容为空或文件头不完整" }
        when {
            magic.contentEquals(MAGIC_V2) -> decryptV2(buffered, output)
            magic.contentEquals(MAGIC_V1) -> decryptV1(buffered, output)
            else -> {
                buffered.reset()
                decryptLegacy(buffered, output)
            }
        }
    }.isSuccess

    private fun decryptV2(input: BufferedInputStream, output: FileOutputStream) {
        val data = DataInputStream(input)
        val storedAlgorithm = Algorithm.values().getOrNull(data.readUnsignedByte())
            ?: error("不支持的文件加密算法")
        val saltSize = data.readUnsignedByte()
        val ivSize = data.readUnsignedByte()
        val wrappedSize = data.readInt()
        require(saltSize <= 64 && ivSize in 8..32 && wrappedSize in 0..16_384) { "文件头参数无效" }
        val salt = ByteArray(saltSize).also(data::readFully)
        val iv = ByteArray(ivSize).also(data::readFully)
        val wrapped = ByteArray(wrappedSize).also(data::readFully)
        val contentKey = when (storedAlgorithm) {
            Algorithm.AES_GCM -> deriveKey(secret, salt, 32, "AES")
            Algorithm.SM4_GCM -> deriveKey(secret, salt, 16, "SM4")
            Algorithm.RSA_OAEP -> SecretKeySpec(
                unwrapKey(wrapped, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "RSA", null), "AES"
            )
            Algorithm.SM2 -> SecretKeySpec(
                unwrapKey(wrapped, "SM2", "EC", BouncyCastleProvider.PROVIDER_NAME), "SM4"
            )
        }
        val cipher = contentCipher(storedAlgorithm, Cipher.DECRYPT_MODE, contentKey, iv)
        CipherInputStream(input, cipher).use { decrypted -> decrypted.copyTo(output, BUFFER_SIZE) }
    }

    private fun decryptV1(input: BufferedInputStream, output: FileOutputStream) {
        val salt = ByteArray(SALT_SIZE)
        val iv = ByteArray(IV_SIZE)
        require(input.read(salt) == salt.size && input.read(iv) == iv.size) { "文件头不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(secret, salt, 32, "AES"), GCMParameterSpec(128, iv))
        CipherInputStream(input, cipher).use { decrypted -> decrypted.copyTo(output, BUFFER_SIZE) }
    }

    private fun contentCipher(type: Algorithm, mode: Int, key: SecretKeySpec, iv: ByteArray): Cipher {
        val keyAlgorithm = if (type == Algorithm.SM4_GCM || type == Algorithm.SM2) "SM4" else "AES"
        val cipher = if (keyAlgorithm == "SM4") Cipher.getInstance("SM4/GCM/NoPadding", "BC")
        else Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, iv), random)
        return cipher
    }

    private fun wrapKey(bytes: ByteArray, transformation: String, keyAlgorithm: String, provider: String?): ByteArray {
        val factory = if (provider == null) KeyFactory.getInstance(keyAlgorithm) else KeyFactory.getInstance(keyAlgorithm, provider)
        val key = factory.generatePublic(X509EncodedKeySpec(fromPem(secret)))
        val cipher = if (provider == null) Cipher.getInstance(transformation) else Cipher.getInstance(transformation, provider)
        cipher.init(Cipher.ENCRYPT_MODE, key, random)
        return cipher.doFinal(bytes)
    }

    private fun unwrapKey(bytes: ByteArray, transformation: String, keyAlgorithm: String, provider: String?): ByteArray {
        val factory = if (provider == null) KeyFactory.getInstance(keyAlgorithm) else KeyFactory.getInstance(keyAlgorithm, provider)
        val key = factory.generatePrivate(PKCS8EncodedKeySpec(fromPem(secret)))
        val cipher = if (provider == null) Cipher.getInstance(transformation) else Cipher.getInstance(transformation, provider)
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(bytes)
    }

    private fun deriveKey(password: String, salt: ByteArray, size: Int, name: String): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, size * 8)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, name)
    }

    private fun decryptLegacy(input: BufferedInputStream, output: FileOutputStream) {
        val legacyKey = SecretKeySpec(MessageDigestUtils.md5(secret), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, legacyKey)
        CipherInputStream(input, cipher).use { decrypted -> decrypted.copyTo(output, BUFFER_SIZE) }
    }

    private fun fromPem(value: String): ByteArray {
        val content = value.replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s"), "")
        return Base64.getDecoder().decode(content)
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun ensureBc() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    companion object {
        private val MAGIC_V2 = "TBXFILE2".toByteArray(Charsets.US_ASCII)
        private val MAGIC_V1 = "TBXFILE1".toByteArray(Charsets.US_ASCII)
        private const val SALT_SIZE = 16
        private const val IV_SIZE = 12
        private const val MAX_HEADER_SIZE = 20_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}

/** 仅用于兼容已有的图片预览格式。 */
class BytesCrypto(password: String, useMD5: Boolean = true, @Suppress("UNUSED_PARAMETER") useUrlSafe: Boolean = true) {
    private val key = SecretKeySpec(if (useMD5) MessageDigestUtils.md5(password) else password.toByteArray(), "AES")

    fun encrypt(inputBytes: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, key)
        doFinal(inputBytes)
    }

    fun decrypt(inputBytes: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.DECRYPT_MODE, key)
        doFinal(inputBytes)
    }
}
