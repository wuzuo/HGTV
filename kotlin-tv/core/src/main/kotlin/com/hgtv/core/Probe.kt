package com.hgtv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

fun main(args: Array<String>) {
    args.indexOf("--identity").takeIf { it >= 0 }?.let { at ->
        args.getOrNull(at + 1)?.let { DeviceIdentityFile.path = java.io.File(it) }
    }
    val repository = ContentRepository()
    if (args.contains("--category-recommendations")) {
        try {
            for (category in listOf(DramaCategory.COMIC, DramaCategory.REAL)) {
                val cursor = RecommendationCursor()
                val feed = RecommendationFeed(repository.http, cursor = cursor, category = category)
                val first = feed.page(1)
                val second = feed.page(2)
                val fresh = second.items.count { row -> first.items.none { it.id == row.id } }
                check(first.items.isNotEmpty() && fresh > 0)
                val detail = repository.detail(first.items.first().id)
                check(detail.episodes.isNotEmpty())
                first.trialHints[first.items.first().id]?.let {
                    check(it.episodeId == detail.episodes.first())
                    check(it.episodeCount == detail.episodes.size)
                }
                println("category=${category.name}; directTrialHints=${first.trialHints.size}")
                if (args.contains("--prepare-video")) {
                    val remote =
                        RemoteVideo(
                            repository.http,
                            repository.stream(detail.episodes.first(), 720),
                        )
                    try {
                        remote.prepare()
                        check(remote.total > 0)
                        println("category=${category.name}; videoPrepared=true")
                    } finally {
                        remote.close()
                    }
                }
                println(
                    "category=${category.name}; first=${first.items.size}; second=${second.items.size}; new=$fresh; detailEpisodes=${detail.episodes.size}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--recommendations")) {
        try {
            val cursor = RecommendationCursor()
            val feed = RecommendationFeed(repository.http, cursor = cursor)
            val first = feed.page(1)
            val second = feed.page(2)
            val new = second.items.count { row -> first.items.none { it.id == row.id } }
            check(first.items.isNotEmpty() && second.items.isNotEmpty() && new > 0)
            val refreshed = RecommendationFeed(repository.http, cursor = cursor.nextBatch()).page(1)
            val initial = first.items.first()
            val hint = first.trialHints[initial.id]
            if (hint != null) {
                val detail = repository.detail(initial.id)
                check(hint.episodeId == detail.episodes.first())
                check(hint.episodeCount == detail.episodes.size)
            }
            println("recommendationDirectTrialHints=${first.trialHints.size}")
            println(
                "officialFeed=true; first=${first.items.size}; second=${second.items.size}; new=$new; firstMore=${first.hasMore}; secondMore=${second.hasMore}; refreshed=${refreshed.items.size}; authenticated=false"
            )
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--discovery")) {
        try {
            for (category in DramaCategory.entries) {
                val first = repository.discover(category)
                val info = checkNotNull(first.discovery)
                check(first.items.isNotEmpty() && info.topics.isNotEmpty())
                val topic = info.topics.first()
                val filtered = repository.discover(category, topic.id)
                val second = repository.discover(category, topic.id, 2)
                check(filtered.items.isNotEmpty() && second.items.isNotEmpty())
                check(filtered.items.map { it.id } != second.items.map { it.id })
                println(
                    "category=${category.route}; topics=${info.topics.joinToString { it.label }}; first=${first.items.size}; filtered=${filtered.items.size}; secondPage=${second.items.size}; totalPages=${info.totalPages}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--tv-rankings")) {
        try {
            for (board in RankBoard.entries) {
                val snapshot = repository.rankSnapshot(board)
                check(snapshot.items.isNotEmpty()) { "Empty ${board.name} board" }
                println(
                    "board=${board.name}; items=${snapshot.items.size}; topics=${snapshot.topics.joinToString(",")}; ranks=${snapshot.ranking.positions.values.mapNotNull { it.rank }.minOrNull()}..${snapshot.ranking.positions.values.mapNotNull { it.rank }.maxOrNull()}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--official-qr-login")) {
        try {
            val login = WebLogin(repository.http)
            val qr = login.qr()
            java.io.File("/tmp/hgtv-official-login.png").writeBytes(qr.image)
            println(
                "officialQrReady=true; image=/tmp/hgtv-official-login.png; expiresIn=${qr.expiresIn}"
            )
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                if (!login.poll()) continue
                println("officialWebLogin=true")
                val web = login.credentials()
                val client = CloudAccountClient(repository.http)
                val device = checkNotNull(client.registerDevice()) { "Device registration failed" }
                val account = web.copy(deviceId = device.deviceId, installId = device.installId)
                val profile = client.profile(account)
                println("mobileAccountVerified=true; idPresent=${profile.id.isNotBlank()}")
                val history = client.history(account)
                println("cloudHistoryLoaded=true; items=${history.size}")
                return
            }
            println("qrExpired=true")
        } catch (error: Exception) {
            println("loginTestFailed=${error.message?.take(180)}")
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--account-register")) {
        try {
            val registered = CloudAccountClient(repository.http).registerDevice()
            check(registered != null) { "Account device registration rejected" }
            println("accountDeviceRegistered=true; credentialsRequested=false; smsSent=false")
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--search-all")) {
        try {
            val keyword = args.getOrNull(args.indexOf("--search-all") + 1) ?: "大佬"
            for (page in 1..2) {
                val result = repository.searchAll(keyword, page)
                println(
                    "allSearchPage=$page; items=${result.items.size}; hasMore=${result.hasMore}; titles=${result.items.take(5).joinToString { it.title }}"
                )
                println(
                    "allSearchPage=$page; ids=${result.items.take(5).joinToString { "${it.id}:${it.title}" }}"
                )
                if (!result.hasMore) break
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--ranking")) {
        try {
            val first = repository.comicRanking()
            val metadata = checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen = mutableSetOf<String>()
            for (pageNumber in
                listOf(1, 2, metadata.totalPages).distinct().filter { it <= metadata.totalPages }) {
                val result = if (pageNumber == 1) first else repository.comicRanking(pageNumber)
                val ranking = checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks = result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(
                    ranks.all { it != null && it > 0 } &&
                        ranks.filterNotNull().zipWithNext().all { (a, b) -> a < b }
                )
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore == (pageNumber < ranking.totalPages))
                println(
                    "rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}"
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--episodes")) {
        val series = args.getOrNull(args.indexOf("--episodes") + 1) ?: return
        try {
            val detail = repository.detail(series)
            println("series=${detail.series.title}; episodes=${detail.episodes.size}")
            detail.episodes.forEachIndexed { index, id -> println(" ${index + 1}: $id") }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--codecs")) {
        // 一次比对多集：只在「某集播不了、相邻集能播」时需要，看的是每档的编码。
        val episodes =
            args.drop(args.indexOf("--codecs") + 1).filter { it.matches(Regex("[0-9]{1,30}")) }
        try {
            for (episode in episodes) {
                runCatching {
                        val entry = JSONObject(repository.diagnosePlaybackModel(episode))
                        val model =
                            entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
                        val list = model.optJSONArray("video_list")
                        val codecs =
                            (0 until (list?.length() ?: 0)).mapNotNull { index ->
                                list!!
                                    .optJSONObject(index)
                                    ?.optJSONObject("video_meta")
                                    ?.let { "${it.optString("definition")}/${it.optString("codec_type")}" }
                            }
                        println("$episode -> ${codecs.joinToString(" ")}")
                    }
                    .onFailure { println("$episode -> ${it.message}") }
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--playback-model")) {
        // 只看结构，不打印媒体地址：定位「为什么没有可播档位」时，编码/加密/地址缺失
        // 三种原因在 streamLadder 里都被压成同一个空列表。
        val episode = args.getOrNull(args.indexOf("--playback-model") + 1) ?: return
        try {
            val entry = JSONObject(repository.diagnosePlaybackModel(episode))
            val model = entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
            println(
                "modelKeys=${model.keys().asSequence().joinToString(",")}; fallback=${model.opt("fallback_api")?.let { it.toString().take(60) }}"
            )
            val list = model.optJSONArray("video_list")
            println("video_list=${list?.length() ?: 0}")
            for (i in 0 until (list?.length() ?: 0)) {
                val row = list!!.optJSONObject(i) ?: continue
                val meta = row.optJSONObject("video_meta")
                val encryption = row.optJSONObject("encrypt_info")
                val url = row.optString("main_url")
                println(
                    " row$i codec=${meta?.optString("codec_type")}; definition=${meta?.optString("definition")}; " +
                        "w=${meta?.optInt("vwidth")}; h=${meta?.optInt("vheight")}; urlLen=${url.length}; " +
                        "urlHead=${url.take(8)}; encrypt=${encryption?.opt("encrypt")}; " +
                        "spade=${!encryption?.optString("spade_a").isNullOrEmpty()}"
                )
            }
            // fallback 才是真正取流的地址；兼容请求（codec_type=0 + force_codec=h264）在同一
            // 地址上追加参数，两条都打出来才能看出服务端到底给不给 H.264。
            val rawFallback = model.opt("fallback_api")?.toString().orEmpty()
            val fallbackUrl =
                if (rawFallback.startsWith("{")) JSONObject(rawFallback).optString("fallback_api")
                else rawFallback
            fun dumpFallback(label: String, url: String) {
                val body =
                    repository.http
                        .newCall(okhttp3.Request.Builder().url(url).build())
                        .execute()
                        .use { it.body?.string().orEmpty() }
                val info = JSONObject(body).optJSONObject("video_info")?.optJSONObject("data")
                val rows = info?.optJSONObject("video_list")
                println(
                    "$label http=${body.length}B; keys=${rows?.keys()?.asSequence()?.joinToString(",")}; " +
                        "seed=${!info?.optString("key_seed").isNullOrEmpty()}"
                )
                for (k in rows?.keys()?.asSequence().orEmpty()) {
                    val o = rows!!.optJSONObject(k) ?: continue
                    println(
                        "  $label[$k] codec=${o.str("codec_type")}; desc=${o.str("quality_desc")}; " +
                            "h=${o.str("height")}; urlLen=${o.str("main_url").length}"
                    )
                }
            }
            if (fallbackUrl.isNotBlank()) {
                dumpFallback("fb", fallbackUrl)
                dumpFallback(
                    "fb-h264",
                    fallbackUrl.toHttpUrl()
                        .newBuilder()
                        .setQueryParameter("codec_type", "0")
                        .setQueryParameter("force_codec", "h264")
                        .build()
                        .toString(),
                )
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--codec-level")) {
        // 同一集、同一身份下比对不同 device_level 的编码供给，用来区分「档位决定」与「身份决定」。
        val ids = args.drop(args.indexOf("--codec-level") + 1).filter { it.matches(Regex("[0-9]{1,30}")) }
        for (level in listOf(3, 1)) {
            for (id in ids) {
                runCatching {
                        val entry = JSONObject(repository.diagnosePlaybackModel(id, level))
                        val model =
                            entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
                        val list = model.optJSONArray("video_list")
                        val codecs =
                            (0 until (list?.length() ?: 0)).mapNotNull { index ->
                                list!!
                                    .optJSONObject(index)
                                    ?.optJSONObject("video_meta")
                                    ?.let { it.optString("codec_type") }
                            }
                        println("level=$level $id -> ${codecs.distinct().joinToString(",")}")
                    }
                    .onFailure { println("level=$level $id -> ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
        repository.http.connectionPool.evictAll()
        repository.http.dispatcher.executorService.shutdown()
        return
    }
    if (args.contains("--ladder")) {
        // 走正式选流链路（含「全档 BVC2 时自动换低档机口径」的降级），验证这一集能否播。
        val episode = args.getOrNull(args.indexOf("--ladder") + 1) ?: return
        try {
            val ladder = repository.streamLadder(episode)
            ladder.renditions.forEach {
                println("${it.quality}p ${it.codec} ${it.width}x${it.height}")
            }
            println(
                "count=${ladder.renditions.size} best=${ladder.renditions.maxByOrNull { it.quality }?.quality}p"
            )
        } catch (problem: Exception) {
            println("FAILED ${problem.javaClass.simpleName}: ${problem.message}")
            problem.printStackTrace()
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if (args.contains("--sample")) {
        // 把某集某个档位的前 1MB 落盘，用来确认服务端给的码流到底是什么编码——
        // 只看 codec_type 字段不够，那只是服务端的标签。
        val at = args.indexOf("--sample")
        val episode = args.getOrNull(at + 1) ?: return
        val out = args.getOrNull(at + 2) ?: "sample.mp4"
        try {
            val entry = JSONObject(repository.diagnosePlaybackModel(episode))
            val model = entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
            val rawFallback = model.opt("fallback_api")?.toString().orEmpty()
            val fallbackUrl =
                if (rawFallback.startsWith("{")) JSONObject(rawFallback).optString("fallback_api")
                else rawFallback
            val body =
                repository.http
                    .newCall(okhttp3.Request.Builder().url(fallbackUrl).build())
                    .execute()
                    .use { it.body?.string().orEmpty() }
            val info = JSONObject(body).optJSONObject("video_info")?.optJSONObject("data") ?: return
            val seed = info.str("key_seed")
            val rows = info.optJSONObject("video_list") ?: return
            val row =
                rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.lastOrNull() ?: return
            var url = cleanUrl(row.str("main_url"))
            if (seed.isNotEmpty()) url = MediaCrypto.decryptUrl(url, decode64(seed))
            val key = row.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
            println("codec=${row.str("codec_type")}; urlLen=${url.length}; key=${key != null}")
            RemoteVideo(repository.http, StreamInfo(url, key, "sample")).use { video ->
                video.prepare(256 * 1024)
                val bytes = video.read(0, 1 shl 20)
                java.io.File(out).writeBytes(bytes)
                println("saved=${bytes.size}; total=${video.total}; track=${video.trackInfo()}")
            }
        } finally {
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type = if (args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword = if (type == ContentType.COMIC) "修仙" else "总裁"
    val search = repository.search(keyword, type = type)
    println("search=${search.items.size}; hasMore=${search.hasMore}")
    if (type == ContentType.COMIC && search.hasMore) {
        val next = repository.search(keyword, 2, type)
        println(
            "searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}"
        )
    }
    val home = repository.home(type = type)
    println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if (type == ContentType.COMIC) {
        val next = repository.home(2, type)
        println(
            "homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}"
        )
    }
    val detail =
        repository.detail(
            args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id
        )
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream = repository.stream(detail.episodes.first())
    RemoteVideo(repository.http, stream).use { remote ->
        remote.prepare()
        val first = remote.read(0, 1024 * 1024)
        val tail = remote.read((remote.total - 65536).coerceAtLeast(0), 65536)
        println(
            "quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}"
        )
        if (args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll()
    repository.http.dispatcher.executorService.shutdown()
}
