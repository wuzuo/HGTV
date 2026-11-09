// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 画中画的入口。
 *
 * Compose 里拿不到 `onUserLeaveHint`，所以播放页把「我现在想支持画中画」和「怎么进」登记到
 * 这里，由 MainActivity 在用户按 Home 时决定要不要缩窗。
 */
object PipBridge {
    /** 播放页在前台、且已经开始播放：按 Home 应该进画中画。 */
    var active by mutableStateOf(false)

    /** 当前是否已经处于画中画（界面据此隐藏控制层和弹幕）。 */
    var inPip by mutableStateOf(false)

    var enter: (() -> Unit)? = null
}

/** 播放页需要独占的窗口属性：方向、常亮、全屏沉浸。 */
@Stable
class PlayerWindow(private val activity: Activity?) {
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var previousBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /**
     * 进播放页。短剧基本是竖屏（9:16），所以默认竖屏；只有拿到明确是横屏的尺寸后
     * 才由 [setLandscape] 切过去——先锁横屏会让竖屏内容只占中间一条。
     */
    fun enter() {
        val window = activity?.window ?: return
        previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        previousBrightness = window.attributes.screenBrightness
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    /** 按画面比例切换横竖屏；同一集里尺寸不会变，重复调用无副作用。 */
    fun setLandscape(landscape: Boolean) {
        val target =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (activity?.requestedOrientation != target) {
            activity?.requestedOrientation = target
        }
    }

    fun exit() {
        val window = activity?.window ?: return
        activity.requestedOrientation = previousOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes =
            window.attributes.apply { screenBrightness = previousBrightness }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
    }
}

/** 当前的系统亮度（0.01~1），读不到就按 0.5 算。 */
fun systemBrightness(context: Context): Float {
    val value =
        runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }
            .getOrDefault(128)
    return (value / 255f).coerceIn(0.01f, 1f)
}

fun applyBrightness(activity: Activity?, value: Float) {
    val window = activity?.window ?: return
    window.attributes =
        window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
}

fun audioManager(context: Context) = context.getSystemService(AudioManager::class.java)

tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
