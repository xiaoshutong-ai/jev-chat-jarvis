package com.jev.probe.core

import org.junit.Assert.*
import org.junit.Test

class SecretWritePolicyTest {
    @Test fun unreadableKeystoreDoesNotWipeOldCiphertext() {
        var erased = false
        assertThrows(IllegalStateException::class.java) {
            SecretWritePolicy.clearIfReadable(
                "ciphertext",
                decode = { throw IllegalStateException("temporarily unavailable") },
                removeAll = { erased = true; true }
            )
        }
        assertFalse(erased)
    }

    @Test fun readableCiphertextCanBeExplicitlyCleared() {
        var erased = false
        SecretWritePolicy.clearIfReadable(
            "ciphertext",
            decode = { "readable" },
            removeAll = { erased = true; true }
        )
        assertTrue(erased)
    }

    @Test fun encryptedWriteCommitsThenRemovesLegacy() {
        val calls = mutableListOf<String>()
        SecretWritePolicy.persist(
            "test-api-value",
            encode = { calls.add("encrypt"); "encrypted" },
            decode = { calls.add("decrypt"); "test-api-value" },
            commitEncrypted = { cipher ->
                assertEquals("encrypted", cipher)
                calls.add("commit")
                true
            },
            removeLegacy = { calls.add("removeLegacy"); true }
        )
        assertEquals(listOf("encrypt", "decrypt", "commit", "removeLegacy"), calls)
    }

    @Test fun keystoreFailureNeverPersistsPlaintext() {
        var encryptedCommitted = false
        var legacyRemoved = false
        assertThrows(IllegalStateException::class.java) {
            SecretWritePolicy.persist(
                "never-write-plaintext",
                encode = { throw IllegalStateException("keystore unavailable") },
                decode = { it },
                commitEncrypted = { encryptedCommitted = true; true },
                removeLegacy = { legacyRemoved = true; true }
            )
        }
        assertFalse(encryptedCommitted)
        assertFalse(legacyRemoved)
    }

    @Test fun roundTripMismatchNeverPersists() {
        var encryptedCommitted = false
        assertThrows(IllegalStateException::class.java) {
            SecretWritePolicy.persist(
                "original",
                encode = { "cipher" },
                decode = { "different" },
                commitEncrypted = { encryptedCommitted = true; true },
                removeLegacy = { true }
            )
        }
        assertFalse(encryptedCommitted)
    }

    @Test fun failedCiphertextCommitDoesNotFallBackToPlaintext() {
        var legacyRemoved = false
        assertThrows(IllegalStateException::class.java) {
            SecretWritePolicy.persist(
                "api-key",
                encode = { "cipher" },
                decode = { "api-key" },
                commitEncrypted = { false },
                removeLegacy = { legacyRemoved = true; true }
            )
        }
        assertFalse(legacyRemoved)
    }

    @Test fun failedLegacyCleanupReportsFailureWithoutPlaintextWrite() {
        var ciphertextCommitted = false
        assertThrows(IllegalStateException::class.java) {
            SecretWritePolicy.persist(
                "api-key",
                encode = { "cipher" },
                decode = { "api-key" },
                commitEncrypted = { ciphertextCommitted = true; true },
                removeLegacy = { false }
            )
        }
        assertTrue(ciphertextCommitted)
    }
}
