package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitClipboardPolicyTest {
    @Test fun copiedMessageIsOneUnverifiedIncomingTextNotNotificationHistory() {
        val snapshot = ExplicitClipboardPolicy.fromUserCopy(
            "  你明天有空吗？\n几点方便？  ", 1_000_000L
        )!!
        assertNull(snapshot.title)
        assertEquals(1, snapshot.messages.size)
        assertEquals("other", snapshot.messages.single().side)
        assertEquals("你明天有空吗？\n几点方便？", snapshot.messages.single().text)
        assertEquals(1_000_000L, snapshot.messages.single().ts)
        assertTrue(snapshot.note!!.contains("未核验联系人"))
        assertTrue(snapshot.note!!.contains("消息方向"))
    }

    @Test fun emptyTooLongNulOrInvalidClockDoesNotProceedToProvider() {
        assertNull(ExplicitClipboardPolicy.fromUserCopy(" ", 1_000L))
        assertNull(ExplicitClipboardPolicy.fromUserCopy("a".repeat(4001), 1_000L))
        assertNull(ExplicitClipboardPolicy.fromUserCopy("a\u0000b", 1_000L))
        assertNull(ExplicitClipboardPolicy.fromUserCopy("abc", 0L))
        assertEquals(4000, ExplicitClipboardPolicy.fromUserCopy("a".repeat(4000), 1_000L)!!
            .messages.single().text.length)
    }

    @Test fun duplicateGuardWorksOnlyInMemoryWithinShortWindow() {
        val guard = RecentExplicitCopy()
        assertFalse(guard.checkAndMark("Hello", 100_000L))
        assertTrue(guard.checkAndMark(" Hello ", 100_300L))
        assertFalse(guard.checkAndMark("Different", 100_400L))
        assertFalse(guard.checkAndMark("Hello", 100_500L))
        assertTrue(guard.checkAndMark("Hello", 101_000L))
        // The anti-double-tap window must not suppress an identical sentence
        // intentionally copied from another unverified chat one minute later.
        assertFalse(guard.checkAndMark("Hello", 111_001L))
        guard.clear()
        assertFalse(guard.checkAndMark("Hello", 111_100L))
        assertFalse(guard.checkAndMark("Hello", 0L))
    }

    @Test fun fingerprintsDoNotContainRawTextOrContactIdentity() {
        val fp = ExplicitClipboardPolicy.fingerprint("Sensitive message")
        assertEquals(64, fp.length)
        assertTrue(fp.matches(Regex("[0-9a-f]+")))
        assertFalse(fp.contains("Sensitive"))
        assertEquals(fp, ExplicitClipboardPolicy.fingerprint(" Sensitive message "))
    }
}
