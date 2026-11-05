// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放异常取证。
 *
 * 这类「花屏 + 闪退」多数发生在解码器（native）里：进程被直接带走，Java 层收不到任何回调，
 * 界面上也留不下痕迹。所以两头都记：
 *
 * 1. 进入播放前写一个标记文件，正常退出时删掉；
 * 2. 注册兜底的未捕获异常处理器。
 *
 * 下次启动时如果标记还在，就说明上一次是在播放途中没的（原生崩溃或被系统回收），
 * 记一条「异常中断」，并把当时的清晰度/编码/渲染方式一起留下。
 *
 * 文件都在应用私有目录，不上传；设置页可以查看和复制。
 */
object PlaybackDiagnostics {
    private const val REPORT = "playback-diag.txt"
    private const val ACTIVE = "playback-active.txt"
    private const val LIMIT = 16 * 1024

    private fun reportFile(context: Context) = File(context.applicationContext.filesDir, REPORT)
    private fun activeFile(context: Context) = File(context.applicationContext.filesDir, ACTIVE)

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    fun deviceLine(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = manager?.memoryClass ?: 0
        val lowRam = manager?.isLowRamDevice == true
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
            " (SDK ${Build.VERSION.SDK_INT}) · ${memory}MB 堆" +
            if (lowRam) " · 低内存设备" else ""
    }

    @Volatile private var installed = false

    /** 只兜 Java 层；原生崩溃靠下面的标记文件。[install] 必须排在其它初始化之前。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { recordCrash(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun recordCrash(context: Context, thread: Thread, error: Throwable) {
        val stack = error.stackTraceToString().lineSequence().take(24).joinToString("\n")
        append(
            context,
            "Java 崩溃 ${stamp()} thread=${thread.name}\n${error.javaClass.name}: ${error.message}\n$stack",
        )
    }

    fun beginPlayback(context: Context, detail: String) {
        runCatching {
            activeFile(context).writeText("${stamp()} $detail")
        }
    }

    fun endPlayback(context: Context) {
        runCatching { activeFile(context).delete() }
    }

    /**
     * 启动时调用：上次是播放中异常退出就补记一条，返回当时的详情（没有则返回 null）。
     */
    fun noteStartup(context: Context): String? {
        val marker = activeFile(context)
        if (!marker.exists()) return null
        val detail = runCatching { marker.readText() }.getOrDefault("")
        runCatching { marker.delete() }
        append(context, "上次播放中异常中断：$detail")
        return detail
    }

    fun report(context: Context): String {
        val body = runCatching { reportFile(context).readText() }.getOrDefault("").trim()
        return buildString {
            appendLine("设备：${deviceLine(context)}")
            appendLine("画面输出：${PlaybackCompat.label()}")
            appendLine("已学到的解码器失败记录：${CodecCapabilities.learnedCount()} 条")
            appendLine()
            if (body.isEmpty()) {
                append("还没有异常记录。")
            } else {
                append(body.takeLast(4000))
            }
        }
    }

    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    private fun append(context: Context, line: String) {
        runCatching {
            val file = reportFile(context)
            val existing = if (file.exists()) file.readText() else ""
            val next = (existing + if (existing.isEmpty()) "" else "\n") + line + "\n"
            file.writeText(next.takeLast(LIMIT))
        }
    }
}
