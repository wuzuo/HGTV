// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.bridge

import com.hgtv.core.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors

/** Private loopback bridge. The native player receives decrypted ranges, never account data. */
fun main() {
    // 自用场景：把真实设备的播放身份放到 device-identity.txt 就完全不用自动注册。
    // 先找程序目录（前端通过 HGTV_APP_ROOT 告诉本进程，即 exe 所在目录），
    // 再找本进程工作目录（打包布局下是 <程序目录>\bridge）和 ~/.hgtv/，都没有就走自动注册。
    val appRoot = System.getenv("HGTV_APP_ROOT")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
    DeviceIdentityFile.path = appRoot?.resolve("device-identity.txt")
    DeviceIdentityFile.fallbacks =
        listOf(
            java.io.File("device-identity.txt").absoluteFile,
            java.io.File(System.getProperty("user.home") ?: ".", ".hgtv/device-identity.txt"),
        ).distinct()
    // 启动就读一次：前端通过 /identity 查询「有没有读到配置」，不必等到第一次播放。
    DeviceIdentityMonitor.resolve()
    val repository=ContentRepository()
    // 冷启动接着上次刷：服务端在没有 session_id 时永远返回同一批冷启动内容，
    // 所以把分页会话与 offset 落盘，下次启动先恢复再请求。快照只含分页状态与已看 ID。
    val feedCursorFile =
        appRoot?.resolve("feed-cursor.json")
            ?: java.io.File(System.getProperty("user.home") ?: ".", ".hgtv/feed-cursor.json")
    fun restoreFeedCursor(): RecommendationCursor =
        DramaFeedState().recommendationCursor.also { cursor ->
            runCatching {
                if (feedCursorFile.isFile && feedCursorFile.length() <= 64_000)
                    cursor.restore(feedCursorFile.readText())
            }
        }
    fun saveFeedCursor(cursor: RecommendationCursor) {
        runCatching {
            feedCursorFile.parentFile?.mkdirs()
            feedCursorFile.writeText(cursor.snapshot())
        }
    }
    var feedCursor=restoreFeedCursor()
    val catalogLock=Any()
    val account=MacAccount(repository)
    val publicComments=PublicComments(repository.http)
    val timedComments=TimedComments()
    val appCommentPages=AppCommentPages()
    val token=UUID.randomUUID().toString()
    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),16)
    val executor=Executors.newFixedThreadPool(6)
    server.executor=executor
    val lock=Any()
    var current: Pair<String,RemoteVideo>?=null
    // 整集只剩 BVC2 时的软解兜底。和 current 一样是单槽：同一时刻只转一集，切集即换。
    // 带上集号是为了让同集重复播放（比如切清晰度又切回来）复用已转好的会话，
    // 而不是把一百多 MB 再下一次、再转一遍。
    data class SoftSession(val id: String,val episode: String,val transcoder: Bvc2Transcoder)
    var transcoded: SoftSession?=null
    // FFmpeg 的定位与编码器探测都要起子进程，第一次用到再算，之后缓存。
    var ffmpegPath: String?=null
    var ffmpegEncoder: String?=null
    val warmLock=Any()
    // tiers：这一集实际可用的清晰度档位（升序）。播放器菜单据此标出「本集没有」的档位，
    // 不让用户以为切清晰度没生效。
    data class WarmClip(val episode: String,val quality: Int,val actual: String,val video: RemoteVideo,val tiers: List<Int>)
    var warmed: WarmClip?=null
    fun json(exchange: HttpExchange, value: JSONObject, status: Int=200) {
        val bytes=value.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type","application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control","no-store")
        exchange.sendResponseHeaders(status,bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    /** Resolves an episode and buffers its first [warmBytes] so a later play call is instant. */
    /** 档位排序：升序去重，只保留正数档。 */
    fun tiersOf(ladder: StreamLadder): List<Int> =
        ladder.renditions.map{it.quality}.filter{it>0}.distinct().sorted()

    data class PickedStream(val info: StreamInfo, val tiers: List<Int>)

    /**
     * 选档。等价于 [ContentRepository.stream]，只是把整条清晰度链路一起留下来，
     * 免得为了拿档位再请求一次播放信息。
     */
    fun pickStream(episode: String, quality: Int): PickedStream {
        val ladder=repository.streamLadder(episode)
        val selected=ladder.select(quality) ?: error("没有可播放的清晰度，请稍后再试或选择其他剧集")
        val info=selected.toStreamInfo().let {
            if(selected.quality>quality) it.copy(quality=it.quality+"（兼容资源）") else it
        }
        return PickedStream(info,tiersOf(ladder))
    }

    fun prepareClip(episode: String, quality: Int, warmBytes: Int): WarmClip {
        val picked=pickStream(episode,quality)
        val clip=RemoteVideo(repository.http,picked.info)
        try { clip.warm(warmBytes) } catch(problem: Exception) { clip.close();throw problem }
        return WarmClip(episode,quality,picked.info.quality,clip,picked.tiers)
    }
    fun keepWarmed(prepared: WarmClip) {
        synchronized(lock) {
            val previous=warmed
            warmed=prepared
            if(previous!=null && previous!==prepared) previous.video.close()
        }
    }

    /**
     * 整集只剩 BVC2 时起本地软解：解密落盘成 FFmpeg 能读的明文容器，视频按样本表转 Annex-B
     * 喂 stdin，音轨让 FFmpeg 从明文容器里直通，输出 HLS 交给播放器。
     *
     * 这是最后一道兜底——正常路径会先换注册身份重取，服务端多半给回 HEVC，根本走不到这里。
     * 返回的会话已经等到 m3u8 落地，调用方可以立刻把地址交给播放器。
     */
    fun startSoftDecode(episode: String, info: StreamInfo): SoftSession {
        // 同一集已经转好且在跑：直接复用，省掉重复下载与转码。
        synchronized(lock) {
            transcoded?.takeIf { it.episode==episode && it.transcoder.alive }?.let { return it }
        }
        val path=ffmpegPath ?: Ffmpeg.locate(appRoot) ?: error(
            "这一集只有 BVC2（H.266）编码，需要本机 FFmpeg 软解，但没有找到 ffmpeg 可执行文件；" +
                "请安装 FFmpeg 或设置 HGTV_FFMPEG 指向它")
        ffmpegPath=path
        val encoder=ffmpegEncoder ?: Ffmpeg.encoder(path).also { ffmpegEncoder=it }
        val video=RemoteVideo(repository.http,info)
        try {
            video.prepare()
            val table=video.sampleTable() ?: error("这一集只有 BVC2 编码，但播放资源不是可软解的 MP4")
            val workDir=java.nio.file.Files.createTempDirectory("hgtv-bvc2")
            val plain=workDir.resolve("plain.mp4")
            // 先落盘再用：FFmpeg 读第二输入的音轨要能随机访问整个文件，边下边读的管道拿不到。
            java.io.FileOutputStream(plain.toFile()).use { video.exportDecrypted(it) }
            // stss 给不出同步点时只能扫一遍码流（字节家的流把随机访问点放在带内 NAL 上）。
            val raps=
                if(table.syncSamples.isEmpty()) AnnexB.scanSyncSamples(table,video::read)
                else emptyList()
            val session=Bvc2Transcoder(video,table,raps,plain,workDir.resolve("hls"),path,encoder)
            val id=UUID.randomUUID().toString()
            val soft=SoftSession(id,episode,session)
            synchronized(lock) { transcoded?.transcoder?.close();transcoded=soft }
            // 等播放列表落地再返回：前端拿到地址就会立刻去拉 m3u8，早于它出现只会拿到 404。
            val deadline=System.currentTimeMillis()+30_000
            while(!java.nio.file.Files.exists(session.indexFile) && session.alive &&
                System.currentTimeMillis()<deadline) Thread.sleep(120)
            if(!java.nio.file.Files.exists(session.indexFile))
                error(session.failReason ?: "软解转码没有在预期时间内生成播放列表")
            return soft
        } catch(problem: Exception) {
            video.close()
            throw problem
        }
    }
    server.createContext("/") { exchange ->
        try {
            val path=exchange.requestURI.path
            if(!path.startsWith("/$token/") || exchange.requestMethod !in setOf("GET","HEAD","POST") || exchange.requestHeaders.getFirst("Origin")!=null) {
                exchange.sendResponseHeaders(403,-1);return@createContext
            }
            val query=exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                val parts=it.split('=',limit=2)
                URLDecoder.decode(parts[0],"UTF-8") to URLDecoder.decode(parts[1],"UTF-8")
            }
            val operation=path.removePrefix("/$token/")
            if(operation.startsWith("account/")) {
                json(exchange,account.handle(exchange,operation.removePrefix("account/"),query));return@createContext
            }
            // BVC2 软解产物：HLS 播放列表与分片。只允许读当前会话目录下的文件名，
            // 路径里带分隔符或 .. 一律拒绝，避免被拼成任意文件读取。
            if(operation.startsWith("bvc2/")) {
                val rest=operation.removePrefix("bvc2/")
                val cut=rest.indexOf('/')
                val id=if(cut>0) rest.substring(0,cut) else ""
                val name=if(cut>0) rest.substring(cut+1) else ""
                val session=synchronized(lock) { transcoded?.takeIf { it.id==id }?.transcoder }
                if(session==null || name.isEmpty() || name.contains("..") ||
                    name.contains('\\') || name.contains('/')) {
                    exchange.sendResponseHeaders(404,-1);return@createContext
                }
                val base=session.workDir.toAbsolutePath().normalize()
                val target=base.resolve(name).normalize()
                val file=target.toFile()
                if(!target.startsWith(base) || !file.isFile) {
                    exchange.sendResponseHeaders(404,-1);return@createContext
                }
                val bytes=file.readBytes()
                exchange.responseHeaders.set("Content-Type",
                    if(name.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t")
                exchange.responseHeaders.set("Cache-Control","no-store")
                if(exchange.requestMethod=="HEAD") exchange.sendResponseHeaders(200,-1)
                else {
                    exchange.sendResponseHeaders(200,bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                return@createContext
            }
            when(operation) {
                // 本地设备身份配置的状态：前端据此提示「已读取配置 / 配置已失效」。
                // 没有配置是正常路径（走自动注册），所以 configured=false 不算错误。
                "identity" -> {
                    // 每次查询顺手重新探测：运行中新增/删除配置文件也能在下一轮反映出来。
                    DeviceIdentityMonitor.resolve()
                    val loaded=DeviceIdentityMonitor.peek()
                    json(exchange,JSONObject()
                        .put("configured",loaded!=null)
                        .put("deviceId",loaded?.identity?.deviceId ?: JSONObject.NULL)
                        .put("file",loaded?.file?.absolutePath ?: JSONObject.NULL)
                        .put("rejected",DeviceIdentityMonitor.wasRejected()))
                }
                "comment-page" -> json(exchange,appCommentPages.read(query["series"].orEmpty(),query["episode"].orEmpty(),query["cursor"].orEmpty()))
                "danmaku" -> json(exchange,timedComments.read(query["series"].orEmpty(),query["episode"].orEmpty(),query["start"]?.toLong() ?: -1,query["duration"]?.toLong() ?: -1))
                // 整集一次取完：窗口编排与 TV / 手机端同一份（core 的 DanmakuWindows，
                // 首窗探明步长后其余并发）。前端不用再自己维护分页游标。
                "danmaku-all" -> {
                    val items=ArrayList<JSONObject>()
                    val seen=HashSet<String>()
                    DanmakuWindows().load(
                        totalMs=query["duration"]?.toLong() ?: 0L,
                        read={start,total ->
                            timedComments.read(query["series"].orEmpty(),query["episode"].orEmpty(),start,total)
                        }
                    ) { _,page,_ ->
                        val rows=page.optJSONArray("items") ?: JSONArray()
                        for(i in 0 until rows.length()) {
                            val row=rows.optJSONObject(i) ?: continue
                            val id=row.optString("id")
                            if(id.isBlank() || !seen.add(id)) continue
                            items+=row
                        }
                    }
                    items.sortBy { it.optLong("timeMS",0L) }
                    json(exchange,JSONObject().put("items",JSONArray(items)))
                }
                "comments" -> json(exchange,publicComments.read(query["series"].orEmpty(),query["episode"].orEmpty()))
                "search" -> {
                    val result=repository.searchAll(query["q"].orEmpty(),query["page"]?.toInt() ?: 1)
                    json(exchange,JSONObject().put("items",JSONArray(result.items.map { it.toJson() })).put("hasMore",result.hasMore))
                }
                "catalog" -> {
                    val category=DramaCategory.valueOf(query["category"] ?: "COMIC")
                    val page=query["page"]?.toInt() ?: 1
                    val result=repository.discover(category,query["topic"].orEmpty(),page)
                    json(exchange,JSONObject().put("items",JSONArray(result.items.map { it.toJson() })).put("hasMore",result.hasMore)
                        .put("topics",JSONArray(result.discovery?.topics.orEmpty().map { JSONObject().put("id",it.id).put("label",it.label) })))
                }
                "rank" -> {
                    val snapshot=repository.rankSnapshot(RankBoard.valueOf(query["board"] ?: "COMIC"))
                    val positions=JSONObject().apply { snapshot.ranking.positions.forEach { (id,value) -> put(id,JSONObject().put("rank",value.rank ?: JSONObject.NULL).put("heat",value.heat)) } }
                    json(exchange,JSONObject().put("items",JSONArray(snapshot.filtered(query["topic"].orEmpty()).map { it.toJson() })).put("hasMore",false).put("positions",positions)
                        .put("topics",JSONArray(snapshot.topics.map { JSONObject().put("id",it).put("label",it) })))
                }
                "feed" -> synchronized(catalogLock) {
                    if(query["refresh"]=="true") feedCursor=feedCursor.nextBatch()
                    val category=query["category"]?.takeIf { it!="ALL" }?.let { DramaCategory.valueOf(it) }
                    val page=query["page"]?.toInt() ?: 1
                    // page=1 不再把游标重置成新的：冷启动要从落盘快照接着刷，「换一批」走 refresh=true。
                    val result=RecommendationFeed(repository.http,account=account.snapshot(),cursor=feedCursor,category=category).page(page)
                    saveFeedCursor(feedCursor)
                    json(exchange,JSONObject().put("items",JSONArray(result.items.map { it.toJson() })).put("hasMore",result.hasMore).put("topics",JSONArray()))
                }
                "related" -> {
                    val items=repository.related(query["id"].orEmpty())
                    json(exchange,JSONObject().put("items",JSONArray(items.map { JSONObject().put("series",it.series.toJson()).put("season",it.season).put("upcoming",it.upcoming) })))
                }
                "detail" -> {
                    val detail=repository.detail(query["id"].orEmpty())
                    json(exchange,JSONObject().put("series",detail.series.toJson()).put("episodes",JSONArray(detail.episodes)))
                }
                "warm" -> synchronized(warmLock) {
                    val detail=repository.detail(query["id"].orEmpty())
                    val episode=detail.episodes.firstOrNull() ?: error("No episodes")
                    val quality=query["quality"]?.toInt() ?: 1080
                    require(quality in setOf(480,720,1080))
                    keepWarmed(prepareClip(episode,quality,1024*1024))
                    json(exchange,JSONObject().put("series",detail.series.toJson()).put("episodes",JSONArray(detail.episodes)))
                }
                // Preloads the *next* episode while the current one is still playing. The clip
                // is kept in the same single warm slot that `play` consumes, so switching only
                // pays for handing the already-parseable stream to the player.
                "preload" -> synchronized(warmLock) {
                    val quality=query["quality"]?.toInt() ?: 1080
                    require(quality in setOf(480,720,1080))
                    val prepared=prepareClip(query["id"].orEmpty(),quality,4*1024*1024)
                    keepWarmed(prepared)
                    json(exchange,JSONObject().put("ok",true).put("quality",prepared.actual))
                }
                "play" -> {
                    val quality=query["quality"]?.toInt() ?: 1080
                    require(quality in setOf(480,720,1080))
                    val episode=query["id"].orEmpty()
                    val cached=synchronized(lock) {
                        warmed?.takeIf { it.episode==episode && it.quality==quality }?.also { warmed=null }
                    }
                    val prepared=cached ?: run {
                        try {
                            val picked=pickStream(episode,quality)
                            val clip=RemoteVideo(repository.http,picked.info)
                            try { clip.prepare() } catch(problem: Exception) { clip.close();throw problem }
                            WarmClip(episode,quality,picked.info.quality,clip,picked.tiers)
                        } catch(unsupported: UnsupportedCodecException) {
                            // 换注册身份重取之后仍然只剩 BVC2：桌面端有软解能力，改走本地转码，
                            // 而不是把「换一集」这个结论甩给用户。
                            val soft=unsupported.fallback ?: throw unsupported
                            val session=startSoftDecode(episode,soft)
                            json(exchange,JSONObject()
                                .put("url","http://127.0.0.1:${server.address.port}/$token/bvc2/${session.id}/index.m3u8")
                                .put("quality","${soft.quality}（软解）")
                                .put("tiers",JSONArray()))
                            return@createContext
                        }
                    }
                    val clip=prepared.video
                    val id=UUID.randomUUID().toString()
                    synchronized(lock) { current?.second?.close();current=id to clip }
                    json(exchange,JSONObject().put("url","http://127.0.0.1:${server.address.port}/$token/video?id=$id").put("quality",prepared.actual).put("tiers",JSONArray(prepared.tiers)))
                }
                "stop" -> { synchronized(lock) { current?.second?.close();current=null;warmed?.video?.close();warmed=null;transcoded?.transcoder?.close();transcoded=null };json(exchange,JSONObject().put("ok",true)) }
                "video" -> {
                    val selected=synchronized(lock) { current?.takeIf { it.first==query["id"] }?.second }
                    if(selected==null) { exchange.sendResponseHeaders(404,-1);return@createContext }
                    val range=exchange.requestHeaders.getFirst("Range")
                    val match=range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                    val start=match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val end=match?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(selected.total-1) ?: (selected.total-1)
                    if((range!=null && match==null) || start>end || start>=selected.total) {
                        exchange.responseHeaders.set("Content-Range","bytes */${selected.total}")
                        exchange.sendResponseHeaders(416,-1);return@createContext
                    }
                    exchange.responseHeaders.set("Content-Type","video/mp4")
                    exchange.responseHeaders.set("Accept-Ranges","bytes")
                    exchange.responseHeaders.set("Cache-Control","no-store")
                    val count=end-start+1
                    if(range!=null) exchange.responseHeaders.set("Content-Range","bytes $start-$end/${selected.total}")
                    exchange.responseHeaders.set("Content-Length",count.toString())
                    if(exchange.requestMethod=="HEAD") exchange.sendResponseHeaders(if(range!=null) 206 else 200,-1)
                    else {
                        exchange.sendResponseHeaders(if(range!=null) 206 else 200,count)
                        exchange.responseBody.use { out ->
                            var offset=start
                            while(offset<=end) {
                                val bytes=selected.read(offset,minOf(1024*1024L,end-offset+1).toInt())
                                if(bytes.isEmpty()) break
                                out.write(bytes);offset+=bytes.size
                            }
                        }
                    }
                }
                else -> exchange.sendResponseHeaders(404,-1)
            }
        } catch(problem: Exception) {
            // The bridge only listens on loopback behind a per-launch token, so the already
            // user-facing messages (bad phone number, rejected verification code, expired QR)
            // are safe — and necessary — to show instead of a blanket failure.
            val detail=when(problem) {
                is IllegalArgumentException, is IllegalStateException,
                is PhoneLogin.Failure, is java.io.IOException ->
                    problem.message?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
            runCatching { json(exchange,JSONObject().put("error",detail ?: "请求失败，请重试；若持续失败，请检查网络或更新客户端。"),502) }
        } finally { exchange.close() }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        synchronized(lock) { current?.second?.close();current=null;warmed?.video?.close();warmed=null;transcoded?.transcoder?.close();transcoded=null }
        repository.http.dispatcher.cancelAll()
        account.close();server.stop(0);executor.shutdownNow()
        repository.http.dispatcher.cancelAll();repository.http.connectionPool.evictAll()
        repository.http.dispatcher.executorService.shutdownNow()
    })
    server.start()
    println("http://127.0.0.1:${server.address.port}/$token/")
    System.out.flush()
    // Closing the native app's stdin tears down the helper and its playback requests.
    while(System.`in`.read()!=-1) { }
    kotlin.system.exitProcess(0)
}
