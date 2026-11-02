package com.hgtv.tv

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hgtv.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * 观看历史 / 收藏这类本机片库页：上方头部很高，网格视口只剩半屏。
 * 这里守住两件事——海报不被压扁、副标题两行都还在（不再退化成只剩「看到第 N 集」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class HistoryCardLayoutTest {
    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test
    fun historyCardsKeepPosterRatioAndFullLabels() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            val titles = listOf("很长的剧名测试一二三四五六七八九十", "短剧名", "中等长度的剧集名称测试")
            val rows =
                titles.mapIndexed { i, t ->
                    val s = Series("$i", t, badge = "共 ${100 + i} 集")
                    BackupProgress(s, "${i}01", listOf(7, 12, 101)[i], 95_000, 120_000, false, 1_000L + i)
                }
            Library::class
                .java
                .getDeclaredField("index")
                .apply { isAccessible = true }
                .set(
                    library,
                    LibraryIndex(BackupData(emptyList(), rows, emptySet(), emptyList(), BackupSettings())),
                )
            MainActivity::class
                .java
                .getDeclaredMethod("switchTab", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, 4)
            val root =
                activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1920, 1080)
            // 卡片的收缩是在 post 里做的，跑一遍主线程消息队列让它们生效。
            org.robolectric.shadows.ShadowLooper.idleMainLooper()

            val cards = collectCards(root)
            assertEquals(3, cards.size)
            cards.forEach { card ->
                val poster = card.getChildAt(0)
                val natural = ((card.width - 12) / TvStyle.POSTER_ASPECT).toInt()
                assertTrue(
                    "海报高度 ${poster.height} 被压到自然高度 $natural 的八成以下",
                    poster.height >= (natural * .8f).toInt(),
                )
                val badge = card.getChildAt(2) as TextView
                assertTrue("副标题应保留两行：${badge.text}", badge.maxLines >= 2)
                assertTrue("副标题文字应含集数与进度：${badge.text}", badge.text.contains("\n"))
                val title = card.getChildAt(1) as TextView
                assertTrue("标题应保留两行：${title.maxLines}", title.maxLines >= 2)
            }
        } finally {
            controller.destroy()
        }
    }

    private fun collectCards(v: View): List<LinearLayoutCard> {
        val out = mutableListOf<LinearLayoutCard>()
        fun walk(view: View) {
            if (view is ViewGroup && view.contentDescription?.isNotEmpty() == true && view.childCount == 3) {
                out += LinearLayoutCard(view, view.contentDescription.toString())
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(v)
        return out
    }

    /** 只用来承载卡片视图，方便测试里取尺寸。 */
    class LinearLayoutCard(val view: View, val label: String) {
        val width get() = view.width
        fun getChildAt(i: Int) = (view as ViewGroup).getChildAt(i)
    }
}
