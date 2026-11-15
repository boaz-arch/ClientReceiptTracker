package com.company.supportvisit.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where available and never leaves the device). Only the ciphertext is in
 * SharedPreferences. Used instead of the deprecated EncryptedSharedPreferences.
 */
object SecretStore {

    const val GREEN_INVOICE_KEY_ID = "green_invoice_key_id"
    const val GREEN_INVOICE_SECRET = "green_invoice_secret"

    private const val TAG = "SecretStore"
    private const val PREFS = "secrets"
    private const val KEY_ALIAS = "supportvisit_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    fun put(context: Context, name: String, value: String?) {
        val prefs = prefs(context)
        if (value.isNullOrEmpty()) {
            prefs.edit { remove(name) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
    }

    /** The stored value, or null if missing or no longer decryptable (e.g. after a backup restore). */
    fun get(context: Context, name: String): String? {
        val stored = prefs(context).getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            }
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Stored secret can't be decrypted; it must be entered again")
            null
        }
    }

    fun has(context: Context, name: String): Boolean = get(context, name) != null

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
