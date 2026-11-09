package com.hgtv.tv

import com.hgtv.playback.PlaybackCompat
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
        // 只认「补记」那条记录本身：报告头部的说明文字里也带着「异常中断」四个字。
        assertFalse(PlaybackDiagnostics.report(context).contains("上次播放中"))
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

    @Test
    fun `标记里带上崩溃当时的内存与运行时长`() {
        PlaybackDiagnostics.beginPlayback(context, "ep=7 1080P codec=bytevc1 size=1924x1080")
        val detail = PlaybackDiagnostics.noteStartup(context)!!
        // 原生崩溃和被系统回收表现一样，只有内存与开机时长能区分，必须随标记留下。
        assertTrue(detail.contains("avail="))
        assertTrue(detail.contains("low="))
        assertTrue(detail.contains("heap="))
        assertTrue(detail.contains("uptime="))
    }

    @Test
    fun `能从标记里取出这套解码组合`() {
        val parsed =
            PlaybackDiagnostics.interruptedCodec(
                "11-05 21:04:58 ep=136 1080P codec=bytevc1 size=1924x1080 render=默认（硬件叠加）"
            )
        assertNotNull(parsed)
        assertEquals("bytevc1", parsed!!.codec)
        assertEquals(1924, parsed.width)
        assertEquals(1080, parsed.height)
    }

    @Test
    fun `标记缺编码或尺寸时不猜`() {
        // codec 未知、尺寸缺失、尺寸非法，都不能拿去记住一条假的「解不了」。
        assertNull(PlaybackDiagnostics.interruptedCodec("ep=3 1080P codec=? size=1924x1080"))
        assertNull(PlaybackDiagnostics.interruptedCodec("ep=3 1080P codec=bytevc1"))
        assertNull(PlaybackDiagnostics.interruptedCodec("ep=3 1080P codec=bytevc1 size=0x0"))
        assertNull(PlaybackDiagnostics.interruptedCodec(""))
    }

    @Test
    fun `播放器报错会写进报告且不影响异常中断`() {
        PlaybackDiagnostics.notePlaybackError(
            context,
            "ep=147 quality=1080P codec=bytevc1 size=1924x1080 · ERROR_CODE_UNSPECIFIED",
        )
        val report = PlaybackDiagnostics.report(context)
        assertTrue(report.contains("播放器报错"))
        assertTrue(report.contains("ERROR_CODE_UNSPECIFIED"))
        // 只是报错、进程还活着，不能算成「上次播放中异常中断」。
        assertNull(PlaybackDiagnostics.noteStartup(context))
    }
}
