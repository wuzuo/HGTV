// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * 界面到播放服务的那根线。
 *
 * 用 [MediaController] 而不是自己持有 ExoPlayer：控制器连上的是同一个进程里的会话，
 * 换进程（服务被系统重建）也不会让界面里的引用变成悬空的播放器。
 *
 * 连接是异步的（要先起服务），所以 [controller] 可能有一小段时间是 null，
 * 调用方必须把它当作正常状态处理。
 */
@Stable
class PlaybackConnection(context: Context) {
    private val app = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null

    var controller by mutableStateOf<MediaController?>(null)
        private set

    /** 连接失败的原因；只用于界面提示，不影响重试。 */
    var failure by mutableStateOf<String?>(null)
        private set

    // SessionToken 被 media3 标成不稳定 API，但它是连接播放服务唯一的入口。
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun connect() {
        if (future != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val pending = MediaController.Builder(app, token).buildAsync()
        future = pending
        pending.addListener(
            {
                runCatching { pending.get() }
                    .onSuccess {
                        controller = it
                        failure = null
                    }
                    .onFailure { failure = it.message ?: "播放服务连接失败" }
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }
}
