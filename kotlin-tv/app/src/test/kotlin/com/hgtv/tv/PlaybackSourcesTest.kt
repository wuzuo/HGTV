package com.hgtv.tv

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourcesTest {
    @Test
    fun failureSummaryKeepsTheRealCauseWithoutLeakingAddressesOrResponseText() {
        val empty = com.hgtv.core.EmptyPlaybackResponseException()
        assertEquals("播放信息接口返回空响应", playbackFailureSummary(empty))
        assertEquals(
            "播放信息请求失败（HTTP 503）",
            playbackFailureSummary(com.hgtv.core.ContentHttpException(503)),
        )
        assertEquals("视频分段提前断开", playbackFailureSummary(java.io.EOFException("fixture")))
        val unknown =
            playbackFailureSummary(java.io.IOException("https://example.com/video?token=test-only"))
        assertFalse(unknown.contains("https://"))
        assertFalse(unknown.contains("token"))
    }

    @Test
    fun manualRetryOnlyOffersLowerTierThanAttempted() {
        assertEquals(720, nextLowerQuality(1080))
        assertEquals(480, nextLowerQuality(720))
        assertNull(nextLowerQuality(480))
    }

    @Test
    fun autoDowngradeNeedsBudgetLowerTierAndKnownQuality() {
        val tiers =
            listOf(
                com.hgtv.core.Rendition(1080, "h264", 1080, 1920, "u1", null),
                com.hgtv.core.Rendition(480, "h264", 480, 854, "u2", null),
            )
        assertTrue(shouldAutoDowngrade(0, 2, tiers, 1080))
        assertFalse(shouldAutoDowngrade(2, 2, tiers, 1080)) // budget exhausted
        assertFalse(shouldAutoDowngrade(0, 2, tiers, 480)) // nothing lower
        assertFalse(shouldAutoDowngrade(0, 2, emptyList(), 1080))
        assertFalse(shouldAutoDowngrade(0, 2, tiers, 0)) // unknown attempted quality
    }
}
