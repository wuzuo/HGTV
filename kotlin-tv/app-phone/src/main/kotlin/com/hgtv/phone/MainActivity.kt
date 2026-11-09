// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.hgtv.phone.ui.DetailScreen
import com.hgtv.phone.ui.HgtvTheme
import com.hgtv.phone.ui.HomeScreen
import com.hgtv.phone.ui.PlayerScreen

/**
 * 导航只有三层，用一个列表当返回栈就够了。
 *
 * 没有引入 navigation-compose：那套要额外的序列化与回退栈配置，而这里三个页面
 * 之间没有深链、没有多入口，手写反而更直白。manifest 声明了 orientation 等
 * configChanges，横竖屏切换不会重建 Activity，栈也就不会丢。
 */
private sealed interface Route {
    data object Home : Route
    data class Detail(val seriesId: String) : Route
    data class Player(val episodes: List<String>, val index: Int) : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容页贴边显示海报，播放页全屏。状态栏图标固定白色（深色背景）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { HgtvTheme { HgtvApp() } }
    }
}

@Composable
private fun HgtvApp() {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val pop: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // 只有存在上一页时才接管返回键，首页交给系统退出应用。
    BackHandler(enabled = stack.size > 1, onBack = pop)

    when (val route = stack.last()) {
        Route.Home ->
            HomeScreen(onOpen = { series -> stack.add(Route.Detail(series.id)) })
        is Route.Detail ->
            DetailScreen(
                seriesId = route.seriesId,
                onBack = pop,
                onPlay = { _, episodes, index -> stack.add(Route.Player(episodes, index)) },
            )
        is Route.Player ->
            PlayerScreen(
                episodes = route.episodes,
                startIndex = route.index,
                onBack = pop,
            )
    }
}
