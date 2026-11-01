package com.neoplus.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable key held in the Android Keystore. */
object KeystoreVault {
    private const val ALIAS = "neoplus_master_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}

/** Plain settings plus the encrypted AI API key. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("neo_prefs", Context.MODE_PRIVATE)

    var emergencyStop: Boolean
        get() = sp.getBoolean("emergency_stop", false)
        set(v) = sp.edit().putBoolean("emergency_stop", v).apply()

    var accent: Int
        get() = sp.getInt("accent", 0xFF35E0FF.toInt())
        set(v) = sp.edit().putInt("accent", v).apply()

    fun saveApiKey(key: String) =
        sp.edit().putString("api_key_enc", KeystoreVault.encrypt(key)).apply()

    fun apiKey(): String? = sp.getString("api_key_enc", null)
        ?.let { runCatching { KeystoreVault.decrypt(it) }.getOrNull() }

    var model: String
        get() = sp.getString("ai_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) = sp.edit().putString("ai_model", v.trim()).apply()

    var awayOn: Boolean
        get() = sp.getBoolean("away_on", false)
        set(v) = sp.edit().putBoolean("away_on", v).apply()
    var awaySince: Long
        get() = sp.getLong("away_since", 0L)
        set(v) = sp.edit().putLong("away_since", v).apply()
    /** READ (read only), SUGGEST (draft replies), AUTO (reply to allowed senders). */
    var awayPolicy: String
        get() = sp.getString("away_policy", "SUGGEST") ?: "SUGGEST"
        set(v) = sp.edit().putString("away_policy", v).apply()
    var allowedSenders: String
        get() = sp.getString("allowed_senders", "") ?: ""
        set(v) = sp.edit().putString("allowed_senders", v).apply()
    var ownerName: String
        get() = sp.getString("owner_name", "") ?: ""
        set(v) = sp.edit().putString("owner_name", v.trim()).apply()

    fun hasApiKey() = sp.contains("api_key_enc")
    fun clearApiKey() = sp.edit().remove("api_key_enc").apply()
}
