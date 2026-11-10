// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.bridge

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 定位 FFmpeg 并挑一个真能用的 H.264 编码器。
 *
 * 为什么不把 ffmpeg.exe 打进分发包：全功能构建有一百多 MB，而软解兜底只在「整集只剩 BVC2」
 * 这种罕见情况下才会触发，让绝大多数用户白背这份体积不值得。所以按「显式配置 → 程序目录 →
 * PATH → 常见安装位置」的顺序找，找不到就把原因如实带到界面上。
 */
internal object Ffmpeg {
    /**
     * 返回可用的 ffmpeg 路径；找不到时 null。
     *
     * [appRoot] 是桌面端 exe 所在目录（bridge 通过 HGTV_APP_ROOT 拿到），包里自带时放在
     * `<appRoot>/ffmpeg/ffmpeg.exe`。
     */
    fun locate(appRoot: File?): String? {
        val candidates =
            buildList {
                System.getenv("HGTV_FFMPEG")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
                appRoot?.let { add(File(it, "ffmpeg/ffmpeg.exe")) }
                appRoot?.let { add(File(it, "ffmpeg/bin/ffmpeg.exe")) }
                System.getenv("PATH")
                    ?.split(File.pathSeparator)
                    ?.filter { it.isNotBlank() }
                    ?.forEach {
                        add(File(it, "ffmpeg.exe"))
                        add(File(it, "ffmpeg"))
                    }
                // Windows 上最常见的两处安装位置：scoop 与手动解压到 Program Files。
                add(File("C:/Program Files/ffmpeg/bin/ffmpeg.exe"))
                add(File("C:/ffmpeg/bin/ffmpeg.exe"))
                add(File("/usr/bin/ffmpeg"))
                add(File("/usr/local/bin/ffmpeg"))
            }
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    /**
     * 挑一个真的能编码出帧的 H.264 编码器。
     *
     * 只看 `-encoders` 是不够的：NVENC 在驱动版本低于构建时用的 API 版本时会「列出来但用不了」，
     * 而且失败会被编码器探测静默吞掉、退化成软编，表象只是变慢（构建脚本里有同一条教训）。
     * 所以这里对候选逐个真编一帧，编得出来才算数。
     */
    fun encoder(path: String): String {
        val listed = run(path, listOf("-hide_banner", "-encoders"))?.let { output ->
            ENCODERS.filter { output.contains(" $it ") }
        }.orEmpty()
        for (candidate in listed) {
            val probe =
                run(
                    path,
                    listOf(
                        "-hide_banner", "-loglevel", "error",
                        "-f", "lavfi", "-i", "color=black:s=256x256:d=0.04",
                        "-frames:v", "1", "-c:v", candidate, "-f", "null", "-",
                    ),
                )
            if (probe != null) return candidate
        }
        return "libx264"
    }

    private fun run(path: String, args: List<String>): String? =
        runCatching {
                val proc =
                    ProcessBuilder(listOf(path) + args)
                        .redirectErrorStream(true)
                        .start()
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                if (!proc.waitFor(8, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    return@runCatching null
                }
                if (proc.exitValue() == 0) output else null
            }
            .getOrNull()

    /** 优先级：先用硬件编码器（1080p 软编在多数机器上赶不上实时），再退 libx264。 */
    private val ENCODERS = listOf("h264_nvenc", "h264_qsv", "h264_amf", "h264_videotoolbox", "libx264")
}
