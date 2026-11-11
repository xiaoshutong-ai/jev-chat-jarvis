package com.jev.probe.session

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Volatile, per-profile notification tap evidence. It never grants permission
 * to insert a reply and never stores message text or writes a file/log.
 */
object NotificationClickTracker {
    // Random for each app process. Short chat phrases cannot be looked up
    // against a public SHA-256 dictionary if process memory is inspected.
    private val fingerprintKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private const val MAX_POSTED = 80
    private const val POST_TTL_MS = 30 * 60_000L
    private const val CLICK_TTL_MS = 15_000L

    private data class Posted(val profileScope: String, val fingerprint: String?, val recordedAtMs: Long)
    private val posted = LinkedHashMap<String, Posted>()
    private var lastClick: SessionBindingResolver.NotificationOpenHint? = null

    @Synchronized
    fun recordPosted(profileScope: String, key: String, content: String, nowMs: Long) {
        if (profileScope.isBlank() || key.isBlank() || nowMs <= 0L) return
        val fingerprint = fingerprint(content)
        posted.remove(key)
        posted[key] = Posted(profileScope, fingerprint, nowMs)
        val iterator = posted.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.recordedAtMs > POST_TTL_MS || posted.size > MAX_POSTED) iterator.remove()
        }
    }

    @Synchronized
    fun recordRemoved(profileScope: String, key: String, reason: SessionBindingResolver.RemovalReason, nowMs: Long) {
        val previous = posted.remove(key) ?: return
        if (reason != SessionBindingResolver.RemovalReason.CLICK || previous.profileScope != profileScope ||
            nowMs < previous.recordedAtMs || nowMs - previous.recordedAtMs > POST_TTL_MS) return
        lastClick = SessionBindingResolver.NotificationOpenHint(profileScope, key, reason, nowMs, previous.fingerprint)
    }

    @Synchronized
    fun latestClick(profileScope: String, nowMs: Long): SessionBindingResolver.NotificationOpenHint? {
        val item = lastClick ?: return null
        if (profileScope != item.profileScope || nowMs < item.clickedAtMs || nowMs - item.clickedAtMs > CLICK_TTL_MS) {
            return null
        }
        return item
    }

    @Synchronized
    fun clear() {
        posted.clear()
        lastClick = null
    }

    /** Opaque, per-message correlation only; short texts remain guessable from their hash. */
    fun fingerprint(text: String): String? {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isBlank()) return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(fingerprintKey, "HmacSHA256"))
        val bytes = mac.doFinal(normalized.toByteArray(Charsets.UTF_8))
        return "hmac256:" + bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
