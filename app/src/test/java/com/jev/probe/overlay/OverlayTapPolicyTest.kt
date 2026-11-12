package com.jev.probe.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTapPolicyTest {
    private val t = 1_000_000L

    @Test fun clipboardSafeModeIsOneTapWhenNoNotification() {
        assertEquals(OverlayTapPolicy.Action.EXPLICIT_CLIPBOARD,
            OverlayTapPolicy.decide(false, false, 0L, true, t))
    }

    @Test fun expandedPanelCollapsesBeforeAnyNewClipboardRead() {
        assertEquals(OverlayTapPolicy.Action.COLLAPSE,
            OverlayTapPolicy.decide(true, true, t, true, t))
    }

    @Test fun freshNotificationAndClipboardMustBeChosenExplicitly() {
        assertEquals(OverlayTapPolicy.Action.CHOICE_MENU,
            OverlayTapPolicy.decide(false, true, t - 1_000L, true, t))
    }

    @Test fun notificationWithoutClipboardCanStillBeOneTap() {
        assertEquals(OverlayTapPolicy.Action.NOTIFICATION,
            OverlayTapPolicy.decide(false, true, t - 1_000L, false, t))
    }

    @Test fun oldOrFutureNotificationDoesNotHijackCopiedMessage() {
        assertEquals(OverlayTapPolicy.Action.EXPLICIT_CLIPBOARD,
            OverlayTapPolicy.decide(false, true, t - 20_001L, true, t))
        assertEquals(OverlayTapPolicy.Action.EXPLICIT_CLIPBOARD,
            OverlayTapPolicy.decide(false, true, t + 1L, true, t))
    }

    @Test fun noClipboardCapabilityReturnsSafeMenu() {
        assertEquals(OverlayTapPolicy.Action.SAFE_MENU,
            OverlayTapPolicy.decide(false, false, 0L, false, t))
    }
}
