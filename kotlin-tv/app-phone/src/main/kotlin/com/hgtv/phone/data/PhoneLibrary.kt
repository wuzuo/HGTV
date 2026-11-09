// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hgtv.core.BackupData
import com.hgtv.core.BackupProgress
import com.hgtv.core.BackupSettings
import com.hgtv.core.ContentType
import com.hgtv.core.LibraryBackup
import com.hgtv.core.LibraryIndex
import com.hgtv.core.RecentSearch
import com.hgtv.core.Series
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手机端本机片库：收藏、观看记录、搜索历史。
 *
 * 数据模型与序列化直接用 `:core` 里的 [LibraryIndex] / [LibraryBackup]（TV 端也是这套，
 * 导出文件因此互通），这里只补一层 Android 存储与 Compose 可观察状态。
 *
 * 线程约定：`index` 只在主线程读写；写盘在单线程执行器上串行进行，交给它的是一份
 * 主线程编码好的 JSON 字符串，避免磁盘线程读到写了一半的内存结构。
 */
@Stable
class PhoneLibrary(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("phone-library-v1", Context.MODE_PRIVATE)
    private val writer =
        Executors.newSingleThreadExecutor { task -> Thread(task, "hgtv-phone-library") }
    private val writeLock = Any()
    private var pendingJson: String? = null
    private var writing = false

    private var index: LibraryIndex? = null

    var loaded by mutableStateOf(false)
        private set

    var favorites by mutableStateOf<List<Series>>(emptyList())
        private set

    var favoriteIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var history by mutableStateOf<List<BackupProgress>>(emptyList())
        private set

    var searches by mutableStateOf<List<RecentSearch>>(emptyList())
        private set

    private var watchedIds by mutableStateOf<Set<String>>(emptySet())

    /** 首次进入任意页面时调用一次；重复调用无副作用。 */
    suspend fun load() {
        if (loaded) return
        val data =
            withContext(Dispatchers.IO) {
                val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return@withContext null
                runCatching { LibraryBackup.decode(raw) }
                    .onFailure { Log.w(TAG, "本地片库解析失败，按空库启动", it) }
                    .getOrNull()
            }
        val built =
            LibraryIndex(
                data
                    ?: BackupData(
                        favorites = emptyList(),
                        history = emptyList(),
                        watched = emptySet(),
                        searches = emptyList(),
                        settings = BackupSettings(),
                    )
            )
        index = built
        publish(built)
        loaded = true
    }

    fun isFavorite(id: String) = id in favoriteIds

    fun isWatched(id: String) = id in watchedIds

    /** 这部剧的观看记录；没有看过返回 null。 */
    fun progress(seriesId: String): BackupProgress? =
        history.firstOrNull { it.series.id == seriesId }

    /** 返回 true 表示现在已收藏。 */
    fun toggleFavorite(series: Series): Boolean {
        val current = index ?: return false
        val added = current.toggle(series)
        publish(current)
        persist()
        return added
    }

    /**
     * 播放进度落盘。内容与上一条完全一致时 [LibraryIndex.save] 会拒绝，
     * 这里也就不会产生多余的磁盘写入（暂停时每 5 秒一次的心跳就是靠这个收敛的）。
     */
    fun saveProgress(
        series: Series,
        episodeId: String,
        episodeIndex: Int,
        position: Long,
        duration: Long,
        completed: Boolean,
    ) {
        val current = index ?: return
        val changed =
            current.save(
                BackupProgress(
                    series = series,
                    episodeId = episodeId,
                    episodeIndex = episodeIndex,
                    position = position.coerceAtLeast(0),
                    duration = duration.coerceAtLeast(0),
                    completed = completed,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        if (!changed) return
        publish(current)
        persist()
    }

    fun markWatched(seriesId: String, value: Boolean) {
        val current = index ?: return
        if (!current.setWatched(seriesId, value)) return
        publish(current)
        persist()
    }

    fun removeHistory(seriesId: String) {
        val current = index ?: return
        current.removeHistory(seriesId)
        publish(current)
        persist()
    }

    fun clearHistory() {
        val current = index ?: return
        current.clearHistory()
        publish(current)
        persist()
    }

    fun rememberSearch(query: String) {
        val current = index ?: return
        current.rememberSearch(query, ContentType.SHORT)
        publish(current)
        persist()
    }

    fun clearSearches() {
        val current = index ?: return
        current.clearSearches()
        publish(current)
        persist()
    }

    private fun publish(current: LibraryIndex) {
        favorites = current.favorites()
        favoriteIds = favorites.mapTo(HashSet()) { it.id }
        history = current.history()
        searches = current.searches()
        watchedIds = current.watched()
    }

    /**
     * 立刻把当前状态排进写盘队列。
     *
     * 同一时刻只跑一个写任务；写的过程中又有新快照时，用 dirty 标记让它再跑一轮，
     * 否则连续两次收藏很可能只落盘一次。
     */
    private fun persist() {
        val current = index ?: return
        val json =
            runCatching { LibraryBackup.encode(current.snapshot()) }
                .onFailure { Log.w(TAG, "本地片库编码失败", it) }
                .getOrNull() ?: return
        synchronized(writeLock) {
            pendingJson = json
            if (writing) return
            writing = true
        }
        writer.execute {
            while (true) {
                val next =
                    synchronized(writeLock) {
                        val value = pendingJson
                        pendingJson = null
                        if (value == null) writing = false
                        value
                    } ?: break
                val ok = prefs.edit().putString(KEY_SNAPSHOT, next).commit()
                if (!ok) Log.w(TAG, "本地片库写入失败，等待下一次变更重试")
            }
        }
    }

    private companion object {
        const val TAG = "HgtvPhoneLibrary"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
