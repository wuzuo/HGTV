// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.playback

import android.app.NotificationManager
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
 * 播放宿主。
 *
 * 播放器必须活在这里（而不是 Composable 里），否则切到桌面、进画中画、锁屏之后
 * 系统随时可以把 Activity 连同播放器一起回收。界面侧通过
 * [androidx.media3.session.MediaController] 连上来控制；数据源由
 * [EpisodeMediaSourceFactory] 负责把加密流接进 ExoPlayer。
 *
 * 通知栏不留常驻控制条，详见 [onUpdateNotification]。
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

    /**
     * 通知栏策略：播放时不挂媒体通知。
     *
     * media3 默认会在播放开始后 startForeground，并在通知栏常驻一条带播放/暂停按钮的
     * 控制条——那是音乐播放器的做法，看剧时它只会占地方。这里**完全不调父类**：
     * media3 的 `onUpdateNotificationAsync` 只有在父类默认实现被调用过之后才会去
     * 建通知，不调就等于彻底关掉。
     *
     * 代价是服务不再进前台。看剧时应用本来就在前台，没有影响；退到桌面后声音还会继续，
     * 但失去前台服务保护，系统在内存吃紧时可能把进程收掉——这是「不挂通知」的必然代价。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 通知 id 由 media3 内部决定，与其猜一个常量，不如按「本应用当前的通知」逐个撤：
        // 这个应用除了媒体通知没有别的东西，不存在误伤。
        getSystemService(NotificationManager::class.java)?.let { manager ->
            manager.activeNotifications?.forEach { manager.cancel(it.id) }
        }
    }

    /** 从最近任务里划掉：视同退出，收干净走人，不在后台留声音。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.run {
            stop()
            clearMediaItems()
        }
        stopSelf()
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
