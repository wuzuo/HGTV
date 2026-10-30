// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

import android.util.Log
import com.hgtv.core.ContentHttpException
import com.hgtv.core.ContentRepository
import com.hgtv.core.EmptyPlaybackResponseException
import com.hgtv.core.PlaybackQuality
import com.hgtv.core.RemoteVideo
import com.hgtv.core.Rendition
import com.hgtv.core.VideoTracks
import okhttp3.OkHttpClient

/** 只展示已知错误类别，不把响应正文、媒体地址或查询凭据带到界面和日志。 */
internal fun playbackFailureSummary(problem: Throwable): String {
    val causes = generateSequence(problem) { it.cause }.take(8).toList()
    if (causes.any { it is com.hgtv.core.PlaybackDeviceRegistrationException })
        return "播放设备注册失败"
    if (causes.any { it is EmptyPlaybackResponseException }) return "播放信息接口返回空响应"
    causes.filterIsInstance<ContentHttpException>().firstOrNull()?.let {
        return "播放信息请求失败（HTTP ${it.statusCode}）"
    }
    causes
        .mapNotNull { it.message }
        .firstOrNull { it.matches(Regex("播放接口暂不可用（错误码 \\d+）")) }
        ?.let {
            return it
        }
    if (causes.any { it is java.net.SocketTimeoutException }) return "网络请求超时"
    if (causes.any { it is java.net.UnknownHostException }) return "无法解析服务器域名"
    if (causes.any { it is java.io.EOFException }) return "视频分段提前断开"
    val known = setOf("播放请求已取消", "播放准备已取消", "视频分段范围不匹配", "视频资源已变化，请重试", "播放信息为空", "播放地址格式异常")
    causes
        .mapNotNull { it.message }
        .firstOrNull { it in known }
        ?.let {
            return it
        }
    if (problem is androidx.media3.common.PlaybackException) return "播放器错误：${problem.errorCodeName}"
    return if (causes.any { it is java.io.IOException }) "播放数据读取失败" else "播放准备失败"
}

internal class OpenedStream(
    val video: RemoteVideo,
    val rendition: Rendition?,
    val tiers: List<Rendition>,
    val track: VideoTracks.Track?,
) : java.io.Closeable {
    override fun close() = video.close()
}

/**
 * Opens the first rendition the device can actually decode: select by API metadata, then verify the
 * coded size read from the MP4 header and step down the ladder on mismatch.
 */
internal fun openStream(
    http: OkHttpClient,
    repository: ContentRepository,
    id: String,
    maxQuality: Int,
    supportsRendition: (Rendition) -> Boolean,
    initialBytes: Int = 256 * 1024,
    metadataAhead: List<String> = emptyList(),
    supportsTrack: (VideoTracks.Track?) -> Boolean,
): OpenedStream? {
    val ladder = repository.streamLadder(id, metadataAhead)
    for (rendition in ladder.playbackOrder(maxQuality, supportsRendition)) {
        val video = RemoteVideo(http, rendition.toStreamInfo())
        try {
            video.prepare(initialBytes)
            val track = video.trackInfo()
            if (supportsTrack(track))
                return OpenedStream(video, rendition, ladder.renditions, track)
            Log.i(
                "HGTV",
                "Skipped ${rendition.quality}P ${rendition.codec}: coded ${track?.width}x${track?.height} beyond decoder capability",
            )
            video.close()
        } catch (error: Exception) {
            video.close()
            throw error
        }
    }
    return null
}

/** Next manual retry tier: only offer a lower option than what was already attempted. */
internal fun nextLowerQuality(attempted: Int): Int? =
    PlaybackQuality.options.filter { it < attempted }.maxOrNull()

/**
 * Auto-downgrade is worth another attempt while budget remains and a strictly lower tier exists.
 */
internal fun shouldAutoDowngrade(
    attempts: Int,
    maxAttempts: Int,
    tiers: List<Rendition>,
    currentQuality: Int,
): Boolean =
    attempts < maxAttempts &&
        currentQuality > 0 &&
        tiers.any { it.quality in 1 until currentQuality }
