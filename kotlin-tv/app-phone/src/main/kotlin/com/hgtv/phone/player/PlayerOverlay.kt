// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hgtv.phone.ui.Palette

/** 底部/顶部控制层与状态提示。 */
@Composable
fun PlayerControls(
    title: String,
    episodeLabel: String,
    position: Long,
    duration: Long,
    playing: Boolean,
    buffering: Boolean,
    danmakuOn: Boolean,
    qualityLabel: String,
    speedLabel: String,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: (() -> Unit)?,
    onEpisodes: () -> Unit,
    onQuality: () -> Unit,
    onSpeed: () -> Unit,
    onDanmakuToggle: () -> Unit,
    onComments: () -> Unit,
    onPip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))
                )
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 集数这一行同时是选集入口：短剧动辄上百集，只有「下一集」翻页太慢。
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onEpisodes)
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        episodeLabel,
                        color = Color(0xB3FFFFFF),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = "选集",
                        tint = Color(0xB3FFFFFF),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            IconButton(onClick = onComments) {
                Icon(
                    Icons.AutoMirrored.Outlined.Comment,
                    contentDescription = "评论",
                    tint = Color.White,
                )
            }
            // 没有画中画能力的设备（如电视）不显示这个按钮，点了也没有反应更糟。
            if (onPip != null) {
                IconButton(onClick = onPip) {
                    Icon(
                        Icons.Filled.PictureInPictureAlt,
                        contentDescription = "画中画",
                        tint = Color.White,
                    )
                }
            }
        }

        Box(
            Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center,
        ) {
            if (buffering) {
                CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp)
            } else if (!playing) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color(0xE6FFFFFF),
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .clickable(onClick = onPlayPause)
                        .padding(12.dp),
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                )
        ) {
            Row(
                Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                    )
                }
                if (onNext != null) {
                    IconButton(onClick = onNext) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "下一集", tint = Color.White)
                    }
                }
                Text(
                    "${formatDuration(position)} / ${formatDuration(duration)}",
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                ControlChip(
                    icon = Icons.Filled.Subtitles,
                    label = if (danmakuOn) "弹幕开" else "弹幕关",
                    active = danmakuOn,
                    onClick = onDanmakuToggle,
                )
                ControlChip(
                    icon = Icons.Outlined.HighQuality,
                    label = qualityLabel,
                    active = false,
                    onClick = onQuality,
                )
                ControlChip(
                    icon = Icons.Filled.Speed,
                    label = speedLabel,
                    active = false,
                    onClick = onSpeed,
                )
            }
            // 进度条贴在屏幕最下沿：2dp 一条线，触摸区域仍是 16dp，拖动与点击都能用。
            SeekBar(position = position, duration = duration, onSeek = onSeek)
        }
    }
}

@Composable
private fun ControlChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0x33FF8964) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Palette.Accent else Color.White,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            label,
            color = if (active) Palette.Accent else Color(0xE6FFFFFF),
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * 贴在最下方的细进度条。
 *
 * 用 Slider 做不到「一条 2dp 的线」：Material3 的 Slider 自带 44dp 的触摸区与滑块，
 * 放在屏幕最底部会顶出一大块黑色。这里自绘两条线加一个点，触摸区仍然是 16dp，
 * 点击跳转和拖动都支持。
 *
 * 拖动期间用本地值，松手才 seek：播放位置每 250ms 刷新一次，直接绑控制器会让滑块
 * 在手指下面被拽回去。
 */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var draft by remember { mutableFloatStateOf(0f) }
    val total = duration.coerceAtLeast(1L).toFloat()
    val shown = (if (dragging) draft else position.toFloat()).coerceIn(0f, total)
    val fraction = shown / total

    Canvas(
        Modifier.fillMaxWidth()
            .height(16.dp)
            .pointerInput(total) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.coerceAtLeast(1) * total).toLong())
                }
            }
            .pointerInput(total) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        draft =
                            (offset.x / size.width.coerceAtLeast(1) * total).coerceIn(0f, total)
                    },
                    onHorizontalDrag = { _, amount ->
                        draft =
                            (draft + amount / size.width.coerceAtLeast(1) * total)
                                .coerceIn(0f, total)
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek(draft.toLong())
                    },
                    onDragCancel = { dragging = false },
                )
            }
    ) {
        val centerY = size.height / 2f
        val thickness = 2.dp.toPx()
        drawRoundRect(
            color = Color(0x4DFFFFFF),
            topLeft = Offset(0f, centerY - thickness / 2),
            size = Size(size.width, thickness),
            cornerRadius = CornerRadius(thickness / 2),
        )
        if (fraction > 0f) {
            drawRoundRect(
                color = Palette.Accent,
                topLeft = Offset(0f, centerY - thickness / 2),
                size = Size(size.width * fraction, thickness),
                cornerRadius = CornerRadius(thickness / 2),
            )
        }
        // 2dp 的线在亮画面上会看不见，用一个点标出当前位置。
        drawCircle(
            color = Palette.Accent,
            radius = if (dragging) 4.dp.toPx() else 2.5.dp.toPx(),
            center = Offset(size.width * fraction, centerY),
        )
    }
}

/** 拖动进度/调节亮度或音量时的居中提示。 */
@Composable
fun PlayerHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xB3000000))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
