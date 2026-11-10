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
