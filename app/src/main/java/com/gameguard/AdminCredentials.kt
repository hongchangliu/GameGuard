package com.gameguard

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AdminCredentials(context: Context) {
    // Credential-encrypted storage keeps the verifier unavailable before the user unlocks.
    private val prefs = context.getSharedPreferences("admin_credentials", Context.MODE_PRIVATE)

    fun isSet(): Boolean = prefs.contains("hash")

    fun create(password: String) {
        require(!isSet()) { "管理员密码已设置" }
        require(password.length >= 8) { "密码至少需要 8 位" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        check(prefs.edit().putString("salt", encode(salt))
            .putString("hash", encode(derive(password, salt)))
            .putInt("failures", 0).putLong("locked_until", 0).commit())
    }

    fun change(oldPassword: String, newPassword: String): Boolean {
        if (!verify(oldPassword)) return false
        require(newPassword.length >= 8) { "密码至少需要 8 位" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        check(prefs.edit().putString("salt", encode(salt))
            .putString("hash", encode(derive(newPassword, salt)))
            .putInt("failures", 0).putLong("locked_until", 0).commit())
        return true
    }

    fun verify(password: String): Boolean {
        if (!isSet() || System.currentTimeMillis() < prefs.getLong("locked_until", 0)) return false
        val salt = Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("hash", ""), Base64.NO_WRAP)
        val correct = MessageDigest.isEqual(expected, derive(password, salt))
        if (correct) {
            prefs.edit().putInt("failures", 0).putLong("locked_until", 0).apply()
        } else {
            val failures = prefs.getInt("failures", 0) + 1
            prefs.edit().putInt("failures", failures)
                .putLong("locked_until", if (failures >= 5) System.currentTimeMillis() + 60_000 else 0)
                .apply()
        }
        return correct
    }

    fun lockMessage(): String = if (System.currentTimeMillis() < prefs.getLong("locked_until", 0))
        "密码错误次数过多，请 1 分钟后重试" else "管理员密码错误"

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, 200_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
