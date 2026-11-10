// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hgtv.core.RankBoard
import com.hgtv.core.Series
import com.hgtv.phone.RankStore
import kotlinx.coroutines.launch

/**
 * 榜单页：四个榜横向切换，网格里带名次角标。
 *
 * 不做题材筛选——手机上已经有四个榜的切换条，再叠一层筛选会让首屏只剩控件。
 * 想看某个题材直接去搜索，榜单保持「一眼扫完一百名」的用途。
 */
@Composable
fun RankScreen(
    store: RankStore,
    grids: Map<RankBoard, LazyGridState>,
    onOpen: (Series) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val grid = grids[store.board] ?: return

    // 进入页面与切榜都走这里：TTL 内的缓存直接返回，不会产生请求。
    LaunchedEffect(store.board) { store.load() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("榜单", style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
            Box(Modifier.weight(1f))
            if (store.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp).width(18.dp).height(18.dp),
                    color = Palette.Accent,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新榜单",
                    tint = Palette.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { scope.launch { store.load(force = true) } }
                        .padding(8.dp)
                        .width(20.dp)
                        .height(20.dp),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RankBoard.entries.forEach { board ->
                BoardChip(board.label, board == store.board) { store.select(board) }
            }
        }

        // 副标题：更新时间与部数；更新失败时说明仍在显示旧榜，避免用户以为看到的是最新名次。
        val caption = store.error?.let { "更新失败：$it（显示上次榜单）" } ?: store.caption
        if (caption.isNotBlank()) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (store.error != null) Palette.TextTertiary else Palette.TextSecondary,
                maxLines = 2,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(store.items, key = { it.id }) { series ->
                SeriesCard(
                    series,
                    rank = store.positions[series.id]?.rank,
                    onClick = { onOpen(series) },
                )
            }

            if (store.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ListFooter(
                        loading = store.loading || !store.loaded,
                        error = store.error,
                        empty = store.loaded && !store.loading && store.error == null,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        fontSize = 13.sp,
        color = if (selected) Palette.Background else Palette.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.Accent else Palette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
