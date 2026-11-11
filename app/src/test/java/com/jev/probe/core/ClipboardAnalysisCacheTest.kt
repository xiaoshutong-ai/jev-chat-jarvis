package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardAnalysisCacheTest {
    private fun saved(reply: String) = ClipboardAnalysisCache.Result(
        judgment = Analysis(
            trueIntent = null, dangerLevel = null, sheNeeds = null,
            shouldReplyNow = null, bestAction = null, tensionResolved = null,
            literalQuestion = null, rankedReplies = emptyList(), latencyMs = 0L
        ),
        replies = listOf(RankedReply(reply, 1.0)),
        contextNotes = 1
    )

    @Test fun duplicateTapWhilePendingCannotStartAnotherModelCall() {
        val cache = ClipboardAnalysisCache()
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("message:route"))
        assertEquals(ClipboardAnalysisCache.Decision.Pending, cache.begin("message:route"))
        assertFalse(cache.invalidateCompleted("message:route"))
    }

    @Test fun completedResultIsReusedAfterTimeAndOtherMessages() {
        val cache = ClipboardAnalysisCache()
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("one:route"))
        assertTrue(cache.complete("one:route", saved("回复甲")))
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("two:route"))
        val reused = cache.begin("one:route") as ClipboardAnalysisCache.Decision.Cached
        assertEquals("回复甲", reused.result.replies.single().text)
        assertEquals(1, reused.result.contextNotes)
    }

    @Test fun changedRouteOrMessageStartsFreshAnalysis() {
        val cache = ClipboardAnalysisCache()
        cache.begin("same:provider-A")
        cache.complete("same:provider-A", saved("旧"))
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("same:provider-B"))
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("different:provider-A"))
    }

    @Test fun explicitRefreshAllowsOnlyCompletedResultToRecalculate() {
        val cache = ClipboardAnalysisCache()
        cache.begin("same:route")
        cache.complete("same:route", saved("旧"))
        assertTrue(cache.invalidateCompleted("same:route"))
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("same:route"))
        assertFalse(cache.invalidateCompleted("same:route"))
    }

    @Test fun failureReleasesPendingWithoutCachingError() {
        val cache = ClipboardAnalysisCache()
        cache.begin("same:route")
        cache.fail("same:route")
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("same:route"))
        assertFalse(cache.complete("missing", saved("no")))
    }

    @Test fun memoryIsBoundedAndOldestCompletedEntryIsEvicted() {
        val cache = ClipboardAnalysisCache(capacity = 2)
        cache.begin("one"); cache.complete("one", saved("1"))
        cache.begin("two"); cache.complete("two", saved("2"))
        assertTrue(cache.begin("one") is ClipboardAnalysisCache.Decision.Cached)
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("three"))
        assertEquals(ClipboardAnalysisCache.Decision.New, cache.begin("two"))
    }
}
