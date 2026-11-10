package com.jev.probe.overlay

/** Pure, deterministic bubble-tap routing. Never polls the clipboard. */
object OverlayTapPolicy {
    const val NOTIFICATION_TTL_MS = 20_000L

    enum class Action { COLLAPSE, NOTIFICATION, EXPLICIT_CLIPBOARD, CHOICE_MENU, SAFE_MENU }

    fun decide(
        expanded: Boolean,
        hasNotificationAction: Boolean,
        notificationReadyAtMs: Long,
        clipboardOneTapAvailable: Boolean,
        nowMs: Long
    ): Action {
        if (expanded) return Action.COLLAPSE
        val freshNotification = hasNotificationAction && notificationReadyAtMs > 0L &&
            nowMs >= notificationReadyAtMs &&
            nowMs - notificationReadyAtMs <= NOTIFICATION_TTL_MS
        return when {
            freshNotification && clipboardOneTapAvailable -> Action.CHOICE_MENU
            freshNotification -> Action.NOTIFICATION
            clipboardOneTapAvailable -> Action.EXPLICIT_CLIPBOARD
            else -> Action.SAFE_MENU
        }
    }
}
