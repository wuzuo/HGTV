package com.hgtv.tv

import org.junit.Assert.*
import org.junit.Test

class DecoderLessonsTest {
    @Test
    fun remembersExactCodecAndSize() {
        val lessons = DecoderLessons()
        lessons.remember("hvc1", 1080, 1920)
        assertTrue(lessons.fails("hvc1", 1080, 1920))
        assertFalse(lessons.fails("hvc1", 1920, 1080)) // landscape unaffected
        assertFalse(lessons.fails("avc1", 1080, 1920)) // other codec unaffected
    }

    @Test
    fun codecNamesNormalizeCaseAndWhitespace() {
        val lessons = DecoderLessons()
        assertEquals("hvc1|1080|1920", lessons.remember(" HVC1 ", 1080, 1920))
        assertTrue(lessons.fails("hvc1", 1080, 1920))
    }

    @Test
    fun sizelessVerdictDoesNotBlockTheWholeCodec() {
        val lessons = DecoderLessons()
        lessons.remember("bytevc1", null, null)
        // 无尺寸记录来自元数据缺失的那次失败。拿它封杀整个编码，一次误判就会让该编码
        // 所有清晰度全线不可播（0.1.24 的实际故障），所以只保留条目、不参与判定。
        assertFalse(lessons.fails("bytevc1", 1920, 1080))
        assertFalse(lessons.fails("bytevc1", null, null))
        assertEquals(1, lessons.size())
    }

    @Test
    fun forgetDropsOnlyTheExactVerdict() {
        val lessons = DecoderLessons()
        lessons.remember("bytevc1", 1924, 1080)
        lessons.remember("bytevc1", 1280, 720)
        assertTrue(lessons.forget("bytevc1", 1924, 1080))
        assertFalse(lessons.fails("bytevc1", 1924, 1080))
        assertTrue(lessons.fails("bytevc1", 1280, 720)) // 另一档不受影响
        assertFalse(lessons.forget("bytevc1", 1924, 1080)) // 已经不存在
    }

    @Test
    fun blankCodecIsIgnored() {
        val lessons = DecoderLessons()
        assertNull(lessons.remember("", 1080, 1920))
        assertFalse(lessons.fails("", 1080, 1920))
    }

    @Test
    fun snapshotRestoresAndResetClears() {
        val lessons = DecoderLessons()
        lessons.remember("avc1", 1080, 1920)
        lessons.remember("h264", null, null)
        val revived = DecoderLessons().also { it.restore(lessons.keys()) }
        assertTrue(revived.fails("avc1", 1080, 1920))
        assertFalse(revived.fails("h264", 720, 1280)) // 无尺寸记录不再外溢到具体尺寸
        revived.reset()
        assertEquals(0, revived.size())
        assertFalse(revived.fails("avc1", 1080, 1920))
    }
}
