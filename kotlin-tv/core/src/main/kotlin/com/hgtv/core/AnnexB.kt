// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.io.OutputStream

/**
 * BVC2 软解所需的最小码流处理：长度前缀 NALU 转 Annex-B、定位起播样本、推算帧率与码率。
 *
 * 为什么能绕过容器差异：bvc2 的码流本身就是标准 VVC，与 VVC 的唯一区别在封装
 * （`bv2C` 盒的数据按 4 字节长度前缀切分，而不是 `vvcC` 的参数集布局）。FFmpeg 主线
 * 自带 `vvc` 解码器，只要把样本还原成一串起始码分隔的 NAL，`-f vvc` 就能直接解，
 * 不需要给 FFmpeg 打任何补丁。
 */
object AnnexB {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** VVC 随机访问 NAL 类型：IDR_W_RADL(7)、IDR_N_LP(8)、CRA(10)、STSA_R(5)。 */
    private val RAP_TYPES = setOf(5, 7, 8, 10)

    /**
     * 单个解密样本（一串 4 字节长度前缀 NALU）转 Annex-B 追加到 [out]。
     *
     * 长度字段非法时静默停下，而不是抛异常：真出现畸形样本时，把这一帧丢掉继续解码
     * 比整条转码链断掉更划算，后面的帧通常仍能正常解出。
     */
    fun toAnnexB(sample: ByteArray, out: OutputStream) {
        var cursor = 0
        while (cursor + 4 <= sample.size) {
            val nal =
                ((sample[cursor].toInt() and 255) shl 24) or
                    ((sample[cursor + 1].toInt() and 255) shl 16) or
                    ((sample[cursor + 2].toInt() and 255) shl 8) or
                    (sample[cursor + 3].toInt() and 255)
            if (nal <= 0 || cursor + 4 + nal > sample.size) break
            out.write(START_CODE)
            out.write(sample, cursor + 4, nal)
            cursor += 4 + nal
        }
    }

    /**
     * `stss` 缺失时从样本数据推导真实随机访问点：逐样本读首 NALU 的 2 字节头判类型。
     * 字节家的流把同步信息放在带内 NAL 类型上而不是 `stss` 里，所以只能扫一遍；
     * 按 ≤1MB 聚合读，代价约等于单次顺序拉流。
     *
     * [read] 与 [RemoteVideo.read] 同签名（返回解密后的字节）。
     */
    fun scanSyncSamples(
        table: Mp4SampleTable.Table,
        read: (start: Long, length: Int) -> ByteArray,
    ): List<Int> {
        val raps = ArrayList<Int>()
        val samples = table.samples
        var index = 0
        while (index < samples.size) {
            val start = samples[index].offset
            var last = index
            while (
                last < samples.size &&
                    samples[last].offset + samples[last].size - start <= SPAN
            )
                last++
            if (last == index) last = index + 1
            val spanEnd = samples[last - 1].offset + samples[last - 1].size
            val blob = read(start, (spanEnd - start).toInt())
            for (i in index until last) {
                val base = (samples[i].offset - start).toInt()
                // 样本首 NALU：4 字节长度前缀 + 2 字节 NAL 头；type 占第二字节高 5 位。
                if (base + 6 <= blob.size) {
                    val type = (blob[base + 5].toInt() shr 3) and 0x1F
                    if (type in RAP_TYPES) raps += i
                }
            }
            index = last
        }
        return raps
    }

    /**
     * 从样本表 dts 步进推真实帧率（分数字符串）。
     *
     * 必须显式传给 `-r`：FFmpeg 对裸 VVC 流默认按 SPS 或 25fps 排时间戳，和容器真实
     * 帧率不一致时音画会整体错位。
     */
    fun frameRate(table: Mp4SampleTable.Table): String {
        val samples = table.samples
        if (samples.size < 2) return "25"
        val delta = samples[1].dts - samples[0].dts
        if (delta <= 0) return "25"
        return "${1_000_000}/${delta}"
    }

    /**
     * seek 目标（微秒）→ 起播样本下标：先找首个 dts ≥ 目标的样本，再回退到最近的随机
     * 访问点。优先用 `stss`，缺失时用带内扫描出的 [rapIndices]，两者皆空才按「全部同步」退化。
     */
    fun startIndex(
        table: Mp4SampleTable.Table,
        offsetUs: Long,
        rapIndices: List<Int>,
    ): Int {
        val samples = table.samples
        if (samples.isEmpty()) return 0
        var index = samples.indexOfFirst { it.dts >= offsetUs }
        if (index < 0) index = samples.size - 1
        val sync = if (table.syncSamples.isNotEmpty()) table.syncSamples else rapIndices
        if (sync.isEmpty()) return index
        var low = 0
        var high = sync.size - 1
        var best = sync[0]
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sync[mid] <= index) {
                best = sync[mid]
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /**
     * 分辨率 → H.264 目标码率。转码只是一次性的本地兼容通道，按「VVC 源 ×2 以上」给足，
     * 尽量做到观感无损。
     */
    fun videoBitrate(table: Mp4SampleTable.Table): String {
        val longest = maxOf(table.width, table.height)
        return when {
            longest >= 1900 -> "8M"
            longest >= 1200 -> "5M"
            longest >= 1000 -> "3M"
            else -> "1500k"
        }
    }

    private const val SPAN = 1_000_000
}
