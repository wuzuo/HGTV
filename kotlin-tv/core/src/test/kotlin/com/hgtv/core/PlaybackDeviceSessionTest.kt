package com.hgtv.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlaybackDeviceSessionTest {
    private val model =
        """{"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""

    @Test
    fun registersBeforePlaybackAndReusesIdentityAcrossRepositoriesAndRetries() {
        var registrations = 0
        var models = 0
        val requests = mutableListOf<okhttp3.Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val registration = request.url.encodedPath == "/service/2/device_register/"
                    val body =
                        if (registration) {
                            registrations++
                            assertNull(request.header("Cookie"))
                            assertNull(request.header("x-gorgon"))
                            """{"device_id":"333","install_id":"444"}"""
                        } else {
                            requests += request
                            assertEquals("333", request.url.queryParameter("device_id"))
                            assertEquals("444", request.url.queryParameter("iid"))
                            assertEquals("ttreq=test-only", request.header("Cookie"))
                            assertNotNull("播放请求必须带完整签名", request.header("x-gorgon"))
                            models++
                            model
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .apply {
                            if (registration) addHeader("Set-Cookie", "ttreq=test-only; Path=/")
                        }
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val session = PlaybackDeviceSession()
        ContentRepository(http, playbackSession = session).streamLadder("123")
        ContentRepository(http, playbackSession = session).streamLadder("123")
        // 成功拿到模型就不会换身份：跨 Repository 复用同一台设备。
        assertEquals(1, registrations)
        assertEquals(2, models)
        assertEquals(1, requests.map { it.url.queryParameter("cdid") }.distinct().size)
    }

    @Test
    fun concurrentCallersRegisterOnlyOnceAndKeepOneProfile() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            DeviceRegister.Registered("333", "444")
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first =
                pool.submit<PlaybackDeviceSession.Profile> { session.profile(OkHttpClient()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second =
                pool.submit<PlaybackDeviceSession.Profile> { session.profile(OkHttpClient()) }
            release.countDown()
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun rejectedRegistrationNeverRequestsPlaybackWithRandomIds() {
        var models = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    models++
                    error("Playback must not be requested")
                }
                .build()
        for (registered in
            listOf(
                null,
                DeviceRegister.Registered("0", "444"),
                DeviceRegister.Registered("333", "bad"),
            )) {
            val repo =
                ContentRepository(http, playbackSession = PlaybackDeviceSession { registered })
            assertThrows(PlaybackDeviceRegistrationException::class.java) {
                repo.streamLadder("123")
            }
        }
        assertEquals(0, models)
    }

    @Test
    fun registrationFailureCanBeRetriedButSuccessfulIdentityNeverRotates() {
        var calls = 0
        val session = PlaybackDeviceSession {
            if (++calls == 1) throw IOException("test-only")
            DeviceRegister.Registered(
                "333",
                "444",
                cookies = mapOf("ttreq" to "test-only", "sessionid" to "excluded"),
            )
        }
        val http = OkHttpClient()
        assertThrows(PlaybackDeviceRegistrationException::class.java) { session.profile(http) }
        val profile = session.profile(http)
        assertEquals("ttreq=test-only", profile.cookie)
        assertSame(profile, session.profile(http))
        assertEquals(2, calls)
    }

    /**
     * 空响应不是网络抖动，而是服务端不再认这台设备：必须换一台设备身份重试。
     *
     * 线上表现就是「播放信息接口返回空响应」，用户关掉应用重开（= 重新注册）才恢复。
     * 这条用例锁住「客户端自己会做这件事」，并且确认 `cdid` 也一起换新（与重启后等价）。
     */
    @Test
    fun emptyPlaybackResponseRotatesDeviceIdentityInsteadOfFailing() {
        var registrations = 0
        val devices = mutableListOf<String>()
        val cdids = mutableListOf<String>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val registration = request.url.encodedPath == "/service/2/device_register/"
                    val body =
                        if (registration) {
                            val id = if (++registrations == 1) "111" else "222"
                            """{"device_id":"$id","install_id":"$id"}"""
                        } else {
                            val device = request.url.queryParameter("device_id").orEmpty()
                            devices += device
                            cdids += request.url.queryParameter("cdid").orEmpty()
                            // 第一台设备被服务端静默拒绝：200 + 0 字节。
                            if (device == "111") "" else model
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .apply {
                            if (registration) addHeader("Set-Cookie", "ttreq=test-only; Path=/")
                        }
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val ladder = ContentRepository(http, playbackSession = PlaybackDeviceSession()).streamLadder("123")
        assertEquals(listOf(720), ladder.renditions.map { it.quality })
        assertEquals(2, registrations)
        assertTrue("first attempts must use the original identity: $devices", devices.all { it == "111" || it == "222" })
        assertEquals("222", devices.last())
        // 播放接口一台设备只放行一次，所以第一台设备只发一次请求就被换掉。
        assertEquals(1, devices.count { it == "111" })
        assertEquals("cdid must be regenerated with the identity", 2, cdids.distinct().size)
    }

    /** 三个域名都空响应就到此为止：每个域名只发一次，把真实原因交给界面。 */
    @Test
    fun persistentEmptyResponseRotatesOnlyOnceThenReportsEmptyResponse() {
        var registrations = 0
        var models = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val registration = request.url.encodedPath == "/service/2/device_register/"
                    val body =
                        if (registration) {
                            registrations++
                            """{"device_id":"111","install_id":"111"}"""
                        } else {
                            models++
                            ""
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession())
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.streamLadder("123") }
        // 播放域名有 5 个，每个都要换一台设备身份（首台设备注册 1 次 + 换 4 次）。
        assertEquals(5, registrations)
        assertEquals(5, models)
    }

    /** 设备未被放行（101000）同样换一次身份，不用等用户重启。 */
    @Test
    fun deviceRejectedCodeRotatesIdentityOnce() {
        var registrations = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val registration = request.url.encodedPath == "/service/2/device_register/"
                    val body =
                        if (registration) {
                            val id = if (++registrations == 1) "111" else "222"
                            """{"device_id":"$id","install_id":"$id"}"""
                        } else if (request.url.queryParameter("device_id") == "111") {
                            """{"Code":101000,"Message":"服务异常"}"""
                        } else model
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val ladder = ContentRepository(http, playbackSession = PlaybackDeviceSession()).streamLadder("123")
        assertEquals(listOf(720), ladder.renditions.map { it.quality })
        assertEquals(2, registrations)
    }

    @Test
    fun cancelledScopeDoesNotSendOrCacheRegistration() {
        var registrations = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    registrations++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body("""{"device_id":"333","install_id":"444"}""".toResponseBody())
                        .build()
                }
                .build()
        val session = PlaybackDeviceSession()
        val scope = RequestScope()
        scope.cancel()
        assertThrows(IOException::class.java) { scope.run { session.profile(http) } }
        assertEquals(0, registrations)
        session.profile(http)
        assertEquals(1, registrations)
    }

    /** 有本地配置就不该再打注册接口——这正是「省掉每次播放一次注册」的关键。 */
    @Test
    fun localIdentitySkipsRegistrationAndIsMarkedTrusted() {
        var registrations = 0
        val session =
            PlaybackDeviceSession(
                register = {
                    registrations++
                    DeviceRegister.Registered("333", "444")
                },
                localIdentity = { DeviceIdentity("777", "888", LOCAL_CDID) },
            )
        val profile = session.profile(OkHttpClient())
        assertTrue("本地配置的身份必须标记成 trusted", profile.trusted)
        assertEquals("777", profile.values["device_id"])
        assertEquals("888", profile.values["iid"])
        assertEquals(LOCAL_CDID, profile.values["cdid"])
        assertEquals("本地身份没有注册响应，Cookie 只能是空", "", profile.cookie)
        assertEquals(0, registrations)
        assertSame(profile, session.profile(OkHttpClient()))
    }

    /** 本地配置被判定失效后再 invalidate：本次进程不再读它，改走自动注册兜底。 */
    @Test
    fun rejectedLocalIdentityFallsBackToRegistration() {
        var registrations = 0
        val session =
            PlaybackDeviceSession(
                register = {
                    registrations++
                    DeviceRegister.Registered("333", "444")
                },
                localIdentity = { DeviceIdentity("777", "888", LOCAL_CDID) },
            )
        assertTrue(session.profile(OkHttpClient()).trusted)
        session.invalidate()
        val next = session.profile(OkHttpClient())
        assertFalse("失效的本地身份不能一直复用", next.trusted)
        assertEquals("333", next.values["device_id"])
        assertEquals(1, registrations)
        // 之后即使再 invalidate，也不该回头读那份已经废掉的配置。
        session.invalidate()
        assertEquals("333", session.profile(OkHttpClient()).values["device_id"])
        assertEquals(2, registrations)
    }

    private companion object {
        const val LOCAL_CDID = "6f1c2a9e-2b3d-4c5e-8f70-91a2b3c4d5e6"
    }
}
