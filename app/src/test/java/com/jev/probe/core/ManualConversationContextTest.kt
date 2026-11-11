package com.jev.probe.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ManualConversationContextTest {
    @After fun clearAfter() = ManualConversationContext.clear()

    @Test fun labelsAndLastTwentyArePreservedWithoutFakedTimestamps() {
        val transcript = (1..21).joinToString("\n") { i ->
            if (i % 2 == 0) "我：这是我发的第${i}条消息" else "对方：这是对方第${i}条消息"
        }
        val parsed = ManualTranscriptParser.parse(transcript)
        assertEquals(20, parsed.messages.size)
        assertEquals("me", parsed.messages.first().side)
        assertEquals("other", parsed.messages.last().side)
        assertNull(parsed.messages.first().ts)
        assertEquals("这是对方第21条消息", parsed.messages.last().text)
    }

    @Test fun strictLabelsRejectAmbiguousOrMalformedSpeaker() {
        for (raw in listOf(
            "消息一\n对方：消息二",
            "甲：消息一\n对方：消息二",
            "我：消息一\n我：消息二",
            "对方：消息一\n对方：",
            "对方：消息一\n对方：${"x".repeat(501)}"
        )) {
            try { ManualTranscriptParser.parse(raw); fail(raw) }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun onlyExactLatestIncomingCopyActivatesUnverifiedContext() {
        val now = 1_000_000L
        ManualConversationContext.import(
            "我：明天我先看看时间\n对方：那你大概什么时候有空", true, now
        )
        assertNull(ManualConversationContext.matchCopiedIncoming("其他聊天的消息", now + 1000L))
        val match = ManualConversationContext.matchCopiedIncoming("那你大概什么时候有空", now + 1000L)!!
        assertEquals(2, match.snapshot.messages.size)
        assertNull(match.snapshot.title)
        assertTrue(match.snapshot.note!!.contains("未核验"))
        assertTrue(match.snapshot.styleExamples.isEmpty())
    }

    @Test fun styleRequiresAtLeastThreeExplicitOwnExamples() {
        val raw = listOf(
            "我：嗯好，我晚一点再跟你说",
            "对方：那你周末呢",
            "我：周末应该可以，我看看",
            "我：到时候我再跟你确认哈",
            "我：收到，晚点我回复你",
            "对方：那就等你消息了"
        ).joinToString("\n")
        val now = 2_000_000L
        val on = ManualConversationContext.import(raw, true, now)
        assertTrue(on.styleSamples >= 3)
        val match = ManualConversationContext.matchCopiedIncoming("那就等你消息了", now + 1)!!
        assertTrue(match.snapshot.styleExamples.all { it in raw })
        val off = ManualConversationContext.import(raw, false, now + 2)
        assertEquals(0, off.styleSamples)
    }

    @Test fun importedContextExpiresAndNeverPersistsAcrossClear() {
        val now = 3_000_000L
        ManualConversationContext.import("我：明白稍后回复你\n对方：好的没问题", false, now)
        assertTrue(ManualConversationContext.status(now).active)
        assertNull(ManualConversationContext.matchCopiedIncoming("好的没问题", now + 60 * 60_000L))
        assertNull(ManualConversationContext.matchCopiedIncoming("好的没问题", now + 60 * 60_000L + 1))
        assertFalse(ManualConversationContext.status(now + 60 * 60_000L + 1).active)
        ManualConversationContext.import("我：明白稍后回复你\n对方：好的没问题", false, now)
        ManualConversationContext.clear()
        assertNull(ManualConversationContext.matchCopiedIncoming("好的没问题", now + 3))
    }

    @Test fun newImportBumpsRevisionSoPaidCacheCannotReuseStaleContext() {
        val now = 4_000_000L
        val raw = "我：好的我看一下再答复\n对方：你看到了吗"
        ManualConversationContext.import(raw, false, now)
        val first = ManualConversationContext.matchCopiedIncoming("你看到了吗", now)!!.revision
        ManualConversationContext.import(raw, true, now + 1)
        val second = ManualConversationContext.matchCopiedIncoming("你看到了吗", now + 1)!!.revision
        assertNotEquals(first, second)
    }
}
