package com.jev.probe.session

/**
 * Bounded and volatile messages already obtained from an explicitly trusted
 * desktop source. The adapter must authenticate a peer AND explicitly pair
 * a desktop account to this Android user before calling [mergeTrusted].
 *
 * No WeChat protocol, database, process, accessibility or file access lives here.
 */
object DesktopMessageIndex {
    const val MAX_SESSIONS = 50
    const val MAX_MESSAGES_PER_SESSION = 40
    private const val MAX_MESSAGE_CHARS = 4000
    private const val MAX_LABEL_CHARS = 120
    private const val MAX_BATCH = 100
    private const val FUTURE_TOLERANCE_MS = 120_000L

    enum class Direction { ME, OTHER }
    data class Incoming(
        val accountRef: String,
        val sessionId: String,
        val messageId: String,
        val displayName: String,
        val direction: Direction,
        val text: String,
        val timeMs: Long
    )
    private data class Session(
        val profileScope: String,
        val accountRef: String,
        val sessionId: String,
        var displayName: String,
        val messages: LinkedHashMap<String, Incoming> = LinkedHashMap()
    )

    private val sessions = LinkedHashMap<String, Session>()

    private fun key(profileScope: String, accountRef: String, sessionId: String): String =
        "$profileScope\u0000$accountRef\u0000$sessionId"

    /**
     * This is intentionally not an exported Android IPC method. All profiles
     * come from an already paired local Android user, never an untrusted JSON
     * field. Reject a batch with any cross-account item (no partial import).
     */
    /**
     * Preflight the entire batch before acknowledging the desktop cursor.
     * Empty batches are valid (the remote cursor can still advance), but
     * malformed nonempty batches must never be mistaken for duplicate IDs.
     */
    fun acceptsTrustedBatch(
        profileScope: String,
        pairedAccountRef: String,
        batch: List<Incoming>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = profileScope.startsWith("com.tencent.mm@") &&
        pairedAccountRef.isNotBlank() && nowMs > 0L &&
        batch.size <= MAX_BATCH &&
        batch.none {
            it.accountRef != pairedAccountRef ||
                it.sessionId.isBlank() || it.messageId.isBlank() ||
                it.sessionId.length > 200 || it.messageId.length > 200 ||
                it.text.isBlank() || it.text.length > MAX_MESSAGE_CHARS ||
                it.displayName.length > MAX_LABEL_CHARS || it.timeMs <= 0L ||
                it.timeMs > nowMs + FUTURE_TOLERANCE_MS
        }

    @Synchronized
    fun mergeTrusted(
        profileScope: String,
        pairedAccountRef: String,
        batch: List<Incoming>,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (batch.isEmpty() || !acceptsTrustedBatch(profileScope, pairedAccountRef, batch, nowMs)) return 0

        var inserted = 0
        for (message in batch) {
            val k = key(profileScope, pairedAccountRef, message.sessionId)
            val session = sessions.getOrPut(k) {
                Session(profileScope, pairedAccountRef, message.sessionId, message.displayName)
            }
            if (message.displayName.isNotBlank()) session.displayName = message.displayName
            if (session.messages.containsKey(message.messageId)) continue
            session.messages[message.messageId] = message
            inserted++
            val sorted = session.messages.values.sortedWith(
                compareBy<Incoming> { it.timeMs }.thenBy { it.messageId }
            ).takeLast(MAX_MESSAGES_PER_SESSION)
            session.messages.clear()
            sorted.forEach { session.messages[it.messageId] = it }
            // Reinsert the entire session to keep bounded LRU by activity.
            sessions.remove(k)
            sessions[k] = session
        }
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        return inserted
    }

    @Synchronized
    fun conversations(profileScope: String, pairedAccountRef: String): List<SessionBindingResolver.DesktopConversation> =
        sessions.values.asSequence()
            .filter { it.profileScope == profileScope && it.accountRef == pairedAccountRef }
            .mapNotNull { session ->
                val latest = session.messages.values.lastOrNull() ?: return@mapNotNull null
                // A click notification reflects a received message, not our own send.
                val lastReceived = session.messages.values.lastOrNull { it.direction == Direction.OTHER }
                SessionBindingResolver.DesktopConversation(
                    profileScope = profileScope,
                    sessionId = session.sessionId,
                    displayName = session.displayName,
                    latestMessageFingerprint = lastReceived?.let { NotificationClickTracker.fingerprint(it.text) },
                    latestMessageAtMs = lastReceived?.timeMs ?: latest.timeMs
                )
            }.sortedByDescending { it.latestMessageAtMs }.toList()

    @Synchronized
    fun history(profileScope: String, pairedAccountRef: String, sessionId: String): List<Incoming> =
        sessions[key(profileScope, pairedAccountRef, sessionId)]?.messages?.values?.toList() ?: emptyList()

    @Synchronized
    fun clearScoped(profileScope: String, accountRef: String) {
        sessions.entries.removeIf {
            it.value.profileScope == profileScope && it.value.accountRef == accountRef
        }
    }

    @Synchronized
    fun clear() = sessions.clear()
}
