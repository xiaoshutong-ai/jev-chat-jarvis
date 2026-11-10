package com.jev.probe.session

import com.jev.probe.session.SessionBindingResolver as R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBindingResolverTest {
    private val editor = R.EditorFocus("com.tencent.mm", "com.tencent.mm@0", 7, 1_000_000)
    private val clicked = R.NotificationOpenHint(
        "com.tencent.mm@0", "notification-key", R.RemovalReason.CLICK, 999_700, "sha256:hello"
    )
    private val a = R.DesktopConversation("com.tencent.mm@0", "wxid-a", "A", "sha256:hello", 999_650)

    @Test fun uniqueClickWithMatchingMessageIsOnlyASuggestion() {
        val suggestion = R.suggest(editor, clicked, listOf(a))
        assertTrue(suggestion is R.Suggestion.One)
        assertFalse(R.canInsert(editor, null, "wxid-a", 1_000_000))
    }

    @Test fun dismissedNotificationOrMissingHintCannotBind() {
        assertEquals(R.Suggestion.NoMatch, R.suggest(editor, null, listOf(a)))
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked.copy(removalReason = R.RemovalReason.USER_DISMISSED), listOf(a)))
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked.copy(removalReason = R.RemovalReason.APP_CANCEL), listOf(a)))
    }

    @Test fun wrongAppOrAndroidProfileCannotBind() {
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor.copy(packageName = "com.example.other"), clicked, listOf(a)))
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked.copy(profileScope = "com.tencent.mm@999"), listOf(a)))
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked, listOf(a.copy(profileScope = "com.tencent.mm@999"))))
    }

    @Test fun missingOrStaleTimingAndTextIsNotAConversationId() {
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked.copy(messageFingerprint = null), listOf(a)))
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked.copy(messageFingerprint = "another-message"), listOf(a)))
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked.copy(clickedAtMs = 984_000), listOf(a)))
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked.copy(clickedAtMs = 1_000_001), listOf(a)))
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked, listOf(a.copy(latestMessageAtMs = 700_000))))
    }

    @Test fun sameMessageInTwoDifferentConversationsIsAmbiguous() {
        assertEquals(R.Suggestion.Ambiguous,
            R.suggest(editor, clicked, listOf(a, a.copy(sessionId = "wxid-b"))))
        assertTrue(R.suggest(editor, clicked, listOf(a, a.copy())) is R.Suggestion.One)
    }

    @Test fun explicitUserConfirmationIsBoundToExactChatAndEditorEpoch() {
        val suggestion = R.suggest(editor, clicked, listOf(a)) as R.Suggestion.One
        assertNull(R.confirm(editor, suggestion, "wxid-b", 1_000_200))
        val bound = R.confirm(editor, suggestion, "wxid-a", 1_000_200)
        assertNotNull(bound)
        assertTrue(R.canInsert(editor, bound, "wxid-a", 1_000_300))
        assertFalse(R.canInsert(editor, bound, "wxid-b", 1_000_300))
        assertFalse(R.canInsert(editor.copy(epoch = 8), bound, "wxid-a", 1_000_300))
        assertFalse(R.canInsert(editor.copy(profileScope = "com.tencent.mm@999"), bound, "wxid-a", 1_000_300))
        assertFalse(R.canInsert(editor.copy(packageName = "com.other"), bound, "wxid-a", 1_000_300))
    }

    @Test fun confirmedBindingIsShortLivedAndRejectsClockReversal() {
        val suggestion = R.suggest(editor, clicked, listOf(a)) as R.Suggestion.One
        val bound = R.confirm(editor, suggestion, "wxid-a", 1_000_200)
        assertFalse(R.canInsert(editor, bound, "wxid-a", 999_900))
        assertFalse(R.canInsert(editor, bound, "wxid-a", 1_060_201))
        assertNull(R.confirm(editor, suggestion, "wxid-a", 1_061_000))
    }

    @Test fun userSelectedSessionNeverSpoofsNotificationOpenHint() {
        val manual = a.copy(
            sessionId = R.USER_SELECTED_SESSION_PREFIX + "f7b0d0349e2a",
            displayName = "手动选择：同学甲（联系人未核验）"
        )
        // Even identical fingerprints and timing cannot authenticate a
        // user-labelled clipboard session as the opened WeChat conversation.
        assertEquals(R.Suggestion.NoMatch,
            R.suggest(editor, clicked, listOf(manual)))
        assertTrue(R.suggest(editor, clicked, listOf(a, manual)) is R.Suggestion.One)
        assertFalse(R.canInsert(editor, null, manual.sessionId, 1_000_200))
    }
}
