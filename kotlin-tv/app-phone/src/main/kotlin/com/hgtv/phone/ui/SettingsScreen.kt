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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hgtv.core.DeviceIdentityMonitor
import com.hgtv.core.PlaybackQuality
import com.hgtv.phone.PhoneApp
import com.hgtv.phone.data.PhoneSettings
import com.hgtv.playback.IdentityStatus
import com.hgtv.playback.openAllFilesAccessSettings
import kotlin.math.roundToInt

/** 设置页：只放真正会改变行为的开关，不做「关于我们」这类占位。 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = PhoneApp.settings
    val library = PhoneApp.library
    val account = PhoneApp.account
    val context = LocalContext.current
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var identityOpen by remember { mutableStateOf(false) }
    var identity by remember { mutableStateOf(IdentityStatus.of(context)) }

    // 从系统「所有文件访问」设置页回来时重新读一次：权限刚开的话，这时才看得到共享目录里的文件。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        runCatching { DeviceIdentityMonitor.resolve() }
        identity = IdentityStatus.of(context)
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
            // 播放身份放在最前面：播不出来时第一件事就是看它。
            SettingTitle("播放身份")
            ActionRow("设备身份配置  ·  ${identityLabel(identity)}") { identityOpen = true }
            Text(
                "读到真实设备 identity 就跳过自动注册。没有也能播，只是每集要多注册一次设备。",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )

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

    if (identityOpen) {
        AlertDialog(
            onDismissRequest = { identityOpen = false },
            title = { Text(identityTitle(identity)) },
            text = { Text(identityMessage(identity)) },
            confirmButton = {
                if (identity is IdentityStatus.NoPermission) {
                    TextButton(onClick = {
                        openAllFilesAccessSettings(context)
                        identityOpen = false
                    }) { Text("去开启") }
                } else {
                    TextButton(onClick = { identityOpen = false }) { Text("好") }
                }
            },
            dismissButton = if (identity is IdentityStatus.NoPermission) {
                { TextButton(onClick = { identityOpen = false }) { Text("取消") } }
            } else null,
            containerColor = Palette.Surface,
        )
    }
}

/** 设置页那一行的状态词，控制在四个字以内，和 TV 端一致。 */
private fun identityLabel(status: IdentityStatus): String =
    when (status) {
        is IdentityStatus.Ready -> "已读取"
        IdentityStatus.NoPermission -> "需授权"
        IdentityStatus.NotConfigured -> "未放置"
    }

private fun identityTitle(status: IdentityStatus): String =
    when (status) {
        is IdentityStatus.Ready -> "设备身份配置"
        IdentityStatus.NoPermission -> "需要「所有文件访问」权限"
        IdentityStatus.NotConfigured -> "还没有设备身份配置"
    }

private fun identityMessage(status: IdentityStatus): String =
    when (status) {
        is IdentityStatus.Ready ->
            "读取位置：${status.file.absolutePath}\n\n" +
                "device_id ${status.identity.deviceId}\n" +
                "install_id ${status.identity.installId}\n" +
                "cdid ${status.identity.cdid}\n\n" +
                "播放时直接复用这份身份，不再注册新设备。" +
                "同一份身份不要和官方客户端同时播放。"
        IdentityStatus.NoPermission ->
            "Android 11 起，应用读 /sdcard/hgtv/ 这类共享目录需要「所有文件访问」权限。" +
                "没有这个权限时，系统会把目录对你隐藏——文件明明放着也读不到，而且不报错。\n\n" +
                "开启后回到 HGTV 会重新读取。"
        IdentityStatus.NotConfigured ->
            "把 device-identity.txt 放到 /sdcard/hgtv/ 目录下即可生效。文件内容三行：\n\n" +
                "device_id=...\n" +
                "install_id=...\n" +
                "cdid=...\n\n" +
                "也可以放在这个不需要权限的位置：\n" +
                (com.hgtv.core.DeviceIdentityFile.fallbacks.firstOrNull()?.absolutePath ?: "应用私有目录")
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
