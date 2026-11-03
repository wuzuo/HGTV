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

/**
 * 播放身份：优先用本地配置里的真实设备身份，没有就注册一台匿名设备。
 *
 * 两者的差别只在播放接口的放行额度上——实测新注册的设备身份基本只放行一次，而服务端已经认过的
 * 老身份不限次。所以本地身份（[Profile.trusted]）跨播放复用，注册身份则用一次就换。
 */
class PlaybackDeviceSession(
    private val localIdentity: () -> DeviceIdentity? = { DeviceIdentityFile.read() },
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
    },
) {
    companion object {
        val shared = PlaybackDeviceSession()
    }

    internal data class Profile(
        val values: Map<String, String>,
        val cookie: String,
        /**
         * 来自本地配置的真实设备身份。
         *
         * 为 true 时播放接口不按「新设备只放行一次」处理：跨播放复用同一套 `device_id`，也不再
         * 主动替换。注册来的身份是 false，用一次就得换。
         */
        val trusted: Boolean = false,
    )

    private var cdid = UUID.randomUUID().toString()
    private var ready: Profile? = null

    /** 本地配置已经被判定不可用（播放拿不到内容），本次进程内不再尝试，交给自动注册兜底。 */
    private var localRejected = false

    /**
     * 丢弃缓存的设备身份，下次 [profile] 会重新取一台。
     *
     * 对注册身份而言这是「换一台新设备」：服务端按 `device_id` + `install_id` 这一对放行内容接口，
     * 而新注册的这对身份对播放接口基本只有一次额度，之后再请求一律 `200 + 0 字节`，退避重试都没用，
     * 只有换身份才恢复。
     *
     * 对本地配置的真实身份而言，这只表示「这份配置失效了」：把它记下来，后续改走自动注册，
     * 避免拿着坏配置反复失败。
     */
    @Synchronized
    internal fun invalidate() {
        if (ready?.trusted == true) localRejected = true
        ready = null
        cdid = UUID.randomUUID().toString()
    }

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        if (!localRejected) {
            val identity = runCatching { localIdentity() }.getOrNull()
            if (identity != null) {
                return Profile(
                        VendorConstants.device +
                            mapOf(
                                "device_id" to identity.deviceId,
                                "iid" to identity.installId,
                                "cdid" to identity.cdid,
                            ),
                        cookie = "",
                        trusted = true,
                    )
                    .also { ready = it }
            }
        }
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
