package com.jev.probe.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed value encryption for API credentials.
 *
 * Ciphertext lives in ordinary app-private SharedPreferences; the AES key never
 * leaves AndroidKeyStore. This deliberately avoids deprecated
 * EncryptedSharedPreferences / MasterKey APIs.
 *
 * Migration is fail-safe: plaintext is encrypted, immediately decrypted and
 * compared, and only after a verified successful commit is plaintext removed.
 */
internal class SecureSecretStore(
    context: Context,
    private val prefs: SharedPreferences
) {

    private val alias = context.packageName + ".jev.api-secrets.v1"

    fun readOrMigrate(encryptedPref: String, legacyPlainPref: String): String {
        val encrypted = prefs.getString(encryptedPref, null)
        if (!encrypted.isNullOrBlank()) {
            try {
                val value = decrypt(encrypted)
                // A prior phase-2 cleanup may have failed; retry opportunistically.
                if (prefs.contains(legacyPlainPref) &&
                    !prefs.edit().remove(legacyPlainPref).commit()
                ) {
                    Log.w(TAG, "legacy plaintext cleanup failed")
                    return ""
                }
                return value
            } catch (e: Exception) {
                // Never overwrite an existing but temporarily unreadable
                // ciphertext with a stale legacy plaintext credential.
                Log.w(TAG, "secret decrypt failed: ${e.javaClass.simpleName}")
                return ""
            }
        }

        val legacy = prefs.getString(legacyPlainPref, "") ?: ""
        if (legacy.isBlank()) return ""

        return try {
            SecretWritePolicy.persist(
                plain = legacy,
                encode = ::encrypt,
                decode = ::decrypt,
                commitEncrypted = { cipher ->
                    prefs.edit().putString(encryptedPref, cipher).commit()
                },
                removeLegacy = { prefs.edit().remove(legacyPlainPref).commit() }
            )
            Log.i(TAG, "secret migrated to AndroidKeyStore")
            legacy
        } catch (e: Exception) {
            // Keep existing data for a future retry, but never use a key that
            // could not be securely migrated during this request.
            Log.w(TAG, "secret migration deferred: ${e.javaClass.simpleName}")
            ""
        }
    }

    fun write(encryptedPref: String, legacyPlainPref: String, value: String) {
        if (value.isBlank()) {
            try {
                SecretWritePolicy.clearIfReadable(
                    encrypted = prefs.getString(encryptedPref, null),
                    decode = ::decrypt,
                    removeAll = {
                        prefs.edit().remove(encryptedPref).remove(legacyPlainPref).commit()
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "secure secret clear rejected: ${e.javaClass.simpleName}")
                throw IllegalStateException("安全存储暂不可读取或清除，原密钥已保留", e)
            }
            return
        }

        try {
            SecretWritePolicy.persist(
                plain = value,
                encode = ::encrypt,
                decode = ::decrypt,
                commitEncrypted = { cipher ->
                    prefs.edit().putString(encryptedPref, cipher).commit()
                },
                removeLegacy = { prefs.edit().remove(legacyPlainPref).commit() }
            )
        } catch (e: Exception) {
            // Never overwrite a previously encrypted credential or persist a
            // newly supplied credential in plaintext when Keystore fails.
            Log.w(TAG, "secure secret write rejected: ${e.javaClass.simpleName}")
            throw IllegalStateException("安全存储未完成，请重试", e)
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return VERSION + "." +
            encoder.encodeToString(cipher.iv) + "." +
            encoder.encodeToString(ciphertext)
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split('.')
        require(parts.size == 3 && parts[0] == VERSION) { "unsupported secret format" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[1])
        val ciphertext = decoder.decode(parts[2])
        require(iv.size == GCM_IV_BYTES) { "invalid GCM IV" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION = "v1"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }
}
