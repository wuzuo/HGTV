// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.media

import com.hgtv.core.Danmaku
import com.hgtv.core.TimedComments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 拉一集的弹幕。
 *
 * 接口是「从某一刻起往后给一段」（每段约 30 秒），要按 `nextMS` 一窗窗往前推才能覆盖整集。
 * 短剧一集通常一两分钟，进片时一次拉完即可；片长未知时按 3 分钟估算，遇到 `nextMS` 不再
 * 前进就停下，不会空转。
 *
 * 失败只返回已经拿到的部分：弹幕是锦上添花，不该让播放页报错。
 */
object DanmakuLoader {
    private const val MAX_PAGES = 10
    private const val ASSUMED_DURATION_MS = 180_000L

    suspend fun load(seriesId: String, episodeId: String, durationMs: Long): List<Danmaku> =
        withContext(Dispatchers.IO) {
            if (!seriesId.matches(Regex("[0-9]{6,30}")) || !episodeId.matches(Regex("[0-9]{6,30}"))) {
                return@withContext emptyList()
            }
            val total = (if (durationMs > 0) durationMs else ASSUMED_DURATION_MS).coerceIn(1_000L, 86_400_000L)
            val timed = TimedComments()
            val seen = HashSet<String>()
            val items = ArrayList<Danmaku>()
            var start = 0L
            repeat(MAX_PAGES) {
                val page =
                    runCatching { timed.read(seriesId, episodeId, start, total) }.getOrNull()
                        ?: return@withContext items
                val rows = page.optJSONArray("items") ?: JSONArray()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val text = row.optString("text").trim()
                    val at = row.optLong("timeMS", -1)
                    if (text.isEmpty() || at < 0) continue
                    if (!seen.add("$at\u0000$text")) continue
                    items += Danmaku(at, text)
                }
                val next = page.optLong("nextMS", total)
                if (next <= start) return@withContext items
                start = next
                if (start >= total) return@withContext items
            }
            items.sortedBy { it.offsetMs }
        }
}
