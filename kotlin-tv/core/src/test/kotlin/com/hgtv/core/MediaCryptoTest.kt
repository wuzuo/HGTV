package com.hgtv.core

import org.junit.Assert.*
import org.junit.Test

class MediaCryptoTest {
    private val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
    private val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    private val plain =
        hex(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"
        )
    private val encrypted =
        hex(
            "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee"
        )

    @Test
    fun nistCtrVectorsAcrossEveryUnalignedRange() {
        val prefix = ByteArray(23) { 0x66 }
        val source = prefix + encrypted + ByteArray(12) { 0x77 }
        val expected = prefix + plain + ByteArray(12) { 0x77 }
        val prepared = MediaCrypto.Prepared(byteArrayOf(), listOf(MediaCrypto.Sample(23, 87, iv)))
        for (start in source.indices) for (length in
            1..minOf(29, source.size - start)) assertArrayEquals(
            "$start+$length",
            expected.copyOfRange(start, start + length),
            MediaCrypto.decrypt(
                source.copyOfRange(start, start + length),
                start.toLong(),
                key,
                prepared,
            ),
        )
    }

    @Test
    fun subsamplesPreserveClearBytesAndContinueCtrOffset() {
        val source =
            byteArrayOf(1, 2, 3) +
                encrypted.copyOfRange(0, 21) +
                byteArrayOf(4, 5) +
                encrypted.copyOfRange(21, 64)
        val expected =
            byteArrayOf(1, 2, 3) +
                plain.copyOfRange(0, 21) +
                byteArrayOf(4, 5) +
                plain.copyOfRange(21, 64)
        val prepared =
            MediaCrypto.Prepared(
                byteArrayOf(),
                listOf(MediaCrypto.Sample(3, 24, iv), MediaCrypto.Sample(26, 69, iv, 21)),
            )
        assertArrayEquals(expected, MediaCrypto.decrypt(source, 0, key, prepared))
        assertArrayEquals(
            expected.copyOfRange(22, 47),
            MediaCrypto.decrypt(source.copyOfRange(22, 47), 22, key, prepared),
        )
    }

    /** Byte-for-byte restatement of the allocation-heavy implementation this replaced. */
    private fun reference(
        window: ByteArray,
        start: Long,
        key: ByteArray,
        prepared: MediaCrypto.Prepared,
    ): ByteArray {
        val result = window.copyOf()
        val end = start + window.size
        if (start < prepared.header.size)
            prepared.header.copyInto(
                result,
                0,
                start.toInt(),
                minOf(end, prepared.header.size.toLong()).toInt(),
            )
        for (sample in prepared.samples) {
            if (sample.end <= start || sample.start >= end) continue
            val begin = maxOf(start, sample.start)
            val stop = minOf(end, sample.end)
            if (begin >= stop) continue
            val relative = begin - sample.start + sample.encryptedOffset
            val skip = (relative % 16).toInt()
            val counter =
                (java.math.BigInteger(1, sample.iv) + java.math.BigInteger.valueOf(relative / 16))
                    .and(java.math.BigInteger.ONE.shiftLeft(128) - java.math.BigInteger.ONE)
                    .toByteArray()
            val iv = ByteArray(16)
            counter.copyInto(
                iv,
                maxOf(0, 16 - counter.size),
                maxOf(0, counter.size - 16),
                counter.size,
            )
            val decrypted =
                aes(
                    "CTR",
                    key,
                    iv,
                    ByteArray(skip) +
                        result.copyOfRange((begin - start).toInt(), (stop - start).toInt()),
                )
            decrypted.copyInto(result, (begin - start).toInt(), skip)
        }
        return result
    }

    @Test
    fun decryptIntoWritesExactlyTheRequestedWindowIntoTheExistingBuffer() {
        val prefix = ByteArray(23) { 0x66 }
        val source = prefix + encrypted + ByteArray(12) { 0x77 }
        val prepared = MediaCrypto.Prepared(byteArrayOf(), listOf(MediaCrypto.Sample(23, 87, iv)))
        for (start in source.indices) for (length in 1..minOf(29, source.size - start)) {
            val expected = ByteArray(length + 8) { 0x5a }
            reference(source.copyOfRange(start, start + length), start.toLong(), key, prepared)
                .copyInto(expected, 4)
            val actual = ByteArray(length + 8) { 0x5a }
            MediaCrypto.decryptInto(source, start, length, start.toLong(), key, prepared, actual, 4)
            assertArrayEquals("$start+$length", expected, actual)
        }
    }

    @Test
    fun decryptIntoMatchesReferenceOverManySamplesAndCounterWrap() {
        val raw = ByteArray(4096) { ((it * 37 + 11) and 0xff).toByte() }
        val header = ByteArray(64) { 0x11 }
        val samples = mutableListOf<MediaCrypto.Sample>()
        var at = 64L
        var index = 0
        while (at + 100 <= raw.size) {
            // Every sample gets a counter that overflows the low bytes sooner or later.
            val sampleIv = ByteArray(16) { if (index % 3 == 0) 0xff.toByte() else maxOf(0, 200 - index).toByte() }
            samples += MediaCrypto.Sample(at, at + 100, sampleIv, if (index % 5 == 0) 7 else 0)
            at += 100
            index++
        }
        val prepared = MediaCrypto.Prepared(header, samples)
        for (start in 0 until raw.size step 13) {
            for (length in intArrayOf(1, 15, 16, 17, 101, 512, 1024)) {
                if (start + length > raw.size) continue
                val expected = ByteArray(length + 32) { 0x5a }
                reference(raw.copyOfRange(start, start + length), start.toLong(), key, prepared)
                    .copyInto(expected, 16)
                val buffer = ByteArray(length + 32) { 0x5a }
                MediaCrypto.decryptInto(raw, start, length, start.toLong(), key, prepared, buffer, 16)
                assertArrayEquals("$start+$length", expected, buffer)
            }
        }
    }

    private fun box(type: String, body: ByteArray) = be32(body.size + 8) + type.toByteArray() + body

    private fun fixture(codec: String = "avc1", auxSize: Int = 8): ByteArray {
        val ftyp = box("ftyp", "isom0000".toByteArray())
        fun moov(offset: Int): ByteArray {
            val entry = box("encv", ByteArray(78) + box("sinf", box("frma", codec.toByteArray())))
            val stsd = box("stsd", ByteArray(4) + be32(1) + entry)
            val stsz = box("stsz", ByteArray(4) + be32(16) + be32(1))
            val stsc = box("stsc", ByteArray(4) + be32(1) + be32(1) + be32(1) + be32(1))
            val stco = box("stco", ByteArray(4) + be32(1) + be32(4096))
            val saiz = box("saiz", ByteArray(4) + byteArrayOf(auxSize.toByte()) + be32(1))
            val saio = box("saio", ByteArray(4) + be32(1) + be32(offset))
            return box(
                "moov",
                box(
                    "trak",
                    box("mdia", box("minf", box("stbl", stsd + stsz + stsc + stco + saiz + saio))),
                ) + box("free", iv.copyOf(8)),
            )
        }
        val initial = ftyp + moov(0)
        return ftyp + moov(initial.size - 8)
    }

    @Test
    fun headerRestoresOriginalAvcCodecAndSampleIndex() {
        val data = fixture()
        val prepared = MediaCrypto.prepare(data, key)
        assertTrue(String(prepared.header).contains("avc1"))
        assertFalse(String(prepared.header).contains("encv"))
        assertFalse(String(prepared.header).contains("sinf"))
        assertEquals(1, prepared.samples.size)
        assertEquals(4096L, prepared.samples[0].start)
        assertEquals(4112L, prepared.samples[0].end)
        assertEquals(data.size, MediaCrypto.headerEnd(data, 5000))
    }

    @Test(expected = java.io.IOException::class)
    fun truncatedAuxiliaryDataFailsClosed() {
        MediaCrypto.prepare(fixture(auxSize = 16), key)
    }

    @Test(expected = java.io.IOException::class)
    fun oversizedMoovRejected() {
        MediaCrypto.headerEnd(
            box("ftyp", "isom0000".toByteArray()) + be32(9000000) + "moov".toByteArray(),
            10000000,
        )
    }

    @Test
    fun routerParserHandlesEscapedQuotesAndNestedBraces() {
        val data =
            ContentRepository.extractRouter(
                "<script>window._ROUTER_DATA = {\"loaderData\":{\"x\":\"a}\\\"b\",\"list\":[1,2]}};</script>"
            )
        assertEquals("a}\"b", data.getJSONObject("loaderData").getString("x"))
    }
}
