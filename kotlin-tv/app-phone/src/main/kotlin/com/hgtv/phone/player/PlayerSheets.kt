// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hgtv.phone.media.CommentItem
import com.hgtv.phone.ui.Palette

/** 播放页能弹出的面板。 */
sealed interface PlayerSheet {
    data object Quality : PlayerSheet

    data object Speed : PlayerSheet

    data object Episodes : PlayerSheet

    data object Comments : PlayerSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(
    title: String,
    onDismiss: () -> Unit,
    // 选集面板要把画面留在上方，蒙层会把缩小的画面压黑，所以单独关掉。
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        contentColor = Palette.TextPrimary,
        scrimColor = scrimColor,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            content()
        }
    }
}

/**
 * 清晰度面板：只列当前设备能解的档位。
 *
 * [tiers] 来自解码后的播放阶梯（元数据里 reported 的档位），按从高到低展示；
 * 选了哪一档就写回设置，下一集直接从这一档开始。
 */
@Composable
fun QualitySheet(
    tiers: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet("清晰度", onDismiss) {
        val options = tiers.ifEmpty { listOf(480, 720, 1080) }.sortedDescending()
        options.forEach { quality ->
            SheetRow(
                text = if (quality >= 1080) "1080P 超清" else "${quality}P",
                selected = quality == current,
                onClick = { onPick(quality) },
            )
        }
    }
}

@Composable
fun SpeedSheet(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    Sheet("播放速度", onDismiss) {
        com.hgtv.phone.data.PhoneSettings.speeds.forEach { speed ->
            SheetRow(
                text = if (speed == 1f) "1.0x 正常" else "${speed}x",
                selected = kotlin.math.abs(speed - current) < 0.01f,
                onClick = { onPick(speed) },
            )
        }
    }
}

@Composable
fun EpisodesSheet(
    count: Int,
    current: Int,
    watchedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 面板压到半屏，剩下的地方留给缩小的画面。用实测高度算而不是按 dp 猜：
    // PlayerScreen 那边按 0.5 缩放画面，两边对不上就会切掉一截。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelHeight = (maxHeight * 0.5f - 120.dp).coerceAtLeast(160.dp)
        Sheet("选集（共 $count 集）", onDismiss, scrimColor = Color.Transparent) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = panelHeight),
            ) {
            itemsIndexed(List(count) { it }) { index, _ ->
                val selected = index == current
                val watched = index < watchedIndex
                Box(
                    Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                selected -> Palette.Accent
                                watched -> Palette.SurfaceHigh
                                else -> Palette.Surface
                            }
                        )
                        .clickable { onPick(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (index + 1).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            when {
                                selected -> Color(0xFF1A0B05)
                                watched -> Palette.TextSecondary
                                else -> Palette.TextPrimary
                            },
                    )
                }
            }
        }
        }
    }
}

@Composable
fun CommentsSheet(
    items: List<CommentItem>?,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet("评论", onDismiss) {
        when {
            error != null ->
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Accent,
                        modifier = Modifier.clickable(onClick = onRetry).padding(8.dp),
                    )
                }
            items == null ->
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp)
                }
            items.isEmpty() ->
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("这一集还没有评论", style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                }
            else ->
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(items) { comment ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    comment.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.TextSecondary,
                                )
                                Spacer(Modifier.width(8.dp))
                                if (comment.likes > 0) {
                                    Text(
                                        "${comment.likes} 赞",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Palette.TextTertiary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                comment.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.TextPrimary,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun SheetRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Palette.Accent else Palette.TextPrimary,
        )
    }
}
