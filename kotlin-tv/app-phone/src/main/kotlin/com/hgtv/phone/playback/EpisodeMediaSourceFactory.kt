// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.hgtv.core.RemoteVideo
import com.hgtv.playback.OpenedStream
import com.hgtv.playback.VideoDataSource

/**
 * 把 `hgtv://episode/<id>` 解析成可播放的加密流。
 *
 * 关键点：**取流不能在主线程做**。`openStream` 要拉播放模型、签名、读 32 KB 头做尺寸复核，
 * 慢的时候几秒。所以这里只搭 MediaSource 的壳，真正打开放在 [LazyVideoDataSource.open]，
 * 那时 ExoPlayer 已经在加载线程上了。
 *
 * 同一个 MediaSource 会反复创建 DataSource（初始化分段、seek 后的重试），因此打开的流按
 * 「集号 + 档位」缓存；换集或换档位时才把旧的关掉，避免每次重试都重新握手。
 */
@UnstableApi
class EpisodeMediaSourceFactory(
    private val opener: (episodeId: String, quality: Int, ahead: List<String>) -> OpenedStream,
) : MediaSource.Factory {

    private val lock = Any()

    /** 已打开的流，按「集号/档位」索引；队尾是最近用过的。 */
    private val opened = LinkedHashMap<String, OpenedStream>()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val episodeId = mediaItem.mediaId
        val extras = mediaItem.mediaMetadata.extras
        val quality = extras?.getInt(EXTRA_QUALITY, DEFAULT_QUALITY) ?: DEFAULT_QUALITY
        val ahead =
            extras?.getString(EXTRA_AHEAD)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        return ProgressiveMediaSource.Factory { LazyVideoDataSource(this, episodeId, quality, ahead) }
            .createMediaSource(mediaItem)
    }

    /** 打开（或复用）这一集的流；由加载线程调用，允许阻塞。 */
    internal fun video(episodeId: String, quality: Int, ahead: List<String>): RemoteVideo {
        val key = "$episodeId/$quality"
        val started = android.os.SystemClock.elapsedRealtime()
        synchronized(lock) {
            opened[key]?.let { hit ->
                // 命中就往队尾挪：淘汰的是「最久没用过的那一集」。
                opened.remove(key)
                opened[key] = hit
                android.util.Log.i(
                    "HGTV",
                    "stream hit ${episodeId.takeLast(8)} cost=${android.os.SystemClock.elapsedRealtime() - started}ms",
                )
                return hit.video
            }
            val fresh = opener(episodeId, quality, ahead)
            opened[key] = fresh
            evictLocked()
            android.util.Log.i(
                "HGTV",
                "stream open ${episodeId.takeLast(8)} cost=${android.os.SystemClock.elapsedRealtime() - started}ms",
            )
            return fresh.video
        }
    }

    /**
     * 一集一个连接：留 4 个够「上一集 / 当前 / 下一集」来回滑。整条剧集塞在同一个 playlist
     * 里之后，切换会先后碰到多个 item，只留一个会把别的踢掉。
     */
    private fun evictLocked() {
        while (opened.size > MAX_OPEN) {
            val oldest = opened.keys.first()
            opened.remove(oldest)?.let { runCatching { it.close() } }
        }
    }

    /** 播放器释放时调用：把底层连接一起关掉。 */
    fun release() {
        synchronized(lock) {
            opened.values.forEach { runCatching { it.close() } }
            opened.clear()
        }
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): MediaSource.Factory = this

    companion object {
        const val EXTRA_QUALITY = "hgtv.quality"
        const val EXTRA_AHEAD = "hgtv.ahead"
        const val DEFAULT_QUALITY = 1080

        /** 同时留几集的流。多了会占着连接和服务端额度，少了来回滑就总是重新握手。 */
        private const val MAX_OPEN = 4
    }
}

/**
 * 把 [VideoDataSource] 延后到第一次 open 才创建。
 *
 * ExoPlayer 要求 `createDataSource` 立刻返回，而 [VideoDataSource] 的构造需要已经准备好的
 * `RemoteVideo`（要联网），所以只能把打开动作推迟到 `open`。
 *
 * 传输回调（transferInitializing / transferStarted / bytesTransferred / transferEnded）必须
 * 由这一层自己上报：挂着监听器的是返回给 ExoPlayer 的这个实例，内层那份没有观察者。
 */
@UnstableApi
private class LazyVideoDataSource(
    private val owner: EpisodeMediaSourceFactory,
    private val episodeId: String,
    private val quality: Int,
    private val ahead: List<String>,
) : BaseDataSource(true) {

    private var delegate: VideoDataSource? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        uri = spec.uri
        val source =
            delegate
                ?: VideoDataSource(owner.video(episodeId, quality, ahead)).also { delegate = it }
        val length = source.open(spec)
        opened = true
        transferStarted(spec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
