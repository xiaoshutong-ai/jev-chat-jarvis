package com.jev.probe.session

/**
 * Composes authenticated desktop transport, bounded conversation context,
 * and user-opt-in candidate generation. No background loop is started here.
 *
 * Production callers must supply a user-approved Config and keep this
 * pipeline alive for cursor continuity. Tests may inject a synthetic source.
 */
class DesktopSyncPipeline(
    private val config: DesktopBridgeClient.Config,
    generate: (DesktopAnalysisCoordinator.ConversationContext) -> List<String>,
    private val source: (Long) -> DesktopBridgeClient.PollBatch
) {
    constructor(
        config: DesktopBridgeClient.Config,
        generate: (DesktopAnalysisCoordinator.ConversationContext) -> List<String>
    ) : this(config, generate, DesktopBridgeClient(config)::pollOnce)

    private val coordinator = DesktopAnalysisCoordinator(generate)

    data class Outcome(
        val accepted: Int,
        val analyzedSessions: Int,
        val cursor: Long,
        val hasMore: Boolean
    )

    @Synchronized
    fun pollOnce(
        autoAnalysisApproved: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Outcome {
        val batch = source(nowMs) // actual source authenticates its bearer token
        require(!batch.gap) { "source gap requires explicit historical backfill" }
        require(batch.messages.size <= 100) { "relay batch too large" }
        require(batch.messages.all {
            it.accountRef == config.pairedAccountRef &&
                it.timeMs > 0L && it.timeMs <= nowMs + 120_000L
        }) { "cross-account or future source data rejected" }

        val inserted = DesktopMessageIndex.mergeTrusted(
            config.androidProfileScope, config.pairedAccountRef, batch.messages, nowMs
        )
        require(DesktopPairingState.establishAfterVerifiedPairing(
            config.androidProfileScope, config.pairedAccountRef
        )) { "invalid Android profile" }

        val generated = coordinator.onAcceptedBatch(
            config.androidProfileScope, config.pairedAccountRef,
            batch.messages, autoAnalysisApproved, nowMs
        )
        return Outcome(inserted, generated, batch.cursor, batch.hasMore)
    }

    /** Does not touch other Android profiles or Windows account mappings. */
    @Synchronized fun stop() {
        coordinator.clear()
        DesktopPairingState.revoke(config.androidProfileScope)
        DesktopMessageIndex.clearScoped(config.androidProfileScope, config.pairedAccountRef)
        DesktopReplyInbox.clearScoped(config.androidProfileScope, config.pairedAccountRef)
    }
}
