// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.hgtv.core.Danmaku
import com.hgtv.core.DanmakuEmoji

/**
 * 压在画面上的弹幕层。
 *
 * 位置完全由播放器给（每帧读一次 `position`），自己不维护时钟，所以暂停、拖动进度、
 * 倍速都不会和画面脱节。
 *
 * 排轨规则与 TV 端一致：同一条轨道上，后一条要等前一条**完全进画**才能出发
 * （`freeAt = enterTime + (宽度 + 间距) / 速度`），因此同轨两条永远不会追尾；
 * 排不下就换轨，轨数不够就丢弃这一条，宁可不显示也不叠字。
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
    var size by remember { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    var tick by remember { mutableLongStateOf(0L) }

    val style =
        remember(scale) {
            TextStyle(
                fontSize = (14f * scale).sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                shadow = Shadow(Color(0xCC000000), Offset(1.5f, 1.5f), 3f),
            )
        }

    val placed =
        remember(items, width, size, style) {
            if (width <= 0 || size <= 0 || items.isEmpty()) {
                emptyList()
            } else {
                layoutLanes(items, width, size, measurer, style)
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
            val elapsed = now - line.item.offsetMs
            if (elapsed > line.travelMs) break
            if (elapsed >= 0) {
                val progress = elapsed / line.travelMs
                val x = width - progress * (width + line.textWidth)
                drawText(line.layout, topLeft = Offset(x, line.top))
            }
            index -= 1
        }
    }
}

private class PlacedLine(
    val item: Danmaku,
    val layout: androidx.compose.ui.text.TextLayoutResult,
    val textWidth: Float,
    val top: Float,
    /** 从右边缘外到左边缘外所需时间；同一条弹幕在屏幕上的存活时长。 */
    val travelMs: Float,
)

/** 从右到左横穿一屏的时间（毫秒）。 */
private const val TRAVEL_MS = 8_500f
private const val LANE_GAP_PX = 24f

private fun layoutLanes(
    items: List<Danmaku>,
    width: Int,
    height: Int,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
): List<PlacedLine> {
    val sorted = items.sortedBy { it.offsetMs }
    val measured =
        sorted.map { item ->
            // 平台的弹幕表情不是字形而是 `[笑哭]` 这类方括号标签；换成对应字形后
            // 交给系统字体画——手机上系统 emoji 字体本身就是彩色的，不需要再铺一层图片。
            val layout =
                measurer.measure(
                    AnnotatedString(DanmakuEmoji.displayText(item.text)),
                    style,
                    maxLines = 1,
                )
            item to layout
        }
    val lineHeight = measured.maxOfOrNull { it.second.size.height }?.toFloat() ?: 0f
    if (lineHeight <= 0f) return emptyList()
    val laneHeight = lineHeight + 16f
    // 只占上半屏：竖屏短剧的字幕和人物都在中间，弹幕压满会看不清。
    val laneCount = ((height * 0.36f) / laneHeight).toInt().coerceIn(1, 8)
    // 顶部留出控制条：返回键和标题就在这一带，弹幕从更下面才开始。
    val safeTop = height * 0.10f
    val freeAt = LongArray(laneCount) { 0L }
    val result = ArrayList<PlacedLine>(measured.size)
    for ((item, layout) in measured) {
        val textWidth = layout.size.width.toFloat()
        if (textWidth > width * 1.4f) continue // 超长弹幕直接丢，否则会挡住半屏
        val speed = (width + textWidth) / TRAVEL_MS
        var lane = -1
        for (i in 0 until laneCount) {
            if (item.offsetMs >= freeAt[i]) {
                lane = i
                break
            }
        }
        if (lane < 0) continue
        freeAt[lane] = item.offsetMs + ((textWidth + LANE_GAP_PX) / speed).toLong()
        result +=
            PlacedLine(
                item = item,
                layout = layout,
                textWidth = textWidth,
                top = safeTop + lane * laneHeight,
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
        if (lines[mid].item.offsetMs <= now) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}
