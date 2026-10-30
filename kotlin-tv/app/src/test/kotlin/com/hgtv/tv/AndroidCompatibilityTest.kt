package com.hgtv.tv

import android.app.Activity
import android.os.Build
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26], qualifiers = "land")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AndroidCompatibilityTest {
    @Test
    fun mainActivityCreatesAndClosesOnSupportedSystems() {
        assertTrue(Build.VERSION.SDK_INT in listOf(23, 26))
        val controller =
            Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val content = controller.get().findViewById<FrameLayout>(android.R.id.content)
            assertTrue(content.childCount > 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun returningFromInstallPermissionWorksOnSupportedSystems() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val updater = AppUpdater(controller.get()) { false }
        try {
            // Also exercise state restored from a newer system or a pending installer action.
            val requested =
                AppUpdater::class.java.getDeclaredField("permissionRequested").apply {
                    isAccessible = true
                }
            requested.setBoolean(updater, true)
            updater.resume()
            assertFalse(requested.getBoolean(updater))
        } finally {
            updater.destroy()
            controller.pause().stop().destroy()
        }
    }
}
