package com.hgtv.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIdentityFileTest {
    private fun file(text: String): File =
        File.createTempFile("device-identity", ".txt").also {
            it.deleteOnExit()
            it.writeText(text)
        }

    @Test
    fun readsTheThreeValuesAndIgnoresCommentsAndBlankLines() {
        val identity =
            DeviceIdentityFile.read(
                file(
                    """
                    # 从 applog_stats.xml 抄过来的
                    device_id=7000000000000000001

                    install_id = 7000000000000000002
                    CDID=3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d  # 结尾注释
                    """
                )
            )
        assertEquals(
            DeviceIdentity("7000000000000000001", "7000000000000000002", "3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d"),
            identity,
        )
    }

    /** `iid` 是请求参数里的叫法，用户直接抄 query 时也能用。 */
    @Test
    fun acceptsIidAsAnAliasForInstallId() {
        val identity =
            DeviceIdentityFile.read(
                file("device_id=111\niid=222\ncdid=3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d")
            )
        assertEquals(DeviceIdentity("111", "222", "3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d"), identity)
    }

    @Test
    fun missingFileMeansNoConfiguredIdentity() {
        assertNull(DeviceIdentityFile.read(null))
        assertNull(DeviceIdentityFile.read(File("/definitely/not/here/device-identity.txt")))
    }

    @Test
    fun incompleteConfigIsIgnoredInsteadOfHalfApplied() {
        assertNull(DeviceIdentityFile.read(file("device_id=111\ninstall_id=222")))
        assertNull(DeviceIdentityFile.read(file("device_id=111\ncdid=3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d")))
        assertNull(DeviceIdentityFile.read(file("")))
    }

    /** 非法值直接当没有配置：宁可走自动注册，也不要拿一个畸形 id 去打播放接口。 */
    @Test
    fun malformedValuesAreRejected() {
        val cdid = "3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d"
        assertNull(DeviceIdentityFile.read(file("device_id=0\ninstall_id=222\ncdid=$cdid")))
        assertNull(DeviceIdentityFile.read(file("device_id=abc\ninstall_id=222\ncdid=$cdid")))
        assertNull(DeviceIdentityFile.read(file("device_id=111\ninstall_id=222\ncdid=")))
        assertNull(DeviceIdentityFile.read(file("device_id=111\ninstall_id=222\ncdid=有空格 的值")))
    }

    /** 记事本另存为 UTF-8 会带 BOM、换行也可能变成 CRLF，这两种都要能吃下去。 */
    @Test
    fun toleratesBomQuotesAndWindowsLineEndings() {
        val identity =
            DeviceIdentityFile.read(
                file(
                    "\uFEFFdevice_id=\"7000000000000000001\"\r\n" +
                        "install_id='7000000000000000002'\r\n" +
                        "cdid=3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d\r\n"
                )
            )
        // 单引号不是 JSON 风格，但用户可能这么写；只剥双引号，单引号原样留下会被判非法。
        assertEquals("7000000000000000001", identity?.deviceId)
        assertEquals("7000000000000000002", identity?.installId)
        assertEquals("3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d", identity?.cdid)
    }
}
