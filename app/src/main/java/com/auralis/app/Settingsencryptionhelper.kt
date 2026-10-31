package com.auralis.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 用来加密"敏感设置"（网易云 cookie 这类）再放进 Drive 备份。支持两种格式：
 *
 * ── v1（旧格式，没有设置备份口令时仍然使用）──
 *   密钥 = SHA-256("auralis_backup_v1::" + 账号邮箱)，AES-256-GCM，随机 IV。
 *   注意它的强度有限：邮箱不是秘密，所以拿到那份 Drive 备份、又知道邮箱的人，可以自己算出密钥解开。
 *   它只能防"随手打开文件就看到明文"，防不了有心的人。保留它是为了：没设口令的用户行为不变、旧备份仍然能恢复。
 *
 * ── v2（设置了"备份口令"之后使用）──
 *   密钥 = PBKDF2-HMAC-SHA256(口令, 随机盐 16 字节, 600000 次迭代)，AES-256-GCM，随机 IV。
 *   格式： "v2:" + 迭代次数 + ":" + Base64( 盐 ‖ IV ‖ 密文+认证标签 )
 *   口令只在用户自己的设备上保存（见 [BackupPassphraseStore]，由 Android Keystore 保护），不会上传，
 *   所以只拿到 Drive 备份的人没有口令就解不开；换新设备时输入同一个口令即可恢复。
 *
 * 为什么不直接用 Android Keystore 的密钥来加密备份：Keystore 里的密钥不能导出，
 * 一台设备加密的内容换一台设备就永远解不开，跨设备同步就没意义了。所以用"口令派生密钥"，
 * 口令本身再交给 Keystore 保护在本机。
 *
 * 用的都是 AES/GCM（带认证的加密模式）：密钥不对或数据被篡改，解密会直接抛异常，而不是解出一堆乱码。
 */
object SettingsEncryptionHelper {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12   // GCM 标准推荐 12 字节 IV
    private const val GCM_TAG_LENGTH = 128 // 认证 tag 长度（bit）

    private const val V2_PREFIX = "v2:"
    private const val V2_ITERATIONS = 600_000
    private const val V2_SALT_LENGTH = 16

    /** 这段密文是不是 v2（需要备份口令才能解开）。界面用它判断"需要口令"。 */
    fun needsPassphrase(encoded: String): Boolean = encoded.startsWith(V2_PREFIX)

    // ───────────── v1 ─────────────

    private fun deriveKey(accountEmail: String): SecretKeySpec {
        // 加一个固定的 App 专属前缀当 salt，不直接拿邮箱明文当 key 素材
        val material = "auralis_backup_v1::$accountEmail"
        val sha = MessageDigest.getInstance("SHA-256")
        val key = sha.digest(material.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(key, "AES") // SHA-256 正好 32 字节，对应 AES-256
    }

    // ───────────── v2 ─────────────

    private fun derivePassphraseKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /**
     * 加密：明文 -> 密文字符串。
     * [passphrase] 为空 → 用旧的 v1（邮箱派生）；不为空 → 用 v2（口令派生）。
     * 随机的盐 / IV 都拼在密文里，解密时自动拆出来，调用方不需要自己保存。
     */
    fun encrypt(plainText: String, accountEmail: String, passphrase: String? = null): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv = randomBytes(GCM_IV_LENGTH)
            if (passphrase.isNullOrEmpty()) {
                cipher.init(Cipher.ENCRYPT_MODE, deriveKey(accountEmail), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                Base64.encodeToString(iv + encryptedBytes, Base64.NO_WRAP)
            } else {
                val salt = randomBytes(V2_SALT_LENGTH)
                cipher.init(Cipher.ENCRYPT_MODE, derivePassphraseKey(passphrase, salt, V2_ITERATIONS), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                V2_PREFIX + V2_ITERATIONS + ":" + Base64.encodeToString(salt + iv + encryptedBytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            android.util.Log.e("DriveSync", "settings encrypt failed", e)
            ""
        }
    }

    /**
     * 解密：失败的话（口令不对、账号换了、数据损坏、篡改过）直接返回空字符串，
     * 不抛异常炸掉整个恢复流程——万一这块坏了，也不该连累收藏、歌单这些正常数据恢复不了。
     * v2 的密文在没有口令时同样返回空字符串（调用方可以用 [needsPassphrase] 区分原因）。
     */
    fun decrypt(encoded: String, accountEmail: String, passphrase: String? = null): String {
        if (encoded.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            if (encoded.startsWith(V2_PREFIX)) {
                if (passphrase.isNullOrEmpty()) {
                    android.util.Log.w("DriveSync", "备份是用口令加密的（v2），但这台设备还没有设置备份口令")
                    return ""
                }
                val rest = encoded.removePrefix(V2_PREFIX)
                val iterations = rest.substringBefore(':').toInt()
                // 迭代次数写在密文里，被篡改成一个巨大的值会把手机卡死，限定在合理范围内
                require(iterations in 10_000..5_000_000) { "bad iteration count" }
                val combined = Base64.decode(rest.substringAfter(':'), Base64.NO_WRAP)
                val salt = combined.copyOfRange(0, V2_SALT_LENGTH)
                val iv = combined.copyOfRange(V2_SALT_LENGTH, V2_SALT_LENGTH + GCM_IV_LENGTH)
                val cipherBytes = combined.copyOfRange(V2_SALT_LENGTH + GCM_IV_LENGTH, combined.size)
                cipher.init(Cipher.DECRYPT_MODE, derivePassphraseKey(passphrase, salt, iterations), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } else {
                val combined = Base64.decode(encoded, Base64.NO_WRAP)
                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(accountEmail), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            android.util.Log.e("DriveSync", "settings decrypt failed", e)
            ""
        }
    }
}
