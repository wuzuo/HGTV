// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hgtv.core.Series
import com.hgtv.phone.FeedStore
import com.hgtv.phone.PhoneApp

@Composable
fun HomeScreen(onOpen: (Series) -> Unit) {
    val store = remember { FeedStore(PhoneApp.repository) }
    val grid = rememberLazyGridState()

    LaunchedEffect(Unit) { store.loadMore() }

    // 触底再拉一页：把「最后可见位置」和「已有条目数」一起放进快照，
    // 这样新一页追加进来之后条件会重新计算，不必等用户再滑一次。
    LaunchedEffect(grid) {
        snapshotFlow {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to store.items.size
        }.collect { (last, size) ->
            if (size > 0 && last >= size - 6) store.loadMore()
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("红果短剧", style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
            Box(Modifier.weight(1f))
            Text(
                "推荐",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Accent,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(store.items, key = { it.id }) { series ->
                SeriesCard(series) { onOpen(series) }
            }

            if (store.loading || store.error != null || (store.items.isEmpty() && !store.finished)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Footer(
                        loading = store.loading,
                        error = store.error,
                        empty = store.items.isEmpty() && !store.loading,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(series: Series, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.SurfaceHigh),
        ) {
            AsyncImage(
                model = series.cover,
                contentDescription = series.title,
                modifier = Modifier.fillMaxSize(),
            )
            val badge = series.badge.ifBlank { series.heat }
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            series.title,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 列表底部：加载中、失败、空三种状态共用一块位置。
 *
 * 失败时不自动重试——接口失败通常是设备额度或网络问题，自动重试只会把提示刷掉。
 */
@Composable
private fun Footer(loading: Boolean, error: String?, empty: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            error != null ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            loading -> CircularProgressIndicator(
                modifier = Modifier.width(22.dp).height(22.dp),
                color = Palette.Accent,
                strokeWidth = 2.dp,
            )
            empty -> Text(
                "暂时没有内容",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
            )
        }
    }
}
