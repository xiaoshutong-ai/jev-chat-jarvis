package com.jev.probe.session

/**
 * Fail-closed resolver for a candidate prepared from desktop conversation history.
 *
 * This module never reads WeChat's view tree, screen or private database. A
 * notification click is a hint that a conversation may have been opened; it
 * is not proof of which conversation is active when the user later types.
 * The last step remains an explicit user confirmation of the named session.
 */
object SessionBindingResolver {
    const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val OPEN_HINT_TTL_MS = 15_000L
    private const val MESSAGE_TIME_TOLERANCE_MS = 120_000L
    private const val CONFIRM_TTL_MS = 60_000L

    data class EditorFocus(
        val packageName: String,
        /** Verified Android profile scope, not a contact nickname. */
        val profileScope: String,
        /** Changes every time an editor focus/session is recreated. */
        val epoch: Long,
        val startedAtMs: Long
    )

    enum class RemovalReason { CLICK, USER_DISMISSED, APP_CANCEL, OTHER }

    data class NotificationOpenHint(
        val profileScope: String,
        /** Android StatusBarNotification key; never used as a conversation ID. */
        val notificationKey: String,
        /** Map Android NotificationListenerService.REASON_CLICK to CLICK. */
        val removalReason: RemovalReason,
        val clickedAtMs: Long,
        /** Opaque fingerprint of notification message text, no raw message here. */
        val messageFingerprint: String?
    )

    data class DesktopConversation(
        val profileScope: String,
        /** Stable per-account desktop session ID, not a display nickname. */
        val sessionId: String,
        val displayName: String,
        val latestMessageFingerprint: String?,
        val latestMessageAtMs: Long
    )

    sealed interface Suggestion {
        data object NoMatch : Suggestion
        data object Ambiguous : Suggestion
        /** Advisory only: never authorizes automatic input or sending. */
        data class One(val conversation: DesktopConversation) : Suggestion
    }

    data class ConfirmedBinding(
        val profileScope: String,
        val sessionId: String,
        val editorEpoch: Long,
        val confirmedAtMs: Long
    )

    fun suggest(
        editor: EditorFocus,
        click: NotificationOpenHint?,
        sessions: List<DesktopConversation>
    ): Suggestion {
        if (editor.packageName != WECHAT_PACKAGE || editor.profileScope.isBlank() || editor.epoch <= 0L) {
            return Suggestion.NoMatch
        }
        val hint = click ?: return Suggestion.NoMatch
        if (hint.removalReason != RemovalReason.CLICK) return Suggestion.NoMatch
        if (hint.profileScope != editor.profileScope || hint.notificationKey.isBlank()) return Suggestion.NoMatch
        if (hint.clickedAtMs <= 0L || hint.clickedAtMs > editor.startedAtMs) return Suggestion.NoMatch
        if (editor.startedAtMs - hint.clickedAtMs > OPEN_HINT_TTL_MS) return Suggestion.NoMatch
        val fingerprint = hint.messageFingerprint?.takeIf { it.isNotBlank() } ?: return Suggestion.NoMatch

        val matches = sessions.filter { candidate ->
            candidate.profileScope == editor.profileScope &&
                candidate.sessionId.isNotBlank() &&
                candidate.latestMessageFingerprint == fingerprint &&
                candidate.latestMessageAtMs > 0L &&
                candidate.latestMessageAtMs <= editor.startedAtMs &&
                editor.startedAtMs - candidate.latestMessageAtMs <= MESSAGE_TIME_TOLERANCE_MS
        }.distinctBy { it.sessionId }
        return when (matches.size) {
            0 -> Suggestion.NoMatch
            1 -> Suggestion.One(matches.single())
            else -> Suggestion.Ambiguous
        }
    }

    /** A named candidate must be confirmed inside this exact editor session. */
    fun confirm(editor: EditorFocus, choice: Suggestion.One, selectedSessionId: String, nowMs: Long): ConfirmedBinding? {
        val item = choice.conversation
        if (editor.packageName != WECHAT_PACKAGE || editor.profileScope.isBlank() || editor.epoch <= 0L) return null
        if (editor.profileScope != item.profileScope || item.sessionId.isBlank() || selectedSessionId != item.sessionId) return null
        if (nowMs < editor.startedAtMs || nowMs - editor.startedAtMs > CONFIRM_TTL_MS) return null
        return ConfirmedBinding(editor.profileScope, item.sessionId, editor.epoch, nowMs)
    }

    /** Use this before any future IME candidate insertion. Never auto-send. */
    fun canInsert(editor: EditorFocus, binding: ConfirmedBinding?, replySessionId: String, nowMs: Long): Boolean {
        val selected = binding ?: return false
        return editor.packageName == WECHAT_PACKAGE &&
            editor.profileScope.isNotBlank() && editor.profileScope == selected.profileScope &&
            editor.epoch > 0L && editor.epoch == selected.editorEpoch &&
            replySessionId.isNotBlank() && replySessionId == selected.sessionId &&
            nowMs >= selected.confirmedAtMs && nowMs - selected.confirmedAtMs <= CONFIRM_TTL_MS
    }
}
