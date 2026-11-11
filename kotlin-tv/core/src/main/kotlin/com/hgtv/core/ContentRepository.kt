// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

// 内容数据访问入口：封装搜索、详情与播放信息请求；界面层通过此处获取内容数据。

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 站点 CDN 在重定向时下发 ttwid 并拦截无 Cookie 的后续请求，内存 Cookie 罐使其自动流转。 */
internal class MemoryCookies : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    // RFC 语义：同名 + 同域 + 同路径的新 Cookie 替换旧值，而不是叠加。
    private fun List<Cookie>.replacedBy(cookies: List<Cookie>) =
        filter { kept ->
                cookies.none {
                    it.name == kept.name && it.domain == kept.domain && it.path == kept.path
                }
            }
            .toMutableList()
            .also { it += cookies }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        store.compute(url.host) { _, kept -> kept.orEmpty().replacedBy(cookies) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host].orEmpty().filter { it.expiresAt > now }
    }
}

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()

internal fun cleanUrl(s: String) =
    s.replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val badge: String = "",
    val tags: String = "",
    /**
     * 接口下发的热度文案，形如「4597万热度」。
     *
     * 列表卡片上集数已经由海报角标承担，副标题改用它，避免同一条目重复写两遍集数。
     * 取不到时为空串，调用方自行回退到 [badge]。
     */
    val heat: String = "",
) {
    fun toJson() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("cover", cover)
            .put("description", description)
            .put("badge", badge)
            .put("tags", tags)
            .put("heat", heat)

    companion object {
        fun fromJson(o: JSONObject) =
            Series(
                o.str("id"),
                o.str("title"),
                o.str("cover"),
                o.str("description"),
                o.str("badge"),
                o.str("tags"),
                o.str("heat"),
            )
    }
}

data class Detail(val series: Series, val episodes: List<String>)

data class RankPosition(val rank: Int?, val heat: String, val metrics: String = "")

data class ComicRanking(
    val positions: Map<String, RankPosition>,
    val totalPages: Int,
    val updatedText: String,
)

data class TrialHint(val episodeId: String, val episodeCount: Int)

data class CatalogPage(
    val items: List<Series>,
    val hasMore: Boolean,
    val ranking: ComicRanking? = null,
    val discovery: DiscoveryInfo? = null,
    val trialHints: Map<String, TrialHint> = emptyMap(),
)

data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)

/** One playable rendition; width/height are the coded dimensions when the API reports them. */
data class Rendition(
    val quality: Int,
    val codec: String,
    val width: Int?,
    val height: Int?,
    val url: String,
    val key: ByteArray?,
) {
    fun toStreamInfo() = StreamInfo(url, key, if (quality > 0) "${quality}P" else "自动")
}

/**
 * All playable renditions of one episode. Capability-first selection: TV boxes commonly cap AVC
 * decoders at 1920x1088 with width/height checked independently, rejecting portrait sizes.
 */
class StreamLadder(val renditions: List<Rendition>) {
    // Ascending order: a greater element is the better tier (higher quality, h264 over others).
    // maxWithOrNull picks the best tier; minWithOrNull yields the closest-below fallback.
    private val better =
        compareBy<Rendition> { it.quality }.thenBy { if (it.codec in AVC_LABELS) 1 else 0 }

    fun select(maxQuality: Int, supported: (Rendition) -> Boolean = { true }): Rendition? {
        val pool = renditions.filter(supported).ifEmpty { renditions }
        return pool.filter { it.quality in 1..maxQuality }.maxWithOrNull(better)
            ?: pool.filter { it.quality <= 0 }.maxWithOrNull(better)
            ?: pool.minWithOrNull(better)
    }

    /** select() first, then every lower or equal tier best-first for verified downgrade. */
    fun playbackOrder(
        maxQuality: Int,
        supported: (Rendition) -> Boolean = { true },
    ): List<Rendition> {
        val pick = select(maxQuality, supported) ?: return emptyList()
        val alternates =
            renditions
                .filter { it !== pick && it.quality <= pick.quality }
                .sortedWith(better.reversed())
        return listOf(pick) + alternates
    }

    companion object {
        private val AVC_LABELS = setOf("h264", "avc", "avc1", "avc3")
    }
}

class ContentHttpException(val statusCode: Int) : IOException("内容请求失败（HTTP $statusCode）")

class SearchSessionExpiredException : IOException("搜索结果已过期，请从第 1 页重新搜索")

/**
 * 播放接口没给出可用的播放模型。
 *
 * 三种原因在业务上都是「拿不到」，但排查时完全是三件事：body 为空（服务端拒绝）、
 * body 不是 JSON（被网关拦下、回了 HTML）、业务错误码非 0（额度/风控/参数不认）。
 * 所以把具体原因挂在 message 上——给用户看的文案另由 [PlaybackSources] 统一映射，
 * 这里的信息只会出现在日志和诊断输出里。
 */
class EmptyPlaybackResponseException(detail: String = "") :
    IOException("播放接口暂时返回空响应，请稍后重试" + if (detail.isBlank()) "" else "（$detail）")

/**
 * 整集只有本机解不了的编码（BVC2/H.266）时抛出。
 *
 * 和「拿不到播放信息」分开：这是确定性结论，重试、换清晰度都不会变，界面应该直接让用户
 * 换一集；混在 [IOException] 里只会得到一句「稍后再试」，让人对着同一集反复重试。
 */
class UnsupportedCodecException(
    val codecs: List<String>,
    /**
     * 整集唯一可用的 BVC2 档位（取最高清那条），供有软解能力的端做兜底；
     * 桌面端拿它起 FFmpeg 软解转码，取不到时为 null。
     */
    val fallback: StreamInfo? = null,
) : IOException("这一集只有 BVC2（H.266）编码，当前设备没有对应解码器，请换一集")

/** 本端（ExoPlayer + MediaCodec）解不了的编码：字节自研 BVC2，没有公开的系统解码器。 */
private val UNPLAYABLE_CODECS = setOf("bytevc2", "bvc2")

class ContentRepository
@JvmOverloads
constructor(
    val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .cookieJar(MemoryCookies())
            .build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession(),
) {
    private val signer = Signer()

    // 榜单页偶发被 CDN 软拦截（空响应或挂起到超时）：收紧超时并单次重试，
    // 让整榜失败在最坏约 15 秒内暴露，而不是分钟级的全局超时。
    private val rankHttp: OkHttpClient by lazy {
        http
            .newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val site = "https://hongguoduanju.com"
    private val suggestionWebId = UUID.randomUUID().toString()

    private data class SearchCursor(
        val searchId: String,
        val passback: String,
        val offset: Int?,
        val updatedAt: Long,
    )

    private val searchCursors = linkedMapOf<Triple<ContentType, String, Int>, SearchCursor>()

    private data class UpcomingPlaybackModel(val entry: JSONObject, val savedAt: Long)

    private val upcomingPlaybackModels = linkedMapOf<String, UpcomingPlaybackModel>()

    /** 最近一次打开的剧集号，只用于给播放请求体补 "1001"。 */
    @Volatile private var lastSeriesId: String? = null

    /**
     * 已确认「默认取流只给 BVC2、必须换注册身份重取」的集号。
     *
     * 降级要重发一次请求（还要注册一台新设备身份），同一集失败重试时没必要再白跑一趟。
     * 只记最近 32 集，够覆盖一轮连播，也不会随使用时长无界增长。
     */
    private val bvc2FallbackEpisodes =
        object : LinkedHashMap<String, Unit>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) =
                size > 32
        }

    /** 当前**注册**身份是否已经用掉播放接口的那一次放行机会（本地真实身份不受此限）。 */
    private var playbackIdentitySpent = false

    // 构造并执行内容请求，统一检查 HTTP 状态和响应大小；仅按规则重试临时故障。
    private fun text(
        url: String,
        signed: Signer.Request? = null,
        retryTransient: Boolean = false,
        client: OkHttpClient = http,
        retryEmptyPlayback: Boolean = false,
        // 播放接口空响应时最多读几次（含首次）。换设备身份后的那次重试用更小的预算，
        // 免得「身份不对」这种必然失败的情形把用户拦在加载动画里五六秒。
        emptyPlaybackReads: Int = 3,
    ): String {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k, v) -> request.header(k, v) }
        signed?.body?.let {
            request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        fun fetch(): String {
            val base = request.build()
            // 重试时换一个请求时间戳；带了 x-ss-req-ticket 的请求必须让头与查询参数保持一致。
            val next =
                if (retryEmptyPlayback) {
                    val fresh = clock().toString()
                    base
                        .newBuilder()
                        .url(base.url.newBuilder().setQueryParameter("_rticket", fresh).build())
                        .header("x-ss-req-ticket", fresh)
                        .build()
                } else base
            return RequestScope.execute(client.newCall(next)) { response ->
                if (!response.isSuccessful) throw ContentHttpException(response.code)
                val body = response.body ?: throw IOException("内容响应为空")
                if (body.contentLength() > 16 * 1024 * 1024) throw IOException("内容响应过大")
                val input = body.byteStream()
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= 16 * 1024 * 1024) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                val bytes = out.toByteArray()
                if (bytes.size > 16 * 1024 * 1024) throw IOException("内容响应过大")
                val content = String(bytes, Charsets.UTF_8)
                if (retryEmptyPlayback && content.isBlank()) throw EmptyPlaybackResponseException()
                content
            }
        }
        var retries = 0
        while (true) {
            try {
                return fetch()
            } catch (e: IOException) {
                val temporary =
                    e is EmptyPlaybackResponseException ||
                        e is java.net.SocketTimeoutException ||
                        e is java.net.SocketException ||
                        e is java.net.UnknownHostException ||
                        (e is ContentHttpException && e.statusCode in setOf(500, 502, 503, 504))
                val delay =
                    if (e is EmptyPlaybackResponseException) {
                        if (retries >= emptyPlaybackReads - 1) null
                        else listOf(700L, 1500L).getOrNull(retries)
                    } else if (retries == 0) 700L else null
                if (
                    !retryTransient ||
                        !temporary ||
                        delay == null ||
                        Thread.currentThread().isInterrupted
                )
                    throw e
                // 空响应最多三次读取；连接故障仍只重试一次，不改变搜索、榜单的原有预算。
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("请求已取消")
                }
                retries++
            }
        }
    }

    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")

    private fun card(item: JSONObject): Series {
        val o = item.optJSONObject("video_data") ?: item
        val count =
            o.optInt(
                "episode_cnt",
                o.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0,
            )
        val tags = o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(
            o.str("series_id"),
            o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },
            cleanUrl(o.str("series_cover")),
            o.str("series_intro"),
            o.str("episode_right_text").ifEmpty { if (count > 0) "全 ${count} 集" else "" },
            (0 until minOf(tags.length(), 5)).joinToString(" · ") {
                tags.optJSONObject(it)?.str("name") ?: tags.optString(it)
            },
            heatOf(o),
        )
    }

    /** 接口把热度包在 `hot_score_data` 里，`text` 是已经格式化好的文案；缺失时返回空串。 */
    private fun heatOf(row: JSONObject): String =
        row.optJSONObject("hot_score_data")?.str("text").orEmpty()

    private fun valid(rows: List<Series>) =
        rows
            .filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }
            .distinctBy { it.id }

    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"), page)
    }

    fun rankPage(board: RankBoard, page: Int = 1): CatalogPage {
        require(page in 1..100)
        // 榜单走收紧超时的专用客户端并重试一次瞬时失败；ttwid 由内存 Cookie 罐自动携带。
        val body =
            text("$site/rank/${board.route}?page=$page", retryTransient = true, client = rankHttp)
        return ComicRank.parse(body, page, board)
    }

    fun rankSnapshot(board: RankBoard): RankSnapshot {
        val first = rankPage(board)
        val meta = first.ranking ?: throw IOException("榜单信息缺失")
        // Public boards currently expose 100 entries across five pages. Never claim
        // a complete board if the upstream size changes beyond this bounded request.
        if (meta.totalPages > 5) throw IOException("榜单页数已变化，请稍后重试")
        val rows = first.items.toMutableList()
        val positions = meta.positions.toMutableMap()
        for (page in 2..meta.totalPages) {
            val next = rankPage(board, page)
            if (next.ranking?.totalPages != meta.totalPages) throw IOException("榜单正在更新，请刷新重试")
            rows += next.items
            positions.putAll(next.ranking.positions)
        }
        return RankSnapshot(board, rows.distinctBy { it.id }, meta.copy(positions = positions))
    }

    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if (type == ContentType.COMIC) return comicRanking(page)
        val data = router("$site/category?tab=1&sort_type=1" + if (page > 1) "&page=$page" else "")
        val section =
            data.optJSONObject("category_page")
                ?: data.optJSONObject("category_$")
                ?: throw IOException("首页数据结构已变化")
        val rows =
            section.optJSONArray("recommendList")
                ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList")
                ?: throw IOException("首页数据暂不可用")
        val items = valid(rows.objects().map(::card))
        val lastPage = section.optJSONObject("pagination")?.optInt("totalPages", 100) ?: 100
        return CatalogPage(items, items.isNotEmpty() && page < minOf(lastPage, 100))
    }

    fun filteredDiscovery(
        selection: DiscoverySelection,
        cursor: DiscoveryCursor? = null,
    ): FilteredDiscoveryPage {
        val values = VendorConstants.device.toMutableMap()
        for (key in
            listOf(
                "version_code",
                "manifest_version_code",
                "update_version_code",
                "pv_player",
            )) values[key] = "73532"
        values["version_name"] = "7.3.5.32"
        val signed =
            signer.sign(
                "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",
                values,
                selection.request(cursor).toString().toByteArray(Charsets.UTF_8),
            )
        return FilteredDiscoveryParser.parse(
            text(signed.url, signed, retryTransient = true),
            cursor,
        )
    }

    fun discover(category: DramaCategory, topic: String = "", page: Int = 1): CatalogPage {
        require(
            page in 1..100 && (topic.isEmpty() || topic.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
        )
        val path = "/category/${category.route}" + if (topic.isBlank()) "" else "/$topic"
        val data = router("$site$path?page=$page")
        val section =
            data.optJSONObject("category_$")
                ?: data.optJSONObject("category_page")
                ?: throw IOException("分类找剧数据已变化")
        val info = DiscoveryParser.info(section, category, topic, page)
        val rows = section.optJSONArray("recommendList") ?: throw IOException("分类剧集列表缺失")
        val items = valid(rows.objects().map(::card))
        if (rows.length() > 0 && items.isEmpty()) throw IOException("分类剧集格式已变化")
        return CatalogPage(items, items.isNotEmpty() && page < info.totalPages, discovery = info)
    }

    // 按内容类型执行关键词搜索；分页状态由对应的搜索实现维护。
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length <= 80 && page in 1..100)
        // The website search does not constrain content type. Never use it for comics.
        if (type == ContentType.COMIC) return appSearch(keyword, page, type)
        try {
            val result = appSearch(keyword, page, type)
            if (result.items.isNotEmpty() || !result.hasMore) return result
        } catch (e: Exception) {
            if (e is SearchSessionExpiredException) throw e
            /* Website fallback for short dramas only. */ }
        return searchAll(keyword, page)
    }

    /** Anonymous first-party website suggestions; pinyin matching is provided by the server. */
    // 获取服务端搜索联想，供遥控搜索输入框展示候选词。
    fun searchSuggestions(keyword: String): List<String> {
        val clean = keyword.trim()
        if (clean.isEmpty()) return emptyList()
        require(clean.length <= 80)
        val raw =
            text(
                "$site/incent_resource/suggestion?app_id=8662&web_id=$suggestionWebId&query=${Signer.encode(clean)}&count=8"
            )
        val rows = JSONObject(raw).optJSONArray("suggest_list") ?: throw IOException("搜索联想暂不可用")
        return rows
            .objects()
            .map {
                it.str("name").ifBlank {
                    it.optJSONArray("display_words")?.optString(0).orEmpty().trim()
                }
            }
            .filter { it.isNotBlank() && it.length <= 80 }
            .distinct()
            .take(8)
    }

    /** The public website now loads cross-category results through its JSON endpoint. */
    fun searchAll(keyword: String, page: Int = 1): CatalogPage {
        require(keyword.isNotBlank() && keyword.length <= 80 && page in 1..100)
        val raw =
            text(
                "$site/api/search/result?query=${Signer.encode(keyword)}&page=$page",
                retryTransient = true,
            )
        val section =
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw IOException("搜索响应格式已变化，请稍后重试")
            }
        if (section.has("pageNum") && section.optInt("pageNum") != page)
            throw IOException("搜索页码不匹配，请重新搜索")
        val rows = section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items = valid(rows.objects().map(::card))
        val more =
            when {
                section.has("hasMore") -> section.optBoolean("hasMore")
                section.optInt("totalPages") > 0 -> page < section.optInt("totalPages")
                else -> page < 100
            }
        return CatalogPage(items, items.isNotEmpty() && page < 100 && more)
    }

    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values = VendorConstants.device.toMutableMap()
        for (k in
            listOf(
                "version_code",
                "manifest_version_code",
                "update_version_code",
                "pv_player",
            )) values[k] = "72232"
        values["version_name"] = "7.2.2.32"
        values.putAll(
            linkedMapOf(
                "query" to keyword,
                "count" to "20",
                "offset" to ((page - 1) * 20).toString(),
                "tab_type" to type.searchTab.toString(),
                "bookshelf_search_plan" to "4",
                "use_correct" to "true",
                "user_is_login" to "0",
            )
        )
        val cursor =
            synchronized(searchCursors) {
                val now = clock()
                searchCursors.entries.removeAll {
                    now - it.value.updatedAt > 300000 ||
                        (page == 1 && it.key.first == type && it.key.second == keyword)
                }
                searchCursors[Triple(type, keyword, page - 1)]
            }
        if (type == ContentType.COMIC && page > 1 && cursor == null)
            throw SearchSessionExpiredException()
        cursor?.let {
            if (it.searchId.isNotBlank()) values["search_id"] = it.searchId
            if (it.passback.isNotBlank()) values["passback"] = it.passback
            it.offset
                ?.takeIf { offset -> offset >= 0 }
                ?.let { offset -> values["offset"] = offset.toString() }
        }
        val signed =
            signer.sign(
                "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",
                values,
            )
        val data = JSONObject(text(signed.url, signed))
        if (data.optInt("code", -1) != 0) throw IOException("搜索接口暂不可用")
        val tabs =
            data.optJSONArray("search_tabs")
                ?: data.optJSONObject("data")?.optJSONArray("search_tabs")
                ?: throw IOException("搜索格式异常")
        val tab =
            tabs.objects().firstOrNull { it.optInt("tab_type") == type.searchTab }
                ?: throw IOException("${type.label}搜索暂不可用")
        val list = mutableListOf<Series>()
        for (cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells = mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }
                .getOrNull()
                ?.let { cells.addAll(it) }
            for (row in cells) {
                val video = row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id =
                    video.str("series_id").ifEmpty {
                        row.str("book_id").ifEmpty { row.str("search_result_id") }
                    }
                val title =
                    row.optJSONObject("search_high_light")
                        ?.optJSONObject("title")
                        ?.str("text")
                        .orEmpty()
                        .ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count = video.optInt("episode_cnt")
                list +=
                    Series(
                        id,
                        title,
                        cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),
                        video.str("video_desc"),
                        if (count > 0) "全 $count 集" else video.str("rec_text"),
                        video.str("sub_title"),
                        heatOf(video),
                    )
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type, keyword, page)] =
                SearchCursor(
                    tab.str("search_id"),
                    tab.str("passback"),
                    tab.str("next_offset").toIntOrNull(),
                    clock(),
                )
            while (searchCursors.size > 100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list), tab.optBoolean("has_more", false) && page < 100)
    }

    private val relatedCache = linkedMapOf<String, List<RelatedWork>>()

    /** 相关作品，不含当前这一部。 */
    fun related(id: String, force: Boolean = false): List<RelatedWork> {
        require(id.matches(Regex("[0-9]{1,30}")))
        return relatedAll(id, force).filter { it.series.id != id }
    }

    /**
     * 相关作品，**含当前这一部**，保持接口顺序。
     *
     * 跨季连播要判断「我在第几季」：只拿别人的列表，站在第 3 季上分不清下一季是 2 还是 4。
     */
    fun seasons(id: String, force: Boolean = false): List<RelatedWork> {
        require(id.matches(Regex("[0-9]{1,30}")))
        return relatedAll(id, force)
    }

    /**
     * 相关作品接口的请求参数。
     *
     * 参数少一个服务端就会回不同的 cell（实测去掉版本号后，「第1季」那类 cell 直接消失），
     * 所以探针和正式请求必须共用这一份。
     */
    private fun relatedValues(id: String) =
        VendorConstants.device.toMutableMap().apply {
            for (key in
                listOf(
                    "version_code",
                    "manifest_version_code",
                    "update_version_code",
                    "pv_player",
                )) put(key, "72232")
            put("version_name", "7.2.2.32")
            putAll(
                mapOf(
                    "scene" to "10",
                    "from" to "detail_page_more_related",
                    "book_id" to id,
                    "new_search_middle_page" to "false",
                    "bookstore_tab_type" to "0",
                    "query_history_removed" to "false",
                    "user_is_login" to "0",
                    "bookstore_tab" to "0",
                    "search_middle_page_version" to "0",
                    "pad_column_detail" to "0",
                    "hot_word_exchange" to "false",
                    "device_level" to "3",
                    "limit" to "0",
                    "offset" to "0",
                    "is_horizontal_screen" to "false",
                    "reader_single_col_from_ip_strategy" to "false",
                )
            )
        }

    private fun relatedAll(id: String, force: Boolean): List<RelatedWork> {
        if (!force)
            synchronized(relatedCache) {
                relatedCache[id]?.let {
                    return it
                }
            }
        var failure: Exception? = null
        val values = relatedValues(id)
        for (host in
            listOf(
                "api5-normal-sinfonlinec.fqnovel.com",
                "api5-normal-hl.fqnovel.com",
                "api5-normal-lf.fqnovel.com",
            )) {
            try {
                val request = signer.sign("https://$host/reading/bookapi/plan/v:version/", values)
                val raw = text(request.url, request)
                if (raw.isBlank()) {
                    failure = IOException("相关作品接口暂无响应")
                    continue
                }
                val rows = RelatedWorks.all(raw)
                synchronized(relatedCache) {
                    if (relatedCache.size >= 8) relatedCache.remove(relatedCache.keys.first())
                    relatedCache[id] = rows
                }
                return rows
            } catch (problem: Exception) {
                if (Thread.currentThread().isInterrupted) throw problem
                failure = problem
            }
        }
        throw IOException("相关作品暂时无法加载，请重试", failure)
    }

    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        // 官方播放请求体里 mixed_video_id_map 除了 "1004"（分集）还带一个 "1001"（剧集 id），
        // 只在 player 场景出现、preload 没有。取流链路拿不到剧集号，这里顺手记下最近一次的。
        lastSeriesId = id
        val o =
            router("$site/detail?series_id=$id")
                .optJSONObject("detail_page")
                ?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw = o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes = (0 until raw.length()).map { raw.getString(it) }
        if (
            episodes.isEmpty() ||
                episodes.any { !it.matches(Regex("[0-9]{1,30}")) } ||
                episodes.distinct().size != episodes.size
        )
            throw IOException("分集数据异常")
        return Detail(card(o).copy(id = id), episodes)
    }

    fun stream(
        id: String,
        maxQuality: Int = 1080,
        supported: (Rendition) -> Boolean = { true },
    ): StreamInfo {
        val selected =
            streamLadder(id).select(maxQuality, supported)
                ?: throw IOException("这一集没有这台设备能播的清晰度，换个清晰度或稍后再试")
        val info = selected.toStreamInfo()
        return if (selected.quality > maxQuality) info.copy(quality = info.quality + "（兼容资源）")
        else info
    }

    /**
     * 拉一次播放模型。
     *
     * 播放接口和别的接口都不一样，实测有两条硬性要求（详见 [playbackResponse]）：完整签名、
     * 签名器内部的设备信息与 query 同源。另外新注册的设备身份基本只有一次放行额度，靠
     * [PlaybackDeviceSession] 换身份或改用本地真实身份来绕。这里只负责按这些要求拼请求体。
     */
    private fun playbackModel(
        ids: List<String>,
        id: String,
        deviceLevel: Int = PREFERRED_DEVICE_LEVEL,
        forceRegistered: Boolean = false,
    ): JSONObject {
        // 与红果 7.2.9.32 的 player 请求体保持一致：少了 dr_scene / use_server_dns_scene /
        // disable_video_relate_book 时，播放服务只回 200 + 0 字节，既不报错也不给内容。
        //
        // device_level 影响服务端按什么解码能力供给编码，实测（2026-11-09，同一集 7704523554519256088）：
        //   注册身份 + 3（高档机） → 五档全 bytevc2（BVC2/H.266），本端无解码器；
        //   注册身份 + 1（低档机） → 五档全 bytevc1（HEVC），系统硬解可播，分辨率不变（1080x1906）；
        //   本地真实身份（device-identity.txt）→ **两种口径都回 bytevc2**，服务端按身份自身档位供给。
        // 所以默认沿用官方口径 3；整集全 BVC2 时由 [streamLadder] 换成注册身份 + 1 重取一次。
        val payload =
            """{"biz_param":{"detail_page_version":0,"device_level":$deviceLevel,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"dr_scene":"player","mixed_video_id_map":{"1004":${JSONArray(ids)}${lastSeriesId?.let { ",\"1001\":[\"$it\"]" } ?: ""}}}"""
                .toByteArray()
        val response = playbackResponse(payload, forceRegistered)
        val data = JSONObject(response)
        val code = data.optInt("Code", data.optInt("code", 0))
        if (code != 0) throw IOException("播放接口暂不可用（错误码 $code）")
        val map = data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val current =
            map.optJSONObject(id)
                ?: (if (ids.size == 1)
                    map.keys()
                        .asSequence()
                        .mapNotNull { map.optJSONObject(it) }
                        .firstOrNull()
                else null)
                ?: throw IOException("播放信息为空")
        synchronized(upcomingPlaybackModels) {
            ids.drop(1).forEach { next ->
                map.optJSONObject(next)
                    ?.takeIf { row ->
                        val model = row.opt("video_model")
                        (model is JSONObject || model is String && model.isNotBlank()) &&
                            row.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024
                    }
                    ?.let { upcomingPlaybackModels[next] = UpcomingPlaybackModel(it, clock()) }
            }
            while (upcomingPlaybackModels.size > 6)
                upcomingPlaybackModels.remove(upcomingPlaybackModels.keys.first())
        }
        return current
    }

    /**
     * 发一次播放模型请求：完整客户端签名 + 播放接口当前使用的域名。
     *
     * 播放接口的行为和别的接口都不一样，两条是实测结论：
     * 1. 必须带完整签名头。只拼 query 时一律 `200 + 0 字节`。
     * 2. 签名器内部打包的 `device_id` / `version_name` 必须和 query 里的一致，否则同样只回空。
     *
     * 另外「一台设备只放行一次」只对**新注册**的身份成立：本地配置进来的真实身份不限次，可以跨
     * 播放复用；注册来的身份每播一次就得换一台（见 [PlaybackDeviceSession.invalidate]）。
     * 域名按网页端集号的归属顺序试：网页详情页的集号属于 hl 集群，其余两个是兜底。
     */
    private fun playbackResponse(payload: ByteArray, forceRegistered: Boolean = false): String {
        var lastProblem: Exception? = null
        // 每个域名的失败原因都留下来：只报最后一个的话，看不出「三个域名回的是同一句话」
        // 还是「一个空 body、一个 404、一个业务码」，而这两者指向的问题完全不同。
        val failures = mutableListOf<String>()
        var sawPlaybackFailure = false
        // 这一趟里已经用过一台注册设备：下一轮必须换，否则必然空响应。
        var spentInThisCall = false
        for (host in PLAYBACK_MODEL_HOSTS) {
            val identity: PlaybackDeviceSession.Profile
            try {
                if (forceRegistered) {
                    // 降级专用：每个域名都换一台全新注册身份，且不动本地配置的真实身份。
                    identity = playbackSession.registeredProfile(http)
                } else {
                    var profile = playbackSession.profile(http)
                    if (!profile.trusted && (playbackIdentitySpent || spentInThisCall)) {
                        // 注册身份只有一次额度：这个身份已经用过（上次播放，或这一趟的上一个域名），换一台。
                        playbackSession.invalidate()
                        profile = playbackSession.profile(http)
                    }
                    if (!profile.trusted) {
                        playbackIdentitySpent = true
                        spentInThisCall = true
                    }
                    identity = profile
                }
            } catch (problem: PlaybackDeviceRegistrationException) {
                // 注册失败保持原有异常类型：界面文案是「播放设备注册失败」，
                // 包成「空响应」会让人以为是服务端不给内容，方向就错了。
                lastProblem = problem
                continue
            }
            try {
                return signedPlayback(host, identity, payload)
            } catch (problem: EmptyPlaybackResponseException) {
                lastProblem = problem
                sawPlaybackFailure = true
                failures += (problem.message ?: "").substringAfter("（").trimEnd('）')
                // 本地配置的真实身份也可能失效：丢掉它，下一个域名改用自动注册兜底。
                if (identity.trusted) playbackSession.invalidate()
            }
        }
        if (sawPlaybackFailure)
            throw EmptyPlaybackResponseException(failures.joinToString(" | "))
        throw lastProblem ?: EmptyPlaybackResponseException()
    }

    /** 一次完整签名的播放请求；空响应或业务错误码都按「拿不到播放信息」上报。 */
    private fun signedPlayback(
        host: String,
        identity: PlaybackDeviceSession.Profile,
        payload: ByteArray,
    ): String {
        val values =
            LinkedHashMap(VendorConstants.device).apply {
                putAll(identity.values)
                putAll(PLAYBACK_QUERY)
            }
        // 官方播放请求的 body 是 gzip 的，X-SS-STUB 取的是**压缩后**字节的 MD5
        // （2026-11-11 抓包核对：BFB2CC4C… 同时等于 stub 与 md5(gzip body)）。
        // 以前发的是明文 JSON，stub 也算明文，自洽但和官方形态不同，网关据此判定不是官方客户端。
        val body = gzip(payload)
        // 签名器会用 device 里的 device_id / version_name 生成头部，必须与实际发出的 query 同源。
        val signed = Signer(device = values).sign("$host$PLAYBACK_MODEL_PATH", values, body)
        val headers = LinkedHashMap(signed.headers)
        headers["User-Agent"] = PLAYBACK_UA
        headers["x-tt-store-region"] = "cn-bj"
        headers["x-tt-store-region-src"] = "did"
        // 官方播放请求里没有这两个头，多带会被当成异常客户端。
        headers.remove("x-tt-request-tag")
        headers.remove("x-tt-dt")
        headers["lc"] = "101"
        headers["Accept-Encoding"] = "gzip"
        headers["Content-Encoding"] = "gzip"
        headers["x-reading-request"] =
            "${headers["x-ss-req-ticket"]}-${(Math.random() * 1_000_000_000L).toLong()}"
        val cookie =
            if (identity.cookie.isNotBlank()) identity.cookie
            else "store-region=cn-bj; store-region-src=did"
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        val request = Signer.Request(signed.url, headers, body)
        // 连接层故障（连不上、读超时、5xx）重试一次是安全的：请求没到服务端，不会消耗掉
        // 播放接口那一次放行机会。空响应不重试，那是服务端已经明确拒绝的信号。
        val response = text(signed.url, request, retryTransient = true)
        val trimmed = response.trim()
        if (trimmed.isEmpty()) throw EmptyPlaybackResponseException("空 body，host=$host")
        val json =
            try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                throw EmptyPlaybackResponseException(
                    "非 JSON，host=$host，${trimmed.length}B：${trimmed.take(120)}"
                )
            }
        val code = json.optInt("Code", json.optInt("code", 0))
        if (code != 0)
            throw EmptyPlaybackResponseException(
                "业务码 $code，host=$host：${trimmed.take(160)}"
            )
        return response
    }

    /**
     * 诊断入口：原样返回这一集的播放模型 JSON。
     *
     * 只给 [Probe] 用来查「这一集为什么没有可播档位」——[streamLadder] 把拿不到档位的
     * 三种原因（编码不兼容 / 地址缺失 / 加密缺 key）压成了同一个空列表，排查时看不出区别。
     */
    @JvmOverloads
    fun diagnosePlaybackModel(id: String, deviceLevel: Int = PREFERRED_DEVICE_LEVEL): String {
        require(id.matches(Regex("[0-9]{1,30}")))
        return playbackModel(listOf(id), id, deviceLevel).toString()
    }

    /** Every playable rendition of one episode, best tier first; one player-API round trip. */
    @JvmOverloads
    fun streamLadder(id: String, metadataAhead: List<String> = emptyList()): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        require(metadataAhead.size <= 2 && metadataAhead.all { it.matches(Regex("[0-9]{1,30}")) })
        val ids = (listOf(id) + metadataAhead).distinct()
        val cached = takeCachedPlaybackModel(id)
        // 这一集已知只给 BVC2：直接用「注册身份 + 低档机口径」要，省掉一次注定白跑的请求。
        val knownFallback = synchronized(bvc2FallbackEpisodes) { id in bvc2FallbackEpisodes }
        val entry =
            cached
                ?: playbackModel(ids, id, if (knownFallback) FALLBACK_DEVICE_LEVEL else PREFERRED_DEVICE_LEVEL)
        try {
            return ladderFrom(entry)
        } catch (unsupported: UnsupportedCodecException) {
            if (knownFallback) throw unsupported
            // 默认身份只给 BVC2，本机没有对应解码器。换一台注册身份并申报低档机重取一次：
            // 实测同一集会返回全档 HEVC，分辨率不变，等于「自动切换成能播的方式」。
            // 代价只是这一集的这一次请求，正常剧集仍走默认身份与默认口径。
            forgetCachedPlaybackModels(ids)
            rememberBvc2Fallback(id)
            val retried =
                runCatching {
                        // 关键在于换一台**注册**身份：本地真实身份被服务端当成高端机，改 device_level
                        // 也会继续给 BVC2（实测 2026-11-09）；注册身份才按低端机给 HEVC。
                        playbackModel(ids, id, FALLBACK_DEVICE_LEVEL, forceRegistered = true)
                    }
                    .getOrElse {
                    // 重取失败（身份额度用尽、网络抖动）时保留原始结论，别把编码问题说成网络问题；
                    // 失败原因挂到 suppressed 上，排查时从堆栈就能看到，不影响用户看到的文案。
                    unsupported.addSuppressed(it)
                    throw unsupported
                }
            return ladderFrom(retried)
        }
    }

    /** 领一份缓存的后续模型；只保留 60 秒，领取即移除，失败重试不会反复复用同一个旧地址。 */
    private fun takeCachedPlaybackModel(id: String): JSONObject? =
        synchronized(upcomingPlaybackModels) {
            val now = clock()
            upcomingPlaybackModels.entries.removeAll { now - it.value.savedAt !in 0..60_000 }
            upcomingPlaybackModels.remove(id)?.entry
        }

    private fun forgetCachedPlaybackModels(ids: List<String>) {
        synchronized(upcomingPlaybackModels) { ids.forEach(upcomingPlaybackModels::remove) }
    }

    private fun rememberBvc2Fallback(id: String) {
        synchronized(bvc2FallbackEpisodes) { bvc2FallbackEpisodes[id] = Unit }
    }

    /**
     * 把一份播放模型解析成档位列表。
     *
     * 整集都是本机解不了的编码时抛 [UnsupportedCodecException]——这是确定性结论，调用方据此
     * 决定是否换一种申报口径重取，界面也可以用来说清「换一集」而不是「稍后再试」。
     */
    private fun ladderFrom(entry: JSONObject): StreamLadder {
        val model = entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        // The current model already carries plain media URLs, dimensions and CENC keys.
        // Only use the legacy fallback endpoint when this shape has no usable rendition.
        val inline =
            model.optJSONArray("video_list")?.objects().orEmpty().mapNotNull { row ->
                runCatching {
                        val meta = row.optJSONObject("video_meta") ?: return@runCatching null
                        val encryption =
                            row.optJSONObject("encrypt_info") ?: return@runCatching null
                        val codec = meta.str("codec_type").lowercase()
                        val url = cleanUrl(row.str("main_url"))
                        if (
                            codec.isBlank() ||
                                codec in UNPLAYABLE_CODECS ||
                                (!url.startsWith("https://") && !url.startsWith("http://"))
                        )
                            return@runCatching null
                        val width = dimension(meta, "vwidth", "width") ?: return@runCatching null
                        val height = dimension(meta, "vheight", "height") ?: return@runCatching null
                        val key =
                            encryption
                                .str("spade_a")
                                .takeIf(String::isNotBlank)
                                ?.let(MediaCrypto::deriveKey)
                        if (
                            key == null &&
                                encryption.opt("encrypt") !in listOf(false, 0, "0", "false")
                        )
                            return@runCatching null
                        Rendition(
                            qualityNumber(meta.str("definition")),
                            codec,
                            width,
                            height,
                            url,
                            key,
                        )
                    }
                    .getOrNull()
            }
        if (inline.isNotEmpty()) return StreamLadder(inline)
        val raw = model.opt("fallback_api")
        val fallback =
            when (raw) {
                is JSONObject -> raw.str("fallback_api")
                is JSONArray -> raw.optString(0)
                else ->
                    runCatching { JSONObject(raw.toString()).str("fallback_api") }
                        .getOrDefault(raw.toString())
            }
        if (!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        var info =
            JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data")
                ?: throw IOException("没有播放资源")
        var videoList = info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        val offered =
            videoList.keys().asSequence().mapNotNull(videoList::optJSONObject).toList()
        if (
            offered.isNotEmpty() &&
                offered.all {
                    it.str("main_url").isNotBlank() &&
                        it.str("codec_type").lowercase() in setOf("bytevc2", "bvc2")
                }
        ) {
            // 官方播放器有 force_codec / codec_type=0 的兼容路径。整集只有 bytevc2 档位时，
            // 额外读一次兼容模型；参数名不保证一定换成 H.264，所以下面继续按实际编码过滤选档。
            // 只在「全档 bytevc2」时发这一次，已有可用档位或编码未知都不多发请求。
            val compatible =
                fallback
                    .toHttpUrl()
                    .newBuilder()
                    .setQueryParameter("codec_type", "0")
                    .setQueryParameter("force_codec", "h264")
                    .build()
                    .toString()
            info =
                JSONObject(text(compatible)).optJSONObject("video_info")?.optJSONObject("data")
                    ?: throw IOException("没有播放资源")
            videoList = info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        }
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4
        // extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val offeredRows =
            videoList
                .keys()
                .asSequence()
                .mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }
                .filter { it.second.str("main_url").isNotEmpty() }
                .toList()
        val rows =
            offeredRows
                .filter { it.second.str("codec_type").lowercase() !in UNPLAYABLE_CODECS }
                .map { (k, o) ->
                    Triple(
                        qualityNumber(
                            o.str("quality_desc").ifEmpty {
                                o.str("height").ifEmpty {
                                    o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } }
                                }
                            }
                        ),
                        k,
                        o,
                    )
                }
                .sortedByDescending { it.first }
                .toList()
        val seed = info.str("key_seed")
        // A broken rendition entry must not take down otherwise playable siblings.
        val renditions =
            rows.mapNotNull { (quality, _, o) ->
                runCatching {
                        var url = cleanUrl(o.str("main_url"))
                        if (seed.isNotEmpty()) url = MediaCrypto.decryptUrl(url, decode64(seed))
                        if (!url.startsWith("https://") && !url.startsWith("http://"))
                            return@runCatching null
                        Rendition(
                            quality,
                            o.str("codec_type").lowercase(),
                            dimension(o, "vwidth", "width"),
                            dimension(o, "vheight", "height"),
                            url,
                            o.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey),
                        )
                    }
                    .getOrNull()
            }
        if (renditions.isEmpty()) {
            // 整集全是被过滤掉的编码：换清晰度、重试都不会变，要说清是编码问题，
            // 否则只剩一句「稍后再试」，用户会对同一集反复重试。
            val codecs = offeredRows.map { it.second.str("codec_type").lowercase() }.distinct()
            if (codecs.isNotEmpty() && codecs.all { it in UNPLAYABLE_CODECS })
                throw UnsupportedCodecException(codecs, softRendition(offeredRows, seed))
            throw IOException("这一集没有可播放的清晰度，请稍后再试或选择其他剧集")
        }
        return StreamLadder(renditions)
    }

    /**
     * 从被过滤掉的档位里挑一条最高清的 BVC2，交给有软解能力的端（桌面 bridge）兜底。
     *
     * 地址解不出、缺 key 时返回 null，调用方照旧抛原来的异常——兜底失败不该掩盖真正的原因。
     */
    private fun softRendition(
        rows: List<Pair<String, JSONObject>>,
        seed: String,
    ): StreamInfo? =
        rows
            .filter { it.second.str("codec_type").lowercase() in UNPLAYABLE_CODECS }
            .mapNotNull { (key, row) ->
                runCatching {
                        var url = cleanUrl(row.str("main_url"))
                        if (seed.isNotEmpty()) url = MediaCrypto.decryptUrl(url, decode64(seed))
                        if (!url.startsWith("https://") && !url.startsWith("http://"))
                            return@runCatching null
                        val quality =
                            qualityNumber(
                                row.str("quality_desc").ifEmpty {
                                    row.str("height").ifEmpty {
                                        row.str("vheight").ifEmpty {
                                            row.str("quality").ifEmpty { key }
                                        }
                                    }
                                }
                            )
                        quality to
                            StreamInfo(
                                url,
                                row.str("spade_a")
                                    .takeIf { it.isNotEmpty() }
                                    ?.let(MediaCrypto::deriveKey),
                                if (quality > 0) "${quality}P" else "自动",
                            )
                    }
                    .getOrNull()
            }
            .maxByOrNull { it.first }
            ?.second

    /** 与官方一致的 gzip：同一份字节既用于签名，也用于 X-SS-STUB。 */
    private fun gzip(data: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(data) }
            out.toByteArray()
        }

    private fun dimension(o: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            val value = o.optInt(key, 0)
            if (value > 0) return value
        }
        return null
    }

    companion object {
        /**
         * 播放接口域名，按网页端集号的归属顺序排列。
         *
         * 网页详情页给出的分集号在 hl 集群上，往 c / b 这些内容集群发只会拿到空响应；
         * 后两个留作兜底，命中不了会各换一台设备身份再试。
         */
        private val PLAYBACK_MODEL_HOSTS =
            listOf(
                // 官方 7.4.3.32 播放时打的是调度域名 reading.snssdk.com，由它 307 到具体集群
                // （2026-11-11 frida 抓包看到 origin_url=reading.snssdk.com → sinfonlineb）。
                // 直连后两个集群也能通，但少了这一步调度。
                "https://reading.snssdk.com",
                "https://api5-normal-sinfonlineb.fqnovel.com",
                "https://api5-normal-hl.fqnovel.com",
                "https://api5-normal-sinfonlinec.fqnovel.com",
                "https://api5-normal-lf.fqnovel.com",
            )

        private const val PLAYBACK_MODEL_PATH =
            "/novel/player/multi_video_model/player/v1"

        /**
         * 播放请求里申报的设备档位。
         *
         * 服务端按这个值挑编码：3（高档机）会推行 BVC2，1（低档机）给 HEVC。默认按官方策略
         * 申报 3，只有整集全 BVC2、本机解不了时才用 1 重取一次（见 [streamLadder]）。
         */
        private const val PREFERRED_DEVICE_LEVEL = 3

        /** 降级口径：服务端据此给兼容编码（实测同一集全档 bytevc1/HEVC，分辨率不变）。 */
        private const val FALLBACK_DEVICE_LEVEL = 1

        /**
         * 播放参数，逐字段对齐官方 7.4.3.32 的真实请求（2026-11-11 frida 抓取）。
         *
         * 三处以前没注意的点：设备型号 / 品牌 / 分辨率取的是**本机真值**（模拟器上报
         * ALT-AL10 / Huawei / 900*1600），服务端会拿它和 device_id 注册时的设备档案比对；
         * `klink_egdi` 是设备指纹（同一台机器上恒定），缺了会被当成非官方客户端；
         * `rom_version` 也必须带上。
         */
        private val PLAYBACK_QUERY =
            linkedMapOf(
                "version_code" to "74332",
                "version_name" to "7.4.3.32",
                "manifest_version_code" to "74332",
                "update_version_code" to "74332",
                "channel" to "xiaomi_8662_64",
                "pv_player" to "74332",
                "device_type" to "ALT-AL10",
                "device_brand" to "Huawei",
                "os_api" to "35",
                "os_version" to "15",
                "resolution" to "900*1600",
                "dpi" to "240",
                "dragon_device_type" to "pad",
                "is_android_pad_screen" to "1",
                "rom_version" to "V417IR release-keys",
                "klink_egdi" to "AAIOr0nvjh5XDmMzropzm0GoAVSgzByESBJNb5bQNgR9BidvXFGWO7mu",
            )

        private const val PLAYBACK_UA =
            "com.phoenix.read/74332 (Linux; U; Android 15; zh_CN_#Hans; ALT-AL10; Build/V417IR;tt-ok/3.12.13.20)"

        fun extractRouter(html: String): JSONObject {
            val marker = html.indexOf("_ROUTER_DATA")
            if (marker < 0) throw IOException("页面没有内容数据")
            val first = html.indexOf('{', marker)
            if (first < 0) throw IOException("页面数据格式错误")
            var depth = 0
            var quoted = false
            var escaped = false
            for (i in first until html.length) {
                val ch = html[i]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                    continue
                }
                when (ch) {
                    '"' -> quoted = true
                    '{',
                    '[' -> depth++
                    '}',
                    ']' -> {
                        depth--
                        if (depth == 0) return JSONObject(html.substring(first, i + 1))
                    }
                }
            }
            throw IOException("页面数据不完整")
        }

        fun qualityNumber(value: String): Int =
            Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt()
                ?: mapOf("1920" to 1080, "1280" to 720, "1024" to 576, "854" to 480, "640" to 360)[
                    value]
                ?: value.toIntOrNull()
                ?: 0
    }
}
