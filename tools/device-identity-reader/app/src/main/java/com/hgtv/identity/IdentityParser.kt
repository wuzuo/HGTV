package com.hgtv.identity

/**
 * 纯逻辑层：把 `su -c` 抓到的红果 shared_prefs 原文解析成设备身份。
 *
 * 不依赖任何 Android API，方便单测。校验规则与 HGTV 的 `DeviceIdentityFile` 保持一致，
 * 避免出现「App 说读到有效值、HGTV 却判定不合法」的错位。
 */
object IdentityParser {

    data class Identity(val deviceId: String, val installId: String, val cdid: String) {
        fun block(pkg: String): String =
            buildString {
                appendLine("# HGTV 本地设备身份（免注册播放）")
                appendLine("# 由「HGTV 设备身份读取」从官方红果客户端导出")
                appendLine("# 来源包名：$pkg")
                appendLine("# 注意：同一份身份不要和手机上的红果同时播放，否则会被判成设备异常。")
                appendLine("device_id=$deviceId")
                appendLine("install_id=$installId")
                appendLine("cdid=$cdid")
            }
    }

    sealed class Outcome {
        data class Success(val pkg: String, val identity: Identity) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    private val idPattern = Regex("[1-9][0-9]{0,29}")
    private val cdidPattern = Regex("[0-9A-Za-z._-]{4,64}")

    private val textForm = Regex("<(\\w+)[^>]*\\bname=\"([A-Za-z0-9_]+)\"[^>]*>([^<]*)</\\1>")
    private val attrForm = Regex("<(\\w+)[^>]*\\bname=\"([A-Za-z0-9_]+)\"[^>]*\\bvalue=\"([^\"]*)\"")

    fun parse(dump: String): Outcome {
        if (dump.startsWith("##ERROR")) {
            return Outcome.Failure("执行 su 失败：${dump.removePrefix("##ERROR").trim()}（设备可能没有 root）")
        }
        val uid = Regex("##UID\\s+(\\d+)").find(dump)?.groupValues?.get(1)
        if (uid != "0") {
            return Outcome.Failure("没有拿到 root 权限。请在 Magisk 里给本应用授权，然后点「重新读取」。")
        }
        val pkg =
            Regex("##PKG\\s+(\\S+)").find(dump)?.groupValues?.get(1)
                ?: return Outcome.Failure("没有找到红果的数据目录。请先安装并打开「红果免费短剧」。")

        val values = LinkedHashMap<String, String>()
        // <string name="device_id">1782760663187289</string>
        textForm.findAll(dump).forEach { match ->
            val key = match.groupValues[2]
            val value = match.groupValues[3].trim()
            if (value.isNotEmpty() && !values.containsKey(key)) values[key] = value
        }
        // <long name="device_id" value="1782760663187289" />
        attrForm.findAll(dump).forEach { match ->
            val key = match.groupValues[2]
            val value = match.groupValues[3].trim()
            if (value.isNotEmpty() && !values.containsKey(key)) values[key] = value
        }

        fun pick(vararg keys: String) =
            keys.firstNotNullOfOrNull { key -> values[key]?.takeIf { it.isNotEmpty() } }

        val deviceId = pick("device_id", "deviceid")
        val installId = pick("install_id", "iid", "installid")
        val cdid = pick("cdid")
        if (deviceId == null || installId == null || cdid == null) {
            return Outcome.Failure(
                "红果的数据目录在，但没找到完整的设备信息（device_id / install_id / cdid）。" +
                    "请打开红果随便播一集，触发一次设备注册后再回来点「重新读取」。",
            )
        }
        if (!idPattern.matches(deviceId) || !idPattern.matches(installId) || !cdidPattern.matches(cdid)) {
            return Outcome.Failure(
                "读到的值格式不对，可能红果换了存储方式：device_id=$deviceId, install_id=$installId, cdid=$cdid",
            )
        }
        return Outcome.Success(pkg, Identity(deviceId, installId, cdid))
    }
}
