// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.data

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hgtv.core.PlaybackQuality

/**
 * 手机端自己的播放偏好。
 *
 * 与 TV 端分开存：TV 的默认档位、倍速、弹幕开关都是为遥控器场景调的，手机用同一份会互相覆盖。
 * 字段全部是 Compose state，写在主线程，读取方（播放页）直接观察即可。
 */
@Stable
class PhoneSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phone-settings-v1", Context.MODE_PRIVATE)

    /** 默认取流档位；播放页里的清晰度切换会写回这里，下次进片直接用它。 */
    var maxQuality by mutableIntStateOf(PlaybackQuality.normalize(prefs.getInt(KEY_QUALITY, 1080)))
        private set

    var playbackSpeed by mutableFloatStateOf(normalizeSpeed(prefs.getFloat(KEY_SPEED, 1f)))
        private set

    var danmakuEnabled by mutableStateOf(prefs.getBoolean(KEY_DANMAKU, true))
        private set

    /** 弹幕字号倍数：1 对应 14sp 基准，范围 0.7~1.6。 */
    var danmakuScale by mutableFloatStateOf(prefs.getFloat(KEY_DANMAKU_SCALE, 1f).coerceIn(0.7f, 1.6f))
        private set

    var autoNext by mutableStateOf(prefs.getBoolean(KEY_AUTO_NEXT, true))
        private set

    fun setQuality(value: Int) {
        val clean = PlaybackQuality.normalize(value)
        if (clean == maxQuality) return
        maxQuality = clean
        prefs.edit().putInt(KEY_QUALITY, clean).apply()
    }

    fun setSpeed(value: Float) {
        val clean = normalizeSpeed(value)
        if (clean == playbackSpeed) return
        playbackSpeed = clean
        prefs.edit().putFloat(KEY_SPEED, clean).apply()
    }

    fun updateDanmakuEnabled(value: Boolean) {
        danmakuEnabled = value
        prefs.edit().putBoolean(KEY_DANMAKU, value).apply()
    }

    fun updateDanmakuScale(value: Float) {
        val clean = value.coerceIn(0.7f, 1.6f)
        if (clean == danmakuScale) return
        danmakuScale = clean
        prefs.edit().putFloat(KEY_DANMAKU_SCALE, clean).apply()
    }

    fun updateAutoNext(value: Boolean) {
        autoNext = value
        prefs.edit().putBoolean(KEY_AUTO_NEXT, value).apply()
    }

    companion object {
        /** 与播放页倍速面板里的档位一致；越界的旧值一律回到 1 倍。 */
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

        private const val KEY_QUALITY = "max_quality"
        private const val KEY_SPEED = "speed"
        private const val KEY_DANMAKU = "danmaku"
        private const val KEY_DANMAKU_SCALE = "danmaku_scale"
        private const val KEY_AUTO_NEXT = "auto_next"

        fun normalizeSpeed(value: Float) = speeds.firstOrNull { kotlin.math.abs(it - value) < 0.01f } ?: 1f
    }
}
