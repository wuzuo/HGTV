// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hgtv.core.Series
import com.hgtv.phone.PhoneApp
import com.hgtv.phone.SearchStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 搜索页。
 *
 * 输入停下 300ms 才去要联想词，避免每个字符一次请求；结果列表只在「确认搜索」或
 * 点联想词之后才换，打字过程中不会跟着抖。
 */
@Composable
fun SearchScreen(
    store: SearchStore,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    onOpen: (Series) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf(store.query) }

    LaunchedEffect(Unit) { store.refreshHistory() }

    LaunchedEffect(text) {
        if (text.isBlank()) {
            store.suggest("")
            return@LaunchedEffect
        }
        delay(300)
        store.suggest(text)
    }

    LaunchedEffect(grid) {
        snapshotFlow {
                val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last to store.items.size
            }
            .collect { (last, size) ->
                if (store.active && size > 0 && last >= size - 6) store.loadMore()
            }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("搜剧名、演员、题材", color = Palette.TextTertiary) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.TextSecondary)
                },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = {
                            text = ""
                            store.reset()
                        }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "清空",
                                tint = Palette.TextSecondary,
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            scope.launch { store.search(text) }
                        }
                    ),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.Accent,
                        unfocusedBorderColor = Palette.SurfaceHigh,
                        focusedTextColor = Palette.TextPrimary,
                        unfocusedTextColor = Palette.TextPrimary,
                        cursorColor = Palette.Accent,
                    ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            )
            Text(
                "搜索",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Accent,
                modifier = Modifier
                    .clickable {
                        keyboard?.hide()
                        if (text.isNotBlank()) scope.launch { store.search(text) }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (!store.active) {
            DiscoveryHints(
                history = store.history,
                suggestions = store.suggestions,
                onPick = { keyword ->
                    text = keyword
                    keyboard?.hide()
                    scope.launch { store.search(keyword) }
                },
                onClearHistory = { store.clearHistory() },
            )
        } else {
            ResultGrid(store = store, grid = grid, onOpen = onOpen)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryHints(
    history: List<com.hgtv.core.RecentSearch>,
    suggestions: List<String>,
    onPick: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (suggestions.isNotEmpty()) {
            Text(
                "猜你想搜",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { word ->
                    KeywordChip(word) { onPick(word) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("搜索历史", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                Text(
                    "清空",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextTertiary,
                    modifier = Modifier.clickable(onClick = onClearHistory).padding(4.dp),
                )
            }
        }
        if (history.isEmpty()) {
            Text(
                "还没有搜索记录",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                history.forEach { entry ->
                    KeywordChip(entry.query) { onPick(entry.query) }
                }
            }
        }
    }
}

@Composable
private fun KeywordChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun ResultGrid(store: SearchStore, grid: androidx.compose.foundation.lazy.grid.LazyGridState, onOpen: (Series) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = grid,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (store.items.isEmpty() && store.loading) "正在搜索「${store.query}」"
                else "「${store.query}」的搜索结果",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(store.items, key = { it.id }) { series ->
            SeriesCard(series) { onOpen(series) }
        }
        if (store.loading || store.error != null || (store.items.isEmpty() && store.finished)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ListFooter(
                    loading = store.loading,
                    error = store.error ?: if (store.items.isEmpty() && store.finished) "没有找到相关剧集" else null,
                    empty = false,
                )
            }
        }
    }
}
