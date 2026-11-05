package com.hgtv.identity

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * 读取本机「红果免费短剧」设备身份（device_id / install_id / cdid），导出成 HGTV 认的
 * `device-identity.txt`，供电视端 HGTV 免注册播放使用。
 *
 * 红果把这些值放在自己的私有目录里（/data/data/com.phoenix.read/shared_prefs/），Android
 * 沙箱禁止普通应用读取，所以**必须 root**（Magisk 授权 su）。
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dollar = "$"
    private val saveRequestCode = 1001

    private lateinit var statusView: TextView
    private lateinit var idDevice: TextView
    private lateinit var idInstall: TextView
    private lateinit var idCdid: TextView
    private lateinit var blockView: TextView
    private lateinit var diagView: TextView
    private lateinit var reloadButton: Button
    private lateinit var copyBlockButton: Button
    private lateinit var shareButton: Button
    private lateinit var saveButton: Button
    private lateinit var writeButton: Button
    private lateinit var diagButton: Button

    private var identity: IdentityParser.Identity? = null
    private var sourcePackage: String? = null
    private var rawDump: String = ""
    private var dumpFile: java.io.File? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        startRead()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun label(
        text: String,
        sizeSp: Float,
        bold: Boolean = false,
        color: Int = Color.parseColor("#202124"),
    ): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(Color.parseColor("#FAFAFA"))
        }

        root.addView(label("红果设备身份读取", 22f, bold = true))
        root.addView(
            label(
                "读取本机「红果免费短剧」的设备身份，导出成 HGTV 认的 device-identity.txt。",
                13f,
                color = Color.parseColor("#5F6368"),
            ).apply { setPadding(0, dp(6), 0, 0) },
        )
        root.addView(
            label(
                "需要 root（Magisk / KernelSU 授权），不需要存储权限。只读取本机红果的私有数据，不联网、不上传。",
                13f,
                color = Color.parseColor("#B3261E"),
            ).apply { setPadding(0, dp(2), 0, dp(14)) },
        )

        statusView = label("准备中…", 14f, bold = true).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#FFF4E5"))
        }
        root.addView(statusView)

        root.addView(separator())

        root.addView(label("设备身份", 15f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })

        val deviceRow = valueRow("device_id")
        idDevice = deviceRow.second
        root.addView(deviceRow.first)

        val installRow = valueRow("install_id")
        idInstall = installRow.second
        root.addView(installRow.first)

        val cdidRow = valueRow("cdid")
        idCdid = cdidRow.second
        root.addView(cdidRow.first)

        root.addView(separator())

        root.addView(label("导出内容（HGTV 文件格式）", 15f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        blockView = label("（尚未读取到设备身份）", 13f, color = Color.parseColor("#1B1B1B")).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#F1F3F4"))
        }
        root.addView(blockView)

        reloadButton = actionButton("重新读取").apply { setOnClickListener { startRead() } }
        copyBlockButton = actionButton("复制全部").apply { setOnClickListener { copyToClipboard() } }
        shareButton = actionButton("分享文字").apply { setOnClickListener { shareText() } }
        saveButton = actionButton("另存为文件…").apply { setOnClickListener { saveFile() } }
        writeButton = actionButton("写入本机 /sdcard/hgtv/").apply { setOnClickListener { writeToSdcard() } }
        diagButton = actionButton("查看诊断信息").apply { setOnClickListener { toggleDiag() } }

        root.addView(copyBlockButton, buttonParams())
        root.addView(shareButton, buttonParams())
        root.addView(saveButton, buttonParams())
        root.addView(writeButton, buttonParams())
        root.addView(reloadButton, buttonParams())
        root.addView(diagButton, buttonParams())

        diagView = label("", 11f, color = Color.parseColor("#3C4043")).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#F1F3F4"))
            visibility = TextView.GONE
        }
        root.addView(diagView, buttonParams())

        setActionsEnabled(false)

        root.addView(separator())

        root.addView(label("怎么用到电视上", 15f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        root.addView(
            label(
                "1. 读到设备身份后，点「另存为文件…」存到 U 盘；或点「分享文字」发到微信/QQ 再传到电脑。\n" +
                    "2. 把文件放到电视的 /sdcard/hgtv/device-identity.txt（文件名必须是这个）。\n" +
                    "3. 重新打开 HGTV，它会自动读取并跳过注册。\n\n" +
                    "说明：同一份身份不要和手机上的红果同时播放，否则服务端会判成设备异常；" +
                    "身份彻底失效时 HGTV 会自动退回注册，不会一直卡住。",
                13f,
                color = Color.parseColor("#5F6368"),
            ),
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun separator(): TextView =
        TextView(this).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(16)
                bottomMargin = dp(16)
            }
        }

    private fun valueRow(key: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(key, 12f, color = Color.parseColor("#5F6368")))
        val value = label("—", 15f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(2), 0, 0)
        }
        column.addView(value)
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            Button(this).apply {
                text = "复制"
                isAllCaps = false
                setOnClickListener {
                    val text = value.text?.toString().orEmpty()
                    if (text.isNotEmpty() && text != "—") {
                        copy(text)
                        toast("已复制 $key")
                    }
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return row to value
    }

    private fun actionButton(text: String): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
        }

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }

    private fun setActionsEnabled(enabled: Boolean) {
        copyBlockButton.isEnabled = enabled
        shareButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        writeButton.isEnabled = enabled
    }

    // ---------------------------------------------------------------- 读取

    /**
     * 不写死包名：把本机所有 shared_prefs 里出现 `cdid` / `device_id` 的都抓回来，交给
     * [IdentityParser] 排序挑选，包名从文件路径反推。
     *
     * 这样即使红果换了包名、装在应用分身（其它 user）里、或者设备身份存在
     * `/data/user_de/0/...`，也都能读到。
     *
     * 每个文件只取命中的那一行，避免把几百 KB 的 ttnet 配置整个读回来。
     */
    private val dumpScript: String =
        """
        echo "##UID ${dollar}(id -u)"
        echo "##SDK ${dollar}(getprop ro.build.version.sdk)"
        echo "##PM-BEGIN"
        pm list packages 2>/dev/null | sed 's/^package://' | grep -iE 'phoenix|hongguo|duanju|shortplay|redfruit'
        echo "##PM-END"
        matches=${dollar}(grep -lE 'cdid|device_id' /data/data/*/shared_prefs/*.xml /data/user/[0-9]*/*/shared_prefs/*.xml /data/user_de/[0-9]*/*/shared_prefs/*.xml 2>/dev/null)
        n=0
        seen=""
        for f in ${dollar}matches; do
          key=${dollar}(echo "${dollar}f" | sed 's|^/data/data/|/data/user/0/|')
          case " ${dollar}seen " in *" ${dollar}key "*) continue;; esac
          seen="${dollar}seen ${dollar}key"
          hits=${dollar}(grep -hE 'name="(device_id|deviceid|install_id|iid|installid|cdid)"' "${dollar}f" 2>/dev/null)
          [ -n "${dollar}hits" ] || continue
          n=${dollar}((n+1))
          echo "##FILE ${dollar}f"
          echo "${dollar}hits"
        done
        echo "##CAND ${dollar}n"
        echo "##OK"
        """.trimIndent()

    private fun startRead() {
        if (loading) return
        loading = true
        reloadButton.isEnabled = false
        statusView.text = "正在读取…（首次会弹出 root 授权，请点允许）"
        statusView.setBackgroundColor(Color.parseColor("#FFF4E5"))
        worker.execute {
            val dump = readDump()
            val outcome = IdentityParser.parse(dump)
            main.post {
                loading = false
                reloadButton.isEnabled = true
                rawDump = dump
                dumpFile = runCatching {
                    File(cacheDir, "last-dump.txt").apply { writeText(dump) }
                }.getOrNull()
                apply(outcome)
            }
        }
    }

    private fun readDump(): String =
        runRootScript("scan.sh", dumpScript) { IdentityParser.parse(it) is IdentityParser.Outcome.Success }

    /**
     * 把脚本写到缓存目录再交给 su 执行，依次尝试三种调用方式，取第一个被 [accept] 认可的输出。
     *
     * 1. `nsenter -t 1 -m`：切到 init 的挂载命名空间。**这条最关键** —— 有些设备（实测
     *    Android 15 + KernelSU 的盒子）root 之后命令仍留在调用方应用的命名空间里，
     *    `/data/data` 被隔离成只剩应用自己的目录，红果的 prefs 直接「不存在」。
     * 2. `--mount-master`：Magisk 的等价写法。
     * 3. 直接执行：老设备 / 没有 nsenter 的 toybox。
     *
     * 都失败时返回信息量最大的那份输出，方便诊断。
     */
    private fun runRootScript(name: String, body: String, accept: (String) -> Boolean): String {
        val file =
            runCatching {
                File(cacheDir, name).apply {
                    writeText(body)
                    setReadable(true, false)
                }
            }.getOrNull()
        val attempts =
            if (file == null) {
                listOf(listOf("-c", body))
            } else {
                val path = file.absolutePath
                listOf(
                    listOf("-c", "nsenter -t 1 -m sh $path 2>/dev/null"),
                    listOf("--mount-master", "-c", "sh $path 2>/dev/null"),
                    listOf("-c", "sh $path"),
                )
            }

        var best = ""
        for (attempt in attempts) {
            val output = runSu(attempt)
            if (accept(output)) return output
            if (output.count { it == '\n' } > best.count { it == '\n' }) best = output
        }
        return best
    }

    private fun runSu(arguments: List<String>): String =
        try {
            val process = ProcessBuilder(listOf("su") + arguments).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "##ERROR ${e.message}"
        }

    private fun apply(outcome: IdentityParser.Outcome) {
        when (outcome) {
            is IdentityParser.Outcome.Success -> {
                identity = outcome.identity
                sourcePackage = outcome.pkg
                idDevice.text = outcome.identity.deviceId
                idInstall.text = outcome.identity.installId
                idCdid.text = outcome.identity.cdid
                blockView.text = outcome.identity.block(outcome.pkg)
                statusView.text =
                    "读取成功 · 来源 ${outcome.pkg}" + (outcome.note?.let { "\n$it" } ?: "")
                statusView.setBackgroundColor(Color.parseColor("#E6F4EA"))
                setActionsEnabled(true)
            }
            is IdentityParser.Outcome.Failure -> {
                identity = null
                sourcePackage = null
                idDevice.text = "—"
                idInstall.text = "—"
                idCdid.text = "—"
                blockView.text = "（尚未读取到设备身份）"
                statusView.text = outcome.message
                statusView.setBackgroundColor(Color.parseColor("#FCE8E6"))
                setActionsEnabled(false)
            }
        }
        if (diagView.visibility == TextView.VISIBLE) renderDiag()
    }

    private fun toggleDiag() {
        if (diagView.visibility == TextView.VISIBLE) {
            diagView.visibility = TextView.GONE
        } else {
            renderDiag()
            diagView.visibility = TextView.VISIBLE
        }
    }

    private fun renderDiag() {
        val text =
            if (rawDump.isEmpty()) {
                "还没有诊断数据，先点「重新读取」。"
            } else {
                rawDump
            }
        diagView.text = text.take(8000) + (dumpFile?.let { "\n\n原始输出：$it" } ?: "")
    }

    // ---------------------------------------------------------------- 导出

    private fun currentBlock(): String? {
        val current = identity ?: return null
        return current.block(sourcePackage ?: "com.phoenix.read")
    }

    private fun copy(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("device-identity", text))
    }

    private fun copyToClipboard() {
        val block = currentBlock() ?: return
        copy(block)
        toast("已复制完整文件内容")
    }

    private fun shareText() {
        val block = currentBlock() ?: return
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "HGTV device-identity.txt")
                putExtra(Intent.EXTRA_TEXT, block)
            }
        startActivity(Intent.createChooser(send, "分享设备身份"))
    }

    private fun saveFile() {
        if (currentBlock() == null) return
        val create =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "device-identity.txt")
            }
        startActivityForResult(create, saveRequestCode)
    }

    @Deprecated("Plain Activity 的 result 回调，够用且不需要 AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != saveRequestCode || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val block = currentBlock() ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(block.toByteArray(Charsets.UTF_8)) }
            toast("已保存到所选位置")
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }

    private fun writeToSdcard() {
        val block = currentBlock() ?: return
        val path = "/sdcard/hgtv/device-identity.txt"
        val target = File(cacheDir, "device-identity.txt").apply { writeText(block) }
        // 同样要切到 init 命名空间：应用自己的 /sdcard 视图和真实共享目录不是一回事
        val script =
            """
            mkdir -p /sdcard/hgtv
            cp ${target.absolutePath} $path
            chmod 644 $path
            echo "##OK"
            """.trimIndent()
        worker.execute {
            val output = runRootScript("write.sh", script) { it.contains("##OK") }
            val ok = output.contains("##OK")
            main.post {
                if (ok) toast("已写入 $path") else toast("写入失败，请确认已授予 root")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
