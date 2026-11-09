// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hgtv.phone.EpisodeLoader
import com.hgtv.playback.OpenedStream
import com.hgtv.playback.VideoDataSource

/** 手机端最高只取 1080P：更高档位在移动网络下加载慢，而且手机屏幕看不出差别。 */
private const val MAX_QUALITY = 1080

private sealed interface PlayStatus {
    data object Preparing : PlayStatus
    data object Ready : PlayStatus
    data class Failed(val message: String) : PlayStatus
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    episodes: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, episodes.lastIndex.coerceAtLeast(0))) }
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<PlayStatus>(PlayStatus.Preparing) }
    var opened by remember { mutableStateOf<OpenedStream?>(null) }

    val player =
        remember {
            val load =
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 30000, 1000, 2000)
                    .setTargetBufferBytes(12 * 1024 * 1024)
                    .build()
            val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            ExoPlayer.Builder(context, renderers).setLoadControl(load).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
            }
        }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            opened?.close()
        }
    }

    // 进播放页锁横屏并保持常亮，退出时全部还原——列表页是竖屏的。
    DisposableEffect(Unit) {
        val activity = view.context.findActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    // 一集播完直接进下一集；最后一集停在结束画面，让用户自己退出。
                    if (state == Player.STATE_ENDED && index < episodes.lastIndex) index += 1
                }

                override fun onPlayerError(error: PlaybackException) {
                    status = PlayStatus.Failed(error.errorCodeName)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(index, attempt) {
        val episodeId = episodes.getOrNull(index) ?: return@LaunchedEffect
        status = PlayStatus.Preparing
        val previous = opened
        try {
            val next =
                EpisodeLoader.open(
                    episodeId = episodeId,
                    maxQuality = MAX_QUALITY,
                    metadataAhead = episodes.drop(index + 1).take(2),
                )
            val source =
                ProgressiveMediaSource.Factory { VideoDataSource(next.video) }
                    .createMediaSource(
                        MediaItem.Builder()
                            .setMediaId(episodeId)
                            .setUri("hgtv://episode/$episodeId")
                            .setMimeType(MimeTypes.VIDEO_MP4)
                            .build(),
                    )
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
            opened = next
            previous?.close()
            status = PlayStatus.Ready
        } catch (problem: Exception) {
            status = PlayStatus.Failed(problem.message ?: "播放失败")
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            "返回",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xCCFFFFFF),
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 8.dp, top = 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x66000000))
                .clickable(onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )

        Text(
            "第 ${index + 1} 集 / 共 ${episodes.size} 集",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0x99FFFFFF),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = 16.dp, bottom = 10.dp),
        )

        when (val state = status) {
            PlayStatus.Preparing ->
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Palette.Accent,
                )
            is PlayStatus.Failed ->
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextPrimary,
                    )
                    Text(
                        "点这里重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Accent,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { attempt += 1 }
                            .padding(8.dp),
                    )
                }
            PlayStatus.Ready -> Unit
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
