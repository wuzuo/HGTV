// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone

import android.app.Application
import com.hgtv.core.ContentRepository
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
        // 解码能力表与「兼容输出」开关都存在 SharedPreferences 里，必须带 Context 初始化，
        // 否则第一次判定会退化成「一律支持」，档位就不会降级。
        CodecCapabilities.init(this)
        PlaybackCompat.init(this)
        // 片库与登录态都是本地数据，冷启动时并行读出来，首页就能直接显示「继续观看」。
        scope.launch { library.load() }
        scope.launch { account.restore() }
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
