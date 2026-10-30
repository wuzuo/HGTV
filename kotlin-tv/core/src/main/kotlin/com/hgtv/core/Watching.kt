// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import org.json.JSONObject

object PlaybackQuality {
    val options = listOf(480, 720, 1080)

    fun normalize(value: Int) = value.takeIf { it in options } ?: 1080
}

/** One automatic recovery budget per episode; only an explicit user retry resets it. */
class RecoveryBudget {
    var attempts = 0
        private set

    val remaining
        get() = attempts < 3

    val delayMillis
        get() = listOf(1000L, 2500L, 5000L).getOrNull(attempts)

    fun consume(): Boolean {
        if (!remaining) return false
        attempts++
        return true
    }

    fun reset() {
        attempts = 0
    }
}

data class ResumeTarget(val index: Int, val position: Long)

object ResumePlayback {
    // 生成本次播放的临时进度，不写入本机历史。
    // 云端与官网的分集 ID 可能不同：先匹配 ID，无法匹配时才使用有效云端集号。
    fun progress(
        detail: Detail,
        local: BackupProgress?,
        cloud: CloudProgress?,
        preferCloud: Boolean = false,
    ): BackupProgress? {
        val device = local?.takeIf { it.series.id == detail.series.id }
        val remote =
            cloud
                ?.takeIf { it.series.id == detail.series.id }
                ?.let {
                    val index =
                        detail.episodes.indexOf(it.episodeId).takeIf { n -> n >= 0 }
                            ?: it.episodeIndex.takeIf { n -> n in detail.episodes.indices }
                            ?: return@let null
                    BackupProgress(
                        detail.series,
                        detail.episodes[index],
                        index,
                        it.position.coerceAtLeast(0),
                        it.duration.coerceAtLeast(0),
                        false,
                        it.updatedAt,
                    )
                }
        // 明确点击账户历史时采用该条云端记录；普通详情入口选较新的记录，同时间优先本机。
        return if (preferCloud && remote != null) remote
        else if (remote != null && (device == null || remote.updatedAt > device.updatedAt)) remote
        else device
    }

    fun target(
        episodes: List<String>,
        episodeId: String?,
        position: Long,
        completed: Boolean,
    ): ResumeTarget {
        require(episodes.isNotEmpty())
        val index = episodes.indexOf(episodeId)
        if (index < 0) return ResumeTarget(0, 0)
        return if (completed) ResumeTarget((index + 1).coerceAtMost(episodes.lastIndex), 0)
        else ResumeTarget(index, position.coerceAtLeast(0))
    }
}

/** Seen count is a high-water mark, so temporary catalog shrinkage is not a new update. */
data class FavoriteUpdate(val total: Int, val seen: Int, val checkedAt: Long) {
    val added
        get() = (total - seen).coerceAtLeast(0)

    fun observe(count: Int, now: Long, acknowledge: Boolean = false): FavoriteUpdate {
        require(count > 0)
        if (now < checkedAt) return this
        return FavoriteUpdate(count, if (acknowledge) maxOf(seen, count) else seen, now)
    }

    fun json() = JSONObject().put("total", total).put("seen", seen).put("checkedAt", checkedAt)

    companion object {
        fun first(count: Int, now: Long): FavoriteUpdate {
            require(count > 0)
            return FavoriteUpdate(count, count, now)
        }

        fun decode(value: JSONObject?) =
            runCatching {
                    require(value != null)
                    val total = value.getInt("total")
                    val seen = value.getInt("seen")
                    val time = value.getLong("checkedAt")
                    require(total > 0 && seen > 0 && time >= 0)
                    FavoriteUpdate(total, seen, time)
                }
                .getOrNull()
    }
}
