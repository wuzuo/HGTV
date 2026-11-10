// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BVC2 软解链路的纯逻辑部分：Annex-B 转换、起播定位、样本表解析。
 *
 * 端到端能不能播出画面得真机验，但这些是「改坏了就整条链断掉」的地方，必须锁住。
 */
class Bvc2SoftDecodeTest {
    @Test
    fun `length prefixed nalu becomes annex b`() {
        val sample = byteArrayOf(0, 0, 0, 3, 0x0A, 0x0B, 0x0C)
        val out = ByteArrayOutputStream()
        AnnexB.toAnnexB(sample, out)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x0A, 0x0B, 0x0C),
            out.toByteArray(),
        )
    }

    @Test
    fun `one sample can carry several nalus`() {
        val sample =
            byteArrayOf(
                0, 0, 0, 2, 0x11, 0x12,
                0, 0, 0, 3, 0x21, 0x22, 0x23,
            )
        val out = ByteArrayOutputStream()
        AnnexB.toAnnexB(sample, out)
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 1, 0x11, 0x12,
                0, 0, 0, 1, 0x21, 0x22, 0x23,
            ),
            out.toByteArray(),
        )
    }

    @Test
    fun `truncated nalu stops without writing`() {
        // 长度前缀声称 100 字节，实际只有 2 字节：畸形样本要静默跳过，
        // 不能把半截数据当成 NAL 写进去，也不能抛异常把整条转码链断掉。
        val sample = byteArrayOf(0, 0, 0, 100, 0x01, 0x02)
        val out = ByteArrayOutputStream()
        AnnexB.toAnnexB(sample, out)
        assertTrue(out.size() == 0)
    }

    @Test
    fun `frame rate comes from the dts step`() {
        val table = table(sampleCount = 3, dtsStepUs = 40_000)
        assertEquals("1000000/40000", AnnexB.frameRate(table))
    }

    @Test
    fun `start index falls back to the nearest random access point`() {
        val table = table(sampleCount = 5, dtsStepUs = 40_000, syncSamples = listOf(0, 3))
        // 目标落在第 4 个样本（160ms）上，最近的同步点是下标 3。
        assertEquals(3, AnnexB.startIndex(table, 150_000, emptyList()))
        assertEquals(0, AnnexB.startIndex(table, 0, emptyList()))
    }

    @Test
    fun `start index uses the in-band scan when stss is missing`() {
        val table = table(sampleCount = 4, dtsStepUs = 40_000)
        assertEquals(2, AnnexB.startIndex(table, 100_000, listOf(0, 2)))
    }

    @Test
    fun `bitrate follows the coded size`() {
        assertEquals("8M", AnnexB.videoBitrate(table(sampleCount = 2, width = 1080, height = 1920)))
        assertEquals("5M", AnnexB.videoBitrate(table(sampleCount = 2, width = 720, height = 1280)))
        assertEquals("1500k", AnnexB.videoBitrate(table(sampleCount = 2, width = 576, height = 720)))
    }

    @Test
    fun `progressive header yields the video sample table`() {
        val header = progressiveHeader(sampleCount = 3, timescale = 1000, dtsDelta = 40)
        val table = Mp4SampleTable.parse(header)
        assertNotNull(table)
        requireNotNull(table)
        assertEquals("bvc2", table.fourcc)
        assertEquals(1080, table.width)
        assertEquals(1920, table.height)
        assertEquals(3, table.samples.size)
        assertEquals(1000L, table.samples[0].offset)
        assertEquals(1100L, table.samples[1].offset)
        assertEquals(40_000L, table.samples[1].dts)
        // 没有 ctts 时 cts 与 dts 相同。
        assertEquals(40_000L, table.samples[1].cts)
        assertTrue(table.syncSamples.isEmpty())
    }

    @Test
    fun `header without a video track is rejected`() {
        assertNull(Mp4SampleTable.parse(ByteArray(0)))
        assertNull(Mp4SampleTable.parse(byteArrayOf(0, 0, 0, 8, 0x66, 0x74, 0x79, 0x70)))
    }

    private fun table(
        sampleCount: Int,
        dtsStepUs: Long = 40_000,
        syncSamples: List<Int> = emptyList(),
        width: Int = 1080,
        height: Int = 1920,
    ): Mp4SampleTable.Table =
        Mp4SampleTable.Table(
            fourcc = "bvc2",
            width = width,
            height = height,
            timescale = 1000,
            samples =
                (0 until sampleCount).map { index ->
                    val dts = index * dtsStepUs
                    Mp4SampleTable.Sample(
                        offset = 1000L + index * 100L,
                        size = 100,
                        dts = dts,
                        cts = dts,
                    )
                },
            syncSamples = syncSamples,
        )

    private fun progressiveHeader(sampleCount: Int, timescale: Int, dtsDelta: Int): ByteArray {
        val stsdEntry =
            ByteArray(86).also { entry ->
                // entry 起始是 size(4)+format(4)，宽高在 entry+32/34（= stsd.body+8 之后 24/26）。
                putU32(entry, 0, 86)
                "bvc2".toByteArray(Charsets.US_ASCII).copyInto(entry, 4)
                putU16(entry, 32, 1080)
                putU16(entry, 34, 1920)
            }
        val stsd =
            box(
                "stsd",
                byteArrayOf(0, 0, 0, 0) + u32(1) + stsdEntry,
            )
        val stsz =
            box(
                "stsz",
                byteArrayOf(0, 0, 0, 0) + u32(100) + u32(sampleCount) +
                    ByteArray(sampleCount * 4),
            )
        val stsc =
            box(
                "stsc",
                byteArrayOf(0, 0, 0, 0) + u32(1) + u32(1) + u32(sampleCount) + u32(1),
            )
        val stco = box("stco", byteArrayOf(0, 0, 0, 0) + u32(1) + u32(1000))
        val stts =
            box(
                "stts",
                byteArrayOf(0, 0, 0, 0) + u32(1) + u32(sampleCount) + u32(dtsDelta),
            )
        val stbl = box("stbl", stsd + stsz + stsc + stco + stts)
        val minf = box("minf", stbl)
        val hdlr =
            box(
                "hdlr",
                byteArrayOf(0, 0, 0, 0) + byteArrayOf(0, 0, 0, 0) +
                    "vide".toByteArray(Charsets.US_ASCII) + ByteArray(12),
            )
        val mdhd =
            box(
                "mdhd",
                byteArrayOf(0, 0, 0, 0) + byteArrayOf(0, 0, 0, 0) + byteArrayOf(0, 0, 0, 0) +
                    u32(timescale) + u32(0),
            )
        val mdia = box("mdia", mdhd + hdlr + minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII) + u32(0))
        return ftyp + moov
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val size = 8 + body.size
        return u32(size) + type.toByteArray(Charsets.US_ASCII) + body
    }

    private fun u32(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun putU32(target: ByteArray, at: Int, value: Int) {
        u32(value).copyInto(target, at)
    }

    private fun putU16(target: ByteArray, at: Int, value: Int) {
        target[at] = (value ushr 8).toByte()
        target[at + 1] = value.toByte()
    }
}
