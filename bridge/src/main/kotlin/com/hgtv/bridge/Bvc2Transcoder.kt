// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.bridge

import com.hgtv.core.AnnexB
import com.hgtv.core.Mp4SampleTable
import com.hgtv.core.RemoteVideo
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 单集 BVC2 的软解兜底：解密取流 → Annex-B → ffmpeg stdin → HLS（H.264 + 音频直通）。
 *
 * 只在这一集**所有档位**都是 BVC2 时才会走到这里。正常情况下 [com.hgtv.core.ContentRepository.streamLadder]
 * 会先换一台注册身份重取，服务端多半会给回 HEVC；转码是最后一道兜底。
 *
 * 状态机 RUNNING → COMPLETED/FAILED/STOPPED。seek = 按新起点重启转码进程（plain.mp4 已落盘，
 * 不必重下）。世代计数让旧喂帧线程在新一轮启动后静默退出，避免误报失败。
 */
internal class Bvc2Transcoder(
    private val source: RemoteVideo,
    private val table: Mp4SampleTable.Table,
    private val rapIndices: List<Int>,
    private val plainFile: Path,
    val workDir: Path,
    private val ffmpeg: String,
    private val encoder: String,
) : Closeable {
    enum class State { RUNNING, COMPLETED, FAILED, STOPPED }

    @Volatile
    var state = State.RUNNING
        private set

    @Volatile
    var failReason: String? = null
        private set

    @Volatile
    private var stopping = false

    private val generation = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private var process: Process? = null
    private var feedThread: Thread? = null

    val alive: Boolean get() = state == State.RUNNING
    val indexFile: Path = workDir.resolve("index.m3u8")

    init {
        start(0L)
    }

    /** 启动或重启转码；[offsetUs] 为点播进度目标。 */
    @Synchronized
    fun start(offsetUs: Long) {
        stopProcess()
        workDir.toFile().deleteRecursively()
        workDir.toFile().mkdirs()
        val index = AnnexB.startIndex(table, offsetUs, rapIndices)
        val command =
            buildList {
                addAll(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin"))
                // `-r` 必须跟着裸流输入：FFmpeg 对裸 VVC 默认按 SPS 或 25fps 排时间戳，
                // 和容器真实帧率不符时音画会整体错位。
                addAll(listOf("-f", "vvc", "-r", AnnexB.frameRate(table), "-i", "pipe:0"))
                // 第二输入是已经解好密的 mp4：只取它的音频轨，视频轨是 BVC2，靠 ignore_unknown 跳过。
                addAll(listOf("-ignore_unknown", "-i", plainFile.toString()))
                addAll(listOf("-map", "0:v:0", "-map", "1:a:0?"))
                addAll(
                    listOf(
                        "-c:v", encoder,
                        "-b:v", AnnexB.videoBitrate(table),
                        "-g", "50",
                        "-c:a", "copy",
                        "-f", "hls",
                        "-hls_time", "2",
                        "-hls_list_size", "0",
                        "-hls_playlist_type", "event",
                        "-hls_segment_filename", workDir.resolve("seg%05d.ts").toString(),
                        indexFile.toString(),
                    ),
                )
            }
        val proc =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(workDir.resolve("ffmpeg.log").toFile()))
                .start()
        process = proc
        state = State.RUNNING
        failReason = null
        val gen = generation.incrementAndGet()
        feedThread = thread(isDaemon = true, name = "bvc2-feed") { feed(proc, index, gen) }
        thread(isDaemon = true, name = "bvc2-watch") { watch(proc, gen) }
    }

    /** seek = 按新起点重启转码。 */
    @Synchronized
    fun seek(offsetUs: Long): Boolean {
        if (closed.get() || state == State.STOPPED) return false
        start(offsetUs)
        return true
    }

    /**
     * 顺序把样本转 Annex-B 喂给 ffmpeg。
     *
     * 按 ≤1MB 聚合读，减少 Range 请求数；每个样本再切出来单独转，保证 NAL 边界不被跨样本
     * 拼接破坏（长度前缀是样本内局部的）。
     */
    private fun feed(proc: Process, startIndex: Int, gen: Long) {
        val samples = table.samples
        try {
            proc.outputStream.use { raw ->
                val stdin = BufferedOutputStream(raw, 1 shl 20)
                var index = startIndex
                while (index < samples.size && !stopping && !closed.get()) {
                    val start = samples[index].offset
                    var last = index
                    while (
                        last < samples.size &&
                            samples[last].offset + samples[last].size - start <= SPAN
                    )
                        last++
                    if (last == index) last = index + 1
                    val spanEnd = samples[last - 1].offset + samples[last - 1].size
                    val blob = source.read(start, (spanEnd - start).toInt())
                    for (i in index until last) {
                        if (stopping || closed.get()) return
                        val sample = samples[i]
                        val base = (sample.offset - start).toInt()
                        if (base + sample.size > blob.size) break
                        AnnexB.toAnnexB(blob.copyOfRange(base, base + sample.size), stdin)
                    }
                    stdin.flush()
                    index = last
                }
            }
        } catch (problem: IOException) {
            // ffmpeg 提前退出会让写管道抛 "Broken pipe"，这不是真实故障，别覆盖它自己的结论。
            if (generation.get() != gen || stopping || closed.get()) return
            synchronized(this) {
                if (state != State.RUNNING) return
                state = State.FAILED
                failReason = "软解取流失败：${problem.message?.take(120)}"
            }
            runCatching { proc.destroyForcibly() }
        }
    }

    private fun watch(proc: Process, gen: Long) {
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        if (generation.get() != gen || stopping || closed.get()) return
        synchronized(this) {
            if (state != State.RUNNING) return
            if (code == 0) {
                state = State.COMPLETED
            } else {
                state = State.FAILED
                failReason = "软解转码退出码 $code"
            }
        }
    }

    private fun stopProcess() {
        val proc = process ?: return
        process = null
        runCatching { proc.outputStream.close() }
        runCatching { proc.destroyForcibly() }
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        feedThread?.let { runCatching { it.join(2_000) } }
        feedThread = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            stopping = true
            state = State.STOPPED
            stopProcess()
        }
        runCatching { workDir.toFile().deleteRecursively() }
        runCatching { File(plainFile.toString()).delete() }
        runCatching { source.close() }
    }

    private companion object {
        const val SPAN = 1_000_000
    }
}
