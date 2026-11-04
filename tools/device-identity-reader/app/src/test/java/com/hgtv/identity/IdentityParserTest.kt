package com.hgtv.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityParserTest {

    private val textFormDump =
        """
        ##UID 0
        ##PKG com.phoenix.read
        ##FILE /data/data/com.phoenix.read/shared_prefs/applog_stats.xml
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="device_id">1782760663187289</string>
            <string name="install_id">1782760662372201</string>
        </map>
        ##FILE /data/data/com.phoenix.read/shared_prefs/com.ss.android.deviceregister.utils.Cdid.xml
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
        </map>
        """.trimIndent()

    @Test
    fun `解析文本式存储`() {
        val outcome = IdentityParser.parse(textFormDump)
        assertTrue(outcome is IdentityParser.Outcome.Success)
        val success = outcome as IdentityParser.Outcome.Success
        assertEquals("com.phoenix.read", success.pkg)
        assertEquals("1782760663187289", success.identity.deviceId)
        assertEquals("1782760662372201", success.identity.installId)
        assertEquals("e7b40284-6c74-438f-87d5-b0d7bebfb813", success.identity.cdid)
    }

    @Test
    fun `解析属性式存储`() {
        val dump =
            """
            ##UID 0
            ##PKG com.phoenix.read
            <map>
                <long name="device_id" value="1782760663187289" />
                <long name="install_id" value="1782760662372201" />
                <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
            </map>
            """.trimIndent()
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("1782760663187289", success.identity.deviceId)
        assertEquals("1782760662372201", success.identity.installId)
        assertEquals("e7b40284-6c74-438f-87d5-b0d7bebfb813", success.identity.cdid)
    }

    @Test
    fun `iid 可以代替 install_id`() {
        val dump =
            """
            ##UID 0
            ##PKG com.phoenix.read
            <map>
                <string name="device_id">1782760663187289</string>
                <long name="iid" value="1782760662372201" />
                <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
            </map>
            """.trimIndent()
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("1782760662372201", success.identity.installId)
    }

    @Test
    fun `没有 root 权限时给出提示`() {
        val dump = "##UID 2000\n##PKG com.phoenix.read\n"
        val outcome = IdentityParser.parse(dump)
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
    fun `没装红果时给出提示`() {
        val outcome = IdentityParser.parse("##UID 0\n")
        assertTrue(outcome is IdentityParser.Outcome.Failure)
        assertTrue((outcome as IdentityParser.Outcome.Failure).message.contains("红果"))
    }

    @Test
    fun `缺 cdid 时判定失败`() {
        val dump =
            """
            ##UID 0
            ##PKG com.phoenix.read
            <map>
                <string name="device_id">1782760663187289</string>
                <string name="install_id">1782760662372201</string>
            </map>
            """.trimIndent()
        val outcome = IdentityParser.parse(dump)
        assertTrue(outcome is IdentityParser.Outcome.Failure)
        assertTrue((outcome as IdentityParser.Outcome.Failure).message.contains("完整"))
    }

    @Test
    fun `device_id 格式非法时判定失败`() {
        val dump =
            """
            ##UID 0
            ##PKG com.phoenix.read
            <map>
                <string name="device_id">0abc</string>
                <string name="install_id">1782760662372201</string>
                <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
            </map>
            """.trimIndent()
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
            """
            ##UID 0
            ##PKG com.phoenix.read
            <map>
                <string name="other">whatever</string>
                <string name="device_id">1782760663187289</string>
                <string name="install_id">1782760662372201</string>
                <string name="cdid">e7b40284-6c74-438f-87d5-b0d7bebfb813</string>
                <int name="version" value="72932" />
            </map>
            """.trimIndent()
        val success = IdentityParser.parse(dump) as IdentityParser.Outcome.Success
        assertEquals("1782760663187289", success.identity.deviceId)
    }
}
