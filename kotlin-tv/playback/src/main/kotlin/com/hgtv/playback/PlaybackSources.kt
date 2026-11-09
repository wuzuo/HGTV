// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.playback

import android.util.Log
import com.hgtv.core.ContentHttpException
import com.hgtv.core.ContentRepository
import com.hgtv.core.EmptyPlaybackResponseException
import com.hgtv.core.PlaybackQuality
import com.hgtv.core.RemoteVideo
import com.hgtv.core.Rendition
import com.hgtv.core.StreamLadder
import com.hgtv.core.VideoTracks
import okhttp3.OkHttpClient

/** 只展示已知错误类别，不把响应正文、媒体地址或查询凭据带到界面和日志。 */
fun playbackFailureSummary(problem: Throwable): String {
    val causes = generateSequence(problem) { it.cause }.take(8).toList()
    // 编码不兼容是确定性结论：怎么说都不会变，别让用户以为等一会儿就好。
    if (causes.any { it is com.hgtv.core.UnsupportedCodecException })
        return "这一集编码不受支持（BVC2／H.266），请换一集"
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
    val known =
        setOf(
            "播放请求已取消",
            "播放准备已取消",
            "视频分段范围不匹配",
            "视频资源已变化，请重试",
            "播放信息为空",
            "播放地址格式异常",
            "这一集没有这台设备能播的清晰度，换个清晰度或稍后再试",
        )
    causes
        .mapNotNull { it.message }
        .firstOrNull { it in known }
        ?.let {
            return it
        }
    if (problem is androidx.media3.common.PlaybackException) return "播放器错误：${problem.errorCodeName}"
    return if (causes.any { it is java.io.IOException }) "播放数据读取失败" else "播放准备失败"
}

class OpenedStream(
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
 *
 * [relaxedRendition] / [relaxedTrack] 是兜底判定：只用静态探测，不看学到的失败记录。
 * 学到的记录可能来自误判（例如进程被原生库带走被当成「这套格式解不了」），一旦误记，
 * 严格判定会把所有档位都过滤掉，用户看到的就是「重试没用、换清晰度也没用」。
 * 两条路都走到尽头时宁可放行试一次：失败只是回到原来的错误提示，不放行就是确定性播不了。
 */
fun openStream(
    http: OkHttpClient,
    repository: ContentRepository,
    id: String,
    maxQuality: Int,
    supportsRendition: (Rendition) -> Boolean,
    initialBytes: Int = 256 * 1024,
    metadataAhead: List<String> = emptyList(),
    supportsTrack: (VideoTracks.Track?) -> Boolean,
    relaxedRendition: (Rendition) -> Boolean = supportsRendition,
    relaxedTrack: (VideoTracks.Track?) -> Boolean = supportsTrack,
): OpenedStream? {
    val ladder = repository.streamLadder(id, metadataAhead)
    val strict = ladder.playbackOrder(maxQuality, supportsRendition)
    openFirst(ladder, http, strict, initialBytes, supportsTrack)?.let { return it }
    val relaxed = ladder.playbackOrder(maxQuality, relaxedRendition)
    if (relaxed.isEmpty()) return null
    // 即使两轮的顺序一样也要重试：真正放宽的是 supportsTrack —— 它才拿得到 MP4 头里的
    // 真实尺寸，而学到的失败记录正是按那个尺寸记的。
    Log.w(
        "HGTV",
        "No rendition passed the strict decoder check; retrying ${relaxed.size} tier(s) " +
            "against the static probe only (learned failures may be wrong)",
    )
    return openFirst(ladder, http, relaxed, initialBytes, relaxedTrack)?.also { opened ->
        // 严格判定说不行、放宽后却打开了，说明那条学习记录是误判，撤掉它，
        // 免得下次播放还要多走这一轮。
        opened.track?.let { CodecCapabilities.forgive(it.fourcc, it.width, it.height) }
    }
}

private fun openFirst(
    ladder: StreamLadder,
    http: OkHttpClient,
    order: List<Rendition>,
    initialBytes: Int,
    supportsTrack: (VideoTracks.Track?) -> Boolean,
): OpenedStream? {
    for (rendition in order) {
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
fun nextLowerQuality(attempted: Int): Int? =
    PlaybackQuality.options.filter { it < attempted }.maxOrNull()

/**
 * Auto-downgrade is worth another attempt while budget remains and a strictly lower tier exists.
 */
fun shouldAutoDowngrade(
    attempts: Int,
    maxAttempts: Int,
    tiers: List<Rendition>,
    currentQuality: Int,
): Boolean =
    attempts < maxAttempts &&
        currentQuality > 0 &&
        tiers.any { it.quality in 1 until currentQuality }
