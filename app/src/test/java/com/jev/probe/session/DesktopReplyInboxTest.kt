package com.jev.probe.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopReplyInboxTest {
    @After fun cleanup() = DesktopReplyInbox.clear()

    @Test fun doesNotExposeRepliesAcrossAccountsOrAndroidProfiles() {
        assertTrue(DesktopReplyInbox.publishTrusted(
            "com.tencent.mm@UserHandle{0}", "desktop-1", "wxid-a", "张三",
            listOf("你好", "收到"), 1_000_000))
        assertEquals(1, DesktopReplyInbox.list("com.tencent.mm@UserHandle{0}", "desktop-1", 1_000_050).size)
        assertTrue(DesktopReplyInbox.list("com.tencent.mm@UserHandle{999}", "desktop-1", 1_000_050).isEmpty())
        assertTrue(DesktopReplyInbox.list("com.tencent.mm@UserHandle{0}", "desktop-2", 1_000_050).isEmpty())
    }

    @Test fun rejectsInvalidOrExpiredCandidates() {
        assertFalse(DesktopReplyInbox.publishTrusted("a", "b", "c", "Name", emptyList(), 1_000_000))
        assertFalse(DesktopReplyInbox.publishTrusted("a", "b", "c", "Name", listOf(""), 1_000_000))
        assertFalse(DesktopReplyInbox.publishTrusted("a", "b", "c", "Name", listOf("a", "b", "c", "d"), 1_000_000))
        assertTrue(DesktopReplyInbox.publishTrusted("a", "b", "c", "Name", listOf("OK"), 1_000_000))
        assertTrue(DesktopReplyInbox.list("a", "b", 1_601_000).isEmpty())
    }

    @Test fun visibleImeCandidateExpiresOrGetsReplacedBeforeTap() {
        val scope = "com.tencent.mm@UserHandle{0}"
        val account = "desktop-1"
        val session = "wxid-a"
        assertTrue(DesktopReplyInbox.publishTrusted(
            scope, account, session, "张三", listOf("旧回复"), 1_000_000))
        assertTrue(DesktopReplyInbox.isActiveCandidate(
            scope, account, session, 1_000_000, "旧回复", 1_000_100))
        assertFalse(DesktopReplyInbox.isActiveCandidate(
            scope, "desktop-2", session, 1_000_000, "旧回复", 1_000_100))
        assertTrue(DesktopReplyInbox.publishTrusted(
            scope, account, session, "张三", listOf("新回复"), 1_000_200))
        assertFalse(DesktopReplyInbox.isActiveCandidate(
            scope, account, session, 1_000_000, "旧回复", 1_000_300))
        assertTrue(DesktopReplyInbox.isActiveCandidate(
            scope, account, session, 1_000_200, "新回复", 1_000_300))
        assertFalse(DesktopReplyInbox.isActiveCandidate(
            scope, account, session, 1_000_200, "新回复", 1_601_000))
        DesktopReplyInbox.consume(scope, account, session)
        assertFalse(DesktopReplyInbox.isActiveCandidate(
            scope, account, session, 1_000_200, "新回复", 1_000_300))
    }

    @Test fun updatingOneSessionCannotReplaceAnother() {
        assertTrue(DesktopReplyInbox.publishTrusted("p", "acc", "one", "First", listOf("r1"), 1_000_000))
        assertTrue(DesktopReplyInbox.publishTrusted("p", "acc", "two", "Second", listOf("r2"), 1_000_010))
        assertTrue(DesktopReplyInbox.publishTrusted("p", "acc", "one", "First", listOf("new"), 1_000_020))
        val items = DesktopReplyInbox.list("p", "acc", 1_000_030)
        assertEquals(2, items.size)
        assertEquals("new", items.first { it.sessionId == "one" }.replies.single())
        assertEquals("r2", items.first { it.sessionId == "two" }.replies.single())
    }
}
