// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.hgtv.core.Danmaku
import com.hgtv.core.DanmakuEmoji
import com.hgtv.core.DanmakuRepeat
import com.hgtv.core.repeatGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 压在画面上的弹幕层。
 *
 * 位置完全由播放器给（每帧读一次 `position`），自己不维护时钟，所以暂停、拖动进度、
 * 倍速都不会和画面脱节。
 *
 * 排轨规则与 TV 端一致：同一条轨道上，后一条要等前一条**完全进画**才能出发
 * （`freeAt = enterTime + (宽度 + 间距) / 速度`），因此同轨两条永远不会追尾；
 * 排不下就换轨，轨数不够就丢弃这一条，宁可不显示也不叠字。
 *
 * 重复内容合并也与 TV 端同源（[repeatGroups]）：同一句在窗口内出现多次只画一条，
 * 文案挂上数量（`从第一季追到第五季的→ ×25`）。不合并的话，一句被刷屏的话会把
 * 八条轨道全占满，画面上只剩那一句——这就是「弹幕数量不对」的直接来源。
 *
 * 表情按平台标签画图（[DanmakuEmoji.parts]）：一条弹幕被切成「文字 + 表情」若干段，
 * 文字按字体量宽、表情按字号正方形量宽。图跟 TV 端、跟 App 里是同一套 53 张；
 * 图还没解码好时先画兜底字形，屏幕不会出现空档。
 */
@Composable
fun DanmakuLayer(
    items: List<Danmaku>,
    positionMillis: () -> Long,
    playing: Boolean,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val context = LocalContext.current
    val density = LocalDensity.current
    var size by remember { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    var tick by remember { mutableLongStateOf(0L) }
    var images by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }

    // 53 张一共 236 KB，一次全解完常驻：按需解码会让第一屏的几条先闪一下字形。
    LaunchedEffect(Unit) {
        images = withContext(Dispatchers.IO) { PhoneEmojiImages.load(context.applicationContext) }
    }

    val textSize = (14f * scale).sp
    val emojiSize = with(density) { textSize.toPx() * EMOJI_SCALE }

    val style =
        remember(scale) {
            TextStyle(
                fontSize = textSize,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                shadow = Shadow(Color(0xCC000000), Offset(1.5f, 1.5f), 3f),
            )
        }

    // 窗口比 TV 端短：手机一屏只有八条轨道，20 秒一合会把热门句子整段压成一条。
    val groups = remember(items) { repeatGroups(items, PHONE_REPEAT_WINDOW_MS) }

    val placed =
        remember(groups, width, size, style, emojiSize) {
            if (width <= 0 || size <= 0 || groups.isEmpty()) {
                emptyList()
            } else {
                layoutLanes(groups, width, size, measurer, style, emojiSize)
            }
        }

    // 只有播放中才逐帧重绘；暂停时停在当前帧，用户拖动进度后由 position 变化触发一次重排。
    LaunchedEffect(playing) {
        while (playing) withFrameMillis { tick = it }
    }

    Canvas(modifier.fillMaxSize().onSizeChanged { width = it.width; size = it.height }) {
        tick.let { /* 读取帧计数，让每帧都重算位置 */ }
        val now = positionMillis()
        if (placed.isEmpty()) return@Canvas
        var index = lastStartedIndex(placed, now)
        while (index >= 0) {
            val line = placed[index]
            val elapsed = now - line.startMs
            if (elapsed > line.travelMs) break
            if (elapsed >= 0) {
                val progress = elapsed / line.travelMs
                var x = width - progress * (width + line.textWidth)
                line.runs.forEach { run ->
                    val layout = run.layout
                    if (layout == null) {
                        val image = images[run.asset]
                        if (image != null) {
                            // 表情按行高居中：偏上偏下都会跟旁边的字不在一条视觉线上。
                            val top = line.top + (line.height - run.width) / 2f
                            drawImage(
                                image = image,
                                dstOffset = IntOffset(x.roundToInt(), top.roundToInt()),
                                dstSize = IntSize(run.width.roundToInt(), run.width.roundToInt()),
                            )
                        } else {
                            run.fallback?.let { drawText(it, topLeft = Offset(x, line.top)) }
                        }
                    } else {
                        drawText(layout, topLeft = Offset(x, line.top))
                    }
                    x += run.width
                }
            }
            index -= 1
        }
    }
}

/** 一条弹幕里量好的一段：文字带排版结果，表情带图片名与兜底字形。 */
private class MeasuredRun(
    /** 文字段的排版结果；表情段为 null。 */
    val layout: TextLayoutResult?,
    /** 表情段的图片文件名；文字段为 null。 */
    val asset: String?,
    /** 表情段的兜底字形排版（图没到位时画它）；文字段为 null。 */
    val fallback: TextLayoutResult?,
    /** 这一段占多宽：文字是实际宽度，表情是固定的正方形边长。 */
    val width: Float,
)

private class PlacedLine(
    /** 这一条（这一组重复弹幕）该出现的时刻。 */
    val startMs: Long,
    val runs: List<MeasuredRun>,
    val textWidth: Float,
    val top: Float,
    /** 这一条的行高：表情按它做垂直居中。 */
    val height: Float,
    /** 从右边缘外到左边缘外所需时间；同一条弹幕在屏幕上的存活时长。 */
    val travelMs: Float,
)

/** 从右到左横穿一屏的时间（毫秒）。 */
private const val TRAVEL_MS = 8_500f
private const val LANE_GAP_PX = 24f

/** 表情边长相对字号的比例：比汉字略大一点，跟 App 里的观感接近。 */
private const val EMOJI_SCALE = 1.15f

/** 同一句在这段时间内出现多次只画一条：手机屏幕小，一场刷屏就能占满所有轨道。 */
private const val PHONE_REPEAT_WINDOW_MS = 6_000L

private fun layoutLanes(
    groups: List<DanmakuRepeat>,
    width: Int,
    height: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    emojiSize: Float,
): List<PlacedLine> {
    val measured =
        groups.map { group ->
            // 平台的弹幕表情不是字形而是 `[笑哭]` 这类方括号标签；按标签切成「文字 + 表情」，
            // 表情画图、文字交给字体。重复过的组带「×N」后缀，和 TV 端同一套文案。
            val runs = mutableListOf<MeasuredRun>()
            var total = 0f
            var lineHeight = 0f
            DanmakuEmoji.parts(group.label).forEach { part ->
                if (part.asset == null) {
                    val layout = measurer.measure(AnnotatedString(part.text), style, maxLines = 1)
                    runs += MeasuredRun(layout, null, null, layout.size.width.toFloat())
                    total += layout.size.width
                    lineHeight = maxOf(lineHeight, layout.size.height.toFloat())
                } else {
                    val fallback = measurer.measure(AnnotatedString(part.text), style, maxLines = 1)
                    runs += MeasuredRun(null, part.asset, fallback, emojiSize)
                    total += emojiSize
                    lineHeight = maxOf(lineHeight, emojiSize)
                }
            }
            Triple(group, runs, total to lineHeight)
        }
    val laneHeight = (measured.maxOfOrNull { it.third.second } ?: 0f) + 16f
    if (laneHeight <= 16f) return emptyList()
    // 只占上半屏：竖屏短剧的字幕和人物都在中间，弹幕压满会看不清。
    val laneCount = ((height * 0.36f) / laneHeight).toInt().coerceIn(1, 8)
    // 顶部留出控制条：返回键和标题就在这一带，弹幕从更下面才开始。
    val safeTop = height * 0.10f
    val freeAt = LongArray(laneCount) { 0L }
    val result = ArrayList<PlacedLine>(measured.size)
    for ((group, runs, metrics) in measured) {
        val (textWidth, lineHeightOfLine) = metrics
        if (textWidth > width * 1.4f) continue // 超长弹幕直接丢，否则会挡住半屏
        val speed = (width + textWidth) / TRAVEL_MS
        var lane = -1
        for (i in 0 until laneCount) {
            if (group.firstMs >= freeAt[i]) {
                lane = i
                break
            }
        }
        if (lane < 0) continue
        freeAt[lane] = group.firstMs + ((textWidth + LANE_GAP_PX) / speed).toLong()
        result +=
            PlacedLine(
                startMs = group.firstMs,
                runs = runs,
                textWidth = textWidth,
                top = safeTop + lane * laneHeight,
                height = lineHeightOfLine,
                travelMs = TRAVEL_MS,
            )
    }
    return result
}

/** 二分找「已经开始飞」的最后一条，避免每帧从头遍历几百条。 */
private fun lastStartedIndex(lines: List<PlacedLine>, now: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startMs <= now) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/**
 * 弹幕表情图的进程级缓存。
 *
 * 53 张 120×120 的解码位图约 3 MB，全量常驻比按需解码划算：手机上一屏就那么几十条，
 * 按需解码的收益被「第一次遇到某张图时先闪字形」抵消掉了。
 */
private object PhoneEmojiImages {
    private var cache: Map<String, ImageBitmap>? = null

    fun load(context: Context): Map<String, ImageBitmap> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded =
                context.assets
                    .list(DIR)
                    .orEmpty()
                    .filter { it.endsWith(".webp") }
                    .mapNotNull { name ->
                        runCatching {
                                context.assets.open("$DIR/$name").use {
                                    BitmapFactory.decodeStream(it)?.asImageBitmap()?.let { image ->
                                        name to image
                                    }
                                }
                            }
                            .getOrNull()
                    }
                    .toMap()
            cache = loaded
            return loaded
        }
    }

    private const val DIR = "danmaku_emoji"
}
