package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WeChatNotificationDedupKeyTest {
    @Test fun sameUserSameNotificationIsDeduped() {
        val first = WeChatNotificationDedupKey.of("UserHandle{0}", "Test", "hello")
        assertEquals(first, WeChatNotificationDedupKey.of("UserHandle{0}", "Test", "hello"))
    }

    @Test fun identicalNotificationInClonedWechatIsNotDedupedAcrossUsers() {
        val a = WeChatNotificationDedupKey.of("UserHandle{0}", "Test", "hello")
        val b = WeChatNotificationDedupKey.of("UserHandle{999}", "Test", "hello")
        assertNotEquals(a, b)
    }

    @Test fun titleAndBodyBoundariesCannotCollide() {
        val a = WeChatNotificationDedupKey.of("UserHandle{0}", "x:y", "z")
        val b = WeChatNotificationDedupKey.of("UserHandle{0}", "x", "y:z")
        assertNotEquals(a, b)
    }
}