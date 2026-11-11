package com.jev.probe.core

/**
 * Per-service, memory-only paid analysis deduplication.
 *
 * The caller supplies a fingerprint of explicitly selected text and the
 * model route. No raw clipboard text, account identity, or API key is stored.
 * Repeated taps reuse completed results; concurrent taps reuse one pending
 * request. Results disappear when the service process ends.
 */
class ClipboardAnalysisCache(private val capacity: Int = 20) {
    init {
        require(capacity > 0)
    }

    data class Result(
        val judgment: Analysis,
        val replies: List<RankedReply>,
        val replyError: String? = null,
        val contextNotes: Int = 0,
        val contextHistory: Int = 0
    )

    sealed interface Decision {
        data object New : Decision
        data object Pending : Decision
        data class Cached(val result: Result) : Decision
    }

    // null = pending; access-order map bounds memory without persisting content.
    private val entries = LinkedHashMap<String, Result?>(16, 0.75f, true)

    @Synchronized
    fun begin(key: String): Decision {
        require(key.isNotBlank())
        if (entries.containsKey(key)) {
            val value = entries[key]
            return if (value == null) Decision.Pending else Decision.Cached(value)
        }
        entries[key] = null
        if (entries.size > capacity) {
            // Prefer evicting completed work over a currently running request.
            val victim = entries.entries.firstOrNull { it.value != null }?.key
                ?: entries.keys.first()
            entries.remove(victim)
        }
        return Decision.New
    }

    @Synchronized
    fun complete(key: String, result: Result): Boolean {
        if (!entries.containsKey(key)) return false
        entries[key] = result.copy(replies = result.replies.toList())
        return true
    }

    @Synchronized
    fun fail(key: String) {
        // Failed/cancelled runs can be explicitly retried without stale pending.
        if (entries[key] == null) entries.remove(key)
    }

    @Synchronized
    fun invalidateCompleted(key: String): Boolean {
        // An explicit refresh must never start a second concurrent paid call.
        if (entries[key] == null) return false
        entries.remove(key)
        return true
    }
}
