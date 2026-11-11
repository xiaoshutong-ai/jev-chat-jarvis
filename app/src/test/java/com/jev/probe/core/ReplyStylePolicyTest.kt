package com.jev.probe.core

import com.jev.probe.jev.JevQuestions
import org.junit.Assert.*
import org.junit.Test

class ReplyStylePolicyTest {
    @Test fun defaultToneIsNaturalWithoutCustomerServiceLanguage() {
        assertEquals("", ReplyStylePolicy.customBlock("  "))
        assertTrue(ReplyStylePolicy.DEFAULT_GUIDANCE.contains("普通人"))
        assertTrue(ReplyStylePolicy.DEFAULT_GUIDANCE.contains("过度客气"))
        assertTrue(ReplyStylePolicy.rankingHint("").contains("natural everyday chat"))
        assertFalse(ReplyStylePolicy.DEFAULT_GUIDANCE.contains("一条简短低姿态"))
    }

    @Test fun personalizedStyleIsBoundedAndUsedOnlyAsTonePreference() {
        val prompt = "简短一点，偶尔用“哈哈”，别整得像客服"
        val block = ReplyStylePolicy.customBlock(prompt)
        assertTrue(block.contains(prompt))
        assertTrue(block.contains("不能改写聊天事实"))
        val long = "你".repeat(ReplyStylePolicy.MAX_PROMPT_CHARS + 100)
        assertEquals(ReplyStylePolicy.MAX_PROMPT_CHARS, ReplyStylePolicy.normalize(long).length)
        assertEquals(ReplyStylePolicy.MAX_PROMPT_CHARS, ReplyStylePolicy.normalize(long).length)
        assertFalse(ReplyStylePolicy.normalize("好\u0000呀").contains('\u0000'))
    }

    @Test fun rankingKnowsCustomStyleWithoutChangingFixedChoiceSchema() {
        val q = JevQuestions.rankQuestion(listOf("甲", "乙", "丙"), "简短像老朋友")
            .getJSONObject("best_reply")
        assertTrue(q.getString("instructions").contains("简短像老朋友"))
        assertTrue(q.getString("instructions").contains(JevQuestions.CHAT_DATA_NOTE))
        val criteria = q.getJSONObject("criteria")
        assertEquals("甲", criteria.getString("reply_a"))
        assertEquals("丙", criteria.getString("reply_c"))
    }

    @Test fun changingStyleChangesCacheIdentityWithoutStoringPlainPrompt() {
        val a = ReplyStylePolicy.cacheFingerprint("")
        val b = ReplyStylePolicy.cacheFingerprint("像老朋友")
        assertNotEquals(a, b)
        assertEquals(b, ReplyStylePolicy.cacheFingerprint(" 像老朋友 "))
        assertEquals(64, b.length)
        assertFalse(b.contains("老朋友"))
    }
}
