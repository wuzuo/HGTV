// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.playback

import android.content.Context

/**
 * 画面输出方式开关。
 *
 * 默认用 `SurfaceView`（硬件叠加层）：最省电、延迟最低，绝大多数盒子都正常。但部分设备
 * —— 尤其是走 HDMI/DP 输出到 AR 眼镜、或者 GPU 合成器比较旧的一体机 —— 在 1080P 叠加层
 * 直出时会出现花屏、撕裂，个别固件甚至会连带把解码器拖崩（表现为闪退）。
 *
 * 打开后改用 `TextureView`：帧先进 GL 纹理再合成，绕开叠加层这条路，代价是多一次拷贝。
 */
object PlaybackCompat {
    private const val PREFS = "playback-compat-v1"
    private const val KEY_TEXTURE = "textureView"

    @Volatile private var texture = false

    fun init(context: Context) {
        texture = prefs(context).getBoolean(KEY_TEXTURE, false)
    }

    fun textureSurface(): Boolean = texture

    fun setTextureSurface(context: Context, value: Boolean) {
        texture = value
        prefs(context).edit().putBoolean(KEY_TEXTURE, value).apply()
    }

    fun label(): String = if (texture) "兼容（TextureView）" else "默认（硬件叠加）"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
