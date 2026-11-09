package com.hgtv.playback

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
    fun failureSummaryNamesUnsupportedCodecInsteadOfAskingToRetry() {
        // ExoPlayer 把数据源的 IOException 统一包成 Source error，原因留在 cause 链里。
        val wrapped =
            java.io.IOException("Source error", com.hgtv.core.UnsupportedCodecException(listOf("bytevc2")))
        assertEquals("这一集编码不受支持（BVC2／H.266），请换一集", playbackFailureSummary(wrapped))
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
