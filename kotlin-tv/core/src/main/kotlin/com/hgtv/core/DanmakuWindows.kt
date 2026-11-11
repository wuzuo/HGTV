// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * 一集弹幕的窗口编排：探明步长 → 并发拉取 → 补齐异常步长。
 *
 * 三端共用同一份规则（TV / 手机 / Windows 的 bridge），避免各写一套慢慢跑偏。
 *
 * **窗口之间没有依赖。** 服务端按 30 秒切窗，`next_query_danmaku_list_time` 只是告诉
 * 下一窗从哪开始；拿任意起点单独请求都能拿到对应那一窗。实测同一集按 7 个窗口并发
 * 请求，每窗条数与串行**完全一致**（78/49/71/58/67/34/0），整集 1519ms → 251ms。
 *
 * 所以第一窗仍然单独等（它是唯一能告诉我们步长的东西），剩下的窗口一次并发打出去。
 * 调用方每拿到一窗就能渲染，不必等整集。
 */
class DanmakuWindows(
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val maxPages: Int = MAX_PAGES,
    private val assumedDurationMs: Long = ASSUMED_DURATION_MS,
) {
    /**
     * 阻塞式并发拉取，直到 [untilMs] 或整集结束。
     *
     * @param totalMs 整集时长；未知时传 0，按 [assumedDurationMs] 先拉一遍。
     * @param fromMs 从哪个位置开始（会向下对齐到窗口边界），续播 / 拖动后重拉用它。
     * @param untilMs 拉到哪个位置为止；默认整集。只预取播放位置附近时传 `now + 90s`。
     * @param read 拉单个窗口；通常就是 [TimedComments.read]，它自带按窗口的缓存。
     * @param onPage 每拿到一窗回调一次，带这一窗的耗时。**在工作线程上、串行调用**，
     *   调用方自行切主线程。
     * @return 已经连续覆盖到的播放位置（毫秒），调用方用它推进自己的游标。
     */
    fun load(
        totalMs: Long,
        fromMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
        read: (start: Long, total: Long) -> JSONObject,
        onPage: (start: Long, page: JSONObject, costMs: Long) -> Unit,
    ): Long {
        val total =
            (if (totalMs > 0) totalMs else assumedDurationMs).coerceIn(1_000L, MAX_DURATION_MS)
        val limit = minOf(untilMs, total)
        val first = align(fromMs)
        if (first >= limit) return first

        val lock = Any()
        var covered = first
        val failed = Collections.synchronizedList(mutableListOf<Long>())

        // 拉一窗并回调；返回下一窗起点，失败返回 -1。
        fun pull(start: Long): Long {
            val began = System.currentTimeMillis()
            val page = runCatching { read(start, total) }.getOrNull() ?: run {
                failed += start
                return -1L
            }
            val cost = System.currentTimeMillis() - began
            val raw = page.optLong("nextMS", total)
            val next = if (raw > start) raw else start + MIN_STEP_MS
            synchronized(lock) {
                onPage(start, page, cost)
                if (next > covered) covered = next
            }
            return next
        }

        // 覆盖到哪儿只认「连续成功」：某一窗挂了就把游标停在它前面，
        // 下一轮（TV 每 500ms 一拍）会从那一窗重来，不会因为别的窗先成功就跳过缺口。
        fun stopAt(): Long = failed.minOrNull()?.let { minOf(covered, it) } ?: covered

        val firstNext = pull(first)
        if (firstNext <= 0) return stopAt()
        val step = (firstNext - first).coerceAtLeast(MIN_STEP_MS)

        val starts = ArrayList<Long>()
        var cursor = firstNext
        while (cursor < limit && starts.size < maxPages) {
            starts += cursor
            cursor += step
        }
        if (starts.isEmpty()) return stopAt()

        val returned = Collections.synchronizedList(mutableListOf<Long>())
        val latch = CountDownLatch(starts.size)
        val pool = Executors.newFixedThreadPool(minOf(concurrency, starts.size))
        try {
            starts.forEach { start ->
                pool.execute {
                    try {
                        val next = pull(start)
                        if (next > 0) returned += next
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }

        // 服务端步长偶尔不是整段等差（`next` 跳了一格或多给一个窗口）：把落在序列外、
        // 还没拉过的窗口串行补上，最多补两轮，异常数据不会把循环拖长。
        val done = starts.toMutableSet().apply { add(first) }
        repeat(2) {
            val missing =
                returned.filter { it in 1 until limit && it !in done }.distinct().take(maxPages)
            if (missing.isEmpty()) return@repeat
            missing.forEach { start ->
                done += start
                val next = pull(start)
                if (next > 0) returned += next
            }
        }
        return stopAt()
    }

    /** 向下对齐到服务端窗口边界（固定 30 秒）。 */
    fun align(positionMs: Long): Long =
        (positionMs / WINDOW_MS).coerceAtLeast(0L) * WINDOW_MS

    companion object {
        const val WINDOW_MS = 30_000L
        const val MIN_STEP_MS = 5_000L
        const val MAX_DURATION_MS = 86_400_000L

        /** 同时最多几个请求在途：一集通常 4~10 窗，再多人也只在同一条链路上排队。 */
        const val DEFAULT_CONCURRENCY = 6

        /** 一次最多拉多少窗（30 分钟）；和手机端的分页上限一致。 */
        const val MAX_PAGES = 60

        /** 整集时长还没到时先按这个长度拉一遍，3 分钟覆盖绝大多数短剧。 */
        const val ASSUMED_DURATION_MS = 180_000L

        /** 并发那批的总等待上限：单请求的 callTimeout 是 20 秒，不能让它把播放页挂住。 */
        private const val READ_TIMEOUT_MS = 25_000L
    }
}
