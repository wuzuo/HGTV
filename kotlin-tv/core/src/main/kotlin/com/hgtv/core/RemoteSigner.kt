// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 远程签名：把请求交给「真机签名服务」算 X-Medusa 等头部。
 *
 * 本机 Kotlin 移植版算出的 X-Medusa 比真机短约 160 字节，服务端会静默拒绝（HTTP 200 + 空包），
 * 而其余 6 个签名头本机算得是对的。所以播放接口改成：签名交给装着红果的手机算，
 * 网络请求仍由本机发出。
 *
 * 服务端协议见 `.workbuddy/signsvc/`：
 *   POST {endpoint}/sign  {"url":..., "headers":[[k,v],...], "body_b64":...}
 *   -> {"ok":true, "url":"<身份与时间戳都已对齐的 url>", "headers":{...}}
 *
 * 服务会把 URL 里的 device_id / iid 改成签名手机上红果的当前身份（X-Medusa 内嵌了
 * device_id，不一致会被静默拒绝），并算好 x-ss-stub。所以**必须用返回的 url 发请求**。
 *
 * 失败时返回 null，调用方回退到本机 [Signer]，不改变原有行为；
 * [lastError] / [successes] / [failures] 供「播放诊断」展示。
 */
object RemoteSigner {
    /** 形如 `http://192.168.0.102:18089`；为空表示不启用远程签名。 */
    @Volatile var endpoint: String? = null

    /** 给「播放诊断」看：失败原因不能静默吞掉，否则电视上完全看不出为什么退回本机签名。 */
    @Volatile var lastError: String? = null
    @Volatile var successes: Int = 0
    @Volatile var failures: Int = 0

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * 给 [base]（不含 query）签名。只需带上参与签名的少数几个头，
     * 其余头（Cookie、lc、x-reading-request 等）在拿到结果后再补不会破坏签名。
     */
    internal fun sign(
        base: String,
        values: Map<String, String>,
        body: ByteArray? = null,
    ): Signer.Request? {
        val root = endpoint?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val result = signOrThrow(root, base, values, body)
            lastError = null
            successes++
            result
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            failures++
            null
        }
    }

    private fun signOrThrow(
        root: String,
        base: String,
        values: Map<String, String>,
        body: ByteArray?,
    ): Signer.Request {
        val ticket = System.currentTimeMillis()
        val url = "$base?${Signer.encode(values + mapOf("_rticket" to ticket.toString()))}"
        val headers =
            linkedMapOf(
                "User-Agent" to
                    (if (body != null) VendorConstants.VIDEO_UA else VendorConstants.APP_UA),
                "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                "x-ss-req-ticket" to ticket.toString(),
                "x-tt-request-tag" to "t=0;n=0",
                "sdk-version" to "2",
                "passport-sdk-version" to "5051452",
                "x-xs-from-web" to "0",
            )
        val payload =
            JSONObject()
                .put("url", url)
                .put(
                    "headers",
                    JSONArray().apply { headers.forEach { (k, v) -> put(JSONArray().put(k).put(v)) } },
                )
        if (body != null) payload.put("body_b64", body.base64())
        val request =
            Request.Builder()
                .url("$root/sign")
                .post(payload.toString().toRequestBody(json))
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("签名服务 HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            val obj = JSONObject(text)
            if (!obj.optBoolean("ok")) error("签名服务失败：${obj.optString("err")}")
            val signed = linkedMapOf<String, String>()
            val h = obj.optJSONObject("headers") ?: JSONObject()
            h.keys().forEach { k -> signed[k] = h.optString(k) }
            return Signer.Request(obj.getString("url"), signed, body)
        }
    }
}
