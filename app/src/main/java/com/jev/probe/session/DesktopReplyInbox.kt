package com.jev.probe.session

/**
 * In-memory replies that belong to one exact desktop account + session.
 * Never pick the most recent chat as an implicit Android input target.
 *
 * The producer must have obtained explicit consent to analyze this session
 * and authenticated the matching desktop account/profile before publishing.
 */
object DesktopReplyInbox {
    private const val MAX_SESSIONS = 20
    private const val MAX_AGE_MS = 10 * 60_000L
    private const val MAX_REPLY_LENGTH = 2000

    data class Bundle(
        val profileScope: String,
        val accountRef: String,
        val sessionId: String,
        val displayName: String,
        val replies: List<String>,
        val publishedAtMs: Long
    )
    private val sessions = LinkedHashMap<String, Bundle>()
    private fun key(scope: String, accountRef: String, sessionId: String): String =
        "$scope\u0000$accountRef\u0000$sessionId"

    @Synchronized
    fun publishTrusted(
        profileScope: String,
        accountRef: String,
        sessionId: String,
        displayName: String,
        replies: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (profileScope.isBlank() || accountRef.isBlank() || sessionId.isBlank() ||
            displayName.isBlank() || nowMs <= 0L || replies.isEmpty() ||
            replies.size > 3 || displayName.length > 120 ||
            replies.any { it.isBlank() || it.length > MAX_REPLY_LENGTH }) return false
        val id = key(profileScope, accountRef, sessionId)
        sessions.remove(id)
        sessions[id] = Bundle(profileScope, accountRef, sessionId, displayName, replies.toList(), nowMs)
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        return true
    }

    @Synchronized
    fun list(profileScope: String, pairedAccountRef: String, nowMs: Long = System.currentTimeMillis()): List<Bundle> =
        sessions.values.asSequence()
            .filter { it.profileScope == profileScope && it.accountRef == pairedAccountRef }
            .filter { nowMs >= it.publishedAtMs && nowMs - it.publishedAtMs <= MAX_AGE_MS }
            .sortedByDescending { it.publishedAtMs }.toList()

    @Synchronized fun clear() = sessions.clear()
}
