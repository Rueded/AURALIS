package com.auralis.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 用来加密"敏感设置"（网易云 cookie、局域网设备 IP 这类）再放进 Drive 备份。
 *
 * 跟哥哥 AiExpenseTracker 里那份参考实现比，改了两个地方，都是为了避免同样的坑：
 *
 * 1. 密钥不是写死在代码里的固定字符串——那种写法只要反编译 APK 就能直接看到密钥，
 *    等于形同虚设。这里改成用"当前登录的 Google 账号邮箱"派生密钥：反正能读到这份
 *    Drive 备份，本来就必须先登录同一个账号，所以拿它做密钥派生因子很自然，而且
 *    不同账号的密钥天然不同，不会出现"全世界用这个 App 的人共用一把钥匙"的情况。
 *
 * 2. IV（初始化向量）不是固定全 0，是每次加密都随机生成一个，然后跟密文拼在一起存。
 *    固定 IV 意味着同样的明文，永远会被加密成同样的密文——这样即使看不懂内容，
 *    也能看出"这条数据是不是没变过"，是真实存在的信息泄漏。随机 IV 才是标准做法。
 *
 * 用的是 AES/GCM（带认证的加密模式），比 AES/CBC 多一层好处：解密时如果数据被
 * 篡改或者密钥不对，会直接抛异常失败，而不是"解出一堆乱码却不知道是不是错的"。
 */
object SettingsEncryptionHelper {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12   // GCM 标准推荐 12 字节 IV
    private const val GCM_TAG_LENGTH = 128 // 认证 tag 长度（bit）

    private fun deriveKey(accountEmail: String): SecretKeySpec {
        // 加一个固定的 App 专属前缀当 salt，不直接拿邮箱明文当 key 素材
        val material = "auralis_backup_v1::$accountEmail"
        val sha = MessageDigest.getInstance("SHA-256")
        val key = sha.digest(material.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(key, "AES") // SHA-256 正好 32 字节，对应 AES-256
    }

    /**
     * 加密：明文 -> Base64 密文。
     * 随机生成的 IV 会拼在密文最前面一起编码，解密时会自动从里面拆出来，
     * 调用的时候不用哥哥自己额外保存 IV。
     */
    fun encrypt(plainText: String, accountEmail: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(accountEmail), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = iv + encryptedBytes
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("DriveSync", "settings encrypt failed", e)
            ""
        }
    }

    /**
     * 解密：失败的话（比如账号换了、数据损坏、篡改过）直接返回空字符串，
     * 不抛异常炸掉整个恢复流程——万一这块坏了，也不该连累收藏、歌单这些正常数据恢复不了。
     */
    fun decrypt(encoded: String, accountEmail: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(accountEmail), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("DriveSync", "settings decrypt failed", e)
            ""
        }
    }
}