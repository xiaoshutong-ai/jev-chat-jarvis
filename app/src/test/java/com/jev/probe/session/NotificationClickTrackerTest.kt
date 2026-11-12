package com.jev.probe.session

import com.jev.probe.session.SessionBindingResolver as R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NotificationClickTrackerTest {
    @After fun cleanup() = NotificationClickTracker.clear()

    @Test fun digestIsEphemeralAndNormalizesLineBreaks() {
        assertNull(NotificationClickTracker.fingerprint("   "))
        assertEquals(
            NotificationClickTracker.fingerprint("hello\r\nworld"),
            NotificationClickTracker.fingerprint(" hello\nworld ")
        )
    }

    @Test fun dismissAndAppCancelNeverProduceClickHint() {
        NotificationClickTracker.recordPosted("com.tencent.mm@0", "key-a", "你好", 1_000_000)
        NotificationClickTracker.recordRemoved(
            "com.tencent.mm@0", "key-a", R.RemovalReason.USER_DISMISSED, 1_000_100
        )
        assertNull(NotificationClickTracker.latestClick("com.tencent.mm@0", 1_000_200))
        NotificationClickTracker.recordPosted("com.tencent.mm@0", "key-b", "你好", 1_000_300)
        NotificationClickTracker.recordRemoved(
            "com.tencent.mm@0", "key-b", R.RemovalReason.APP_CANCEL, 1_000_400
        )
        assertNull(NotificationClickTracker.latestClick("com.tencent.mm@0", 1_000_500))
    }

    @Test fun tappingWrongProfileCannotBind() {
        NotificationClickTracker.recordPosted("com.tencent.mm@0", "key-a", "你好", 1_000_000)
        NotificationClickTracker.recordRemoved(
            "com.tencent.mm@999", "key-a", R.RemovalReason.CLICK, 1_000_100
        )
        assertNull(NotificationClickTracker.latestClick("com.tencent.mm@0", 1_000_200))
    }

    @Test fun clickIsOnlyTemporaryHint() {
        NotificationClickTracker.recordPosted("com.tencent.mm@0", "key-a", "你好", 1_000_000)
        NotificationClickTracker.recordRemoved(
            "com.tencent.mm@0", "key-a", R.RemovalReason.CLICK, 1_000_100
        )
        val hint = NotificationClickTracker.latestClick("com.tencent.mm@0", 1_000_200)
        assertNotNull(hint)
        assertEquals(R.RemovalReason.CLICK, hint!!.removalReason)
        assertEquals(NotificationClickTracker.fingerprint("你好"), hint.messageFingerprint)
        assertNull(NotificationClickTracker.latestClick("com.tencent.mm@999", 1_000_200))
        assertNull(NotificationClickTracker.latestClick("com.tencent.mm@0", 1_015_101))
    }

    @Test fun expiredPostCannotCreateClick() {
        NotificationClickTracker.recordPosted("com.tencent.mm@0", "key-a", "你好", 1_000_000)
        NotificationClickTracker.recordRemoved(
            "com.tencent.mm@0", "key-a", R.RemovalReason.CLICK, 3_000_000
        )
        assertNull(NotificationClickTracker.latestClick("com.tencent.mm@0", 3_000_001))
    }
}
