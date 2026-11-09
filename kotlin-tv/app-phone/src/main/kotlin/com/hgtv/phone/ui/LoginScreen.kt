// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hgtv.phone.PhoneApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手机号验证码登录。
 *
 * 发送与验证都走 `:core` 的 [com.hgtv.core.PhoneLogin]，与 TV 端同一条链路；验证码换到的会话
 * 存在 Keystore 加密分区里，之后收藏与进度就能同步到云端。
 *
 * 注意：平台对短信登录有风控，个别号码会被要求滑块验证——那种情况下这里会如实报错，
 * 不会静默失败。
 */
@Composable
fun LoginScreen(onBack: () -> Unit, onSignedIn: () -> Unit) {
    val account = PhoneApp.account
    val scope = rememberCoroutineScope()
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown -= 1
        }
    }

    val canSend = mobile.length >= 6 && countdown == 0 && !sending && !verifying
    val canVerify = sentTo != null && code.length >= 4 && !verifying && !sending

    Column(
        Modifier.fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
    ) {
        Text(
            "返回",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 14.dp),
        )
        Text(
            "登录红果账号",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextPrimary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "登录后收藏与观看进度会同步到云端，换手机接着看",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextTertiary,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )

        OutlinedTextField(
            value = mobile,
            onValueChange = { input ->
                mobile = input.filter { it.isDigit() || it == '+' }.take(20)
            },
            singleLine = true,
            label = { Text("手机号") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = fieldColors(),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
                singleLine = true,
                label = { Text("验证码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .padding(start = 10.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (canSend) Palette.Surface else Palette.SurfaceHigh)
                    .clickable(enabled = canSend) {
                        scope.launch {
                            sending = true
                            error = null
                            runCatching { account.sendCode(mobile) }
                                .onSuccess {
                                    sentTo = it.maskedMobile.ifBlank { mobile }
                                    countdown = it.retrySeconds.coerceIn(30, 180)
                                }
                                .onFailure { error = it.message ?: "验证码发送失败" }
                            sending = false
                        }
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        sending -> "发送中…"
                        countdown > 0 -> "${countdown}s"
                        sentTo != null -> "重新发送"
                        else -> "获取验证码"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canSend) Palette.Accent else Palette.TextTertiary,
                )
            }
        }

        if (sentTo != null) {
            Text(
                "验证码已发送至 $sentTo",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (error != null) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF7B72),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (account.message != null && error == null) {
            Text(
                account.message!!,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (canVerify) Palette.Accent else Palette.SurfaceHigh)
                .clickable(enabled = canVerify) {
                    scope.launch {
                        verifying = true
                        error = null
                        runCatching { account.submitCode(code) }
                            .onSuccess { onSignedIn() }
                            .onFailure { error = it.message ?: "登录失败" }
                        verifying = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (verifying) {
                CircularProgressIndicator(
                    color = Palette.TextPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(20.dp),
                )
            } else {
                Text(
                    "登录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (canVerify) Color(0xFF1A0B05) else Palette.TextTertiary,
                )
            }
        }

        Text(
            "登录即表示同意仅将账号用于同步收藏与观看进度；本应用不会上传通讯录或设备标识以外的信息。",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextTertiary,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun fieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Accent,
        unfocusedBorderColor = Palette.SurfaceHigh,
        focusedTextColor = Palette.TextPrimary,
        unfocusedTextColor = Palette.TextPrimary,
        focusedLabelColor = Palette.Accent,
        unfocusedLabelColor = Palette.TextTertiary,
        cursorColor = Palette.Accent,
    )
