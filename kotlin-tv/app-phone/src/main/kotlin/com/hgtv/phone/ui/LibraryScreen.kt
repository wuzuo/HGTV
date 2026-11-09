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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hgtv.core.BackupProgress
import com.hgtv.core.Series
import com.hgtv.phone.PhoneApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」：账号、继续观看、收藏、设置入口。
 *
 * 所有数据都来自本机片库（[com.hgtv.phone.data.PhoneLibrary]），登录只影响「能不能同步到云端」，
 * 不登录也能收藏、能续播——这是手机用户最常见的用法，不该被登录墙挡住。
 */
@Composable
fun LibraryScreen(
    onOpen: (Series) -> Unit,
    onPlay: (Series, List<String>, Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val library = PhoneApp.library
    val account = PhoneApp.account
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var openingError by remember { mutableStateOf<String?>(null) }

    if (!library.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp)
        }
        return
    }

    /** 续播要拿到整部剧的分集表才能确定「第几集、从哪一秒」；点一下先拉详情再进播放页。 */
    fun continueWatching(progress: BackupProgress) {
        if (opening) return
        opening = true
        openingError = null
        scope.launch {
            runCatching {
                    val detail = withContext(Dispatchers.IO) {
                        PhoneApp.repository.detail(progress.series.id)
                    }
                    val index = detail.episodes.indexOf(progress.episodeId)
                        .takeIf { it >= 0 }
                        ?: progress.episodeIndex.coerceIn(0, detail.episodes.lastIndex.coerceAtLeast(0))
                    onPlay(detail.series, detail.episodes, index)
                }
                .onFailure { openingError = it.message ?: "剧集信息加载失败" }
            opening = false
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { AccountCard(onOpenLogin = onOpenLogin, onOpenSettings = onOpenSettings) }

        if (library.history.isNotEmpty()) {
            item { SectionHeader("继续观看") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(library.history.take(12), key = { it.series.id }) { progress ->
                        ContinueCard(progress) { continueWatching(progress) }
                    }
                }
            }
        }

        item { SectionHeader("我的收藏（${library.favorites.size}）") }
        if (library.favorites.isEmpty()) {
            item {
                Text(
                    "还没有收藏，看到喜欢的点一下收藏按钮就会出现在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            items(library.favorites, key = { it.id }) { series ->
                FavoriteRow(
                    series = series,
                    onClick = { onOpen(series) },
                    onRemove = { library.toggleFavorite(series) },
                )
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            SettingsRow(onClick = onOpenSettings)
        }

        if (openingError != null) {
            item {
                Text(
                    openingError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (opening) {
            item {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = Palette.Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCard(onOpenLogin: () -> Unit, onOpenSettings: () -> Unit) {
    val account = PhoneApp.account
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (account.profile?.nickname ?: "游").take(1),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Accent,
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                account.profile?.nickname?.takeIf { it.isNotBlank() }
                    ?: if (account.signedIn) "已登录" else "未登录",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    !account.signedIn -> "登录后可同步收藏与观看记录"
                    account.busy -> "正在同步云端记录…"
                    account.message != null -> account.message!!
                    else -> "云端记录已同步"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!account.signedIn) {
            Text(
                "登录",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF1A0B05),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Accent)
                    .clickable(onClick = onOpenLogin)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Palette.TextSecondary)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Palette.TextPrimary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
    )
}

@Composable
private fun ContinueCard(progress: BackupProgress, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.SurfaceHigh),
        ) {
            AsyncImage(
                model = progress.series.cover,
                contentDescription = progress.series.title,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "第 ${progress.episodeIndex + 1} 集",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Text(
            progress.series.title,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (progress.duration > 0) {
            Text(
                "看到 ${formatClock(progress.position)}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
            )
        }
    }
}

@Composable
private fun FavoriteRow(series: Series, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = series.cover,
            contentDescription = series.title,
            modifier = Modifier
                .width(46.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.SurfaceHigh),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                series.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(series.badge, series.heat).firstOrNull { it.isNotBlank() } ?: "短剧",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                maxLines = 1,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "取消收藏", tint = Palette.TextTertiary)
        }
    }
}

@Composable
private fun SettingsRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Settings, contentDescription = null, tint = Palette.TextSecondary)
        Text(
            "播放与存储设置",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextPrimary,
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleMedium, color = Palette.TextTertiary)
    }
}

private fun formatClock(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
