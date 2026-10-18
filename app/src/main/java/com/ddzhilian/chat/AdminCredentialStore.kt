package com.ddzhilian.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 「记住管理员账号」：邮箱明文，密码用 Android KeyStore 里的 AES/GCM 密钥加密后
 * 存进应用私有偏好。Keystore 不可用时静默放弃保存（下次仍需手动输入），
 * 绝不退化成明文密码落地。
 */
class AdminCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("admin_credentials", 0)

    fun savedEmail(): String? = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun hasSaved(): Boolean = savedEmail() != null && prefs.getString(KEY_PASSWORD, null) != null

    fun load(): Pair<String, String>? {
        val email = savedEmail() ?: return null
        val payload = prefs.getString(KEY_PASSWORD, null) ?: return null
        val password = decrypt(payload) ?: return null
        return email to password
    }

    fun save(email: String, password: String) {
        val encrypted = encrypt(password)
        if (encrypted == null) {
            clear()
            return
        }

        prefs.edit().putString(KEY_EMAIL, email.trim()).putString(KEY_PASSWORD, encrypted).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(payload: String): String? = runCatching {
        val parts = payload.split(':')
        if (parts.size != 2) return null

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ddzhilian_admin_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
    }
}
