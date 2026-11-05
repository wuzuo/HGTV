package com.hgtv.tv

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「花屏 + 闪退」发生时进程可能直接被解码器带走，界面留不下任何东西。这里守住取证链路：
 * 播放中的标记必须能在崩溃后补记一次，正常退出必须清掉，兼容开关必须能持久化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackDiagnosticsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun cleanUp() {
        PlaybackDiagnostics.endPlayback(context)
        PlaybackDiagnostics.clear(context)
        PlaybackCompat.setTextureSurface(context, false)
    }

    @Test
    fun `播放中异常退出下次启动补记一次`() {
        PlaybackDiagnostics.beginPlayback(context, "ep=3 1080P codec=h264 size=1920x1080")
        val detail = PlaybackDiagnostics.noteStartup(context)
        assertNotNull(detail)
        assertTrue(detail!!.contains("ep=3"))
        assertTrue(PlaybackDiagnostics.report(context).contains("异常中断"))
        // 补记过就该清掉标记，避免每次启动都报一次。
        assertNull(PlaybackDiagnostics.noteStartup(context))
    }

    @Test
    fun `正常退出不留下异常记录`() {
        PlaybackDiagnostics.beginPlayback(context, "ep=1 720P codec=h264")
        PlaybackDiagnostics.endPlayback(context)
        assertNull(PlaybackDiagnostics.noteStartup(context))
        assertFalse(PlaybackDiagnostics.report(context).contains("异常中断"))
    }

    @Test
    fun `Java 崩溃会写进报告`() {
        PlaybackDiagnostics.recordCrash(
            context,
            Thread.currentThread(),
            IllegalStateException("模拟崩溃"),
        )
        val report = PlaybackDiagnostics.report(context)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("模拟崩溃"))
    }

    @Test
    fun `报告带上设备与画面输出方式`() {
        val report = PlaybackDiagnostics.report(context)
        assertTrue(report.contains("Android"))
        assertTrue(report.contains(PlaybackCompat.label()))
    }

    @Test
    fun `兼容开关能持久化`() {
        PlaybackCompat.setTextureSurface(context, true)
        assertTrue(PlaybackCompat.textureSurface())
        assertTrue(PlaybackCompat.label().contains("TextureView"))
        PlaybackCompat.init(context)
        assertTrue(PlaybackCompat.textureSurface())
        PlaybackCompat.setTextureSurface(context, false)
        PlaybackCompat.init(context)
        assertFalse(PlaybackCompat.textureSurface())
        assertEquals("默认（硬件叠加）", PlaybackCompat.label())
    }

    @Test
    fun `兼容布局用的是 TextureView`() {
        // media3 的 surface_type 只在构造时读一次，所以这条路必须真的从布局里拿。
        val view =
            android.view.LayoutInflater.from(context)
                .inflate(R.layout.player_texture_view, null) as androidx.media3.ui.PlayerView
        assertTrue(view.videoSurfaceView is android.view.TextureView)
    }
}
