package com.hgtv.tv

import com.hgtv.core.DeviceRegister
import com.hgtv.core.PlaybackDeviceSession

internal fun testPlaybackDeviceSession() = PlaybackDeviceSession {
    DeviceRegister.Registered("333", "444")
}
