// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.media

import com.hgtv.core.Danmaku
import com.hgtv.core.DanmakuWindows
import com.hgtv.core.TimedComments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 拉一集的弹幕。
 *
 * 窗口怎么切、怎么并发，全交给 [DanmakuWindows]（三端共用同一份规则）；这里只做两件事：
 * 把每窗的条目累积成一份按时间排序的列表，每攒到新的就往外发一次——第一窗回来就能上屏，
 * 不用等整集。
 *
 * 失败只丢这一窗：弹幕是锦上添花，不该让播放页报错。
 */
object DanmakuLoader {
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

            val seen = HashSet<String>()
            val items = ArrayList<Danmaku>()
            // 发出动作在主协程（主线程），累积在工作线程：用一个不阻塞的通道接一下。
            val batches = Channel<List<Danmaku>>(Channel.UNLIMITED)

            launch(Dispatchers.IO) {
                val began = System.currentTimeMillis()
                try {
                    DanmakuWindows()
                        .load(
                            totalMs = durationMs,
                            read = { start, total ->
                                timed.read(seriesId, episodeId, start, total)
                            },
                        ) { start, page, cost ->
                            val rows = page.optJSONArray("items") ?: JSONArray()
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
                            // 每窗一条：耗时接近 0 就是命中缓存，说明这一遍没再打接口。
                            android.util.Log.i(
                                "HGTV",
                                "danmaku start=$start rows=${rows.length()} " +
                                    "have=${items.size} cost=${cost}ms",
                            )
                            // 同一份累积列表按时间排好再发：播放层拿到就能直接排轨。
                            if (added) batches.trySend(items.sortedBy { it.offsetMs }.toList())
                        }
                } finally {
                    android.util.Log.i(
                        "HGTV",
                        "danmaku done have=${items.size} cost=${System.currentTimeMillis() - began}ms",
                    )
                    batches.close()
                }
            }

            for (batch in batches) send(batch)
        }
}
