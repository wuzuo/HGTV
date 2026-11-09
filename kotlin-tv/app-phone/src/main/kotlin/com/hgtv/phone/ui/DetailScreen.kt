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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hgtv.phone.DetailStore
import com.hgtv.phone.PhoneApp

@Composable
fun DetailScreen(
    seriesId: String,
    onBack: () -> Unit,
    onPlay: (seriesId: String, episodes: List<String>, index: Int) -> Unit,
) {
    val store = remember(seriesId) { DetailStore(PhoneApp.repository) }
    LaunchedEffect(seriesId) { store.load(seriesId) }

    Column(
        Modifier.fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
            detail != null -> DetailBody(
                title = detail.series.title,
                cover = detail.series.cover,
                description = detail.series.description,
                episodes = detail.episodes,
                onPlay = { index -> onPlay(seriesId, detail.episodes, index) },
            )
            store.loading -> CenterBox { CircularProgressIndicator(color = Palette.Accent) }
            else -> CenterBox {
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
    title: String,
    cover: String,
    description: String,
    episodes: List<String>,
    onPlay: (Int) -> Unit,
) {
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
                    model = cover,
                    contentDescription = title,
                    modifier = Modifier
                        .width(108.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.SurfaceHigh),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.TextPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "全 ${episodes.size} 集",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextSecondary,
                    )
                    if (description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextTertiary,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

        itemsIndexed(episodes, key = { _, id -> id }) { index, _ ->
            EpisodeCell(index + 1) { onPlay(index) }
        }
    }
}

@Composable
private fun EpisodeCell(number: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (number == 1) Palette.Accent else Palette.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = if (number == 1) Color(0xFF1A0B05) else Palette.TextPrimary,
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
