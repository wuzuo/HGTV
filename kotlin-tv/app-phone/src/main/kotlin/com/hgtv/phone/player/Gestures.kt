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
 * 上下滑换一集需要的位移，按屏幕高度算。
 *
 * 12% 大约是小拇指轻轻一推的距离：再小就会和「手滑了一下」分不开，再大就要刻意拉很长。
 */
internal const val EPISODE_SWIPE = 0.12f

/**
 * 播放页的手势：单击显隐控制层、双击左右半屏快退/快进、左右拖调进度、上下拖换集。
 *
 * 竖屏短剧的操作习惯是「上滑看下一集」，所以竖直方向留给换集，不再用于亮度/音量——
 * 那两个改成系统音量键与下拉控制中心，比在画面里划一条看不见的分界线更好用。
 *
 * 拖动方向在超过位移阈值后锁定一次，之后不再切换：手指稍微斜一点，进度和换集不会同时触发。
 */
fun Modifier.playerGestures(
    onTap: () -> Unit,
    onDoubleTap: (xFraction: Float) -> Unit,
    onSeekDrag: (deltaMs: Float) -> Unit,
    onSeekCommit: () -> Unit,
    onEpisodeDrag: (fraction: Float) -> Unit,
    onEpisodeCommit: (fraction: Float) -> Unit,
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
            detectDragGestures(
                onDragStart = {
                    axis = AXIS_NONE
                    totalX = 0f
                    totalY = 0f
                },
                onDrag = { _, amount ->
                    totalX += amount.x
                    totalY += amount.y
                    if (axis == AXIS_NONE && (abs(totalX) > SLOP || abs(totalY) > SLOP)) {
                        axis = if (abs(totalX) > abs(totalY)) AXIS_SEEK else AXIS_EPISODE
                    }
                    val width = size.width.coerceAtLeast(1)
                    val height = size.height.coerceAtLeast(1)
                    when (axis) {
                        AXIS_SEEK -> onSeekDrag(totalX / width * DRAG_SEEK_SPAN_MS)
                        // 上滑（负位移）下一集，与短视频一致；拖过阈值才提示，避免误触。
                        AXIS_EPISODE -> onEpisodeDrag(totalY / height)
                    }
                },
                onDragEnd = {
                    when (axis) {
                        AXIS_SEEK -> onSeekCommit()
                        AXIS_EPISODE -> onEpisodeCommit(totalY / size.height.coerceAtLeast(1))
                    }
                    axis = AXIS_NONE
                },
                onDragCancel = {
                    // 手势被抢走（弹出面板等）时也要把界面上的拖动状态收干净，
                    // 否则进度提示会一直停在最后一帧。
                    when (axis) {
                        AXIS_SEEK -> onSeekCommit()
                        AXIS_EPISODE -> onEpisodeCommit(0f)
                    }
                    axis = AXIS_NONE
                },
            )
        }

private const val AXIS_NONE = 0
private const val AXIS_SEEK = 1
private const val AXIS_EPISODE = 2
private const val SLOP = 10f
