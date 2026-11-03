// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

/** A checkpoint is a resume position, never proof that all preceding episodes were watched. */
object EpisodeSummary {
    private val totalPattern = Regex("(?:全|共|更新至)\\s*(\\d+)\\s*集")
    private val posterPattern = Regex("(?:全|共|更新至第?)\\s*\\d+\\s*集")

    fun total(badge: String): Int =
        totalPattern.find(badge)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 0

    /** 提取海报角标用的集数文案，例如「全 80 集」「更新至 90 集」；没有集数信息时返回空串。 */
    fun poster(badge: String): String = posterPattern.find(badge)?.value.orEmpty()

    fun label(
        total: Int,
        episodeIndex: Int? = null,
        account: Boolean = false,
        ready: Boolean = true,
        loading: Boolean = false,
        markedWatched: Boolean = false,
        pending: Boolean = false,
        added: Int = 0,
        withCount: Boolean = true,
    ): String {
        if (pending) return if (withCount) "待上线\n暂不可观看" else "待上线，暂不可观看"
        val count = if (total > 0) "共 $total 集" + (if (added > 0) "（+$added）" else "") else "总集数待更新"
        val progress =
            when {
                markedWatched && !account -> "本机已标记看完"
                episodeIndex != null && episodeIndex >= 0 -> "看到第 ${episodeIndex+1} 集"
                !account -> "暂无本机进度"
                loading -> "账户进度读取中"
                !ready -> "账户进度待刷新"
                else -> "近50条无观看记录"
            }
        // 列表卡片的海报角标已经写了「共 N 集」，副标题再写一遍只是占一行高度；
        // 这时只要进度那一行，各页卡片高度也才统一得起来。
        return if (withCount) "$count\n$progress" else progress
    }
}
