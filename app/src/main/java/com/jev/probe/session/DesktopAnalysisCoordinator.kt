package com.jev.probe.session

/**
 * Explicit opt-in analysis seam. A real provider client is injected by the
 * Android service only after the owner enables desktop analysis.
 *
 * The caller provides newly accepted messages; only a fresh inbound message
 * causes an analysis. No network, clipboard, UI, file or WeChat access here.
 */
class DesktopAnalysisCoordinator(
    private val generate: (ConversationContext) -> List<String>
) {
    data class ConversationContext(
        val profileScope: String,
        val accountRef: String,
        val sessionId: String,
        val displayName: String,
        val messages: List<DesktopMessageIndex.Incoming>
    )

    private val analyzed = LinkedHashMap<String, String>()
    private const val FRESH_MS = 120_000L
    private const val MAX_PROCESSED = 200

    @Synchronized
    fun onAcceptedBatch(
        profileScope: String,
        pairedAccountRef: String,
        acceptedMessages: List<DesktopMessageIndex.Incoming>,
        enabledByUser: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        if (!enabledByUser || profileScope.isBlank() || pairedAccountRef.isBlank() || nowMs <= 0L)
            return 0
        val grouped = acceptedMessages
            .filter { it.accountRef == pairedAccountRef }
            .groupBy { it.sessionId }
        var generated = 0
        for ((sessionId, batch) in grouped) {
            val recentInbound = batch.lastOrNull {
                it.direction == DesktopMessageIndex.Direction.OTHER &&
                    it.timeMs > 0L && it.timeMs <= nowMs &&
                    nowMs - it.timeMs <= FRESH_MS
            } ?: continue
            val indexKey = "$profileScope\u0000$pairedAccountRef\u0000$sessionId"
            if (analyzed[indexKey] == recentInbound.messageId) continue
            val context = DesktopMessageIndex.history(profileScope, pairedAccountRef, sessionId)
            if (context.isEmpty() || context.none { it.messageId == recentInbound.messageId })
                continue
            // Mark before calling the model to avoid duplicate paid calls when
            // an upstream relay retries the same event.
            analyzed[indexKey] = recentInbound.messageId
            while (analyzed.size > MAX_PROCESSED) analyzed.remove(analyzed.keys.first())

            val replies = runCatching {
                generate(ConversationContext(profileScope, pairedAccountRef, sessionId,
                    recentInbound.displayName.ifBlank { sessionId }, context.takeLast(30)))
            }.getOrDefault(emptyList())
            if (replies.isNotEmpty() && DesktopReplyInbox.publishTrusted(
                    profileScope, pairedAccountRef, sessionId,
                    recentInbound.displayName.ifBlank { sessionId },
                    replies.take(3), nowMs
                )) {
                generated++
            }
        }
        return generated
    }

    @Synchronized fun clear() = analyzed.clear()
}
