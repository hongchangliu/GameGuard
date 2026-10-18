package com.gameguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class DailyMail(private val context: Context) {
    private val prefs = context.getSharedPreferences("daily_mail", Context.MODE_PRIVATE)

    init {
        // One-time migration from the older build's configured sender address.
        if (!prefs.contains("address") && BuildConfig.DEFAULT_MAIL_ADDRESS.isNotBlank()) {
            check(prefs.edit().putString("address", BuildConfig.DEFAULT_MAIL_ADDRESS).commit())
        }
    }

    fun address(): String = prefs.getString("address", "") ?: ""

    fun isConfigured(): Boolean = address().isNotBlank() && prefs.contains("secret") && prefs.contains("iv")

    /** The QQ SMTP authorization code is entered only on the phone and encrypted with Android Keystore. */
    fun configure(email: String, code: String) {
        val address = email.trim()
        require(address.matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+"))) { "请输入有效的发件邮箱地址" }
        require(code.isNotBlank() && !code.contains('\n') && !code.contains('\r')) { "请输入 QQ 邮箱 SMTP 授权码" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(code.trim().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("secret", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("address", address).commit())
    }

    fun clear() {
        check(prefs.edit().remove("secret").remove("iv").commit())
    }

    fun lastSentDate(): String? = prefs.getString("last_sent_date", null)

    fun markSent(date: String) {
        check(prefs.edit().putString("last_sent_date", date).commit())
    }

    fun send(date: String, report: String) {
        val address = address()
        check(address.isNotBlank()) { "请先设置发件邮箱地址" }
        val secret = decryptCode()
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
        socket.use {
            socket.soTimeout = 15_000
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.connect(InetSocketAddress("smtp.qq.com", 465), 15_000)
            socket.startHandshake()
            val input = BufferedReader(InputStreamReader(socket.inputStream, Charsets.US_ASCII))
            val output = OutputStreamWriter(socket.outputStream, Charsets.US_ASCII)
            fun reply(expected: Int) {
                var line: String
                do {
                    line = input.readLine() ?: error("邮件服务器断开连接")
                } while (line.length > 3 && line[3] == '-')
                check(line.take(3).toIntOrNull() == expected) { "QQ 邮箱发送失败（SMTP ${line.take(3)}）" }
            }
            fun command(value: String, expected: Int) {
                output.write(value + "\r\n")
                output.flush()
                reply(expected)
            }
            reply(220)
            command("EHLO gameguard.local", 250)
            command("AUTH LOGIN", 334)
            command(Base64.encodeToString(address.toByteArray(Charsets.US_ASCII), Base64.NO_WRAP), 334)
            command(Base64.encodeToString(secret.toByteArray(Charsets.UTF_8), Base64.NO_WRAP), 235)
            command("MAIL FROM:<$address>", 250)
            command("RCPT TO:<$address>", 250)
            command("DATA", 354)
            val subject = Base64.encodeToString("应用使用日报 $date".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            output.write("From: <$address>\r\nTo: <$address>\r\n")
            output.write("Subject: =?UTF-8?B?$subject?=\r\n")
            output.write("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n")
            output.write("Content-Transfer-Encoding: base64\r\n\r\n")
            output.write(Base64.encodeToString(report.toByteArray(Charsets.UTF_8), Base64.CRLF))
            output.write("\r\n.\r\n")
            output.flush()
            reply(250)
            command("QUIT", 221)
        }
    }

    private fun decryptCode(): String {
        val encrypted = prefs.getString("secret", null) ?: error("请先配置 QQ 邮箱授权码")
        val iv = prefs.getString("iv", null) ?: error("邮件配置不完整")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "gameguard_daily_mail_v1"
    }
}
