package com.hgtv.identity

/**
 * 纯逻辑层：把 `su -c` 抓到的红果 shared_prefs 原文解析成设备身份。
 *
 * 不依赖任何 Android API，方便单测。校验规则与 HGTV 的 `DeviceIdentityFile` 保持一致，
 * 避免出现「App 说读到有效值、HGTV 却判定不合法」的错位。
 *
 * 输入格式（由 MainActivity.dumpScript 生成）：
 * ```
 * ##UID 0
 * ##SDK 34
 * ##PM-BEGIN
 * com.phoenix.read
 * ##PM-END
 * ##FILE /data/data/com.phoenix.read/shared_prefs/applog_stats.xml
 * <map> ... </map>
 * ##CAND 2
 * ```
 * 不再要求包名必须是 `com.phoenix.read`：现在是把本机所有含 `cdid` / `device_id` 的
 * shared_prefs 都抓回来，按「像不像红果」排序后挑一份，包名从文件路径反推。
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
        data class Success(val pkg: String, val identity: Identity, val note: String? = null) : Outcome()

        data class Failure(val message: String) : Outcome()
    }

    private val idPattern = Regex("[1-9][0-9]{0,29}")
    private val cdidPattern = Regex("[0-9A-Za-z._-]{4,64}")

    private val textForm = Regex("<(\\w+)[^>]*\\bname=\"([A-Za-z0-9_]+)\"[^>]*>([^<]*)</\\1>")
    private val attrForm = Regex("<(\\w+)[^>]*\\bname=\"([A-Za-z0-9_]+)\"[^>]*\\bvalue=\"([^\"]*)\"")

    private val pkgFromPath = Regex("/data/(?:data|user/[0-9]+|user_de/[0-9]+)/([^/]+)/")

    fun parse(dump: String): Outcome {
        if (dump.startsWith("##ERROR")) {
            return Outcome.Failure("执行 su 失败：${dump.removePrefix("##ERROR").trim()}（设备可能没有 root）")
        }
        val uid = Regex("##UID\\s+(\\d+)").find(dump)?.groupValues?.get(1)
        if (uid != "0") {
            return Outcome.Failure("没有拿到 root 权限。请在 Magisk / KernelSU 里给本应用授权，然后点「重新读取」。")
        }

        val blocks = splitBlocks(dump)
        val legacyPkg = Regex("##PKG\\s+(\\S+)").find(dump)?.groupValues?.get(1)

        // 同一个应用的 device_id / install_id / cdid 往往分散在不同 xml 里，
        // 所以先按包名分组再合并；不同应用之间绝不能混（番茄小说和红果是两套身份）。
        val groups = LinkedHashMap<String, MutableList<Pair<String, Map<String, String>>>>()
        blocks.forEach { (path, body) ->
            val values = valuesOf(body)
            if (values.isEmpty()) return@forEach
            val pkg = pkgOf(path) ?: legacyPkg ?: "com.phoenix.read"
            groups.getOrPut(pkg) { mutableListOf() }.add(path to values)
        }

        val complete = mutableListOf<Candidate>()
        var partialFile: String? = null
        var invalidSample: Candidate? = null

        groups.forEach { (pkg, files) ->
            val merged = LinkedHashMap<String, String>()
            files.forEach { (_, values) ->
                values.forEach { (key, value) -> if (!merged.containsKey(key)) merged[key] = value }
            }
            val deviceId = pick(merged, "device_id", "deviceid")
            val installId = pick(merged, "install_id", "iid", "installid")
            val cdid = pick(merged, "cdid")
            if (deviceId == null && cdid == null) return@forEach
            if (partialFile == null) partialFile = files.firstOrNull { it.first.isNotEmpty() }?.first
            if (deviceId == null || installId == null || cdid == null) return@forEach
            val candidate = Candidate(pkg, Identity(deviceId, installId, cdid), files.first().first)
            val valid = idPattern.matches(deviceId) && idPattern.matches(installId) && cdidPattern.matches(cdid)
            if (valid) complete += candidate else invalidSample = candidate
        }

        if (complete.isNotEmpty()) {
            val best = complete.firstOrNull { isLikelyRedFruit(it.pkg) } ?: complete.first()
            val note =
                if (isLikelyRedFruit(best.pkg)) {
                    null
                } else {
                    "这份身份来自 ${best.pkg}，不是红果（红果免费短剧 = com.phoenix.read）。" +
                        "如果 HGTV 提示身份失效，请回红果里重新读一次。"
                }
            return Outcome.Success(best.pkg, best.identity, note)
        }

        invalidSample?.let {
            return Outcome.Failure(
                "读到的值格式不对，可能红果换了存储方式：device_id=${it.identity.deviceId}, " +
                    "install_id=${it.identity.installId}, cdid=${it.identity.cdid}",
            )
        }

        partialFile?.let { path ->
            return Outcome.Failure(
                "找到了配置文件 ${shortPath(path)}，但里面没有完整的设备信息" +
                    "（device_id / install_id / cdid）。请打开红果随便播一集，等它完成设备注册后再点「重新读取」。",
            )
        }

        return Outcome.Failure(noCandidateMessage(dump))
    }

    // ------------------------------------------------------------- 失败原因

    private fun noCandidateMessage(dump: String): String {
        val scanned = Regex("##CAND\\s+(\\d+)").find(dump)?.groupValues?.get(1)
        val pmList =
            dump.lineSequence()
                .dropWhile { !it.startsWith("##PM-BEGIN") }
                .drop(1)
                .takeWhile { !it.startsWith("##PM-END") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        val hint =
            if (pmList.isEmpty()) {
                "已安装的应用里也没有包名像红果的（phoenix），请确认红果是不是装在这台机器上。"
            } else {
                "已安装的疑似应用：${pmList.joinToString("、")}。"
            }
        val scanText = if (scanned != null) "扫描了本机 $scanned 个配置文件，" else ""
        return "没有找到红果的数据目录。已拿到 root，${scanText}但没有任何一份包含红果的设备信息。" +
            hint +
            "另外请确认红果没有被装在「应用分身/工作资料」里（那种情况下数据在其它用户目录）。"
    }

    // ------------------------------------------------------------- 解析细节

    private data class Candidate(val pkg: String, val identity: Identity, val path: String)

    private fun splitBlocks(dump: String): List<Pair<String, String>> {
        val idx = dump.indexOf("##FILE ")
        if (idx < 0) {
            // 兼容没有 ##FILE 分隔的旧格式：整段当成一个块
            return listOf("" to dump)
        }
        return dump.substring(idx)
            .split("##FILE ")
            .asSequence()
            .filter { it.isNotBlank() }
            .map { chunk ->
                val nl = chunk.indexOf('\n')
                if (nl < 0) chunk.trim() to "" else chunk.substring(0, nl).trim() to chunk.substring(nl + 1)
            }
            .toList()
    }

    private fun valuesOf(text: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        // <string name="device_id">1782760663187289</string>
        textForm.findAll(text).forEach { match ->
            val key = match.groupValues[2]
            val value = match.groupValues[3].trim()
            if (value.isNotEmpty() && !values.containsKey(key)) values[key] = value
        }
        // <long name="device_id" value="1782760663187289" />
        attrForm.findAll(text).forEach { match ->
            val key = match.groupValues[2]
            val value = match.groupValues[3].trim()
            if (value.isNotEmpty() && !values.containsKey(key)) values[key] = value
        }
        return values
    }

    private fun pick(values: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> values[key]?.takeIf { it.isNotEmpty() } }

    private fun pkgOf(path: String): String? = pkgFromPath.find(path)?.groupValues?.get(1)

    private fun isLikelyRedFruit(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("phoenix") || lower.contains("hongguo") || lower.contains("redfruit")
    }

    private fun shortPath(path: String): String {
        val pkg = pkgOf(path) ?: return path
        val name = path.substringAfterLast('/')
        return "$pkg/shared_prefs/$name"
    }
}
