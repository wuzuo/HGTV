package com.hgtv.tv

import com.hgtv.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 首页顶部的「继续观看」只取一条：本机历史里最近的那一集。
 *
 * 文案要能直接回答「看的哪部、第几集、看到哪了」，用户按一次确定就能接着播，
 * 所以这里守住三件事：取最新、集号从 1 开始、已看完的写「接着看下一集」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeResumeTest {
    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun seed(activity: MainActivity, vararg rows: BackupProgress) {
        val library = get(activity, "library") as Library
        Library::class
            .java
            .getDeclaredField("index")
            .apply { isAccessible = true }
            .set(
                library,
                LibraryIndex(
                    BackupData(emptyList(), rows.toList(), emptySet(), emptyList(), BackupSettings())
                ),
            )
    }

    private fun shortcut(activity: MainActivity): ResumeShortcut? =
        MainActivity::class
            .java
            .getDeclaredMethod("resumeShortcut")
            .apply { isAccessible = true }
            .invoke(activity) as ResumeShortcut?

    @Test
    fun namesTheMostRecentEpisodeWithItsProgress() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            // 故意把较早的一条放在前面：排序要按更新时间，不是写入顺序。
            seed(
                activity,
                BackupProgress(Series("1", "较早的剧"), "101", 0, 5_000, 60_000, false, 1_000),
                BackupProgress(Series("2", "最近的剧"), "202", 2, 125_000, 300_000, false, 9_000),
            )
            assertEquals("继续观看 · 最近的剧 · 第 3 集 · 02:05", shortcut(activity)?.label)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun finishedEpisodeOffersTheNextOneInsteadOfTheEnd() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            seed(
                activity,
                BackupProgress(Series("2", "最近的剧"), "202", 2, 300_000, 300_000, true, 9_000)
            )
            assertEquals("继续观看 · 最近的剧 · 接着看下一集", shortcut(activity)?.label)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun emptyHistoryKeepsTheHomeScreenClean() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            seed(activity)
            assertNull(shortcut(activity))
        } finally {
            controller.destroy()
        }
    }
}
