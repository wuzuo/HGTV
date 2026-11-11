// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.io.IOException
import org.json.JSONObject

/**
 * 一部「相关作品」：同系列的其他季，或同题材的其他剧。
 *
 * [order] 是季号（`第2季` → 2，取不到为 0）。跨季连播靠它排序：接口给的顺序不保证，
 * 而「下一季」必须是季号刚好大 1 的那一部。
 */
data class RelatedWork(
    val series: Series,
    val season: String,
    val upcoming: Boolean,
    val order: Int = 0,
) {
    val label
        get() =
            listOf(season, if (upcoming) "待上线 · 预约" else "已上线")
                .filter { it.isNotBlank() }
                .joinToString(" · ")

    /** 能直接播：已上线、有集数。 */
    val playable get() = !upcoming
}

/**
 * Reading plan's related-work cell, verified with public season metadata. Only short-drama types
 * are actionable; novels/audio are intentionally excluded.
 */
object RelatedWorks {
    /** 相关作品列表，不含当前这一部。 */
    fun parse(raw: String, currentId: String): List<RelatedWork> =
        all(raw).filter { it.series.id != currentId }

    /**
     * 含当前这一部的完整列表，**保持接口原始顺序**。
     *
     * 跨季连播需要知道「我是第几部」：当前剧在列表里的位置决定下一季是谁。只留别人的话，
     * 站在第 3 季上根本分不清该去第 2 季还是第 4 季。
     */
    fun all(raw: String): List<RelatedWork> {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) throw IOException("相关作品接口暂不可用")
        val cells = root.optJSONArray("data") ?: throw IOException("相关作品数据格式已变化")
        return cells
            .objects()
            .filter { it.optInt("show_type") == 547 || it.str("cell_name") == "相关作品" }
            .flatMap { cell -> cell.optJSONArray("video_data")?.objects().orEmpty() }
            .mapNotNull { row ->
                val id = row.str("series_id")
                val title = row.str("title")
                val type = row.optInt("content_type")
                // 同一个 cell 里混着「第 N 季」与「同 IP」两类，靠 tag 区分而不是 content_type：
                // 实测第 1~4 季就是 content_type=1，只认 1004 会把真正的季全丢掉，只剩同题材。
                if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank() || type !in SEASON_TYPES)
                    return@mapNotNull null
                val upcoming = type == 1007
                if (!upcoming && (row.optBoolean("disable_play") || row.optInt("episode_cnt") <= 0))
                    return@mapNotNull null
                val season =
                    row.optJSONObject("tag_info")?.str("text").orEmpty().ifBlank {
                        row.optInt("season_index").takeIf { it > 0 }?.let { "第${it}季" }.orEmpty()
                    }
                RelatedWork(
                    Series(
                        id,
                        title,
                        cleanUrl(row.str("cover")),
                        row.str("video_desc"),
                        if (upcoming) "待上线" else "全 ${row.optInt("episode_cnt")} 集",
                    ),
                    season,
                    upcoming,
                    // 季号**只认 tag**：`同IP` 里也有「桃运保镖第二季」这种标题带数字的剧，
                    // 从标题兜底会把别的剧算进本剧的季序列。
                    seasonNumber(season),
                )
            }
            .distinctBy { it.series.id }
            .take(30)
    }

    /**
     * 从剧名解析季号（`…第五部` → 5）；不是分季剧返回 0。
     *
     * 当前正在播的那一季**不在**相关作品列表里（接口只给「其他」），所以播放页要靠剧名
     * 定位自己站在第几季，再去找「季号刚好更大的下一部」。
     */
    fun seasonOrderOf(title: String): Int = seasonNumber(title)

    /**
     * 站在 [title] 这一季上，下一季是谁。
     *
     * 剧名里没有「第X季/部」的按第 1 季算：接口只给「其他季」，第一部本来就没有序号，
     * 按 0 算会让第一部永远进不到第二部（实测《十八岁太奶奶驾到》第一部标题无序号）。
     * 列表里一个带序号的季都没有时直接返回 null，免得单季剧被「同IP」那一堆带偏。
     */
    fun nextSeasonOf(rows: List<RelatedWork>, title: String): RelatedWork? {
        val numbered = rows.filter { it.playable && it.order > 0 }
        if (numbered.isEmpty()) return null
        val current = seasonOrderOf(title).takeIf { it > 0 } ?: 1
        return numbered.filter { it.order > current }.minByOrNull { it.order }
    }

    /** 「相关作品」里可能出现的作品类型：1=剧场短剧、1002/1004=App 内短剧、1007=待上线。 */
    private val SEASON_TYPES = setOf(1, 1002, 1004, 1007)

    /**
     * `第3季` / `第三部` 里的季号；认不出返回 0。
     *
     * 先只认「第 X 季/部」这种带前缀的写法，再退回纯数字：直接抽数字的话，
     * 「18 岁太奶在线训孙」会被读成第 18 季。
     */
    private fun seasonNumber(label: String): Int {
        val marker = Regex("第\\s*([0-9]{1,2}|[零一二三四五六七八九十]{1,2})\\s*[季部]").find(label)
        if (marker != null) {
            val digits = marker.groupValues[1]
            digits.toIntOrNull()?.let {
                return it
            }
            val chinese = "零一二三四五六七八九十"
            val single = digits.singleOrNull()
            if (single != null) return chinese.indexOf(single).coerceAtLeast(0)
        }
        return Regex("(?:^|[^0-9])([0-9]{1,2})\\s*[季部]").find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
    }
}
