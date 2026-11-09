// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.hgtv.core.Series
import com.hgtv.phone.player.PipBridge
import com.hgtv.phone.player.PlayerScreen
import com.hgtv.phone.ui.DetailScreen
import com.hgtv.phone.ui.HgtvTheme
import com.hgtv.phone.ui.HomeScreen
import com.hgtv.phone.ui.LoginScreen
import com.hgtv.phone.ui.SettingsScreen
import com.hgtv.phone.ui.rememberHomeState

/**
 * 导航只有几层，用一个列表当返回栈就够了。
 *
 * 没有引入 navigation-compose：那套要额外的序列化与回退栈配置，而这里没有深链、
 * 没有多入口，手写反而更直白。manifest 声明了 configChanges，横竖屏切换不会重建
 * Activity，栈也就不会丢（画中画的进出同样走 configChanges）。
 */
private sealed interface Route {
    data object Home : Route

    data class Detail(val seriesId: String) : Route

    data class Player(val series: Series, val episodes: List<String>, val index: Int) : Route

    data object Settings : Route

    data object Login : Route
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

    /** 播放中按 Home：缩成小窗继续放，而不是被系统直接切走。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipBridge.active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PipBridge.enter?.invoke()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipBridge.inPip = isInPictureInPictureMode
    }
}

@Composable
private fun HgtvApp() {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    // 首页状态常驻：进出详情/设置不会把页签和搜索结果清掉。
    val home = rememberHomeState()
    val pop: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // 只有存在上一页时才接管返回键，首页交给系统退出应用。
    BackHandler(enabled = stack.size > 1, onBack = pop)

    when (val route = stack.last()) {
        Route.Home ->
            HomeScreen(
                state = home,
                onOpen = { series -> stack.add(Route.Detail(series.id)) },
                onPlay = { series, episodes, index ->
                    stack.add(Route.Player(series, episodes, index))
                },
                onOpenSettings = { stack.add(Route.Settings) },
                onOpenLogin = { stack.add(Route.Login) },
            )
        is Route.Detail ->
            DetailScreen(
                seriesId = route.seriesId,
                onBack = pop,
                onPlay = { series, episodes, index ->
                    // 详情页与「我的」都可能直接起播，统一从详情页跳播放页，
                    // 返回时落回详情而不是首页。
                    stack.add(Route.Player(series, episodes, index))
                },
            )
        is Route.Player ->
            PlayerScreen(
                series = route.series,
                episodes = route.episodes,
                startIndex = route.index,
                onBack = pop,
            )
        Route.Settings -> SettingsScreen(onBack = pop)
        Route.Login -> LoginScreen(onBack = pop, onSignedIn = pop)
    }
}
