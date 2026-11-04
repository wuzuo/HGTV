// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本地设备身份配置的状态广播。
 *
 * 各客户端（Android TV / Windows）都需要在两种时刻给用户可见的提示：
 * * **启动时读到了配置**：说明播放会跳过自动注册；
 * * **播放中配置被判失效**：说明已经退回自动注册，不再是「免注册」状态。
 *
 * 状态只在 [DeviceIdentityFile] 有配置时才存在。没有配置或文件不合法时一切静默，不影响自动注册，
 * 所以这里不上报「读取失败」——那属于正常的默认路径。
 */
object DeviceIdentityMonitor {

    sealed interface Event {
        /** 读到一份可用的本地身份。 */
        data class Loaded(val identity: DeviceIdentity, val file: File) : Event

        /** 这份身份在播放接口上被判失效，后续已改用自动注册。 */
        data class Rejected(val identity: DeviceIdentity) : Event
    }

    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    @Volatile private var loaded: DeviceIdentityFile.Loaded? = null
    @Volatile private var rejectionReported = false

    fun addListener(listener: (Event) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Event) -> Unit) {
        listeners -= listener
    }

    /** 当前生效的本地身份；没有配置时为 null。 */
    fun peek(): DeviceIdentityFile.Loaded? = loaded

    /** 这份配置是否已经被判失效（本次进程内）。 */
    fun wasRejected(): Boolean = rejectionReported

    /**
     * 读取本地身份并更新状态；值发生变化时广播一次 [Event.Loaded]。
     *
     * 可以重复调用：同一份配置不会重复提示，换了文件或换了值才会再提示一次。
     */
    fun resolve(): DeviceIdentity? {
        val current = DeviceIdentityFile.readDetailed()
        if (current == null) {
            loaded = null
            rejectionReported = false
            return null
        }
        val previous = loaded
        if (previous == null || previous.identity != current.identity || previous.file != current.file) {
            loaded = current
            rejectionReported = false
            emit(Event.Loaded(current.identity, current.file))
        }
        return current.identity
    }

    /** 本地身份被播放接口拒绝；同一份配置只上报一次，避免每次失败都弹提示。 */
    fun reportRejected() {
        val current = loaded ?: return
        if (rejectionReported) return
        rejectionReported = true
        emit(Event.Rejected(current.identity))
    }

    /** 测试用：清空状态与监听者。 */
    internal fun reset() {
        listeners.clear()
        loaded = null
        rejectionReported = false
    }

    private fun emit(event: Event) {
        listeners.forEach { listener -> runCatching { listener(event) } }
    }
}
