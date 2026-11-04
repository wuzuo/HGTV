// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.hgtv.core.DeviceIdentity
import com.hgtv.core.DeviceIdentityFile
import java.io.File

/**
 * 设备身份配置在本机上的真实状态。
 *
 * 在这之前，「读不到配置」只有一种表现：静默走自动注册。用户分不清是文件没放对、放对了但没给
 * 存储权限，还是本来就没配置，只能猜——而 Android 11+ 的分区存储恰恰会把没权限时的 `/sdcard/`
 * 共享目录**静默隐藏**（`File.isFile()` 直接返回 false，不抛异常也不打日志），猜都没法猜。
 *
 * 这里把三种情况分开，让设置页能给出准确的一句话。
 */
internal sealed interface IdentityStatus {
    /** 读到了可用配置。 */
    data class Ready(val identity: DeviceIdentity, val file: File) : IdentityStatus

    /**
     * 没读到，而且系统没给「所有文件访问」权限。
     *
     * 这种情况下**无法判断文件到底在不在**：权限不足时首选路径整个被隐藏。所以文案只能说
     * 「需要权限」，不能说「文件不存在」。
     */
    data object NoPermission : IdentityStatus

    /** 有权限，但没有可用配置：没放文件，或文件内容不合法。 */
    data object NotConfigured : IdentityStatus

    companion object {
        /**
         * 是否具备读 `/sdcard/hgtv/` 的能力。
         *
         * 分三段：Android 6–10 靠运行时授予的 `READ_EXTERNAL_STORAGE`（配合 manifest 里的
         * `requestLegacyExternalStorage`）；11 起这个权限被分区存储废掉，只剩「所有文件访问」；
         * 6.0 以下安装时即授予。
         */
        fun hasAllFilesAccess(context: Context): Boolean =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                else -> true
            }

        /** 6–10 上权限是运行时弹窗授予的，11+ 才需要跳系统设置页。 */
        fun needsSettingsJump(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        fun of(context: Context): IdentityStatus =
            resolveIdentityStatus(DeviceIdentityFile.readDetailed(), hasAllFilesAccess(context))
    }
}

/**
 * 把「读到什么」和「有没有权限」拼成最终状态。
 *
 * 单独拎出来是为了能脱离 Android 运行时测：这两个输入已经把环境差异吃干净了。
 */
internal fun resolveIdentityStatus(
    loaded: DeviceIdentityFile.Loaded?,
    permitted: Boolean,
): IdentityStatus =
    when {
        loaded != null -> IdentityStatus.Ready(loaded.identity, loaded.file)
        permitted -> IdentityStatus.NotConfigured
        // 没权限时首选路径整个被隐藏，分不清「没放文件」和「放了但被挡」，只能说缺权限。
        else -> IdentityStatus.NoPermission
    }

/**
 * 跳到系统的「所有文件访问」开关。
 *
 * 部分 ROM 没有按包名直达的入口（会抛 ActivityNotFoundException），回退到应用列表页。
 * 两个都失败就什么都不做——用户仍可自己进系统设置开启。
 */
internal fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
