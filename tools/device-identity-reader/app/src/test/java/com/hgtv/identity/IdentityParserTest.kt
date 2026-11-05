package com.hgtv.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityParserTest {

    private val header = """
        ##UID 0
        ##SDK 34
        ##PM-BEGIN
        com.phoenix.read
        ##PM-END
    """.trimIndent()

    private val applog = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="device_id">1782760663187289</string>
            <string name="install_id">1782760662372201</string>
        </map>
    """.trimIndent()

    private val cdidFile = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
        </map>
    """.trimIndent()

    private fun file(path: String, body: String) = "##FILE $path\n$body\n"

    private val textFormDump =
        header + "\n" +
            file("/data/data/com.phoenix.read/shared_prefs/applog_stats.xml", applog) +
            file("/data/data/com.phoenix.read/shared_prefs/com.ss.android.deviceregister.utils.Cdid.xml", cdidFile) +
            "##CAND 2\n"

    @Test
    fun `解析文本式存储`() {
        val outcome = IdentityParser.parse(textFormDump)
        assertTrue("实际=$outcome", outcome is IdentityParser.Outcome.Success)
        val success = outcome as IdentityParser.Outcome.Success
        assertEquals("com.phoenix.read", success.pkg)
        assertEquals("1782760663187289", success.identity.deviceId)
        assertEquals("1782760662372201", success.identity.installId)
        assertEquals("e7b40284-6c74-438f-87d5-b0d7bebfb813", success.identity.cdid)
        assertNull(success.note)
    }

    @Test
    fun `解析属性式存储`() {
        val dump =
            header + "\n" +
                file(
                    "/data/data/com.phoenix.read/shared_prefs/applog_stats.xml",
                    """
                    <map>
                        <long name="device_id" value="1782760663187289" />
                        <long name="install_id" value="1782760662372201" />
                        <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
                    </map>
                    """.trimIndent(),
                ) +
                "##CAND 1\n"
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("1782760663187289", success.identity.deviceId)
        assertEquals("1782760662372201", success.identity.installId)
        assertEquals("e7b40284-6c74-438f-87d5-b0d7bebfb813", success.identity.cdid)
    }

    @Test
    fun `iid 可以代替 install_id`() {
        val dump =
            header + "\n" +
                file(
                    "/data/data/com.phoenix.read/shared_prefs/applog_stats.xml",
                    """
                    <map>
                        <string name="device_id">1782760663187289</string>
                        <long name="iid" value="1782760662372201" />
                        <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
                    </map>
                    """.trimIndent(),
                ) +
                "##CAND 1\n"
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("1782760662372201", success.identity.installId)
    }

    @Test
    fun `包名不是 com_phoenix_read 也能读`() {
        val dump =
            """
            ##UID 0
            ##PM-BEGIN
            com.phoenix.read.global
            ##PM-END
            """.trimIndent() + "\n" +
                file("/data/data/com.phoenix.read.global/shared_prefs/applog_stats.xml", applog) +
                file("/data/data/com.phoenix.read.global/shared_prefs/cdid.xml", cdidFile) +
                "##CAND 2\n"
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("com.phoenix.read.global", success.pkg)
    }

    @Test
    fun `应用分身目录也能读`() {
        val dump =
            """
            ##UID 0
            ##PM-BEGIN
            ##PM-END
            """.trimIndent() + "\n" +
                file("/data/user/999/com.phoenix.read/shared_prefs/applog_stats.xml", applog) +
                file("/data/user/999/com.phoenix.read/shared_prefs/cdid.xml", cdidFile) +
                "##CAND 2\n"
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("com.phoenix.read", success.pkg)
        assertEquals("1782760663187289", success.identity.deviceId)
    }

    @Test
    fun `多个候选时优先选红果`() {
        val other = applog.replace("1782760663187289", "1999999999999999")
            .replace("1782760662372201", "1888888888888888")
        val otherCdid = cdidFile.replace("e7b40284-6c74-438f-87d5-b0d7bebfb813", "aaaabbbb-cccc-dddd-eeee-ffff00001111")
        val dump =
            header + "\n" +
                file("/data/data/com.dragon.read/shared_prefs/applog_stats.xml", other) +
                file("/data/data/com.dragon.read/shared_prefs/cdid.xml", otherCdid) +
                file("/data/data/com.phoenix.read/shared_prefs/applog_stats.xml", applog) +
                file("/data/data/com.phoenix.read/shared_prefs/cdid.xml", cdidFile) +
                "##CAND 4\n"
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("com.phoenix.read", success.pkg)
        assertEquals("1782760663187289", success.identity.deviceId)
        assertNull(success.note)
    }

    @Test
    fun `只有非红果候选时给出提示`() {
        val dump =
            """
            ##UID 0
            ##PM-BEGIN
            ##PM-END
            """.trimIndent() + "\n" +
                file("/data/data/com.dragon.read/shared_prefs/applog_stats.xml", applog) +
                file("/data/data/com.dragon.read/shared_prefs/cdid.xml", cdidFile) +
                "##CAND 2\n"
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("com.dragon.read", success.pkg)
        assertNotNull(success.note)
        assertTrue(success.note!!.contains("不是红果"))
    }

    @Test
    fun `没有 root 权限时给出提示`() {
        val outcome = IdentityParser.parse("##UID 2000\n")
        assertTrue(outcome is IdentityParser.Outcome.Failure)
        assertTrue((outcome as IdentityParser.Outcome.Failure).message.contains("root"))
    }

    @Test
    fun `su 报错时给出提示`() {
        val outcome = IdentityParser.parse("##ERROR su: not found")
        assertTrue(outcome is IdentityParser.Outcome.Failure)
        assertTrue((outcome as IdentityParser.Outcome.Failure).message.contains("su"))
    }

    @Test
    fun `一个配置文件都没扫到时给出提示`() {
        val outcome = IdentityParser.parse("##UID 0\n##CAND 0\n")
        assertTrue(outcome is IdentityParser.Outcome.Failure)
        val message = (outcome as IdentityParser.Outcome.Failure).message
        assertTrue(message.contains("红果"))
        assertTrue(message.contains("phoenix"))
    }

    @Test
    fun `扫到了文件但没有红果时列出疑似应用`() {
        val dump =
            """
            ##UID 0
            ##SDK 33
            ##PM-BEGIN
            com.phoenix.read
            ##PM-END
            ##CAND 37
            """.trimIndent()
        val message = (IdentityParser.parse(dump) as IdentityParser.Outcome.Failure).message
        assertTrue(message.contains("37"))
        assertTrue(message.contains("com.phoenix.read"))
    }

    @Test
    fun `缺 cdid 时判定失败`() {
        val dump =
            header + "\n" +
                file(
                    "/data/data/com.phoenix.read/shared_prefs/applog_stats.xml",
                    """
                    <map>
                        <string name="device_id">1782760663187289</string>
                        <string name="install_id">1782760662372201</string>
                    </map>
                    """.trimIndent(),
                ) +
                "##CAND 1\n"
        val outcome = IdentityParser.parse(dump)
        assertTrue(outcome is IdentityParser.Outcome.Failure)
        val message = (outcome as IdentityParser.Outcome.Failure).message
        assertTrue(message.contains("完整"))
        assertTrue(message.contains("com.phoenix.read"))
    }

    @Test
    fun `device_id 格式非法时判定失败`() {
        val dump =
            header + "\n" +
                file(
                    "/data/data/com.phoenix.read/shared_prefs/applog_stats.xml",
                    """
                    <map>
                        <string name="device_id">0abc</string>
                        <string name="install_id">1782760662372201</string>
                        <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
                    </map>
                    """.trimIndent(),
                ) +
                "##CAND 1\n"
        val outcome = IdentityParser.parse(dump)
        assertTrue(outcome is IdentityParser.Outcome.Failure)
        assertTrue((outcome as IdentityParser.Outcome.Failure).message.contains("格式"))
    }

    @Test
    fun `导出的文件内容包含 HGTV 需要的三个键`() {
        val success = IdentityParser.parse(textFormDump) as IdentityParser.Outcome.Success
        val block = success.identity.block(success.pkg)
        assertTrue(block.contains("device_id=1782760663187289"))
        assertTrue(block.contains("install_id=1782760662372201"))
        assertTrue(block.contains("cdid=e7b40284-6c74-438f-87d5-b0d7bebfb813"))
        assertTrue(block.lineSequence().first().startsWith("#"))
    }

    @Test
    fun `无关字段不会干扰解析`() {
        val dump =
            header + "\n" +
                file(
                    "/data/data/com.phoenix.read/shared_prefs/applog_stats.xml",
                    """
                    <map>
                        <string name="other">whatever</string>
                        <string name="device_id">1782760663187289</string>
                        <string name="install_id">1782760662372201</string>
                        <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
                        <int name="version" value="72932" />
                    </map>
                    """.trimIndent(),
                ) +
                "##CAND 1\n"
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("1782760663187289", success.identity.deviceId)
    }

    @Test
    fun `真实抓取样本（红果 7_2_9_32）能读到设备身份`() {
        val dump = javaClass.classLoader!!.getResourceAsStream("dump_phoenix_72932.txt")!!
            .bufferedReader().use { it.readText() }
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("com.phoenix.read", success.pkg)
        assertEquals("1782760663187289", success.identity.deviceId)
        assertEquals("1782760662372201", success.identity.installId)
        assertEquals("e7b40284-6c74-438f-87d5-b0d7bebfb813", success.identity.cdid)
        assertNull(success.note)
    }

    @Test
    fun `兼容没有 FILE 标记的旧格式`() {
        val dump =
            """
            ##UID 0
            ##PKG com.phoenix.read
            <map>
                <string name="device_id">1782760663187289</string>
                <string name="install_id">1782760662372201</string>
                <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
            </map>
            """.trimIndent()
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("com.phoenix.read", success.pkg)
    }
}
