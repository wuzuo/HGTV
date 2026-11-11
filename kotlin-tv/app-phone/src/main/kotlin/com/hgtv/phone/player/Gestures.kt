// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
 * 播放页的手势。
 *
 * 单击与双击的语义跟着屏幕方向走：
 * - **竖屏**是刷短剧的姿势，单击直接暂停/继续。[doubleTapToPause] 传 false 时不注册双击
 *   监听——注册了单击就得等 300ms 双击超时才回调，点一下「顿一下才停」很难受。
 * - **横屏**是全屏观看，单击显隐控制层，双击暂停。全屏时手指搭在画面上的概率高，
 *   单击不该改变播放状态。
 *
 * 左右拖调进度、上下拖换集两条拖动始终有效，方向在超过位移阈值后锁定一次，之后不再切换：
 * 手指稍微斜一点，进度和换集不会同时触发。
 */
fun Modifier.playerGestures(
    doubleTapToPause: Boolean,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onSeekDrag: (deltaMs: Float) -> Unit,
    onSeekCommit: () -> Unit,
    onEpisodeDrag: (offsetPx: Float, fraction: Float) -> Unit,
    onEpisodeCommit: (fraction: Float) -> Unit,
    onSpeedUpStart: () -> Unit,
    onSpeedUpEnd: () -> Unit,
    isSpeedUp: () -> Boolean,
): Modifier =
    this.pointerInput(doubleTapToPause) {
            detectTapGestures(
                onTap = { if (!isSpeedUp()) onSingleTap() },
                // null 表示不认双击：单击立即回调，无需等待双击超时。
                onDoubleTap = if (doubleTapToPause) ({ _ -> if (!isSpeedUp()) onDoubleTap() }) else null,
                // 按住不放看到哪：松手要能复原，但 detectTapGestures 不给「抬起」回调，
                // 所以这里只负责开始，结束交给下面那个只看抬手的监听。
                onLongPress = { onSpeedUpStart() },
            )
        }
        // 只观察抬手，不参与竞争：不管这一下是长按、拖动还是点空白，手指离开时都收回加速。
        // 用 isSpeedUp 兜底而不是自己记状态，是为了避免两份状态在重组后对不上。
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                waitForUpOrCancellation()
                if (isSpeedUp()) onSpeedUpEnd()
            }
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
                        // 上滑（负位移）下一集，与短视频一致。位移原样交给界面做跟手，
                        // 手指动多少画面就走多少——只给一个阈值提示的话，划起来像没反应。
                        AXIS_EPISODE -> onEpisodeDrag(totalY, totalY / height)
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
                    // 否则进度提示会一直停在最后一帧，画面也会卡在偏移位置。
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
