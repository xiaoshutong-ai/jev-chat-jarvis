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
    private val source: (Long) -> DesktopBridgeClient.PollBatch,
    private val acknowledge: (DesktopBridgeClient.PollBatch) -> Unit = {}
) {
    private constructor(
        config: DesktopBridgeClient.Config,
        generate: (DesktopAnalysisCoordinator.ConversationContext) -> List<String>,
        client: DesktopBridgeClient
    ) : this(config, generate, client::pollOnce, client::acknowledgeProcessed)

    constructor(
        config: DesktopBridgeClient.Config,
        generate: (DesktopAnalysisCoordinator.ConversationContext) -> List<String>
    ) : this(config, generate, DesktopBridgeClient(config))

    private val coordinator = DesktopAnalysisCoordinator(generate)
    private val ownerId = java.util.UUID.randomUUID().toString()

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

        // Validate *before* index mutation or relay acknowledgement. A return
        // value of zero from mergeTrusted can mean invalid data OR duplicates.
        require(DesktopMessageIndex.acceptsTrustedBatch(
            config.androidProfileScope, config.pairedAccountRef, batch.messages, nowMs
        )) { "invalid account/profile or message in desktop batch" }
        require(DesktopPairingState.establishAfterVerifiedPairing(
            config.androidProfileScope, config.pairedAccountRef, ownerId
        )) { "invalid Android profile" }
        val inserted = DesktopMessageIndex.mergeTrusted(
            config.androidProfileScope, config.pairedAccountRef, batch.messages, nowMs
        )

        val generated = coordinator.onAcceptedBatch(
            config.androidProfileScope, config.pairedAccountRef,
            batch.messages, autoAnalysisApproved, nowMs
        )
        // Acknowledgement is the last step; on any earlier failure the next
        // poll retrieves the same window rather than skipping unread messages.
        acknowledge(batch)
        return Outcome(inserted, generated, batch.cursor, batch.hasMore)
    }

    /** Does not touch other Android profiles or Windows account mappings. */
    @Synchronized fun stop() {
        coordinator.clear()
        val revoked = DesktopPairingState.revokeIfOwned(config.androidProfileScope, ownerId)
        val currentAccount = DesktopPairingState.accountFor(config.androidProfileScope)
        // An old pipeline may complete after the new one has paired. Do not
        // erase data belonging to that replacement (same account/profile).
        if (revoked || currentAccount != config.pairedAccountRef) {
            DesktopMessageIndex.clearScoped(config.androidProfileScope, config.pairedAccountRef)
            DesktopReplyInbox.clearScoped(config.androidProfileScope, config.pairedAccountRef)
        }
    }
}
