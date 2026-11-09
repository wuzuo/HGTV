// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
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

    private val CODEC = Regex("""codec=([A-Za-z0-9_]+)""")
    private val SIZE = Regex("""size=(\d+)x(\d+)""")

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
            activeFile(context).writeText("${stamp()} $detail ${snapshot(context)}")
        }
    }

    /**
     * 崩溃当时的内存与运行时长。
     *
     * 同一条「异常中断」既可能是解码器原生崩溃，也可能是播放中被系统回收——两者的处置完全
     * 不同（前者要降档/换渲染面，后者要降缓冲），光看清晰度和编码分不出来，所以把内存压力
     * 和开机时长一起留下。
     */
    private fun snapshot(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        runCatching { manager?.getMemoryInfo(info) }
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val max = runtime.maxMemory() / (1024 * 1024)
        return "avail=${info.availMem / (1024 * 1024)}MB" +
            " low=${info.lowMemory}" +
            " heap=$used/$max" +
            " uptime=${SystemClock.elapsedRealtime() / 1000}s"
    }

    /**
     * 记录一次「播放器报错但没崩」。
     *
     * 这类错误（例如 `ERROR_CODE_UNSPECIFIED`）本身就说明解码链路出了问题，但进程活下来了，
     * 标记文件不会被触发；不记一笔的话，用户反馈「卡住/黑屏」时手上什么都没有。
     */
    fun notePlaybackError(context: Context, line: String) {
        append(context, "播放器报错 ${stamp()} $line")
    }

    /**
     * 从标记文本里取出可以直接用于「记住这套解码组合」的编码与尺寸。
     *
     * 标记是给人看的单行文本，这里只做最小限度的解析：取不到就返回 null，绝不猜。
     */
    internal fun interruptedCodec(detail: String): InterruptedCodec? {
        val codec = CODEC.find(detail)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (codec.isEmpty() || codec == "?") return null
        val size = SIZE.find(detail) ?: return null
        val width = size.groupValues[1].toIntOrNull() ?: return null
        val height = size.groupValues[2].toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return InterruptedCodec(codec, width, height)
    }

    internal data class InterruptedCodec(val codec: String, val width: Int, val height: Int)

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
            val keys = CodecCapabilities.learnedKeys()
            appendLine("已学到的解码器失败记录：${keys.size} 条")
            // 列出来，否则只看条数分不清是「真解码不了」还是误记 —— 误记会让整类内容播不了。
            keys.forEach { appendLine("  · $it") }
            // 0 条很容易被误解成「从没出过问题」：原生崩溃直接把进程带走，Media3 的
            // 解码器错误回调根本不会执行，所以那一类只能看下面的「异常中断」。
            appendLine("（系统直接终止进程的原生崩溃收不到回调，不计入条数，看下方异常中断）")
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
