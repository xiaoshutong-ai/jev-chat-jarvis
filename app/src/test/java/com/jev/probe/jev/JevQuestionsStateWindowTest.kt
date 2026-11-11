package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Test

class JevQuestionsStateWindowTest {
    @Test fun explicitTwentyMessageWindowUsesLatestTwentyInCorrectOrder() {
        val messages = (1..24).map { i ->
            Msg(if (i % 2 == 0) "other" else "me", "message-$i")
        }
        val json = JevQuestions.buildState(ChatSnapshot(null, messages), "关系未核验")
        val chat = json.getJSONObject("chat")
        val items = chat.getJSONArray("messages")
        assertEquals(20, items.length())
        assertEquals("message-5", items.getJSONObject(0).getString("text"))
        assertEquals("message-24", items.getJSONObject(19).getString("text"))
        assertEquals("other", chat.getString("latest_from"))
    }
}
