package com.jev.probe.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSyncPipelineTest {
    private val scope = "com.tencent.mm@UserHandle{0}"
    private val config = DesktopBridgeClient.Config(
        "http://127.0.0.1:8766", "x".repeat(40), "desktop-1", scope
    )
    private fun event(id: String, account: String = "desktop-1") =
        DesktopMessageIndex.Incoming(
            accountRef = account, sessionId = "wxid-a", messageId = id,
            displayName = "示例会话", direction = DesktopMessageIndex.Direction.OTHER,
            text = "合成数据", timeMs = 1_000_000L
        )

    @After fun cleanup() {
        DesktopPairingState.clear()
        DesktopMessageIndex.clear()
        DesktopReplyInbox.clear()
    }

    @Test fun authenticatedPipelineStoresContextButNeverModelsWithoutOptIn() {
        var calls = 0
        val pipeline = DesktopSyncPipeline(config, { calls++; listOf("回复") }, {
            DesktopBridgeClient.PollBatch("0123456789abcdef", 1, false, false, listOf(event("m1")))
        })
        val result = pipeline.pollOnce(false, 1_000_200)
        assertEquals(1, result.accepted)
        assertEquals(0, result.analyzedSessions)
        assertEquals(0, calls)
        assertEquals("desktop-1", DesktopPairingState.accountFor(scope))
        assertEquals(1, DesktopMessageIndex.history(scope, "desktop-1", "wxid-a").size)
        assertTrue(DesktopReplyInbox.list(scope, "desktop-1", 1_000_200).isEmpty())
    }

    @Test fun optInGeneratesLabelledRepliesAndRejectsDuplicatePaidCall() {
        var calls = 0
        val pipeline = DesktopSyncPipeline(config, { calls++; listOf("回复1", "回复2") }, {
            DesktopBridgeClient.PollBatch("0123456789abcdef", 2, false, false, listOf(event("m1")))
        })
        assertEquals(1, pipeline.pollOnce(true, 1_000_200).analyzedSessions)
        assertEquals(0, pipeline.pollOnce(true, 1_000_300).analyzedSessions)
        assertEquals(1, calls)
        val bundle = DesktopReplyInbox.list(scope, "desktop-1", 1_000_300).single()
        assertEquals("wxid-a", bundle.sessionId)
        assertEquals("示例会话", bundle.displayName)
    }

    @Test fun gapOrForeignAccountNeverEntersContext() {
        val broken = DesktopSyncPipeline(config, { listOf("never") }, {
            DesktopBridgeClient.PollBatch("0123456789abcdef", 2, true, false, listOf(event("m1")))
        })
        var denied = false
        try { broken.pollOnce(true, 1_000_200) } catch (expected: IllegalArgumentException) { denied = true }
        assertTrue(denied)
        assertNull(DesktopPairingState.accountFor(scope))
        assertTrue(DesktopMessageIndex.history(scope, "desktop-1", "wxid-a").isEmpty())

        val spoofed = DesktopSyncPipeline(config, { listOf("never") }, {
            DesktopBridgeClient.PollBatch("0123456789abcdef", 2, false, false, listOf(event("m2", "another")))
        })
        denied = false
        try { spoofed.pollOnce(true, 1_000_200) } catch (expected: IllegalArgumentException) { denied = true }
        assertTrue(denied)
        assertNull(DesktopPairingState.accountFor(scope))
    }

    @Test fun oldPollerCannotRevokeNewOwnerOrDeleteSameAccountContext() {
        val old = DesktopSyncPipeline(config, { listOf("old") }, {
            DesktopBridgeClient.PollBatch("0123456789abcdef", 1, false, false, listOf(event("m1")))
        })
        val current = DesktopSyncPipeline(config, { listOf("new") }, {
            DesktopBridgeClient.PollBatch("0123456789abcdef", 2, false, false, listOf(event("m2")))
        })
        old.pollOnce(false, 1_000_200)
        current.pollOnce(true, 1_000_300)
        old.stop()
        assertEquals("desktop-1", DesktopPairingState.accountFor(scope))
        assertEquals(2, DesktopMessageIndex.history(scope, "desktop-1", "wxid-a").size)
        assertEquals(1, DesktopReplyInbox.list(scope, "desktop-1", 1_000_350).size)
        current.stop()
        assertNull(DesktopPairingState.accountFor(scope))
        assertTrue(DesktopMessageIndex.history(scope, "desktop-1", "wxid-a").isEmpty())
        assertTrue(DesktopReplyInbox.list(scope, "desktop-1", 1_000_350).isEmpty())
    }

    @Test fun ackIsDeferredUntilEntireBatchIsValidatedAndAccepted() {
        var acknowledgements = 0
        val good = event("good")
        val pipeline = DesktopSyncPipeline(
            config,
            { listOf("suggestion") },
            { DesktopBridgeClient.PollBatch("0123456789abcdef", 5, false, false, listOf(good)) },
            { acknowledgements++ }
        )
        val accepted = pipeline.pollOnce(true, 1_000_200)
        assertEquals(1, accepted.accepted)
        assertEquals(1, acknowledgements)
        pipeline.stop()

        // A syntactically valid HTTP response can still contain an illegal
        // domain message; it must not be discarded as a duplicate or ACKed.
        var invalidAcks = 0
        val invalid = DesktopSyncPipeline(
            config,
            { throw AssertionError("must not call model") },
            {
                DesktopBridgeClient.PollBatch("0123456789abcdef", 6, false, false,
                    listOf(good.copy(messageId = "")))
            },
            { invalidAcks++ }
        )
        var denied = false
        try { invalid.pollOnce(true, 1_000_200) }
        catch (expected: IllegalArgumentException) { denied = true }
        assertTrue(denied)
        assertEquals(0, invalidAcks)
        assertNull(DesktopPairingState.accountFor(scope))
        invalid.stop()
    }

    @Test fun duplicateOnlyAndEmptyBatchesCanStillAdvanceCursor() {
        var acks = 0
        val duplicated = event("duplicate")
        DesktopMessageIndex.mergeTrusted(scope, "desktop-1", listOf(duplicated), 1_000_100)
        var invocation = 0
        val pipeline = DesktopSyncPipeline(config, { listOf("candidate") }, {
            invocation++
            DesktopBridgeClient.PollBatch("0123456789abcdef", invocation.toLong(),
                false, false, if (invocation == 1) listOf(duplicated) else emptyList())
        }, { acks++ })
        assertEquals(0, pipeline.pollOnce(false, 1_000_200).accepted)
        assertEquals(0, pipeline.pollOnce(false, 1_000_300).accepted)
        assertEquals(2, acks)
        pipeline.stop()
    }

    @Test fun revokingDesktopAccountDoesNotEraseOtherProfile() {
        val otherScope = "com.tencent.mm@UserHandle{999}"
        DesktopPairingState.establishAfterVerifiedPairing(otherScope, "desktop-2")
        DesktopMessageIndex.mergeTrusted(otherScope, "desktop-2", listOf(event("other", "desktop-2")), 1_000_200)
        val pipeline = DesktopSyncPipeline(config, { listOf("reply") }, {
            DesktopBridgeClient.PollBatch("0123456789abcdef", 1, false, false, listOf(event("m1")))
        })
        pipeline.pollOnce(true, 1_000_200)
        pipeline.stop()
        assertNull(DesktopPairingState.accountFor(scope))
        assertTrue(DesktopMessageIndex.history(scope, "desktop-1", "wxid-a").isEmpty())
        assertTrue(DesktopReplyInbox.list(scope, "desktop-1", 1_000_200).isEmpty())
        assertEquals(1, DesktopMessageIndex.history(otherScope, "desktop-2", "wxid-a").size)
        assertEquals("desktop-2", DesktopPairingState.accountFor(otherScope))
    }
}
