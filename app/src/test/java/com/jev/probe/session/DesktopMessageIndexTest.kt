package com.jev.probe.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMessageIndexTest {
    private val scope = "com.tencent.mm@UserHandle{0}"
    private val acct = "desktop-account-a"
    private fun m(
        id: String, session: String = "wxid-a", time: Long = 1_000_000L,
        direction: DesktopMessageIndex.Direction = DesktopMessageIndex.Direction.OTHER,
        text: String = "Hello", account: String = acct
    ) = DesktopMessageIndex.Incoming(
        accountRef = account, sessionId = session, messageId = id,
        displayName = if (session == "wxid-a") "张三" else "李四",
        direction = direction, text = text, timeMs = time
    )

    @After fun cleanup() { DesktopMessageIndex.clear(); NotificationClickTracker.clear() }

    @Test fun mergesAndDeduplicatesByStableMessageId() {
        assertEquals(2, DesktopMessageIndex.mergeTrusted(scope, acct,
            listOf(m("m2", time = 1_000_200), m("m1", time = 1_000_100)), 1_001_000))
        assertEquals(0, DesktopMessageIndex.mergeTrusted(scope, acct,
            listOf(m("m1", time = 1_000_100)), 1_001_000))
        assertEquals(listOf("m1", "m2"),
            DesktopMessageIndex.history(scope, acct, "wxid-a").map { it.messageId })
    }

    @Test fun rejectsCrossAccountOrInvalidBatchWithoutPartialMutation() {
        assertEquals(0, DesktopMessageIndex.mergeTrusted(scope, acct,
            listOf(m("good"), m("other", account = "desktop-account-b")), 1_001_000))
        assertTrue(DesktopMessageIndex.history(scope, acct, "wxid-a").isEmpty())
        assertEquals(0, DesktopMessageIndex.mergeTrusted(scope, acct,
            listOf(m("future", time = 1_300_000)), 1_001_000))
        assertEquals(0, DesktopMessageIndex.mergeTrusted(scope, acct,
            listOf(m("", text = "valid")), 1_001_000))
        assertEquals(0, DesktopMessageIndex.mergeTrusted(scope, acct,
            listOf(m("empty", text = "  ")), 1_001_000))
    }

    @Test fun differentProfileAndDesktopAccountNeverLeakConversations() {
        DesktopMessageIndex.mergeTrusted(scope, acct, listOf(m("m1")), 1_001_000)
        assertTrue(DesktopMessageIndex.conversations("com.tencent.mm@UserHandle{999}", acct).isEmpty())
        assertTrue(DesktopMessageIndex.conversations(scope, "desktop-account-b").isEmpty())
        assertTrue(DesktopMessageIndex.history(scope, "desktop-account-b", "wxid-a").isEmpty())
        assertEquals(1, DesktopMessageIndex.conversations(scope, acct).size)
    }

    @Test fun latestReceivedMessageFingerprintIgnoresOutgoingMessages() {
        DesktopMessageIndex.mergeTrusted(scope, acct,
            listOf(m("r1", time = 1_000_100, text = "Hello"),
                m("s2", time = 1_000_500, text = "Thanks", direction = DesktopMessageIndex.Direction.ME)),
            1_001_000)
        val hint = DesktopMessageIndex.conversations(scope, acct).single()
        assertEquals(NotificationClickTracker.fingerprint("Hello"), hint.latestMessageFingerprint)
        assertEquals(1_000_100L, hint.latestMessageAtMs)
    }

    @Test fun identicalTextAcrossTwoChatsIsAnAmbiguousSuggestion() {
        DesktopMessageIndex.mergeTrusted(scope, acct,
            listOf(m("a1", session = "wxid-a", text = "到了吗"),
                m("b1", session = "wxid-b", text = "到了吗")), 1_001_000)
        val focus = SessionBindingResolver.EditorFocus(
            "com.tencent.mm", scope, 7L, 1_000_200
        )
        val click = SessionBindingResolver.NotificationOpenHint(
            scope, "notice", SessionBindingResolver.RemovalReason.CLICK,
            1_000_150, NotificationClickTracker.fingerprint("到了吗")
        )
        assertEquals(SessionBindingResolver.Suggestion.Ambiguous,
            SessionBindingResolver.suggest(focus, click, DesktopMessageIndex.conversations(scope, acct)))
    }

    @Test fun retainsBoundedNumberOfMessagesAndSessions() {
        val batch = (1..80).map { i -> m("m$i", time = 1_000_000L + i) }
        assertEquals(80, DesktopMessageIndex.mergeTrusted(scope, acct, batch, 1_001_000))
        assertEquals(40, DesktopMessageIndex.history(scope, acct, "wxid-a").size)
        (1..60).forEach { n ->
            DesktopMessageIndex.mergeTrusted(scope, acct, listOf(m("c$n", session = "session-$n")), 1_001_000)
        }
        assertTrue(DesktopMessageIndex.conversations(scope, acct).size <= DesktopMessageIndex.MAX_SESSIONS)
        assertFalse(DesktopMessageIndex.conversations(scope, acct).any { it.sessionId == "wxid-a" })
    }

    @Test fun manualUserSelectedMessageHasNoNotificationFingerprint() {
        val session = SessionBindingResolver.USER_SELECTED_SESSION_PREFIX + "pilotABC"
        val manual = m("manual-1", session = session, text = "相同通知正文")
        assertEquals(1, DesktopMessageIndex.mergeTrusted(
            scope, acct, listOf(manual), 1_001_000
        ))
        val conversation = DesktopMessageIndex.conversations(scope, acct).single()
        assertEquals(session, conversation.sessionId)
        // Manual clipboard content is a user declaration, not an
        // authenticated notification-to-WeChat-conversation binding.
        assertEquals(null, conversation.latestMessageFingerprint)
        assertEquals(1_000_000L, conversation.latestMessageAtMs)
        assertEquals("相同通知正文", DesktopMessageIndex.history(scope, acct, session).single().text)
    }
}
