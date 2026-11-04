package com.hgtv.tv

import com.hgtv.core.DeviceIdentity
import com.hgtv.core.DeviceIdentityFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「读不到配置」以前只有一种表现，用户分不清是没放文件、放对了没给权限，还是本来就没配置。
 * 这里守住三种状态的判定：没有权限时绝不能报成「没有配置」——那会把人往错的方向引。
 */
class DeviceIdentityStatusTest {
    private val identity =
        DeviceIdentity(
            deviceId = "7000000000000000001",
            installId = "7000000000000000002",
            cdid = "3b1f5c2d-8a4e-4f60-9c71-2d3e4a5b6c7d",
        )

    @Test
    fun loadedConfigIsReadyRegardlessOfPermission() {
        // 备选目录（应用私有外部目录、内部目录）不需要任何权限，能读到就是可用状态。
        val file = File("/sdcard/Android/media/io.github.wuzuo.hgtv/device-identity.txt")
        val status = resolveIdentityStatus(DeviceIdentityFile.Loaded(identity, file), permitted = false)
        assertTrue(status is IdentityStatus.Ready)
        status as IdentityStatus.Ready
        assertEquals(identity, status.identity)
        assertEquals(file, status.file)
    }

    @Test
    fun noConfigWithPermissionIsNotConfigured() {
        assertEquals(IdentityStatus.NotConfigured, resolveIdentityStatus(null, permitted = true))
    }

    @Test
    fun noConfigWithoutPermissionIsNoPermission() {
        // 关键一条：没权限时首选路径整个被隐藏，此时说「没有配置」是在误导用户去重新拷贝文件。
        assertEquals(IdentityStatus.NoPermission, resolveIdentityStatus(null, permitted = false))
    }
}
