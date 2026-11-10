// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

// 远程视频资源处理：管理播放数据读取与资源关闭。

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, val info: StreamInfo) : Closeable {
    @Volatile private var closed = false
    private val calls = mutableSetOf<Call>()

    data class Range(val bytes: ByteArray, val total: Long)

    var total: Long = 0
        private set

    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix = byteArrayOf()

    private fun newCall(start: Long, end: Long): Call {
        if (closed) throw IOException("播放请求已取消")
        require(start >= 0 && end >= start && end - start < 8 * 1024 * 1024)
        val request =
            Request.Builder()
                .url(info.url)
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .header("Referer", "https://novel.snssdk.com/")
                .header("Range", "bytes=$start-$end")
                .header("Accept-Encoding", "identity")
                .build()
        val call = http.newCall(request)
        synchronized(calls) {
            if (closed) throw IOException("播放请求已取消")
            calls += call
        }
        return call
    }

    private fun rangeSize(response: Response, start: Long, end: Long): Pair<Int, Long> {
        if (response.code != 206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
        val match =
            Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                .matchEntire(response.header("Content-Range").orEmpty())
                ?: throw IOException("视频分段响应异常")
        val (begin, finish, size) = match.destructured
        val full = size.toLong()
        val last = minOf(end, full - 1)
        if (begin.toLong() != start || finish.toLong() != last || full <= start)
            throw IOException("视频分段范围不匹配")
        if (total != 0L && full != total) throw IOException("视频资源已变化，请重试")
        return (last - start + 1).toInt() to full
    }

    private fun range(start: Long, end: Long): Range {
        val call = newCall(start, end)
        try {
            return RequestScope.execute(call) { response ->
                val (n, full) = rangeSize(response, start, end)
                val body = response.body ?: throw IOException("视频分段为空")
                val bytes = body.source().readByteArray(n.toLong())
                Range(bytes, full)
            }
        } finally {
            synchronized(calls) { calls -= call }
        }
    }

    /**
     * Consume the index progressively within one request. Avoid a second round trip when moov ends
     * after the initial probe but inside the existing 256 KiB range.
     */
    private fun indexPrefix(initialBytes: Int): Range {
        val call = newCall(0, 256 * 1024 - 1)
        try {
            return RequestScope.execute(call) { response ->
                val (size, full) = rangeSize(response, 0, 256 * 1024 - 1)
                val source = (response.body ?: throw IOException("视频分段为空")).source()
                val first = source.readByteArray(minOf(initialBytes, size).toLong())
                val needed = minOf(MediaCrypto.headerEnd(first, full), size)
                val bytes =
                    if (needed > first.size)
                        first + source.readByteArray((needed - first.size).toLong())
                    else first
                Range(bytes, full)
            }
        } finally {
            synchronized(calls) { calls -= call }
        }
    }

    /**
     * One bounded HTTP range, delivered as bytes arrive instead of waiting for the whole body. The
     * playback data source owns this reader; close() interrupts an outstanding body read.
     */
    fun openRead(start: Long, length: Int): InputStream {
        check(::prepared.isInitialized)
        require(start in 0 until total && length in 1..1024 * 1024)
        val end = minOf(total - 1, start + length - 1)
        val call = newCall(start, end)
        var response: Response? = null
        try {
            val opened = call.execute()
            response = opened
            val (size, _) = rangeSize(opened, start, end)
            val input = (opened.body ?: throw IOException("视频分段为空")).byteStream()
            return object : InputStream() {
                private var position = start
                private var remaining = size
                private var readerClosed = false
                private val scratch = ByteArray(32 * 1024)
                private val single = ByteArray(1)

                override fun read(): Int {
                    return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
                }

                override fun read(target: ByteArray, offset: Int, count: Int): Int {
                    require(offset >= 0 && count >= 0 && offset <= target.size - count)
                    if (count == 0) return 0
                    if (readerClosed || closed) throw IOException("播放请求已取消")
                    if (remaining == 0) return -1
                    val n = input.read(scratch, 0, minOf(count, remaining, scratch.size))
                    if (n < 0) throw java.io.EOFException("视频分段意外结束")
                    MediaCrypto.decryptInto(scratch, 0, n, position, info.key, prepared, target, offset)
                    position += n
                    remaining -= n
                    return n
                }

                override fun close() {
                    if (readerClosed) return
                    readerClosed = true
                    if (remaining > 0) call.cancel()
                    opened.close()
                    synchronized(calls) { calls -= call }
                }
            }
        } catch (error: Exception) {
            call.cancel()
            response?.close()
            synchronized(calls) { calls -= call }
            throw error
        }
    }

    @JvmOverloads
    @Synchronized
    fun prepare(initialBytes: Int = 256 * 1024): RemoteVideo {
        require(initialBytes in 16 * 1024..256 * 1024)
        if (::prepared.isInitialized) return this
        val first =
            if (initialBytes < 256 * 1024) indexPrefix(initialBytes) else range(0, 256 * 1024 - 1)
        total = first.total
        prefix = first.bytes
        val end = MediaCrypto.headerEnd(first.bytes, total)
        val header =
            if (end > first.bytes.size)
                first.bytes + range(first.bytes.size.toLong(), end - 1L).bytes
            else first.bytes.copyOf(end)
        if (header.size > prefix.size) prefix = header
        prepared = MediaCrypto.prepare(header, info.key)
        if (prepared.samples.any { it.end > total }) throw IOException("视频样本超出资源大小")
        return this
    }

    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized
    fun warm(bytes: Int = 1024 * 1024): RemoteVideo {
        require(bytes in 256 * 1024..4 * 1024 * 1024)
        prepare()
        val size = minOf(total, bytes.toLong()).toInt()
        if (prefix.size < size) prefix = prefix + range(prefix.size.toLong(), size - 1L).bytes
        return this
    }

    /** Bytes already held locally; a player can consume these before another network range. */
    fun cachedBytes(start: Long): Int =
        (prefix.size.toLong() - start).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    fun read(start: Long, length: Int): ByteArray {
        require(length in 1..1024 * 1024 && start >= 0)
        val buffer = ByteArray(length)
        val n = readInto(start, length, buffer, 0)
        return if (n == length) buffer else buffer.copyOf(n)
    }

    /**
     * Writes up to [length] decoded bytes at [start] into [target]. Returns the count written, zero
     * when [start] is already past the end. Allocation-free unless the request straddles the cached
     * prefix, which happens once per stream instead of once per read.
     */
    fun readInto(start: Long, length: Int, target: ByteArray, targetOffset: Int): Int {
        require(length in 1..1024 * 1024 && start >= 0)
        require(targetOffset >= 0 && targetOffset <= target.size - length)
        if (closed) throw IOException("播放请求已取消")
        if (start >= total) return 0
        val cached = prefix
        val end = minOf(total, start + length)
        val available = (minOf(end, cached.size.toLong()) - start).coerceAtLeast(0).toInt()
        if (start + available >= end) {
            MediaCrypto.decryptInto(
                cached,
                start.toInt(),
                available,
                start,
                info.key,
                prepared,
                target,
                targetOffset,
            )
            return available
        }
        val raw =
            if (available > 0)
                cached.copyOfRange(start.toInt(), start.toInt() + available) +
                    range(start + available, end - 1).bytes
            else range(start, end - 1).bytes
        MediaCrypto.decryptInto(raw, 0, raw.size, start, info.key, prepared, target, targetOffset)
        return raw.size
    }

    /**
     * Decoded video dimensions after prepare(); null before prepare or when the header has no
     * readable video track.
     */
    fun trackInfo(): VideoTracks.Track? {
        if (!::prepared.isInitialized) return null
        return VideoTracks.videoTrack(prepared.header)
    }

    /**
     * 解密后头里的视频样本表，供 BVC2 软解按样本顺序取流；[prepare] 之前返回 null。
     *
     * 用 [prepared] 而不是重新读一次远端：那里的 header 已经把 `encv` 还原成原始编码标签、
     * 中和了 `sinf`，样本表的解析因此不必感知 CENC。
     */
    fun sampleTable(): Mp4SampleTable.Table? {
        if (!::prepared.isInitialized) return null
        return Mp4SampleTable.parse(prepared.header)
    }

    /** 修正过的文件头（`encv`→原编码、`sinf` 已清零），用于拼出 FFmpeg 能读的明文容器。 */
    fun repairedHeader(): ByteArray? {
        if (!::prepared.isInitialized) return null
        return prepared.header
    }

    /**
     * 把整段资源解密后顺序写到 [out]，得到一个 FFmpeg 能直接当普通 MP4 读的明文文件。
     *
     * 按 8MB 分段（[_newCall] 的单次上限）：若按播放路径的 1MB 走，一百多 MB 的资源会被拆成
     * 上百次 Range 往返，光起播就要多等十几秒。头区间由 [MediaCrypto.decryptInto] 用修正过的
     * header 覆盖，`encv`→原编码、`sinf` 清零一并完成。
     */
    fun exportDecrypted(out: OutputStream) {
        check(::prepared.isInitialized)
        var position = 0L
        while (position < total) {
            val length = minOf(EXPORT_SPAN.toLong(), total - position).toInt()
            val raw = range(position, position + length - 1).bytes
            val buffer = ByteArray(raw.size)
            MediaCrypto.decryptInto(raw, 0, raw.size, position, info.key, prepared, buffer, 0)
            out.write(buffer)
            position += raw.size
        }
    }

    override fun close() {
        synchronized(calls) {
            closed = true
            calls.toList().forEach { it.cancel() }
            calls.clear()
        }
    }

    private companion object {
        /** 单次 Range 上限：比 [newCall] 允许的 8MB 少 1 字节。 */
        const val EXPORT_SPAN = 8 * 1024 * 1024 - 1
    }
}
