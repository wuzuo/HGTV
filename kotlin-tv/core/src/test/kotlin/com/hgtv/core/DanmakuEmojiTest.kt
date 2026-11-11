package com.hgtv.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕表情表与图片资源的对应关系。
 *
 * 表是从官方 `emoji_config.json` 抄来的，顺序就是图片序号：**错一位，整屏表情都错**，
 * 而这种错在界面上看起来「就是个表情」，不会有人报错，只能靠测试卡住。
 */
class DanmakuEmojiTest {
    @Test
    fun officialTableKeepsTheFiftyThreeEntriesInOrder() {
        assertEquals(53, DanmakuEmoji.tagCount)
        assertEquals(53, DanmakuEmoji.tagNames.size)
        // 抽几个位置固定的核对顺序：第 1 个是微笑，第 20 个是笑哭，第 53 个是送花。
        assertEquals("01.webp", DanmakuEmoji.assetFor("微笑"))
        assertEquals("20.webp", DanmakuEmoji.assetFor("笑哭"))
        assertEquals("53.webp", DanmakuEmoji.assetFor("送花"))
    }

    @Test
    fun everyTagPointsAtAnAssetThatIsShipped() {
        val dir = File(sharedAssets(), "danmaku_emoji")
        assertTrue("共享表情目录不存在：${dir.path}", dir.isDirectory)
        val onDisk = dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".webp") }.toSet()
        assertEquals(53, onDisk.size)
        // 表里有、图没有 → 画出来是空白方块；图有、表里没有 → 纯浪费体积。
        DanmakuEmoji.tagNames.forEach { name ->
            val asset = DanmakuEmoji.assetFor(name)
            assertNotNull("「$name」没有对应图片", asset)
            assertTrue("「$name」→ $asset 在 assets 里找不到", asset in onDisk)
        }
    }

    @Test
    fun tagSplitsIntoImageRunAndKeepsSurroundingText() {
        val parts = DanmakuEmoji.parts("前面高能[笑哭]真的")
        assertEquals(3, parts.size)
        assertEquals("前面高能", parts[0].text)
        assertNull(parts[0].asset)
        assertEquals("20.webp", parts[1].asset)
        // 图片还没解码好时按这个字形画，屏幕上不会出现空档。
        assertEquals("😂", parts[1].text)
        assertEquals("真的", parts[2].text)
    }

    @Test
    fun unknownTagIsLeftAsPlainText() {
        // 认不出来的名字不能吃掉：`[1,2]`、`[2024]` 这种正文里的方括号很常见。
        val parts = DanmakuEmoji.parts("第[1,2]集[不存在]")
        assertEquals(1, parts.size)
        assertEquals("第[1,2]集[不存在]", parts[0].text)
        assertEquals("第[1,2]集[不存在]", DanmakuEmoji.displayText("第[1,2]集[不存在]"))
    }

    @Test
    fun fullWidthAndBracketVariantsAreRecognised() {
        assertEquals("32.webp", DanmakuEmoji.parts("【赞】").single().asset)
        assertEquals("32.webp", DanmakuEmoji.parts("［赞］").single().asset)
        // 只有标签没有正文时不能多出空片段。
        assertEquals(1, DanmakuEmoji.parts("[赞]").size)
    }

    @Test
    fun textWithoutTagsStaysOneRun() {
        val parts = DanmakuEmoji.parts("他打我了")
        assertEquals(1, parts.size)
        assertEquals("他打我了", parts[0].text)
        assertNull(parts[0].asset)
    }

    /** 图片在 kotlin-tv/assets-shared 下，两个 app 模块共用；单测从 core 往上找。 */
    private fun sharedAssets(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null) {
            val candidate = File(dir, "assets-shared")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("找不到 assets-shared 目录")
    }
}
