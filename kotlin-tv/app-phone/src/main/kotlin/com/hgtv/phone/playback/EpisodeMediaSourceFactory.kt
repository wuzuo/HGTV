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
    private var opened: OpenedStream? = null
    private var openedKey: String? = null

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
        val stream =
            synchronized(lock) {
                val cached = opened
                if (cached != null && openedKey == key) cached
                else {
                    cached?.close()
                    opener(episodeId, quality, ahead).also {
                        opened = it
                        openedKey = key
                    }
                }
            }
        return stream.video
    }

    /** 播放器释放时调用：把底层连接一起关掉。 */
    fun release() {
        synchronized(lock) {
            opened?.close()
            opened = null
            openedKey = null
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
