// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone

import android.app.Application
import com.hgtv.core.ContentRepository
import com.hgtv.playback.CodecCapabilities
import com.hgtv.playback.PlaybackCompat
import okhttp3.OkHttpClient

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 解码能力表与「兼容输出」开关都存在 SharedPreferences 里，必须带 Context 初始化，
        // 否则第一次判定会退化成「一律支持」，档位就不会降级。
        CodecCapabilities.init(this)
        PlaybackCompat.init(this)
    }

    companion object {
        /**
         * 内容接口与取流共用同一个 client。
         *
         * 不自己 new OkHttpClient：默认构造里带了 cookie jar，播放接口下发的会话要靠它维持；
         * 取流直接复用 [ContentRepository.http]，换集时就不用重新 TLS 握手。
         */
        val repository: ContentRepository by lazy { ContentRepository() }

        val http: OkHttpClient
            get() = repository.http
    }
}
