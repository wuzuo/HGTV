package com.hgtv.core

fun main(args: Array<String>) {
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
