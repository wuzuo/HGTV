// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

/**
 * Android v5 播放模型请求（v04.10.03）。
 *
 * 与旧的 v3 路径完全不同：
 *  - 固定走 `api5-normal-sinfonlinea.fqnovel.com`
 *  - 用移动版 profile（74332 / 7.4.3.32）、`device_level=1`
 *  - 只发 `x-khronos` / `x-helios` / `x-medusa` 三个签名头
 *  - 精确绑定最终 query 与 gzip body，靠 `_rticket` 扫描选中 branch 12
 *
 * 服务端在 2026-11-17 前后恢复了对 v5 medusa 的要求；旧路径（真实 Argus/Ladon、不带
 * medusa）一律返回 `200 + 0 字节`，这条路径是 hongguoTV-main 上已验证可用的实现。
 */
internal object MobilePlayback {
    const val ENDPOINT =
        "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"

    /** 进程内唯一签名器：v5 medusa 里嵌的进程 nonce / 启动时间必须跨请求稳定。 */
    private val v5Signer = Signer(mobileV5 = true)

    /**
     * 移动版查询参数。[device] 必须提供 `device_id` / `iid` / `cdid`，取自匿名注册身份；
     * 其余字段是固定的移动版 profile，v5 medusa 明文会内嵌 version_name/device_type 等。
     */
    fun query(device: Map<String, String>): LinkedHashMap<String, String> =
        linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "74332",
            "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64",
            "device_platform" to "android",
            "device_type" to "PGT-AN10",
            "os_version" to "12",
            "update_version_code" to "74332",
            "iid" to device.getValue("iid"),
            "cdid" to device.getValue("cdid"),
            "device_id" to device.getValue("device_id"),
        )

    /** 保留带时间戳的重载，供既有 v5 主请求调用方使用。 */
    fun query(device: Map<String, String>, now: Long): LinkedHashMap<String, String> =
        query(device).apply { put("_rticket", now.toString()) }

    /**
     * GET video_tab/multi_video_model 也必须用 v5 签名：空 body 参与签名（md5(empty)），
     * 但最终请求是 GET，去掉 stub/Content-Type/Content-Encoding。
     *
     * 这是 hongguoTV-main 2026-11-22 的空响应修复：此前用 v3 签名的 GET 一律 200+0B。
     * UA 用官方移动版完整串（对齐参考 videoTabRequest），not PLAYER_UA。
     */
    fun videoTabRequest(
        id: String,
        device: Map<String, String>,
        host: String,
        now: Long,
    ): okhttp3.Request.Builder {
        require(id.matches(Regex("[0-9]{1,30}")))
        val values = query(device) + linkedMapOf(
            "video_id" to id,
            "video_id_type" to "0",
            "device_level" to "1",
            "need_all_video_definition" to "1",
            "need_mp4_align" to "0",
            "use_os_player" to "0",
            "video_platform" to "1024",
            "source" to "0",
            "from_video_id" to "",
            "_rticket" to now.toString(),
        )
        val url = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"
        val signed = signPlaybackV5(url, values, ByteArray(0), now)
        return okhttp3.Request.Builder()
            .url(signed.url)
            .get()
            .header(
                "User-Agent",
                "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)",
            )
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                signed.headers.forEach { (k, v) -> header(k, v) }
                removeHeader("x-ss-stub")
                removeHeader("Content-Type")
                removeHeader("Content-Encoding")
            }
    }

    /** v5 签名：空 body 用 ByteArray(0)（GET 场景），保持 medusa 与 query 同源。 */
    private fun signPlaybackV5(
        base: String,
        values: Map<String, String>,
        body: ByteArray,
        now: Long,
    ): Signer.Request {
        val signed = v5Signer.signPlayback(base, values, body, now)
        // mobileV5 下 signPlayback 只保留 x-khronos/x-helios/x-medusa，
        // 这里补齐 Accept，供 GET 使用；body 由调用方决定是否附加。
        val headers = LinkedHashMap(signed.headers)
        return Signer.Request(signed.url, headers, null)
    }

    /** gzip 的请求体，逐字段对齐已验证实现（device_level=1 换可解码的 HEVC 档位）。 */
    fun body(id: String): ByteArray {
        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}"""
        )
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        return output.toByteArray()
    }

    /**
     * 构造签名后的请求。[values] 必须是 [query] 返回的同一个 map（含 `_rticket`），
     * [body] 必须是 [body] 返回的同一份 gzip 字节 —— v5 签名对两者逐字节绑定。
     */
    fun sign(base: String, values: Map<String, String>, body: ByteArray, now: Long): Signer.Request {
        val signed = v5Signer.signPlayback(base, values, body, now)
        val headers = LinkedHashMap(signed.headers)
        headers["User-Agent"] = VendorConstants.PLAYER_UA
        headers["Accept"] = "application/json"
        headers["Content-Encoding"] = "gzip"
        return Signer.Request(signed.url, headers, signed.body)
    }
}
