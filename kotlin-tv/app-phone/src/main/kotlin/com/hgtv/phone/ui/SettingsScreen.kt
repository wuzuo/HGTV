// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hgtv.core.PlaybackQuality
import com.hgtv.phone.PhoneApp
import com.hgtv.phone.data.PhoneSettings
import kotlin.math.roundToInt

/** 设置页：只放真正会改变行为的开关，不做「关于我们」这类占位。 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = PhoneApp.settings
    val library = PhoneApp.library
    val account = PhoneApp.account
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

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
            Spacer(Modifier.weight(1f))
            Text("设置", style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.padding(horizontal = 14.dp))
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingTitle("默认清晰度")
            Row(Modifier.padding(horizontal = 16.dp)) {
                PlaybackQuality.options.forEach { quality ->
                    ChoiceChip(
                        label = "${quality}P",
                        selected = settings.maxQuality == quality,
                        onClick = { settings.setQuality(quality) },
                    )
                }
            }

            SettingTitle("默认倍速")
            Row(Modifier.padding(horizontal = 16.dp)) {
                PhoneSettings.speeds.forEach { speed ->
                    ChoiceChip(
                        label = if (speed == 1f) "1x" else "${speed}x",
                        selected = kotlin.math.abs(settings.playbackSpeed - speed) < 0.01f,
                        onClick = { settings.setSpeed(speed) },
                    )
                }
            }

            SettingSwitch(
                title = "显示弹幕",
                subtitle = "关闭后播放页不再请求弹幕接口",
                checked = settings.danmakuEnabled,
                onChange = { settings.updateDanmakuEnabled(it) },
            )

            SettingTitle("弹幕字号 ${(settings.danmakuScale * 100).roundToInt()}%")
            Slider(
                value = settings.danmakuScale,
                onValueChange = { settings.updateDanmakuScale(it) },
                valueRange = 0.7f..1.6f,
                colors =
                    SliderDefaults.colors(
                        thumbColor = Palette.Accent,
                        activeTrackColor = Palette.Accent,
                        inactiveTrackColor = Palette.SurfaceHigh,
                    ),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SettingSwitch(
                title = "自动下一集",
                subtitle = "一集播完直接接下一集",
                checked = settings.autoNext,
                onChange = { settings.updateAutoNext(it) },
            )

            SettingTitle("数据")
            ActionRow("清空观看记录（${library.history.size} 条）") { confirmClearHistory = true }
            ActionRow("清空搜索历史（${library.searches.size} 条）") { library.clearSearches() }
            if (account.signedIn) {
                ActionRow("退出登录") { confirmSignOut = true }
            }

            SettingTitle("关于")
            Text(
                "HGTV 手机版 0.1.0 · 与电视端共用内容核心与播放层\n播放链路：设备身份签名 → AES-CTR 流式解密 → 硬件解码",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空观看记录？") },
            text = { Text("收藏不受影响，但「继续观看」会一起清掉。") },
            confirmButton = {
                TextButton(onClick = {
                    library.clearHistory()
                    confirmClearHistory = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("取消") }
            },
            containerColor = Palette.Surface,
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("退出登录？") },
            text = { Text("本机的收藏与观看记录会保留，只是不再同步到云端。") },
            confirmButton = {
                TextButton(onClick = {
                    account.signOut()
                    confirmSignOut = false
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("取消") }
            },
            containerColor = Palette.Surface,
        )
    }
}

@Composable
private fun SettingTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) Color(0xFF1A0B05) else Palette.TextPrimary,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.Accent else Palette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1A0B05),
                    checkedTrackColor = Palette.Accent,
                    uncheckedThumbColor = Palette.TextSecondary,
                    uncheckedTrackColor = Palette.SurfaceHigh,
                ),
        )
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
    }
}
