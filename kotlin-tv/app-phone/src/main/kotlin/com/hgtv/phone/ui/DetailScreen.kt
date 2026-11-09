// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hgtv.core.Detail
import com.hgtv.core.ResumePlayback
import com.hgtv.core.Series
import com.hgtv.phone.DetailStore
import com.hgtv.phone.PhoneApp
import kotlinx.coroutines.launch

/**
 * 详情页：封面、简介、继续观看、收藏、选集。
 *
 * 「继续观看」同时看本机与云端两条记录，取更新的那条（[ResumePlayback.progress]）：
 * 在电视上看了一半的剧，换到手机上能接着看，反过来也一样。
 */
@Composable
fun DetailScreen(
    seriesId: String,
    onBack: () -> Unit,
    onPlay: (Series, List<String>, Int) -> Unit,
) {
    val store = remember(seriesId) { DetailStore(PhoneApp.repository) }
    val library = PhoneApp.library
    val account = PhoneApp.account
    val scope = rememberCoroutineScope()

    LaunchedEffect(seriesId) { store.load(seriesId) }

    Column(
        Modifier.fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "返回",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.clickable(onClick = onBack).padding(4.dp),
            )
        }

        val detail = store.detail
        when {
            detail != null -> {
                val resume = ResumePlayback.progress(detail, library.progress(seriesId), account.cloudProgress(seriesId))
                val target = resume?.let {
                    ResumePlayback.target(detail.episodes, it.episodeId, it.position, it.completed)
                }
                DetailBody(
                    detail = detail,
                    favorite = seriesId in library.favoriteIds,
                    resumeIndex = target?.index,
                    resumePosition = target?.position ?: 0L,
                    onPlay = { index -> onPlay(detail.series, detail.episodes, index) },
                    onToggleFavorite = {
                        val added = library.toggleFavorite(detail.series)
                        if (account.signedIn) {
                            scope.launch { account.pushFavorite(detail.series, added) }
                        }
                    },
                )
            }
            store.loading -> CenterBox { CircularProgressIndicator(color = Palette.Accent) }
            else ->
                CenterBox {
                    Text(
                        store.error ?: "剧集详情加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextSecondary,
                    )
                }
        }
    }
}

@Composable
private fun DetailBody(
    detail: Detail,
    favorite: Boolean,
    resumeIndex: Int?,
    resumePosition: Long,
    onPlay: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val series = detail.series
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                AsyncImage(
                    model = series.cover,
                    contentDescription = series.title,
                    modifier = Modifier
                        .width(108.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.SurfaceHigh),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        series.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.TextPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOf("全 ${detail.episodes.size} 集", series.heat)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextSecondary,
                    )
                    if (series.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            series.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextTertiary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.Accent)
                        .clickable { onPlay(resumeIndex ?: 0) }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF1A0B05),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (resumeIndex != null && resumePosition > 0)
                            "继续第 ${resumeIndex + 1} 集 ${formatPlayhead(resumePosition)}"
                        else if (resumeIndex != null && resumeIndex > 0) "继续第 ${resumeIndex + 1} 集"
                        else "播放第 1 集",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF1A0B05),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.Surface)
                        .clickable(onClick = onToggleFavorite)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (favorite) "取消收藏" else "收藏",
                        tint = if (favorite) Palette.Accent else Palette.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (favorite) "已收藏" else "收藏",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (favorite) Palette.Accent else Palette.TextSecondary,
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "选集",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }

        itemsIndexed(detail.episodes, key = { _, id -> id }) { index, _ ->
            EpisodeCell(
                number = index + 1,
                selected = index == resumeIndex,
                watched = resumeIndex != null && index < resumeIndex,
                onClick = { onPlay(index) },
            )
        }
    }
}

@Composable
private fun EpisodeCell(number: Int, selected: Boolean, watched: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> Palette.Accent
                    watched -> Palette.SurfaceHigh
                    else -> Palette.Surface
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
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

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun formatPlayhead(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
