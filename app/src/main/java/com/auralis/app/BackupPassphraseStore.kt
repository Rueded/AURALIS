package com.auralis.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 把"备份口令"保存在本机：用 Android Keystore 里的一把不可导出的 AES-256 密钥加密后，再放进 SharedPreferences。
 *
 * 这样既能在每次备份 / 恢复时自动取出口令（不用每次都让用户输入），
 * 又不会把口令明文留在磁盘上；Keystore 密钥不能被导出，别的 app 或者拷走的数据文件都解不开它。
 * 口令本身绝不会上传到 Drive。
 *
 * 保存这份数据的文件（auralis_secure.xml）已经在备份规则里排除了：
 * 云备份 / 设备迁移还原到另一台手机时，那台手机的 Keystore 里没有这把密钥，还原过去的密文也是解不开的。
 */
object BackupPassphraseStore {
    private const val TAG = "BackupPassphrase"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "auralis_backup_passphrase_key"
    private const val PREFS = "auralis_secure"
    private const val KEY_ENC = "backup_passphrase_enc"
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 保存口令。失败（设备不支持安全存储等）返回 false。 */
    fun set(context: Context, passphrase: String): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())               // IV 由 Keystore 生成
        val encrypted = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENC, packed).apply()
        true
    } catch (e: Exception) {
        Log.e(TAG, "保存备份口令失败", e)
        false
    }

    /** 取出口令；没设置、或者解不开（比如 Keystore 密钥丢了）都返回 null。 */
    fun get(context: Context): String? {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENC, null) ?: return null
        return try {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val cipherBytes = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "读取备份口令失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun isSet(context: Context): Boolean = get(context) != null

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENC).apply()
    }
}
