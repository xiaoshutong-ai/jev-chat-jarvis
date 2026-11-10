package com.jev.probe.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAnalysisCoordinatorTest {
    private val scope = "com.tencent.mm@UserHandle{0}"
    private val account = "desktop-1"

    private fun msg(
        id: String, whenMs: Long = 1_000_000L,
        direction: DesktopMessageIndex.Direction = DesktopMessageIndex.Direction.OTHER,
        acct: String = account
    ) = DesktopMessageIndex.Incoming(
        accountRef = acct, sessionId = "chat-a", messageId = id,
        displayName = "测试会话", direction = direction, text = "合成消息", timeMs = whenMs
    )

    @After fun cleanup() {
        DesktopMessageIndex.clear()
        DesktopReplyInbox.clear()
    }

    @Test fun disabledModeDoesNotInvokeModel() {
        val incoming = msg("received")
        DesktopMessageIndex.mergeTrusted(scope, account, listOf(incoming), 1_000_050)
        var calls = 0
        val coordinator = DesktopAnalysisCoordinator { calls++; listOf("reply") }
        assertEquals(0, coordinator.onAcceptedBatch(scope, account,
            listOf(incoming), enabledByUser = false, nowMs = 1_000_100))
        assertEquals(0, calls)
        assertTrue(DesktopReplyInbox.list(scope, account, 1_000_100).isEmpty())
    }

    @Test fun oneFreshInboundGeneratesOnceAndRetainsSessionScope() {
        val incoming = msg("received")
        DesktopMessageIndex.mergeTrusted(scope, account, listOf(incoming), 1_000_050)
        var calls = 0
        val coordinator = DesktopAnalysisCoordinator { context ->
            calls++
            assertEquals("chat-a", context.sessionId)
            assertEquals("desktop-1", context.accountRef)
            listOf("reply1", "reply2")
        }
        assertEquals(1, coordinator.onAcceptedBatch(scope, account,
            listOf(incoming), enabledByUser = true, nowMs = 1_000_100))
        assertEquals(0, coordinator.onAcceptedBatch(scope, account,
            listOf(incoming), enabledByUser = true, nowMs = 1_000_200))
        assertEquals(1, calls)
        assertEquals(2, DesktopReplyInbox.list(scope, account, 1_000_200).single().replies.size)
        assertTrue(DesktopReplyInbox.list("com.tencent.mm@UserHandle{999}", account, 1_000_200).isEmpty())
    }

    @Test fun outgoingOldOrForeignMessagesCannotGenerate() {
        val outgoing = msg("out", direction = DesktopMessageIndex.Direction.ME)
        val old = msg("old", whenMs = 100_000)
        val foreign = msg("foreign", acct = "desktop-2")
        DesktopMessageIndex.mergeTrusted(scope, account, listOf(outgoing, old), 1_000_050)
        var calls = 0
        val coordinator = DesktopAnalysisCoordinator { calls++; listOf("reply") }
        val accepted = listOf(outgoing, old, foreign)
        assertEquals(0, coordinator.onAcceptedBatch(scope, account, accepted, true, 1_000_200))
        assertEquals(0, calls)
    }

    @Test fun modelFailureCannotCreatePhantomCandidate() {
        val incoming = msg("received")
        DesktopMessageIndex.mergeTrusted(scope, account, listOf(incoming), 1_000_050)
        val coordinator = DesktopAnalysisCoordinator { throw IllegalStateException("provider down") }
        assertEquals(0, coordinator.onAcceptedBatch(scope, account,
            listOf(incoming), true, 1_000_100))
        assertTrue(DesktopReplyInbox.list(scope, account, 1_000_200).isEmpty())
    }

    @Test fun manualInboundOnlyGeneratesWithOptIn() {
        val message = DesktopMessageIndex.Incoming(
            accountRef = account,
            sessionId = SessionBindingResolver.USER_SELECTED_SESSION_PREFIX + "test01",
            messageId = "manual-unique",
            displayName = "手动选择：同学甲（联系人未核验）",
            direction = DesktopMessageIndex.Direction.OTHER,
            text = "测试问题",
            timeMs = 1_000_000L
        )
        DesktopMessageIndex.mergeTrusted(scope, account, listOf(message), 1_000_050)
        var calls = 0
        val coordinator = DesktopAnalysisCoordinator {
            calls++
            assertEquals(message.sessionId, it.sessionId)
            listOf("测试回复")
        }
        assertEquals(0, coordinator.onAcceptedBatch(scope, account,
            listOf(message), enabledByUser = false, nowMs = 1_000_100))
        assertEquals(0, calls)
        assertEquals(1, coordinator.onAcceptedBatch(scope, account,
            listOf(message), enabledByUser = true, nowMs = 1_000_200))
        assertEquals(1, calls)
        assertEquals(0, coordinator.onAcceptedBatch(scope, account,
            listOf(message), enabledByUser = true, nowMs = 1_000_300))
        val bundle = DesktopReplyInbox.list(scope, account, 1_000_300).single()
        assertEquals(message.sessionId, bundle.sessionId)
        assertTrue(bundle.displayName.contains("联系人未核验"))
    }
}
