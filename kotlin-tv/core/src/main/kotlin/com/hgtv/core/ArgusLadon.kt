// SPDX-License-Identifier: GPL-3.0-only
// x-argus / x-ladon ported from hongguo-open-player (src/server/native/argus.js),
// itself adapted from fqapp / N3urda/hongguoTV. GPL-3.0-only.
//
// 以前这里是占位值（`be32(ts)` / `le32(ts)` 的 base64），只有 8 个字符。实测播放接口
// 把这两个头当硬校验：占位值一律 HTTP 200 + 空包，且并不要求 medusa。所以这里按真实
// 算法补齐：Argus = Simon 64/128 + protobuf bean + 551 包装 + AES-128-CBC；Ladon = 34 轮 ARX。
package com.hgtv.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 最小 protobuf 写入器。与 [Signer.proto] 的区别：空 blob 也照样写成长度 0 的字段。 */
private class ProtoBean {
    private class Field(val tag: Int, val varint: Long, val blob: ByteArray?)

    private val fields = ArrayList<Field>()

    fun varint(tag: Int, value: Long) =
        apply { fields.add(Field(tag, value and 0xffffffffL, null)) }

    fun blob(tag: Int, data: ByteArray) = apply { fields.add(Field(tag, 0, data)) }

    fun utf8(tag: Int, value: String?) = blob(tag, (value ?: "").toByteArray())

    fun nested(tag: Int, child: ProtoBean) = blob(tag, child.bytes())

    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in fields) {
            writeVarint(out, (field.tag.toLong() shl 3) or if (field.blob == null) 0L else 2L)
            val blob = field.blob
            if (blob == null) {
                writeVarint(out, field.varint)
            } else {
                writeVarint(out, blob.size.toLong())
                out.write(blob)
            }
        }
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        // 上游用 BigInt.asUintN(32, v)，即所有 varint 都先截到 32 位。
        var n = value and 0xffffffffL
        while (n >= 128) {
            out.write(((n and 127) or 128).toInt())
            n = n ushr 7
        }
        out.write(n.toInt())
    }
}

/** Simon 64/128 分组密码（72 轮，Z 序列取自上游常量）。 */
private object Simon64128 {
    private const val Z = 0x3DC94C3A046D678BL

    fun expandKey(key: LongArray): LongArray {
        val out = LongArray(72)
        for (i in 0..3) out[i] = key[i]
        for (i in 4..71) {
            var tmp = java.lang.Long.rotateRight(out[i - 1], 3) xor out[i - 3]
            tmp = tmp xor java.lang.Long.rotateRight(tmp, 1)
            val bit = (Z ushr ((i - 4) % 62)) and 1L
            out[i] = out[i - 4].inv() xor tmp xor bit xor 3L
        }
        return out
    }

    fun encrypt(x: Long, x1: Long, key: LongArray): LongArray {
        var left = x
        var right = x1
        for (roundKey in key) {
            val tmp = right
            val f = java.lang.Long.rotateLeft(right, 1) and java.lang.Long.rotateLeft(right, 8)
            right = left xor f xor java.lang.Long.rotateLeft(right, 2) xor roundKey
            left = tmp
        }
        return longArrayOf(left, right)
    }
}

/** `x-ladon`：34 轮 ARX，输出 `random(4B) || 密文` 的 base64。 */
internal object LadonSigner {
    fun encrypt(khronos: Int, licenseId: Int, aid: Int, random: ByteArray): String {
        require(random.size == 4) { "ladon random 必须是 4 字节" }
        val data = "$khronos-$licenseId-$aid".toByteArray()
        val md5Hex = digest("MD5", random + aid.toString().toByteArray()).hex().toByteArray()
        val table = expandTable(md5Hex)
        val pad = 16 - data.size % 16
        val plain = ByteArray(data.size + pad) { pad.toByte() }
        data.copyInto(plain)
        val cipher = ByteArrayOutputStream(plain.size)
        var at = 0
        while (at < plain.size) {
            cipher.write(encryptBlock(table, plain, at))
            at += 16
        }
        return (random + cipher.toByteArray()).base64()
    }

    private fun expandTable(md5Hex: ByteArray): LongArray {
        val table = ByteArray(288)
        md5Hex.copyInto(table, 0, 0, minOf(32, md5Hex.size))
        var b0 = table.i64(0, true)
        var b8 = table.i64(8, true)
        val queue = ArrayDeque<Long>()
        queue.addLast(table.i64(16, true))
        queue.addLast(table.i64(24, true))
        for (i in 0 until 34) {
            var x8 = java.lang.Long.rotateRight(b8, 8) + b0
            x8 = x8 xor i.toLong()
            queue.addLast(x8)
            x8 = x8 xor java.lang.Long.rotateLeft(b0, 3)
            le64(x8).copyInto(table, (i + 1) * 8)
            b0 = x8
            b8 = queue.removeFirst()
        }
        return LongArray(36) { table.i64(it * 8, true) }
    }

    private fun encryptBlock(table: LongArray, data: ByteArray, at: Int): ByteArray {
        var d0 = data.i64(at, true)
        var d1 = data.i64(at + 8, true)
        for (i in 0 until 34) {
            d1 = table[i] xor (d0 + java.lang.Long.rotateRight(d1, 8))
            d0 = d1 xor java.lang.Long.rotateLeft(d0, 3)
        }
        return le64(d0) + le64(d1)
    }
}

/** `x-argus`：protobuf bean → Simon → 551 包装 → 反转 → AES-128-CBC。 */
internal object ArgusSigner {
    const val LICENSE_ID = 1611921764

    private val SIGN_KEY =
        hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304")
    private val AES_KEY = hex("d31e3718288a1027baab59f146a09a9c")
    private val AES_IV = hex("ea180a0336ed352fcd24e4d50018ae54")
    private val MAGIC8 = hex("f2f7fcfff2f7fcff")
    private val TAIL9 = hex("eafb2cfe8568519154")

    /**
     * @param query 参与签名的原始 query（不含 `?`，必须是真正发出去的那串）
     * @param stubHex body 的 MD5 十六进制；无 body 时传空串
     * @param randField 随机 int32（服务端不校验，只做扰动）
     */
    fun sign(
        query: String,
        stubHex: String,
        ts: Int,
        aid: Int,
        licenseId: Int,
        deviceId: String,
        installId: String,
        randField: Int,
    ): String {
        val bean =
            ProtoBean()
                .varint(1, 1077940818)
                .varint(2, 2)
                .varint(3, randField.toLong())
                .utf8(4, (if (aid == 0) 1967 else aid).toString())
                .utf8(5, deviceId)
                .utf8(6, (if (licenseId == 0) LICENSE_ID else licenseId).toString())
                .utf8(7, queryParam(query, "version_name") ?: "")
                .utf8(8, "v04.04.05-ov-android")
                .varint(9, 134744640)
                .blob(10, ByteArray(8))
                .varint(11, 0)
                .varint(12, (ts.toLong() shl 1))
                .blob(13, bodyHash(stubHex))
                .blob(14, queryHash(query))
                .nested(
                    15,
                    ProtoBean().varint(1, 1).varint(2, 1).varint(3, 1).varint(7, 0xC792ECCC),
                )
                .utf8(16, installId)
                .utf8(20, "none")
                .varint(21, 738)
                .nested(23, ProtoBean().utf8(1, "NX551J").varint(2, 8196).varint(4, 0x80E0D800))
                .varint(25, 2)

        val encrypted = simonEncrypt(pad16(bean.bytes()))

        // 551 包装：前 8 字节原样，其后整段与 MAGIC8 循环异或，再整体反转。
        val un = MAGIC8 + encrypted + TAIL9
        val mixed = ByteArray(un.size)
        MAGIC8.copyInto(mixed, 0)
        for (i in 8 until un.size) mixed[i] = (un[i].toInt() xor MAGIC8[i % 8].toInt()).toByte()
        mixed.reverse()

        val padded = pad16(mixed + byteArrayOf(0x61, 0x6f))
        val cipher =
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(AES_KEY, "AES"),
                    IvParameterSpec(AES_IV),
                )
                doFinal(padded)
            }
        return (byteArrayOf(0xf2.toByte(), 0x81.toByte()) + cipher).base64()
    }

    private fun bodyHash(stubHex: String): ByteArray =
        if (stubHex.isEmpty()) sm3(ByteArray(16)).copyOf(6)
        else
            runCatching { sm3(hex(stubHex)).copyOf(6) }
                .getOrElse { sm3(ByteArray(0)).copyOf(6) }

    private fun queryHash(query: String): ByteArray =
        if (query.isEmpty()) sm3(ByteArray(16)).copyOf(6)
        else sm3(query.toByteArray()).copyOf(6)

    private fun simonEncrypt(plain: ByteArray): ByteArray {
        val key = Simon64128.expandKey(LongArray(4) { SIGN_KEY.i64(it * 8, true) })
        val out = ByteArray(plain.size)
        var at = 0
        while (at < plain.size) {
            val block = Simon64128.encrypt(plain.i64(at, true), plain.i64(at + 8, true), key)
            le64(block[0]).copyInto(out, at)
            le64(block[1]).copyInto(out, at + 8)
            at += 16
        }
        return out
    }

    private fun pad16(data: ByteArray): ByteArray {
        val pad = 16 - data.size % 16
        return data + ByteArray(pad) { pad.toByte() }
    }

    /**
     * 取 query 里的某个参数，按 `decodeURIComponent` 语义解码（不把 `+` 当空格）。
     */
    fun queryParam(query: String, name: String): String? {
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            if (decode(key) == name) {
                val value = if (eq < 0) "" else part.substring(eq + 1)
                return decode(value)
            }
        }
        return null
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
