// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.hgtv.core.DeviceIdentityMonitor
import com.hgtv.core.PlaybackQuality
import com.hgtv.core.RecoveryBudget
import com.hgtv.core.RelatedWork
import com.hgtv.core.RelatedWorks
import com.hgtv.core.Series
import com.hgtv.phone.PhoneApp
import com.hgtv.phone.R
import com.hgtv.phone.data.PhoneSettings
import com.hgtv.phone.media.CommentItem
import com.hgtv.phone.media.CommentsLoader
import com.hgtv.phone.media.DanmakuLoader
import com.hgtv.phone.playback.EpisodeMediaSourceFactory
import com.hgtv.phone.playback.PlaybackConnection
import com.hgtv.phone.ui.Palette
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 长按加速用的倍速。和设置里的倍速分开：松手要回到用户自己选的那档。 */
private const val SPEED_UP_RATE = 2f

/** 选集面板占下半屏，画面缩到原来的一半刚好让开。 */
private const val SHRUNK_SCALE = 0.5f

/**
 * 全屏播放页。
 *
 * 播放器本体在 [com.hgtv.phone.playback.PlaybackService] 里，这里拿到的是
 * [MediaController]；它同时也是锁屏与画中画看到的那个播放器，所以切到桌面、
 * 息屏、进小窗都不会中断。通知栏不挂常驻控制条。
 *
 * 控制层是自绘的（PlayerView 的控制器关掉）：弹幕、手势、清晰度、倍速都要压在画面上，
 * 用系统控制器会互相打架。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    initialSeries: Series,
    initialEpisodes: List<String>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    // 跨季连播要换「当前在播的哪一部」，所以 series/episodes 是可变状态而不是入参：
    // 入参只决定起播的那一季。
    var series by remember(initialSeries.id) { mutableStateOf(initialSeries) }
    var episodes by remember(initialSeries.id) { mutableStateOf(initialEpisodes) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val window = remember(activity) { PlayerWindow(activity) }

    val settings = PhoneApp.settings
    val library = PhoneApp.library
    val account = PhoneApp.account

    val connection = remember(context) { PlaybackConnection(context) }
    DisposableEffect(connection) {
        connection.connect()
        onDispose {
            // 先停再断。控制器一旦释放就调不动播放器了，而服务里的播放器
            // 不会因为控制器断开就自己停——只断连接的话，声音会一直在后台响。
            runCatching { connection.controller?.stop() }
            connection.release()
        }
    }
    val controller = connection.controller

    var index by remember(initialSeries.id) {
        mutableIntStateOf(startIndex.coerceIn(0, episodes.lastIndex.coerceAtLeast(0)))
    }
    var quality by remember { mutableIntStateOf(settings.maxQuality) }
    var speed by remember { mutableFloatStateOf(settings.playbackSpeed) }
    var danmakuOn by remember { mutableStateOf(settings.danmakuEnabled) }
    val danmakuScale = settings.danmakuScale
    var danmaku by remember { mutableStateOf<List<Danmaku>>(emptyList()) }

    /** [danmaku] 属于哪一集：换集要先清空，不能把上一集的弹幕挂在新画面上。 */
    var danmakuEpisode by remember { mutableStateOf("") }
    var comments by remember { mutableStateOf<List<CommentItem>?>(null) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var tiers by remember { mutableStateOf<List<Int>>(emptyList()) }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    /** 一集内的自动重试预算：取流失败多半是瞬时的（换设备身份、域名抖动），先自己救三次。 */
    val recovery = remember { RecoveryBudget() }
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    var dragSeekMs by remember { mutableFloatStateOf(Float.NaN) }

    /** 长按加速是否生效。只影响速率，不写回设置——松手要能回到原来那档。 */
    var speedUp by remember { mutableStateOf(false) }

    /** 上一次换集 seek 的时刻：用来量「滑完到能播」的耗时，0 表示没有待测的切换。 */
    var switchStarted by remember { mutableLongStateOf(0L) }

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

    /** 上下滑换集时的提示（「松开播放下一集」），松手即清。 */
    var episodeHint by remember { mutableStateOf<String?>(null) }

    /**
     * 当前画面是不是横屏。
     *
     * 与 [PlayerWindow.setLandscape] 同一个口径：由视频本身的比例决定，不是设备朝向。
     * 手势语义按它分派——竖屏单击暂停，横屏双击暂停。
     */
    var landscape by remember { mutableStateOf(false) }

    /** 换集拖动的跟手位移（像素）：手指走多少画面跟多少，松手归零。 */
    var episodeOffset by remember { mutableFloatStateOf(0f) }

    /** 未达阈值松手后的回弹。 */
    var episodeSettling by remember { mutableStateOf(false) }

    /**
     * 缓冲超过一小会儿才转圈。
     *
     * 换集要重新取流，那一秒左右的等待如果不画点东西，画面停在上集最后一帧看着像卡死；
     * 但刚起播就闪一下转圈更烦，所以短于 250ms 的缓冲不显示。
     */
    var showLoading by remember { mutableStateOf(false) }

    val episodeId = episodes.getOrNull(index).orEmpty()

    // 播放页独占屏幕：横屏、常亮、隐藏系统栏；退出时全部还原。
    DisposableEffect(window) {
        window.enter()
        onDispose { window.exit() }
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

    /**
     * 下一季：相关作品里季号刚好大于当前这一季、且已上线的那一部。
     *
     * 红果把「第 N 季」和「同 IP」混在同一个列表里，季号只写在 tag 上；而当前这一季
     * **不在列表里**（接口只给「其他」），所以自己的季号只能从剧名（`…第五部`）解析。
     * 解析不出来就当它不是分季剧，不提供跨季——瞎猜会把用户送进另一部剧。
     */
    var nextSeason by remember { mutableStateOf<RelatedWork?>(null) }

    LaunchedEffect(series.id) {
        nextSeason = null
        // 标题里没有季号的可能是第一部，不能靠它省掉请求，所以每部剧都问一次相关作品；
        // 一个带序号的季都没有时 nextSeasonOf 会返回 null，单季剧就到此为止。
        val rows =
            runCatching {
                    withContext(Dispatchers.IO) { PhoneApp.repository.seasons(series.id) }
                }
                .getOrNull()
                .orEmpty()
        nextSeason = RelatedWorks.nextSeasonOf(rows, series.title)
    }

    /** 进入下一季：本季按看完记下，换掉剧与集列表，回到新季第 1 集。 */
    suspend fun enterNextSeason() {
        val target = nextSeason ?: return
        hint = "正在进入${target.season.ifBlank { "下一季" }}"
        val detail =
            runCatching {
                    withContext(Dispatchers.IO) { PhoneApp.repository.detail(target.series.id) }
                }
                .getOrNull()
        if (detail == null || detail.episodes.isEmpty()) {
            hint = "下一季加载失败，请稍后再试"
            return
        }
        persistProgress(completed = true, at = duration)
        uploadToCloud()
        recovery.reset()
        series = detail.series
        episodes = detail.episodes
        index = 0
        hint = target.season.ifBlank { "下一季" }
    }

    // 离开播放页：落进度、停播放、回上一页。
    // 顶部箭头和系统返回键必须走同一条路——之前只有箭头调了 stop()，
    // 按返回键是从 MainActivity 的返回栈直接弹页，播放器还保持着 playWhenReady，
    // 于是「退出后声音还在响」。
    val leave: () -> Unit = {
        persistProgress()
        uploadToCloud()
        runCatching { controller?.stop() }
        onBack()
    }
    BackHandler(onBack = leave)

    // 换集或换档位就是一次新的取流：重试预算重新算。
    LaunchedEffect(index, quality) { recovery.reset() }

    /** 一条媒体项：集号、档位、以及要顺手预取的后两集。 */
    fun episodeItem(id: String, position: Int): MediaItem {
        val extras =
            bundleOf(
                EpisodeMediaSourceFactory.EXTRA_QUALITY to quality,
                EpisodeMediaSourceFactory.EXTRA_AHEAD to
                    episodes.drop(position + 1).take(2).joinToString(","),
            )
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri("hgtv://episode/$id")
            .setMimeType(MimeTypes.VIDEO_MP4)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(series.title)
                    .setSubtitle("第 ${position + 1} 集")
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    // 整条剧集一次性塞进同一个 playlist：换集只是 seek，不再重建媒体源、重新握手。
    // 这是「滑动换集要不要等」的分水岭——重建一次要重新拉播放模型、签名、解析 moov。
    val playlistKey = remember(episodes, quality) { episodes.hashCode() to quality }
    var preparedKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(controller, playlistKey, attempt) {
        val c = controller ?: return@LaunchedEffect
        if (episodes.isEmpty()) return@LaunchedEffect
        failure = null
        buffering = true
        val items = episodes.mapIndexed { position, id -> episodeItem(id, position) }
        val start = index.coerceIn(0, items.lastIndex)
        val target = pendingSeek.takeIf { it >= 0 } ?: resumeFor(episodes[start])
        pendingSeek = -1L
        c.setMediaItems(items, start, 0L)
        preparedKey = playlistKey
        resumeToApply = target
        android.util.Log.i(
            "HGTV",
            "playlist size=${items.size} start=${start + 1} resumeAt=${target}ms",
        )
        c.prepare()
        // 换集/换档位时如果还按着屏幕，别把加速冲掉——松手才复原。
        c.setPlaybackSpeed(if (speedUp) SPEED_UP_RATE else speed)
        c.play()
    }

    // 同一份 playlist 内换集：只 seek，不重建。接着上次看到的位置续播。
    LaunchedEffect(index, preparedKey) {
        val c = controller ?: return@LaunchedEffect
        if (preparedKey == null) return@LaunchedEffect
        if (c.currentMediaItemIndex == index) return@LaunchedEffect
        val id = episodes.getOrNull(index) ?: return@LaunchedEffect
        switchStarted = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("HGTV", "switch seek -> ${index + 1}")
        c.seekTo(index, resumeFor(id).coerceAtLeast(0L))
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
                        if (switchStarted > 0) {
                            android.util.Log.i(
                                "HGTV",
                                "switch ready cost=${android.os.SystemClock.elapsedRealtime() - switchStarted}ms",
                            )
                            switchStarted = 0L
                        }
                    }
                    if (state == Player.STATE_ENDED) {
                        persistProgress(completed = true, at = c.duration)
                        if (settings.autoNext) {
                            if (index < episodes.lastIndex) {
                                uploadToCloud()
                                index += 1
                            } else if (nextSeason != null) {
                                // 本季最后一集播完接着下一季，和继续上滑走同一个入口。
                                scope.launch { enterNextSeason() }
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    buffering = false
                    val detail = describePlaybackError(error)
                    // 「这一集编码不支持」重试三次也只是多等 8.5 秒，结果一样。
                    val permanent =
                        generateSequence(error as Throwable) { it.cause }
                            .any { it is com.hgtv.core.UnsupportedCodecException }
                    val wait = if (permanent) null else recovery.delayMillis
                    if (wait != null && recovery.consume()) {
                        // 取流失败一大半是瞬时的：注册身份这一集刚好被服务端判重、域名抖动、
                        // 连接被掐。先按 1s / 2.5s / 5s 自己救三次，别让用户看一行英文就没了。
                        failure = "$detail\n${wait / 1000f} 秒后自动重试（${recovery.attempts}/3）"
                        android.util.Log.w("HGTV", "playback error, retry ${recovery.attempts}/3: $detail")
                        scope.launch {
                            delay(wait)
                            pendingSeek = c.currentPosition.takeIf { it > 0 } ?: position
                            attempt += 1
                        }
                    } else {
                        failure = detail
                        android.util.Log.e("HGTV", "playback failed: $detail", error)
                    }
                }

                // 短剧多为 9:16，横屏视频才转横屏；尺寸一知道就定下来，避免中途转向。
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val wide = videoSize.width > videoSize.height
                        landscape = wide
                        window.setLandscape(wide)
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
    //
    // 加载器是**流式**的（每拉完一窗就发一份累积快照），所以这里只要跟着收：
    // 第一窗回来就能上屏，不用等整集拉完。时长从 0 变成真实值会重跑一次，
    // 但窗口缓存按「起点」存，前几窗直接命中——不会像以前那样整集重新请求一遍。
    LaunchedEffect(series.id, episodeId, danmakuOn, duration > 0) {
        if (!danmakuOn) {
            danmaku = emptyList()
            danmakuEpisode = ""
            return@LaunchedEffect
        }
        if (danmakuEpisode != episodeId) {
            danmaku = emptyList()
            danmakuEpisode = episodeId
        }
        DanmakuLoader.load(series.id, episodeId, duration).collect { danmaku = it }
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

    /** 上下滑换集：最后一集继续往上滑就进下一季。 */
    fun switchEpisode(delta: Int) {
        val next = index + delta
        if (next !in episodes.indices) {
            if (delta > 0 && nextSeason != null) {
                scope.launch { enterNextSeason() }
            } else {
                hint = if (delta > 0) "已经是最后一集" else "已经是第一集"
            }
            return
        }
        // 上一集按「看完」记：换回来时不至于又从中间开始。
        persistProgress(completed = delta > 0, at = if (delta > 0) duration else null)
        index = next
        hint = "第 ${next + 1} 集"
    }

    // 缓冲超过 250ms 才转圈，短于这个时长不闪。
    LaunchedEffect(buffering) {
        if (!buffering) {
            showLoading = false
        } else {
            delay(250)
            showLoading = true
        }
    }

    // 没滑够就松手：画面回弹。固定时长比弹簧干脆，换集方向的手势本来就快。
    LaunchedEffect(episodeSettling) {
        if (!episodeSettling) return@LaunchedEffect
        animate(episodeOffset, 0f, animationSpec = tween(durationMillis = 180)) { value, _ ->
            episodeOffset = value
        }
        episodeOffset = 0f
        episodeSettling = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 选集面板铺在下半屏时，画面收到上方，不要被整块盖住——看不出还在播就以为断了。
        // 缩放而不是重排版：PlayerView 的尺寸没变，Surface 不用重建，缩起来不掉帧。
        val shrink by animateFloatAsState(
            targetValue = if (sheet == PlayerSheet.Episodes) 1f else 0f,
            animationSpec = tween(durationMillis = 220),
            label = "shrink",
        )
        // 画面与弹幕一起跟手：换集拖动时整层随手指走，手指松开或换集完成时归零。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = episodeOffset
                    // 拉得越远越淡，滑过阈值时能看出「这一下算数」。
                    val lift =
                        if (size.height > 0f) (abs(episodeOffset) / size.height).coerceIn(0f, 1f)
                        else 0f
                    alpha = 1f - lift * 0.35f
                    if (shrink > 0f) {
                        val s = 1f - (1f - SHRUNK_SCALE) * shrink
                        scaleX = s
                        scaleY = s
                        // 从顶边缩：面板占下半屏，画面剩的部分正好贴在面板上方。
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                },
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    // 用 XML 里的 PlayerView：surface_type 只能在布局属性里指定，
                    // 而跟手平移需要 TextureView（SurfaceView 的画面不跟 View 的变换走）。
                    android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.player_view, null) as PlayerView
                },
                update = { it.player = controller },
                modifier = Modifier.fillMaxSize(),
            )

            // 弹幕不跟着缩：字号缩一半读不清，缩窗期间直接淡掉。
            if (danmakuOn && danmaku.isNotEmpty() && !PipBridge.inPip) {
                DanmakuLayer(
                    items = danmaku,
                    positionMillis = { controller?.currentPosition ?: 0L },
                    playing = playing,
                    scale = danmakuScale,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = (1f - shrink * 2f).coerceIn(0f, 1f) },
                )
            }
        }

        // 手势层：始终在，控制层收起后也能点出来。
        Box(
            Modifier
                .fillMaxSize()
                .playerGestures(
                    // 竖屏单击就暂停（不注册双击，单击不必等 300ms 超时）；
                    // 横屏单击显隐控制层，双击暂停。
                    doubleTapToPause = landscape,
                    onSingleTap = {
                        if (landscape) {
                            controlsVisible = !controlsVisible
                        } else {
                            controller?.let { c ->
                                if (c.isPlaying) c.pause() else c.play()
                                // 暂停时把控制层带出来：停下了还看不到进度条，会以为卡住了。
                                controlsVisible = true
                            }
                        }
                    },
                    onDoubleTap = {
                        controller?.let { c ->
                            if (c.isPlaying) c.pause() else c.play()
                            controlsVisible = true
                        }
                    },
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
                    // 上滑下一集、下滑上一集：竖屏短剧最顺手的操作，和短视频一致。
                    // 亮度/音量不再用手势——画面里画不出一条分界线，系统音量键更直接。
                    onEpisodeDrag = { offsetPx, fraction ->
                        // 手指又按下去了：停掉上一次的回弹，否则动画会和手指抢位置。
                        if (episodeSettling) episodeSettling = false
                        episodeOffset = offsetPx
                        episodeHint =
                            when {
                                fraction <= -EPISODE_SWIPE -> "松开播放下一集"
                                fraction >= EPISODE_SWIPE -> "松开播放上一集"
                                else -> null
                            }
                    },
                    onEpisodeCommit = { fraction ->
                        episodeHint = null
                        when {
                            // 换集时直接归零而不是回弹：画面马上要换成新一集，
                            // 让旧帧再滑一段反而更割裂。
                            fraction <= -EPISODE_SWIPE -> {
                                episodeOffset = 0f
                                switchEpisode(1)
                            }
                            fraction >= EPISODE_SWIPE -> {
                                episodeOffset = 0f
                                switchEpisode(-1)
                            }
                            else -> episodeSettling = true
                        }
                    },
                    // 按住不放 2 倍速：短剧常用姿势，松开回到用户选的那档。
                    onSpeedUpStart = {
                        speedUp = true
                        controller?.setPlaybackSpeed(SPEED_UP_RATE)
                    },
                    onSpeedUpEnd = {
                        speedUp = false
                        controller?.setPlaybackSpeed(speed)
                    },
                    isSpeedUp = { speedUp },
                )
        )

        if (speedUp) {
            PlayerHint("${SPEED_UP_RATE}x 加速中")
        } else if (episodeHint != null) {
            PlayerHint(episodeHint!!)
        } else if (!dragSeekMs.isNaN()) {
            PlayerHint("${formatDuration(dragSeekMs.toLong())} / ${formatDuration(duration)}")
        } else if (hint != null) {
            PlayerHint(hint!!)
        }

        // 换集/换档位的等待：不画点什么，画面停在上集最后一帧就像卡死了。
        if (showLoading && failure == null && controller != null && !PipBridge.inPip) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(28.dp),
                color = Palette.Accent,
                strokeWidth = 2.dp,
            )
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
                    // 没有本地设备身份时，「空响应」基本都会撞上：注册身份只放行一次。
                    // 这里给一句能直接照做的提示，比让用户反复点重试有用。
                    if (DeviceIdentityMonitor.peek() == null) {
                        Text(
                            "没有读到设备身份配置，每集都要重新注册设备。" +
                                "可以在「我的 → 播放与存储设置 → 播放身份」里放一份 device-identity.txt。",
                            color = Palette.TextTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    Text(
                        "点这里重试",
                        color = Palette.Accent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x33FFFFFF))
                            .clickable {
                                // 手动重试也把自动重试预算还回去：用户主动点了，就再给三次机会。
                                recovery.reset()
                                attempt += 1
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            controller == null ->
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = Palette.Accent,
                )
        }

        // 选集面板打开时把控制层一起收起：缩小的画面上压着标题和按钮，看不清画面。
        if (controlsVisible && sheet == null && !PipBridge.inPip) {
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
                onBack = leave,
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

/**
 * 把 ExoPlayer 的异常翻成用户看得懂的一句话。
 *
 * 数据源抛的 IOException 会被 ProgressiveMediaSource 统一包成 `Source error`，
 * 真正的原因（「播放接口暂时返回空响应，请稍后重试」这类）藏在 cause 链最深处，
 * 直接显示 `error.message` 就只剩一行英文，既不知道是哪一步失败也不知道该怎么办。
 */
private fun describePlaybackError(error: PlaybackException): String {
    val chain = generateSequence(error as Throwable) { it.cause }.toList()
    // 编码不兼容是确定性结论，重试和降档都不会变，直接把该怎么做说清楚。
    chain
        .filterIsInstance<com.hgtv.core.UnsupportedCodecException>()
        .firstOrNull()
        ?.let {
            return it.message ?: "这一集编码不受支持，请换一集"
        }
    val reason =
        chain
            .mapNotNull { it.message?.trim()?.takeIf { message -> message.isNotEmpty() } }
            .lastOrNull { !it.equals("Source error", ignoreCase = true) }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接不上，检查网络后重试"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "播放地址返回异常" + (reason?.let { "：$it" }.orEmpty())
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "视频资源不存在或已下架"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "这台设备解不了这个清晰度，换低一档试试"
        else ->
            when {
                reason == null -> "播放失败（${error.errorCodeName}）"
                // 中文原因直接用；剩下的英文兜底加一句上下文，至少知道是取流那一步。
                reason.any { it.code in 0x4E00..0x9FFF } -> reason.take(120)
                else -> "取流失败：${reason.take(100)}"
            }
    }
}
