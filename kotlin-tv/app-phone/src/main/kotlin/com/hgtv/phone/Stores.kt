// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hgtv.core.ContentRepository
import com.hgtv.core.Detail
import com.hgtv.core.RecentSearch
import com.hgtv.core.Series
import com.hgtv.phone.data.PhoneLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首页分页状态。
 *
 * 接口是同步阻塞的，全部调用包在 [Dispatchers.IO] 里；状态字段是 Compose state，
 * 只在主线程写，所以不需要额外同步。
 */
@Stable
class FeedStore(private val repository: ContentRepository) {
    var items by mutableStateOf<List<Series>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var finished by mutableStateOf(false)
        private set

    private var page = 0
    private var inFlight = false

    suspend fun loadMore() {
        if (inFlight || finished) return
        inFlight = true
        loading = true
        error = null
        try {
            val next = withContext(Dispatchers.IO) { repository.home(page + 1) }
            // 翻页偶尔会吐出上一页已经有的条目，按 id 去重，否则 LazyGrid 的 key 会撞。
            val known = items.mapTo(HashSet()) { it.id }
            items = items + next.items.filter { it.id !in known }
            page += 1
            finished = !next.hasMore
        } catch (problem: Exception) {
            error = problem.message ?: "加载失败"
        } finally {
            loading = false
            inFlight = false
        }
    }

    suspend fun retry() {
        error = null
        loadMore()
    }
}

/** 详情页状态：一次拉齐剧集信息与全集 id 列表。 */
@Stable
class DetailStore(private val repository: ContentRepository) {
    var detail by mutableStateOf<Detail?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var loadedFor: String? = null

    suspend fun load(seriesId: String, force: Boolean = false) {
        if (!force && loadedFor == seriesId) return
        loading = true
        error = null
        try {
            detail = withContext(Dispatchers.IO) { repository.detail(seriesId) }
            loadedFor = seriesId
        } catch (problem: Exception) {
            error = problem.message ?: "剧集详情加载失败"
        } finally {
            loading = false
        }
    }
}

/**
 * 搜索状态：联想词、结果分页、历史记录。
 *
 * 关键词只在 [search] 时定稿；联想每输入一次就换一批，但结果列表不会跟着抖动——
 * 用户点了搜索或点了联想词才算数。
 */
@Stable
class SearchStore(private val repository: ContentRepository, private val library: PhoneLibrary) {
    var query by mutableStateOf("")
        private set
    var items by mutableStateOf<List<Series>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var finished by mutableStateOf(false)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var active by mutableStateOf(false)
        private set

    var history by mutableStateOf<List<RecentSearch>>(emptyList())
        private set

    private var page = 0
    private var inFlight = false

    fun refreshHistory() {
        history = library.searches
    }

    fun clearHistory() {
        library.clearSearches()
        history = library.searches
    }

    suspend fun suggest(text: String) {
        query = text
        if (text.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestions =
            runCatching { withContext(Dispatchers.IO) { repository.searchSuggestions(text) } }
                .getOrDefault(emptyList())
    }

    suspend fun search(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        query = clean
        suggestions = emptyList()
        active = true
        finished = false
        page = 0
        items = emptyList()
        error = null
        library.rememberSearch(clean)
        history = library.searches
        loadMore()
    }

    suspend fun loadMore() {
        if (inFlight || finished || !active) return
        inFlight = true
        loading = true
        error = null
        try {
            val next = withContext(Dispatchers.IO) { repository.search(query, page + 1) }
            val known = items.mapTo(HashSet()) { it.id }
            items = items + next.items.filter { it.id !in known }
            page += 1
            finished = !next.hasMore
        } catch (problem: Exception) {
            error = problem.message ?: "搜索失败"
        } finally {
            loading = false
            inFlight = false
        }
    }

    fun reset() {
        active = false
        items = emptyList()
        query = ""
        suggestions = emptyList()
        finished = false
        page = 0
        error = null
    }
}

