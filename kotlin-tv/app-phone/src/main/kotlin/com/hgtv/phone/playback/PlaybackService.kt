// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hgtv.phone.EpisodeLoader
import com.hgtv.phone.MainActivity

/**
 * 后台播放的宿主。
 *
 * 播放器必须活在这里（而不是 Composable 里），否则切到桌面、进画中画、锁屏之后
 * 系统随时可以把 Activity 连同播放器一起回收，也没有可用的媒体通知。
 *
 * 界面侧通过 [androidx.media3.session.MediaController] 连上来控制；数据源由
 * [EpisodeMediaSourceFactory] 负责把加密流接进 ExoPlayer。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var factory: EpisodeMediaSourceFactory? = null

    override fun onCreate() {
        super.onCreate()
        val sourceFactory = EpisodeMediaSourceFactory { episodeId, quality, ahead ->
            // 这里已经在 ExoPlayer 的加载线程上，允许阻塞；取流本身要发请求、读头部，
            // 只能同步等它完成（渲染层拿不到协程作用域）。
            kotlinx.coroutines.runBlocking {
                EpisodeLoader.open(
                    episodeId = episodeId,
                    maxQuality = quality,
                    metadataAhead = ahead,
                )
            }
        }
        factory = sourceFactory

        val loadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 1000, 2000)
                .setTargetBufferBytes(12 * 1024 * 1024)
                .build()
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val player =
            ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(sourceFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .setHandleAudioBecomingNoisy(true)
                // 后台播放要能撑过息屏：网络唤醒锁交给播放器自己按需持有。
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()

        session =
            MediaSession.Builder(this, player)
                .setSessionActivity(openAppIntent())
                .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 从最近任务里划掉时：还在播就继续播（交给前台服务），停了就收工。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        val keep = player != null && player.playWhenReady && player.mediaItemCount > 0
        if (!keep) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        factory?.release()
        factory = null
        super.onDestroy()
    }
}
