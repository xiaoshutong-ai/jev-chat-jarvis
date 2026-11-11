package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun allNativeJudgeQuestionsTreatChatContentsAsUntrustedData() {
        val questions = JevQuestions.judge()
        val keys = questions.keys()
        var checked = 0
        while (keys.hasNext()) {
            val q = questions.getJSONObject(keys.next())
            val instructions = q.getString("instructions")
            assertTrue(instructions.contains(JevQuestions.CHAT_DATA_NOTE))
            checked++
        }
        assertEquals(7, checked)
        val ranked = JevQuestions.rankQuestion(listOf("甲", "乙", "丙"))
        assertTrue(
            ranked.getJSONObject("best_reply")
                .getString("instructions").contains(JevQuestions.CHAT_DATA_NOTE)
        )
    }
}
