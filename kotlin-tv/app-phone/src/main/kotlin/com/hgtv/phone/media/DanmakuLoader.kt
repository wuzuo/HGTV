// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.media

import com.hgtv.core.Danmaku
import com.hgtv.core.TimedComments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 拉一集的弹幕。
 *
 * 接口是「从某一刻起往后给一段」，服务端按 30 秒切窗，用响应里的 `nextMS` 推进
 * （与官方客户端一致，抓包见 2026-11-11）。
 *
 * **并行拉，不是串行拉完再上屏。** 早先是 `repeat(MAX_PAGES)` 一页一页等：一集 210 秒
 * 有 7 个窗口，本机实测串行合计 1519ms（首窗还要先付 TLS 握手 476ms），移动网络下更久，
 * 表现就是「进片干等一阵，然后弹幕一次性全冒出来」。窗口之间没有依赖——同一集按任意
 * 起点单独请求、7 个并发请求，每个窗口的条数与串行**完全一致**（78/49/71/58/67/34/0），
 * 所以第一页回来探明步长后，剩下的一次并发打出去（实测总耗时 251ms）。
 *
 * 每拿到一窗就往外发一次累积快照，第一窗（≈200~500ms）就足够让画面先有弹幕。
 *
 * 上限按 30 分钟算：早先写死 10 页（300 秒），超过 5 分钟的集数后半段是没有弹幕的，
 * 而且看不出是「本来就没有」还是「没去拉」。遇到 `nextMS` 不再前进就停下，不会空转。
 *
 * 失败只丢这一窗：弹幕是锦上添花，不该让播放页报错。
 */
object DanmakuLoader {
    private const val MAX_PAGES = 60
    private const val ASSUMED_DURATION_MS = 180_000L

    /**
     * 同时在途的请求数。
     *
     * 一集通常 4~10 个窗口，6 路并发能把「全集拉完」压到一次往返的量级；再多对短剧没有
     * 收益，只是在同一条链路上排队。
     */
    private const val CONCURRENCY = 6

    private val ID = Regex("[0-9]{6,30}")

    /**
     * 跨集、跨重拉共用一份。
     *
     * 时长到齐会让 [load] 整个重跑一次：如果这里每次都 new 一个，窗口缓存跟着一起丢掉，
     * 那一遍等于从头再请求整集——正是「弹幕先出来一批，又停一阵，再冒出一批」的后半段。
     */
    private val timed = TimedComments()

    fun load(seriesId: String, episodeId: String, durationMs: Long): Flow<List<Danmaku>> =
        channelFlow {
            if (!seriesId.matches(ID) || !episodeId.matches(ID)) return@channelFlow
            val total =
                (if (durationMs > 0) durationMs else ASSUMED_DURATION_MS)
                    .coerceIn(1_000L, 86_400_000L)
            val seen = HashSet<String>()
            val items = ArrayList<Danmaku>()
            val lock = Mutex()

            /** 拉一个窗口并上屏；返回服务端给的下一个窗口起点，失败返回 -1。 */
            suspend fun pull(start: Long): Long {
                val began = System.currentTimeMillis()
                val page =
                    runCatching {
                            withContext(Dispatchers.IO) {
                                timed.read(seriesId, episodeId, start, total)
                            }
                        }
                        .getOrNull() ?: return -1L
                val rows = page.optJSONArray("items") ?: JSONArray()
                lock.withLock {
                    var added = false
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        val text = row.optString("text").trim()
                        val at = row.optLong("timeMS", -1L)
                        if (text.isEmpty() || at < 0) continue
                        if (!seen.add("$at\u0000$text")) continue
                        items += Danmaku(at, text)
                        added = true
                    }
                    // 同一份累积列表按时间排好再发：播放层拿到就能直接排轨。
                    if (added) send(items.sortedBy { it.offsetMs }.toList())
                    // 每窗一条：耗时接近 0 就是命中缓存，说明这一遍没再打接口。
                    android.util.Log.i(
                        "HGTV",
                        "danmaku start=$start rows=${rows.length()} have=${items.size} " +
                            "cost=${System.currentTimeMillis() - began}ms",
                    )
                }
                return page.optLong("nextMS", total)
            }

            // 第一页必须单独等：步长（窗口大小）只有它能告诉我们。
            val firstNext = pull(0L)
            if (firstNext <= 0L) return@channelFlow
            val step = firstNext
            val starts = ArrayList<Long>()
            var cursor = firstNext
            while (cursor in 1 until total && starts.size < MAX_PAGES) {
                starts += cursor
                cursor += step
            }

            val covered = mutableSetOf(0L, firstNext)
            val returned = mutableListOf<Long>()
            for (batch in starts.chunked(CONCURRENCY)) {
                val results =
                    coroutineScope { batch.map { async { it to pull(it) } }.awaitAll() }
                for ((start, next) in results) {
                    covered += start
                    if (next > 0) returned += next
                }
            }

            // 服务端步长偶尔不是整段等差（`next` 跳了一格或多给了一个窗口）：把落在
            // 序列外、还没拉过的窗口补上，最多补两轮，避免异常数据把循环拖长。
            repeat(2) {
                val missing =
                    returned.filter { it in 1 until total && it !in covered }
                        .distinct()
                        .take(MAX_PAGES)
                if (missing.isEmpty()) return@repeat
                for (start in missing) {
                    covered += start
                    val next = pull(start)
                    if (next > 0) returned += next
                }
            }
        }
}
