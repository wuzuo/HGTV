// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.hgtv.core.CloudProgress
import com.hgtv.core.Danmaku
import com.hgtv.core.PlaybackQuality
import com.hgtv.core.Series
import com.hgtv.phone.PhoneApp
import com.hgtv.phone.data.PhoneSettings
import com.hgtv.phone.media.CommentItem
import com.hgtv.phone.media.CommentsLoader
import com.hgtv.phone.media.DanmakuLoader
import com.hgtv.phone.playback.EpisodeMediaSourceFactory
import com.hgtv.phone.playback.PlaybackConnection
import com.hgtv.phone.ui.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏播放页。
 *
 * 播放器本体在 [com.hgtv.phone.playback.PlaybackService] 里，这里拿到的是
 * [MediaController]；它同时也是通知栏、锁屏与画中画看到的那个播放器，所以切到桌面、
 * 息屏、进小窗都不会中断。
 *
 * 控制层是自绘的（PlayerView 的控制器关掉）：弹幕、手势、清晰度、倍速都要压在画面上，
 * 用系统控制器会互相打架。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    series: Series,
    episodes: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val window = remember(activity) { PlayerWindow(activity) }
    val audio = remember(context) { audioManager(context) }

    val settings = PhoneApp.settings
    val library = PhoneApp.library
    val account = PhoneApp.account

    val connection = remember(context) { PlaybackConnection(context) }
    DisposableEffect(connection) {
        connection.connect()
        onDispose { connection.release() }
    }
    val controller = connection.controller

    var index by remember {
        mutableIntStateOf(startIndex.coerceIn(0, episodes.lastIndex.coerceAtLeast(0)))
    }
    var quality by remember { mutableIntStateOf(settings.maxQuality) }
    var speed by remember { mutableFloatStateOf(settings.playbackSpeed) }
    var danmakuOn by remember { mutableStateOf(settings.danmakuEnabled) }
    val danmakuScale = settings.danmakuScale
    var danmaku by remember { mutableStateOf<List<Danmaku>>(emptyList()) }
    var comments by remember { mutableStateOf<List<CommentItem>?>(null) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var tiers by remember { mutableStateOf<List<Int>>(emptyList()) }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    var dragSeekMs by remember { mutableFloatStateOf(Float.NaN) }

    /**
     * 拖动开始那一刻的播放位置。
     *
     * 手势层给的是「从按下算起的累计位移」，所以每次都要用这个基准重新算目标点；
     * 之前直接往 `dragSeekMs` 上累加，等于把累计量反复叠加，拖一下进度就越跑越快。
     */
    var seekBaseMs by remember { mutableFloatStateOf(Float.NaN) }
    var pendingSeek by remember { mutableLongStateOf(-1L) }

    /**
     * 等待应用的续播位置。
     *
     * 不能在 `prepare()` 之后立刻 seek：那时时间线还是占位符、时长未知，seek 会被夹到 0，
     * 表现就是「明明有进度却从头播」。等第一次 STATE_READY 拿到真实时长再定位。
     */
    var resumeToApply by remember { mutableLongStateOf(-1L) }

    var brightness by remember { mutableFloatStateOf(systemBrightness(context)) }
    var volume by remember {
        mutableFloatStateOf(
            (audio?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0).toFloat()
        )
    }
    val volumeMax =
        remember(audio) {
            (audio?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 1)
                .coerceAtLeast(1)
        }

    val episodeId = episodes.getOrNull(index).orEmpty()

    // 播放页独占屏幕：横屏、常亮、隐藏系统栏；退出时全部还原。
    DisposableEffect(window) {
        window.enter()
        onDispose { window.exit() }
    }

    // 通知权限（Android 13+）：媒体通知是后台播放的门面，拒绝了也不影响播放。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** 这一集该从哪开始：本机进度优先，其次云端进度；都对不上就从头。 */
    fun resumeFor(id: String): Long {
        val local = library.progress(series.id)
        val cloud = account.cloudProgress(series.id)
        val candidates =
            listOfNotNull(
                local?.takeIf { it.episodeId == id && !it.completed }?.let { it.position to it.updatedAt },
                cloud?.takeIf { it.episodeId == id }?.let { it.position to it.updatedAt },
            )
        return candidates.maxByOrNull { it.second }?.first?.coerceAtLeast(0) ?: 0L
    }

    fun persistProgress(completed: Boolean = false, at: Long? = null) {
        val id = episodes.getOrNull(index) ?: return
        val now = at ?: controller?.currentPosition ?: position
        val total = controller?.duration?.takeIf { it > 0 } ?: duration
        library.saveProgress(series, id, index, now, total, completed)
    }

    fun uploadToCloud() {
        if (!account.signedIn) return
        val progress = library.progress(series.id) ?: return
        PhoneApp.scope.launch {
            account.uploadProgress(
                CloudProgress(
                    series = progress.series,
                    episodeId = progress.episodeId,
                    episodeIndex = progress.episodeIndex,
                    position = progress.position,
                    duration = progress.duration,
                    updatedAt = progress.updatedAt,
                    episodeCount = episodes.size,
                ),
                episodes.size,
            )
        }
    }

    // 换集 / 换档位 / 重试：重新装一条媒体项。取流由服务里的数据源在加载线程完成。
    LaunchedEffect(controller, index, quality, attempt) {
        val c = controller ?: return@LaunchedEffect
        val id = episodes.getOrNull(index) ?: return@LaunchedEffect
        failure = null
        buffering = true
        val target = pendingSeek.takeIf { it >= 0 } ?: resumeFor(id)
        pendingSeek = -1L
        android.util.Log.i("HGTV", "prepare episode=$id quality=$quality resumeAt=${target}ms")
        val ahead = episodes.drop(index + 1).take(2)
        val extras =
            bundleOf(
                EpisodeMediaSourceFactory.EXTRA_QUALITY to quality,
                EpisodeMediaSourceFactory.EXTRA_AHEAD to ahead.joinToString(","),
            )
        val item =
            MediaItem.Builder()
                .setMediaId(id)
                .setUri("hgtv://episode/$id")
                .setMimeType(MimeTypes.VIDEO_MP4)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(series.title)
                        .setSubtitle("第 ${index + 1} 集")
                        .setExtras(extras)
                        .build()
                )
                .build()
        c.setMediaItem(item)
        c.prepare()
        resumeToApply = target
        c.setPlaybackSpeed(speed)
        c.play()
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    buffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) {
                        val total = c.duration
                        if (total > 0) duration = total
                        val target = resumeToApply
                        // 时长未知时不能定位：C.TIME_UNSET 参与取小会算成 0，等于从头播。
                        if (target > 0 && total > 0) {
                            resumeToApply = -1L
                            val at = target.coerceAtMost((total - 3_000).coerceAtLeast(0))
                            android.util.Log.i("HGTV", "resume episode at ${at}ms of ${total}ms")
                            c.seekTo(at)
                            position = at
                        }
                    }
                    if (state == Player.STATE_ENDED) {
                        persistProgress(completed = true, at = c.duration)
                        if (settings.autoNext && index < episodes.lastIndex) {
                            uploadToCloud()
                            index += 1
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    buffering = false
                    failure = error.message?.take(120) ?: "播放失败（${error.errorCodeName}）"
                }

                // 短剧多为 9:16，横屏视频才转横屏；尺寸一知道就定下来，避免中途转向。
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        window.setLandscape(videoSize.width > videoSize.height)
                    }
                }
            }
        c.addListener(listener)
        playing = c.isPlaying
        buffering = c.playbackState == Player.STATE_BUFFERING
        onDispose { c.removeListener(listener) }
    }

    // 位置轮询：进度条、时间、弹幕都靠它。
    LaunchedEffect(controller) {
        while (true) {
            delay(250)
            val c = controller ?: continue
            position = c.currentPosition.coerceAtLeast(0)
            val total = c.duration
            if (total > 0) duration = total
        }
    }

    // 每 5 秒落一次进度；暂停时也落，但内容没变时 PhoneLibrary 会拒绝写盘。
    LaunchedEffect(controller, index) {
        while (true) {
            delay(5_000)
            if (controller != null) persistProgress()
        }
    }

    // 离开播放页：把最终进度写下去，并同步一次云端。
    DisposableEffect(Unit) {
        onDispose {
            persistProgress()
            uploadToCloud()
            PipBridge.active = false
            PipBridge.enter = null
        }
    }

    // 电视固件普遍没有画中画能力，先问系统再决定要不要给出入口。
    val pipSupported =
        remember(context) {
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_PICTURE_IN_PICTURE
            )
        }

    LaunchedEffect(playing, pipSupported) {
        PipBridge.active = playing && pipSupported
    }

    val enterPip: (() -> Unit) = {
        val act = activity
        if (act != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val size = controller?.videoSize
            val ratio =
                if (size != null && size.width > 0 && size.height > 0)
                    Rational(size.width, size.height)
                else Rational(16, 9)
            runCatching {
                act.enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(ratio).build()
                )
            }
        }
    }

    // Android 12 起交给系统自动进小窗：上滑回桌面的动画由系统接管，比 onUserLeaveHint 更顺。
    // 暂停时关掉，否则「暂停着按 Home」也会缩成一个小窗。
    LaunchedEffect(controller, playing, pipSupported) {
        if (!pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        val size = controller?.videoSize
        val ratio =
            if (size != null && size.width > 0 && size.height > 0)
                Rational(size.width, size.height)
            else Rational(16, 9)
        runCatching {
            act.setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .setAutoEnterEnabled(playing)
                    .build()
            )
        }
    }
    DisposableEffect(activity, controller) {
        PipBridge.enter = if (pipSupported) enterPip else null
        onDispose { PipBridge.enter = null }
    }

    // 弹幕：片长known之后按真实时长拉，未知时按估算值先拉一遍。
    LaunchedEffect(series.id, episodeId, danmakuOn, if (duration > 0) 1 else 0) {
        if (!danmakuOn) {
            danmaku = emptyList()
            return@LaunchedEffect
        }
        danmaku = DanmakuLoader.load(series.id, episodeId, duration)
    }

    // 控制层空闲 4 秒自动收起。
    LaunchedEffect(controlsVisible, playing) {
        if (controlsVisible && playing) {
            delay(4_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_200)
            hint = null
        }
    }

    // 清晰度面板：打开时才去拉阶梯，正常播放不多花一次请求。
    LaunchedEffect(sheet, episodeId) {
        if (sheet == PlayerSheet.Quality && tiers.isEmpty()) {
            val ladder =
                runCatching { PhoneApp.repository.streamLadder(episodeId) }.getOrNull()
            tiers =
                ladder?.renditions
                    ?.map { it.quality }
                    ?.filter { it in PlaybackQuality.options }
                    ?.distinct()
                    ?.sortedDescending()
                    ?: emptyList()
        }
    }

    LaunchedEffect(sheet, episodeId) {
        if (sheet == PlayerSheet.Comments && comments == null) {
            commentsError = null
            runCatching { CommentsLoader.load(PhoneApp.http, series.id, episodeId) }
                .onSuccess { comments = it }
                .onFailure { commentsError = it.message ?: "评论加载失败" }
        }
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val total = if (duration > 0) duration else c.duration.coerceAtLeast(0)
        val target = (c.currentPosition + deltaMs).coerceIn(0L, if (total > 0) total else 0L)
        c.seekTo(target)
        position = target
        hint = if (deltaMs > 0) "快进 10 秒" else "快退 10 秒"
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = controller },
            modifier = Modifier.fillMaxSize(),
        )

        if (danmakuOn && danmaku.isNotEmpty() && !PipBridge.inPip) {
            DanmakuLayer(
                items = danmaku,
                positionMillis = { controller?.currentPosition ?: 0L },
                playing = playing,
                scale = danmakuScale,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 手势层：始终在，控制层收起后也能点出来。
        Box(
            Modifier
                .fillMaxSize()
                .playerGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { fraction -> seekBy(if (fraction < 0.5f) -10_000L else 10_000L) },
                    onSeekDrag = { deltaMs ->
                        val total = duration.toFloat()
                        if (total > 0) {
                            if (seekBaseMs.isNaN()) seekBaseMs = position.toFloat()
                            dragSeekMs = (seekBaseMs + deltaMs).coerceIn(0f, total)
                        }
                    },
                    onSeekCommit = {
                        if (!dragSeekMs.isNaN()) {
                            val target = dragSeekMs.toLong()
                            controller?.seekTo(target)
                            position = target
                        }
                        dragSeekMs = Float.NaN
                        seekBaseMs = Float.NaN
                    },
                    onBrightnessDrag = { delta ->
                        brightness = (brightness + delta).coerceIn(0.01f, 1f)
                        applyBrightness(activity, brightness)
                        hint = "亮度 ${(brightness * 100).roundToInt()}%"
                    },
                    onVolumeDrag = { delta ->
                        volume = (volume + delta * volumeMax).coerceIn(0f, volumeMax.toFloat())
                        audio?.setStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            volume.roundToInt(),
                            0,
                        )
                        hint = "音量 ${(volume / volumeMax * 100).roundToInt()}%"
                    },
                    onDragEnd = {},
                )
        )

        if (!dragSeekMs.isNaN()) {
            PlayerHint("${formatDuration(dragSeekMs.toLong())} / ${formatDuration(duration)}")
        } else if (hint != null) {
            PlayerHint(hint!!)
        }

        when {
            failure != null ->
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        failure!!,
                        color = Palette.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "点这里重试",
                        color = Palette.Accent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x33FFFFFF))
                            .clickable { attempt += 1 }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            controller == null ->
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = Palette.Accent,
                )
        }

        if (controlsVisible && !PipBridge.inPip) {
            PlayerControls(
                title = series.title,
                episodeLabel = "第 ${index + 1} 集 / 共 ${episodes.size} 集",
                position = position,
                duration = duration,
                playing = playing,
                buffering = buffering,
                danmakuOn = danmakuOn,
                qualityLabel = "${quality}P",
                speedLabel = if (speed == 1f) "倍速" else "${speed}x",
                onBack = {
                    persistProgress()
                    controller?.stop()
                    onBack()
                },
                onPlayPause = {
                    val c = controller ?: return@PlayerControls
                    if (c.isPlaying) c.pause() else c.play()
                },
                onSeek = { target ->
                    controller?.seekTo(target)
                    position = target
                },
                onNext =
                    if (index < episodes.lastIndex)
                        {
                            {
                                persistProgress(completed = true, at = duration)
                                index += 1
                            }
                        }
                    else null,
                onEpisodes = { sheet = PlayerSheet.Episodes },
                onQuality = { sheet = PlayerSheet.Quality },
                onSpeed = { sheet = PlayerSheet.Speed },
                onDanmakuToggle = {
                    danmakuOn = !danmakuOn
                    settings.updateDanmakuEnabled(danmakuOn)
                },
                onComments = { sheet = PlayerSheet.Comments },
                onPip = if (pipSupported) enterPip else null,
            )
        }
    }

    when (sheet) {
        PlayerSheet.Quality ->
            QualitySheet(
                tiers = tiers,
                current = quality,
                onPick = { picked ->
                    sheet = null
                    if (picked != quality) {
                        pendingSeek = controller?.currentPosition ?: position
                        quality = picked
                        settings.setQuality(picked)
                    }
                },
                onDismiss = { sheet = null },
            )
        PlayerSheet.Speed ->
            SpeedSheet(
                current = speed,
                onPick = { picked ->
                    sheet = null
                    speed = picked
                    settings.setSpeed(picked)
                    controller?.setPlaybackSpeed(picked)
                },
                onDismiss = { sheet = null },
            )
        PlayerSheet.Episodes ->
            EpisodesSheet(
                count = episodes.size,
                current = index,
                watchedIndex = index,
                onPick = { picked ->
                    sheet = null
                    if (picked != index) {
                        persistProgress(completed = true, at = duration)
                        index = picked
                    }
                },
                onDismiss = { sheet = null },
            )
        PlayerSheet.Comments ->
            CommentsSheet(
                items = comments,
                error = commentsError,
                onRetry = {
                    comments = null
                    commentsError = null
                    sheet = null
                    sheet = PlayerSheet.Comments
                },
                onDismiss = { sheet = null },
            )
        null -> Unit
    }
}
