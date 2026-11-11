package com.jev.probe.core

import java.security.MessageDigest

/**
 * Only text copied by the user and read while JEV owns a foreground Activity.
 * The clipboard is NOT a trusted source of the current WeChat account,
 * conversation, or message direction.
 */
object ExplicitClipboardPolicy {
    const val MAX_TEXT_CHARS = 4_000
    const val NOTE = "用户主动复制的文字；暂按对方消息分析，未核验联系人及消息方向。请核对后再填入。"

    fun fromUserCopy(raw: String, nowMs: Long): ChatSnapshot? {
        if (nowMs <= 0L) return null
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS || '\u0000' in text) return null
        // Preserve newlines as ONE selected message: not a reconstructed history.
        return ChatSnapshot(
            title = null,
            messages = listOf(Msg("other", text, nowMs)),
            note = NOTE
        )
    }

    /** Ephemeral equality marker; no raw clipboard text or contact name is stored. */
    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Prevent repeated paid requests when tapping the bubble with unchanged text. */
class RecentExplicitCopy(private val ttlMs: Long = 10_000L) {
    private var fingerprint: String? = null
    private var acceptedAtMs: Long = 0L

    fun checkAndMark(text: String, nowMs: Long): Boolean {
        if (nowMs <= 0L) return false
        val next = ExplicitClipboardPolicy.fingerprint(text)
        val repeated = fingerprint == next && acceptedAtMs > 0L &&
            nowMs >= acceptedAtMs && nowMs - acceptedAtMs <= ttlMs
        fingerprint = next
        acceptedAtMs = nowMs
        return repeated
    }

    fun clear() {
        fingerprint = null
        acceptedAtMs = 0L
    }
}
