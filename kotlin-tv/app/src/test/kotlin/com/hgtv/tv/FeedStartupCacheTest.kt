// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

import android.app.Activity
import com.hgtv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26])
class FeedStartupCacheTest {
    private val page = CatalogPage(listOf(Series("101", "缓存作品")), true)

    @Test
    fun sourcesAreSeparateAndAccountChangeOrExpiryCannotReuseOldStarter() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var now = 1000L
        val cache = FeedStartupCache(controller.get()) { now }
        try {
            cache.save("test-account-a", "recommend", page)
            assertNotNull(cache.load("test-account-a", "recommend"))
            assertNull(cache.load("test-account-a", DramaCategory.COMIC.route))
            now += 600001
            assertNull(cache.load("test-account-a", "recommend"))
            cache.save("test-account-a", "recommend", page)
            assertNull(cache.load("test-account-b", "recommend"))
            assertNull(cache.load("test-account-a", "recommend"))
            cache.save("test-account-a", "recommend", page)
            controller
                .get()
                .getSharedPreferences(
                    "native-feed-startup-v1",
                    android.content.Context.MODE_PRIVATE,
                )
                .edit()
                .putInt("recommend", 42)
                .apply()
            assertNull(cache.load("test-account-a", "recommend"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun verifiedFirstEpisodeSurvivesBackendRecreationWithoutFeedOrDetailRequest() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(controller.get()) { 1000L }
        cache.save("guest", "recommend", page)
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
        val file = ByteArray(1024 * 1024)
        java.nio.ByteBuffer.wrap(file)
            .putInt(8)
            .put("ftyp".toByteArray())
            .putInt(8)
            .put("moov".toByteArray())
        fun transport() =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    paths += req.url.encodedPath
                    val builder =
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("test")
                    if (req.header("Range") != null) {
                        val header = req.header("Range")!!
                        ranges += header
                        val limits = header.removePrefix("bytes=").split('-').map(String::toInt)
                        builder
                            .code(206)
                            .header("Content-Range", "bytes ${limits[0]}-${limits[1]}/${file.size}")
                            .body(file.copyOfRange(limits[0], limits[1] + 1).toResponseBody())
                            .build()
                    } else {
                        val json =
                            when (req.url.encodedPath) {
                                "/detail" ->
                                    """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"101","series_title":"缓存作品","vid_list":["10101","10102"]}}}};</script>"""
                                "/novel/player/multi_video_model/player/v1" ->
                                    """{"data":{"10101":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""
                                else -> error("Unexpected request")
                            }
                        builder.code(200).body(json.toResponseBody()).build()
                    }
                }
                .build()
        try {
            repeat(2) {
                val backend =
                    PublicFeedBackend(
                        720,
                        http = transport(),
                        startupCache = cache,
                        playbackSession = testPlaybackDeviceSession(),
                    )
                try {
                    val result = backend.recommendations(1, DramaFeedState().recommendationCursor)
                    backend.trial(result.items.single()).use { clip ->
                        assertEquals("10101", clip.episodeId)
                        assertEquals(2, clip.episodeCount)
                        assertEquals(32 * 1024, clip.video.cachedBytes(0))
                    }
                } finally {
                    backend.close()
                }
            }
            assertEquals(1, paths.count { it == "/detail" })
            assertEquals(2, paths.count { it == "/novel/player/multi_video_model/player/v1" })
            assertEquals(listOf("bytes=0-262143", "bytes=0-262143"), ranges)
            assertEquals(5, paths.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun cursorSnapshotIsScopedToIdentityAndOwnerChangeClearsIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(controller.get()) { 1000L }
        try {
            val snapshot = """{"session":"s-1","offset":6,"seen":["101"]}"""
            cache.saveCursor("test-account-a", "recommend", snapshot)
            assertEquals(snapshot, cache.loadCursor("test-account-a", "recommend"))
            assertNull(cache.loadCursor("test-account-a", DramaCategory.COMIC.route))
            // 身份一变（换账号/退出）整份缓存清空，不能把上一个账号的续刷位置带过去。
            assertNull(cache.loadCursor("test-account-b", "recommend"))
            assertNull(cache.loadCursor("test-account-a", "recommend"))
            cache.saveCursor("test-account-a", "recommend", snapshot)
            // 超过 64 KiB 的快照不落盘。
            cache.saveCursor("test-account-a", "recommend", "x".repeat(64_001))
            assertEquals(snapshot, cache.loadCursor("test-account-a", "recommend"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun coldStartResumesSavedCursorInsteadOfReplayingStarterPage() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(controller.get()) { 1000L }
        cache.save("guest", "recommend", page)
        cache.saveCursor("guest", "recommend", """{"session":"old-session","offset":6,"seen":["101"]}""")
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        fun payload(): String {
            val card =
                JSONObject()
                    .put("series_id", "202")
                    .put("series_title", "续刷作品")
                    .put("episode_cnt", 3)
            val tab =
                JSONObject()
                    .put("tab_type", 16)
                    .put("session_id", "live-session")
                    .put(
                        "cell_data",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put(
                                        "cell_data",
                                        JSONArray()
                                            .put(JSONObject().put("video_data", JSONArray().put(card))),
                                    ),
                            ),
                    )
            return JSONObject()
                .put("code", 0)
                .put("data", JSONObject().put("tab_item", JSONArray().put(tab)))
                .toString()
        }
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(payload().toResponseBody())
                        .build()
                }
                .build()
        try {
            val backend =
                PublicFeedBackend(
                    720,
                    http = http,
                    startupCache = cache,
                    playbackSession = testPlaybackDeviceSession(),
                )
            try {
                val result = backend.recommendations(1, DramaFeedState().recommendationCursor)
                // 有续刷快照时直接续拉，不再复用首屏缓存里的那一部。
                assertEquals(listOf("202"), result.items.map { it.id })
                assertEquals("old-session", requests.single().url.queryParameter("session_id"))
                assertEquals("6", requests.single().url.queryParameter("offset"))
                assertEquals(
                    "live-session",
                    JSONObject(cache.loadCursor("guest", "recommend")!!).optString("session"),
                )
            } finally {
                backend.close()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
