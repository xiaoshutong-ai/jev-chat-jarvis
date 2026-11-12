package com.jev.probe.core

/**
 * Pure fail-closed persistence contract for a credential.
 *
 * Never offers a plaintext storage callback. Verify ciphertext before committing,
 * then remove a legacy plaintext slot. The caller reports any failure and keeps
 * previously saved credentials intact; the caller never writes new plaintext.
 */
internal object SecretWritePolicy {
    /** A temporarily unreadable existing ciphertext must not be wiped by a
     * settings screen that happens to display an empty password field. */
    fun clearIfReadable(
        encrypted: String?,
        decode: (String) -> String,
        removeAll: () -> Boolean
    ) {
        if (!encrypted.isNullOrBlank()) decode(encrypted)
        check(removeAll()) { "secret clear failed" }
    }

    fun persist(
        plain: String,
        encode: (String) -> String,
        decode: (String) -> String,
        commitEncrypted: (String) -> Boolean,
        removeLegacy: () -> Boolean
    ) {
        require(plain.isNotBlank()) { "blank credentials use explicit clear" }
        val cipher = encode(plain)
        check(decode(cipher) == plain) { "secret round-trip mismatch" }
        check(commitEncrypted(cipher)) { "encrypted preference commit failed" }
        check(removeLegacy()) { "legacy plaintext cleanup failed" }
    }
}
