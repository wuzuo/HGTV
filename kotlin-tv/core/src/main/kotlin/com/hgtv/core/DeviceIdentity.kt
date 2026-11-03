// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.io.File

/**
 * 一台**真实设备**的播放身份：官方客户端注册后长期复用的那三个值。
 *
 * @property deviceId  `applog_stats.xml` 里的 `device_id`
 * @property installId 同一文件里的 `install_id`（请求里叫 `iid`）
 * @property cdid      `com.ss.android.deviceregister.utils.Cdid.xml` 里的 `cdid`
 */
data class DeviceIdentity(
    val deviceId: String,
    val installId: String,
    val cdid: String,
)

/**
 * 本地设备身份文件：读取用户自己从官方客户端导出的设备信息，省掉每次播放的自动注册。
 *
 * ## 为什么要这个文件
 *
 * 播放接口对**新注册的**设备身份只放行一次，之后一律 `200 + 0 字节`；对服务端已经认过的老身份
 * 则不限制。这个「老」是服务端侧的设备历史，客户端伪造不出来，所以只有两条路：每次播放换一台
 * 新设备（多一次注册请求），或者直接使用一台真实设备的身份。这里是后者。
 *
 * ## 文件格式
 *
 * 纯文本，一行一个 `键=值`，`#` 之后是注释，键名不区分大小写（下面是占位符，填自己的真实值）：
 *
 * ```
 * # 从 /data/data/com.phoenix.read/shared_prefs/applog_stats.xml 取前两个
 * device_id=7000000000000000001
 * install_id=7000000000000000002
 * # 从 .../shared_prefs/com.ss.android.deviceregister.utils.Cdid.xml 取这个
 * cdid=3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d
 * ```
 *
 * ## 注意
 *
 * * 同一份身份不要和官方客户端同时播放：两边共用一个 `device_id`，并发请求会被判成设备异常。
 * * 身份失效（播放持续空响应）后会**自动退回注册**，不会一直卡在坏配置上；此时程序照常能播，
 *   只是恢复成每集注册一次。
 * * 文件缺失、字段不全或格式不合法都当作「没有配置」，静默走自动注册，不影响正常使用。
 */
object DeviceIdentityFile {
    /** 身份文件路径，由各平台在启动时设置；为空表示不启用本地身份。 */
    @Volatile var path: File? = null

    private val idPattern = Regex("[1-9][0-9]{0,29}")

    /** `cdid` 是 UUID，但放宽到字母数字与常见分隔符，避免不同版本格式差异把用户挡在外面。 */
    private val cdidPattern = Regex("[0-9A-Za-z._-]{4,64}")

    /** 读并校验；任何一步不满足都返回 null，由调用方走自动注册。 */
    fun read(file: File? = path): DeviceIdentity? {
        if (file == null || !file.isFile) return null
        val values =
            try {
                file
                    .readLines(Charsets.UTF_8)
                    .map { it.trimStart('\uFEFF').substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { line ->
                        val at = line.indexOf('=')
                        if (at <= 0) null
                        else
                            line.substring(0, at).trim().lowercase() to
                                line
                                    .substring(at + 1)
                                    .trim()
                                    .removeSurrounding("\"")
                                    .removeSurrounding("'")
                    }
                    .toMap()
            } catch (_: Exception) {
                return null
            }
        fun pick(vararg keys: String) =
            keys.firstNotNullOfOrNull { key -> values[key]?.takeIf { it.isNotEmpty() } }
        val deviceId = pick("device_id", "deviceid") ?: return null
        val installId = pick("install_id", "iid", "installid") ?: return null
        val cdid = pick("cdid") ?: return null
        if (!idPattern.matches(deviceId) || !idPattern.matches(installId)) return null
        if (!cdidPattern.matches(cdid)) return null
        return DeviceIdentity(deviceId, installId, cdid)
    }
}
