package com.hgtv.core

import java.io.File
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityMonitorTest {
    private fun file(text: String): File =
        File.createTempFile("device-identity-monitor", ".txt").also {
            it.deleteOnExit()
            it.writeText(text)
        }

    private fun validFile(): File =
        file("device_id=111\ninstall_id=222\ncdid=3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d")

    @After
    fun tearDown() {
        DeviceIdentityMonitor.reset()
        DeviceIdentityFile.path = null
        DeviceIdentityFile.fallbacks = emptyList()
    }

    @Test
    fun `读到配置时广播一次且不重复`() {
        DeviceIdentityFile.path = validFile()
        val events = mutableListOf<DeviceIdentityMonitor.Event>()
        DeviceIdentityMonitor.addListener { events += it }

        assertEquals("111", DeviceIdentityMonitor.resolve()?.deviceId)
        DeviceIdentityMonitor.resolve()

        assertEquals(1, events.size)
        val loaded = events.single() as DeviceIdentityMonitor.Event.Loaded
        assertEquals("111", loaded.identity.deviceId)
        assertTrue(loaded.file.name.startsWith("device-identity-monitor"))
        assertTrue(DeviceIdentityMonitor.peek() != null)
        assertTrue(!DeviceIdentityMonitor.wasRejected())
    }

    @Test
    fun `没有配置时不广播`() {
        DeviceIdentityFile.path = File("/definitely/not/here/device-identity.txt")
        val events = mutableListOf<DeviceIdentityMonitor.Event>()
        DeviceIdentityMonitor.addListener { events += it }

        assertNull(DeviceIdentityMonitor.resolve())
        assertTrue(events.isEmpty())
        assertNull(DeviceIdentityMonitor.peek())
    }

    @Test
    fun `失效只上报一次`() {
        DeviceIdentityFile.path = validFile()
        val events = mutableListOf<DeviceIdentityMonitor.Event>()
        DeviceIdentityMonitor.addListener { events += it }
        DeviceIdentityMonitor.resolve()

        DeviceIdentityMonitor.reportRejected()
        DeviceIdentityMonitor.reportRejected()

        assertEquals(1, events.count { it is DeviceIdentityMonitor.Event.Rejected })
        assertTrue(DeviceIdentityMonitor.wasRejected())
    }

    @Test
    fun `播放身份被判失效时广播`() {
        DeviceIdentityFile.path = validFile()
        val events = mutableListOf<DeviceIdentityMonitor.Event>()
        DeviceIdentityMonitor.addListener { events += it }

        val session = PlaybackDeviceSession()
        val profile = session.profile(OkHttpClient())
        assertTrue("本地配置应当采用受信任身份", profile.trusted)

        // 内容仓库在播放接口返回空内容时会调用 invalidate，这里直接模拟。
        session.invalidate()

        assertEquals(1, events.count { it is DeviceIdentityMonitor.Event.Rejected })
    }

    @Test
    fun `注册身份被换掉不算配置失效`() {
        val events = mutableListOf<DeviceIdentityMonitor.Event>()
        DeviceIdentityMonitor.addListener { events += it }
        val session =
            PlaybackDeviceSession(
                localIdentity = { null },
                register = { throw java.io.IOException("测试不注册") },
            )
        // 没拿到身份时不应产生任何提示。
        assertTrue(events.isEmpty())
        session.invalidate()
        assertTrue(events.isEmpty())
    }
}
