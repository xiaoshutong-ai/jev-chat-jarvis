package com.jev.probe.capture

/**
 * Notification de-duplication is always scoped to an Android user.
 * Length prefixes avoid ambiguity from punctuation in titles or message text.
 * The key stays volatile and is never written to logs or preferences.
 */
internal object WeChatNotificationDedupKey {
    fun of(userLabel: String, title: String, text: String): String =
        userLabel.length.toString() + ":" + userLabel +
            title.length.toString() + ":" + title +
            text.length.toString() + ":" + text
}