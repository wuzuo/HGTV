// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/** Anonymous playback identity. Register once, then keep the same identity across repositories. */
class PlaybackDeviceSession(
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (key, value) ->
            request.header(key, value)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        // Registration has no account cookies. Cancellation belongs to the requesting work scope.
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            DeviceRegister.parse(cloudResponse(response), response.headers("Set-Cookie"))
        }
    }
) {
    companion object {
        val shared = PlaybackDeviceSession()
    }

    internal data class Profile(val values: Map<String, String>, val cookie: String)

    private var cdid = UUID.randomUUID().toString()
    private var ready: Profile? = null

    /**
     * 丢弃缓存的设备身份，下次 [profile] 会重新注册一台。
     *
     * 平台按「`device_id` + `install_id`」这一对身份放行内容接口，而这对身份在进程内只注册一次。
     * 一旦服务端把它判成过期或限流，之后每个播放请求都只回 `200 + 0 字节`，客户端再怎么退避重试
     * 都没用——用户看到的就是「播放信息接口返回空响应」，**只有重启应用（等于重新注册）才恢复**。
     * 现在由调用方在空响应时主动丢弃身份，把「重启应用」收敛成一次重试。
     * `cdid` 一并换新，让新身份与重启后的状态完全等价。
     */
    @Synchronized
    internal fun invalidate() {
        ready = null
        cdid = UUID.randomUUID().toString()
    }

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val device =
            try {
                register(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        val validId = Regex("[1-9][0-9]{0,29}")
        if (!validId.matches(device.deviceId) || !validId.matches(device.installId))
            throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return Profile(
                VendorConstants.device +
                    mapOf(
                        "device_id" to device.deviceId,
                        "iid" to device.installId,
                        "cdid" to cdid,
                    ),
                device.cookies
                    .filterKeys {
                        it in setOf("install_id", "ttreq", "store-region", "store-region-src")
                    }
                    .entries
                    .joinToString("; ") { (name, value) -> "$name=$value" },
            )
            .also { ready = it }
    }
}
