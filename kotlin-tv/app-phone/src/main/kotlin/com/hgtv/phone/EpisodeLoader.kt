// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone

import com.hgtv.playback.CodecCapabilities
import com.hgtv.playback.OpenedStream
import com.hgtv.playback.openStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 取流：先按接口元数据选档，再用 MP4 头里的真实尺寸复核，逐级往下降。
 *
 * 判据与 TV 端完全一致（[com.hgtv.playback.openStream] 就是那边抽出来的），
 * 手机端的差别只是文案——这里不会出现「没有电视可播放的清晰度」这种话。
 */
object EpisodeLoader {
    suspend fun open(
        episodeId: String,
        maxQuality: Int,
        metadataAhead: List<String> = emptyList(),
    ): OpenedStream =
        withContext(Dispatchers.IO) {
            openStream(
                http = PhoneApp.http,
                repository = PhoneApp.repository,
                id = episodeId,
                maxQuality = maxQuality,
                supportsRendition = { CodecCapabilities.supports(it) },
                initialBytes = 32 * 1024,
                metadataAhead = metadataAhead,
                supportsTrack = { it == null || CodecCapabilities.supports(it) },
                relaxedRendition = { CodecCapabilities.supportsIgnoringLessons(it) },
                relaxedTrack = { it == null || CodecCapabilities.supportsIgnoringLessons(it) },
            ) ?: throw IOException("这个清晰度解不了，换个剧集或稍后再试")
        }
}
