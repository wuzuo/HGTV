// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 与 TV 端同一套底色，取色只在两处：这里的常量、以及海报本身。 */
object Palette {
    val Background = Color(0xFF0B0F15)
    val Surface = Color(0xFF151A22)
    val SurfaceHigh = Color(0xFF1E2530)
    val Accent = Color(0xFFFF8964)
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFF8A93A3)
    val TextTertiary = Color(0xFF5C6472)
}

private val colors =
    darkColorScheme(
        primary = Palette.Accent,
        onPrimary = Color(0xFF1A0B05),
        background = Palette.Background,
        onBackground = Palette.TextPrimary,
        surface = Palette.Surface,
        onSurface = Palette.TextPrimary,
        surfaceVariant = Palette.SurfaceHigh,
        onSurfaceVariant = Palette.TextSecondary,
    )

private val typography =
    Typography(
        titleMedium =
            TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    )

@Composable
fun HgtvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
