// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DanmakuWindowsTest {
    private fun page(from: Long, next: Long, count: Int = 2): JSONObject {
        val items = JSONArray()
        repeat(count) {
            items.put(
                JSONObject()
                    .put("id", "c$from-$it")
                    .put("text", "t$from-$it")
                    .put("timeMS", from + it)
            )
        }
        return JSONObject().put("startMS", from).put("nextMS", next).put("items", items)
    }

    private fun collecting(): Pair<MutableList<Long>, (Long, JSONObject, Long) -> Unit> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Long>())
        return seen to { start, _, _ -> seen += start }
    }

    @Test
    fun loadsEveryWindowAndReportsTheEpisodeEnd() {
        val (pages, onPage) = collecting()
        val covered =
            DanmakuWindows(concurrency = 4)
                .load(
                    totalMs = 150_000,
                    read = { start, _ -> page(start, start + 30_000) },
                    onPage = onPage,
                )
        assertEquals(setOf(0L, 30_000L, 60_000L, 90_000L, 120_000L), pages.toSet())
        assertEquals(5, pages.size)
        assertEquals(150_000L, covered)
    }

    @Test
    fun stopsAtTheLookaheadLimit() {
        val (pages, onPage) = collecting()
        DanmakuWindows()
            .load(
                totalMs = 600_000,
                untilMs = 90_000,
                read = { start, _ -> page(start, start + 30_000) },
                onPage = onPage,
            )
        assertEquals(setOf(0L, 30_000L, 60_000L), pages.toSet())
    }

    @Test
    fun alignsTheStartDownToTheWindowBoundary() {
        val (pages, onPage) = collecting()
        DanmakuWindows()
            .load(
                totalMs = 120_000,
                fromMs = 45_000,
                read = { start, _ ->
                    // 起点必须是窗口的整数倍，否则服务端会把时间窗切歪。
                    assertEquals(0L, start % DanmakuWindows.WINDOW_MS)
                    page(start, start + 30_000)
                },
                onPage = onPage,
            )
        assertEquals(30_000L, pages.first())
    }

    @Test
    fun followsTheWindowSizeTheServerReports() {
        // 服务端说下一窗在 45 秒处：后续窗口按它给的步长推，不是硬编码 30 秒。
        val (pages, onPage) = collecting()
        DanmakuWindows()
            .load(
                totalMs = 90_000,
                read = { start, _ -> page(start, start + 45_000) },
                onPage = onPage,
            )
        assertEquals(setOf(0L, 45_000L), pages.toSet())
    }

    @Test
    fun keepsCoverageShortWhenAWindowFails() {
        val (pages, onPage) = collecting()
        val covered =
            DanmakuWindows(concurrency = 3)
                .load(
                    totalMs = 150_000,
                    read = { start, _ ->
                        if (start == 60_000L) throw java.io.IOException("boom")
                        page(start, start + 30_000)
                    },
                    onPage = onPage,
                )
        // 60 秒那窗挂了：游标停在它前面，下一轮从这儿重来，不会因为别的窗先成功就跳过缺口。
        assertEquals(60_000L, covered)
        assertFalse(pages.contains(60_000L))
        assertEquals(setOf(0L, 30_000L, 90_000L, 120_000L), pages.toSet())
    }

    @Test
    fun assumesALengthWhenTheEpisodeDurationIsUnknown() {
        val (pages, onPage) = collecting()
        DanmakuWindows()
            .load(
                totalMs = 0,
                read = { start, total ->
                    // 时长未知时按估算长度问，服务端仍会按窗口给数据。
                    assertEquals(DanmakuWindows.ASSUMED_DURATION_MS, total)
                    page(start, start + 30_000)
                },
                onPage = onPage,
            )
        assertEquals(6, pages.size)
    }
}
