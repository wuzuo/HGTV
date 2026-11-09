// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone

import android.app.Application
import com.hgtv.core.ContentRepository
import com.hgtv.core.DeviceIdentityFile
import com.hgtv.core.DeviceIdentityMonitor
import com.hgtv.phone.data.AccountSession
import com.hgtv.phone.data.PhoneLibrary
import com.hgtv.phone.data.PhoneSettings
import com.hgtv.playback.CodecCapabilities
import com.hgtv.playback.PlaybackCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        install(this)
        // 与 TV 端同一套设备身份配置：读到真实 device_id 就跳过自动注册，播放接口对
        // 「已注册过一次的新设备」只放行一次，注册身份每播一集都要换一台，换不到就空响应，
        // 用户看到的就是 source error。读文件这件事必须赶在第一次播放之前。
        installDeviceIdentity(this)
        // 解码能力表与「兼容输出」开关都存在 SharedPreferences 里，必须带 Context 初始化，
        // 否则第一次判定会退化成「一律支持」，档位就不会降级。
        CodecCapabilities.init(this)
        PlaybackCompat.init(this)
        // 片库与登录态都是本地数据，冷启动时并行读出来，首页就能直接显示「继续观看」。
        scope.launch { library.load() }
        scope.launch { account.restore() }
    }

    /**
     * 指定设备身份文件的候选位置，并立刻读一次。
     *
     * 首选 `/sdcard/hgtv/device-identity.txt`——和 TV 端同一个路径，用户把电视上那份文件
     * 复制过来就能用。Android 11+ 的分区存储会把没有「所有文件访问」权限时的共享目录静默
     * 隐藏（`isFile` 返回 false，不报错），所以后面几个不需要权限的目录必须兜住：
     * 应用自己的 media 目录（文件管理器能看到，`adb push` 也能写）、应用外部私有目录、
     * 内部目录。文件缺失或格式不对一律当作没配置，静默走自动注册。
     */
    private fun installDeviceIdentity(context: Application) {
        runCatching {
            val root = android.os.Environment.getExternalStorageDirectory()
            DeviceIdentityFile.path = java.io.File(root, "hgtv/device-identity.txt")
            DeviceIdentityFile.fallbacks =
                listOfNotNull(
                    context.getExternalMediaDirs().firstOrNull()
                        ?.let { java.io.File(it, "device-identity.txt") },
                    context.getExternalFilesDir(null)
                        ?.let { java.io.File(it, "device-identity.txt") },
                    java.io.File(context.filesDir, "device-identity.txt"),
                )
        }
        // 读一次并广播：设置页据此显示「已读取 / 需授权 / 未放置」。
        // 读不到不抛异常，播放照常走自动注册。
        runCatching { DeviceIdentityMonitor.resolve() }
    }

    companion object {
        /** 与界面生命周期无关的活：片库加载、登录态恢复、云端同步。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /**
         * 内容接口与取流共用同一个 client。
         *
         * 不自己 new OkHttpClient：默认构造里带了 cookie jar，播放接口下发的会话要靠它维持；
         * 取流直接复用 [ContentRepository.http]，换集时就不用重新 TLS 握手。
         */
        val repository: ContentRepository by lazy { ContentRepository() }

        val http: OkHttpClient
            get() = repository.http

        lateinit var library: PhoneLibrary
            private set

        lateinit var settings: PhoneSettings
            private set

        lateinit var account: AccountSession
            private set

        fun install(context: Application) {
            library = PhoneLibrary(context)
            settings = PhoneSettings(context)
            account = AccountSession(context, http)
        }
    }
}
