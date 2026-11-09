// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** 满屏横向拖动对应的快进跨度：两分钟，短剧一集也就这么长。 */
private const val DRAG_SEEK_SPAN_MS = 120_000f

/**
 * 播放页的手势：单击显隐控制层、双击左右半屏快退/快进、左右拖调进度、上下拖调亮度与音量。
 *
 * 拖动方向在超过位移阈值后锁定一次，之后不再切换——否则手指稍微斜一点，
 * 亮度就会和进度条一起动。左半屏是亮度、右半屏是音量，与主流播放器一致。
 */
fun Modifier.playerGestures(
    onTap: () -> Unit,
    onDoubleTap: (xFraction: Float) -> Unit,
    onSeekDrag: (deltaMs: Float) -> Unit,
    onSeekCommit: () -> Unit,
    onBrightnessDrag: (delta: Float) -> Unit,
    onVolumeDrag: (delta: Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier =
    this.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { offset -> onDoubleTap(offset.x / size.width.coerceAtLeast(1)) },
            )
        }
        .pointerInput(Unit) {
            var axis = AXIS_NONE
            var totalX = 0f
            var totalY = 0f
            var startX = 0f
            detectDragGestures(
                onDragStart = { offset ->
                    axis = AXIS_NONE
                    totalX = 0f
                    totalY = 0f
                    startX = offset.x
                },
                onDrag = { _, amount ->
                    totalX += amount.x
                    totalY += amount.y
                    if (axis == AXIS_NONE && (abs(totalX) > SLOP || abs(totalY) > SLOP)) {
                        axis = if (abs(totalX) > abs(totalY)) AXIS_SEEK else AXIS_LEVEL
                    }
                    val width = size.width.coerceAtLeast(1)
                    val height = size.height.coerceAtLeast(1)
                    when (axis) {
                        AXIS_SEEK -> onSeekDrag(totalX / width * DRAG_SEEK_SPAN_MS)
                        AXIS_LEVEL ->
                            if (startX < width / 2f) onBrightnessDrag(-amount.y / height)
                            else onVolumeDrag(-amount.y / height)
                    }
                },
                onDragEnd = {
                    if (axis == AXIS_SEEK) onSeekCommit()
                    axis = AXIS_NONE
                    onDragEnd()
                },
                onDragCancel = {
                    axis = AXIS_NONE
                    onDragEnd()
                },
            )
        }

private const val AXIS_NONE = 0
private const val AXIS_SEEK = 1
private const val AXIS_LEVEL = 2
private const val SLOP = 10f
