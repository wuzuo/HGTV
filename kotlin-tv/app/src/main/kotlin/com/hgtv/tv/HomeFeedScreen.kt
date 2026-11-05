// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

// 首页试播组件：组装分类栏、视频区和操作栏，并管理试播请求及遥控器焦点。

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hgtv.core.*
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * 首页顶部的「最近一条播放历史」入口。
 *
 * 文案由调用方拼好（剧名、集数、进度都是调用方才知道的），点开之后去哪也由调用方决定；
 * 首页只负责把它摆出来、跟着焦点走。返回 null 表示本机还没有观看记录，整行不占位。
 */
internal data class ResumeShortcut(val label: String, val open: () -> Unit)

/** A separate trial player. Never calls Library.save or AccountController.upload. */
internal class HomeFeedScreen(
    private val activity: Activity,
    private val state: DramaFeedState,
    private val nav: List<View>,
    private val watch: (Series) -> Unit,
    private val favorite: (Series, View) -> Unit,
    private val favoriteLabel: (String) -> String,
    private val backend: FeedBackend,
    private val playback: TrialPlayback,
    private val resumeShortcut: () -> ResumeShortcut? = { null },
) : LinearLayout(activity), Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private var generation = 0
    private var scope: RequestScope? = null
    private var job: java.util.concurrent.Future<*>? = null
    private var prefetchScope: RequestScope? = null
    private var prefetchJob: java.util.concurrent.Future<*>? = null
    private var prefetchTarget: String? = null

    private data class CachedTrial(
        val clip: TrialClip,
        val savedAt: Long = android.os.SystemClock.elapsedRealtime(),
    )

    private val cachedTrials = linkedMapOf<String, CachedTrial>()
    private val attemptedPrefetch = mutableSetOf<String>()
    private var prefetchGeneration = 0
    private var attemptedBatch = 0
    private var clip: TrialClip? = null
    private var closed = false
    private var active = false
    private var loading = false
    private var failed = false
    private val stage = FrameLayout(activity)
    /** 本机有播放历史时为 true；操作栏展开期间控件会临时收起，靠它决定要不要还原。 */
    private var resumeAvailable = false
    private val title = label("发现下一部好剧", 26f, TvStyle.textStrong)
    private val meta = label("第一集试播", 13f, TvStyle.accent)
    private val intro = label("喜欢这部作品，就选择观看全集。", 15f, TvStyle.textDim)
    private val status = label("正在准备热门作品…", 15f)
    private val tag = label("热门发现", 12f, TvStyle.accent)
    private val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val buttons = mutableListOf<TextView>()
    private val pauseButton: TextView
    private val watchButton: TextView
    private val resumeButton: TextView
    private lateinit var favoriteButton: TextView
    private val recommendButton: TextView
    private val comicButton: TextView
    private val realButton: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int = TvStyle.text) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private fun lp(w: Int = -1, h: Int = -2) = LayoutParams(w, h)

    private fun button(value: String, action: () -> Unit) =
        label(value, 14f).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minimumHeight = dp(TvStyle.BUTTON_COMPACT + TvStyle.SOFT_BAND * 2)
            minWidth = dp(TvStyle.BUTTON_MIN_WIDTH)
            setPadding(
                dp(14 + TvStyle.SOFT_BAND),
                dp(TvStyle.SOFT_BAND),
                dp(14 + TvStyle.SOFT_BAND),
                dp(TvStyle.SOFT_BAND),
            )
            background = TvStyle.card(activity, false)
            setOnFocusChangeListener { view, _ ->
                paintButton(this)
                TvStyle.focus(view, view.hasFocus())
            }
            setOnClickListener { action() }
        }

    /** 常态凸起、选中按进去、焦点打亮；与全站按钮同一套三个台阶。 */
    private fun paintButton(button: TextView) {
        val focused = button.hasFocus()
        button.background =
            when {
                focused ->
                    TvStyle.soft(
                        activity,
                        TvStyle.lit,
                        TvStyle.RADIUS_PILL,
                        TvStyle.SOFT_BAND,
                        SoftStyle.RAISED,
                        glow = TvStyle.litGlow,
                        depth = 1.2f,
                    )
                button.isSelected ->
                    TvStyle.well(
                        activity,
                        TvStyle.selectedWell,
                        TvStyle.RADIUS_PILL,
                        TvStyle.SOFT_BAND,
                        depth = 1.15f,
                    )
                // 主操作（观看全集）常亮：一屏只有一个，焦点只是再加一圈光。
                button === watchButton && button.isEnabled ->
                    TvStyle.soft(
                        activity,
                        TvStyle.lit,
                        TvStyle.RADIUS_PILL,
                        TvStyle.SOFT_BAND,
                        SoftStyle.RAISED,
                        depth = 1.1f,
                    )
                else ->
                    TvStyle.soft(
                        activity,
                        TvStyle.surface,
                        TvStyle.RADIUS_PILL,
                        TvStyle.SOFT_BAND,
                        SoftStyle.RAISED,
                    )
            }
        button.setTextColor(
            when {
                focused -> TvStyle.onLit
                button.isSelected -> TvStyle.accent
                button === watchButton && button.isEnabled -> TvStyle.onLit
                else -> TvStyle.text
            }
        )
        button.setTypeface(
            null,
            if (button.isSelected || focused) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    /** 「继续观看」是入口而不是主操作：常态凹陷配强调色文字，焦点时整条点亮。 */
    private fun paintResume() {
        val focused = resumeButton.hasFocus()
        resumeButton.background =
            if (focused)
                TvStyle.soft(
                    activity,
                    TvStyle.lit,
                    TvStyle.RADIUS_PILL,
                    TvStyle.SOFT_BAND,
                    SoftStyle.RAISED,
                    glow = TvStyle.litGlow,
                    depth = 1.2f,
                )
            else
                TvStyle.well(
                    activity,
                    TvStyle.selectedWell,
                    TvStyle.RADIUS_PILL,
                    TvStyle.SOFT_BAND,
                    depth = 1.1f,
                )
        resumeButton.setTextColor(if (focused) TvStyle.onLit else TvStyle.accent)
        resumeButton.setTypeface(null, if (focused) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * 刷新「继续观看」这一行。
     *
     * 没有观看记录时整行不占位，并把试播画面的「右」重新指回自己——否则焦点会走到一个看不见的
     * 控件上，按右没反应。有记录时把「上」接到当前选中的分类按钮，与其它控件的焦点链保持一致。
     */
    private fun refreshResume() {
        val shortcut = resumeShortcut()
        resumeAvailable = shortcut != null
        // 操作栏展开时右侧只有约 300dp，这一行先让位；收起操作栏时 back() 会把它放回来。
        val crowded = actions.visibility == View.VISIBLE
        if (shortcut == null) {
            resumeButton.visibility = View.GONE
            stage.nextFocusRightId = stage.id
            return
        }
        resumeButton.text = shortcut.label
        resumeButton.contentDescription = shortcut.label
        resumeButton.visibility = if (crowded) View.GONE else View.VISIBLE
        if (crowded) {
            stage.nextFocusRightId = stage.id
            return
        }
        val above =
            when {
                state.recommendation -> recommendButton
                state.category == DramaCategory.COMIC -> comicButton
                else -> realButton
            }
        resumeButton.nextFocusUpId = above.id
        resumeButton.nextFocusDownId = stage.id
        resumeButton.nextFocusLeftId = stage.id
        resumeButton.nextFocusRightId = resumeButton.id
        // 在画面上按「右」就是这一条，比从分类栏一路往下按更顺手。
        stage.nextFocusRightId = resumeButton.id
        paintResume()
    }

    // 组装首页布局和点击事件；分类栏、内容区、操作栏按纵向排列。
    init {
        orientation = VERTICAL
        stage.id = View.generateViewId()
        val types = LinearLayout(activity)
        recommendButton =
            button("推荐") {
                if (!state.recommendation) {
                    clearCurrent()
                    clearCache()
                    state.recommend()
                    describe()
                    if (state.current != null) loadCurrent() else loadPage(false)
                }
            }
        comicButton = button("漫剧") { changeType(DramaCategory.COMIC) }
        realButton = button("真人剧") { changeType(DramaCategory.REAL) }
        val refresh =
            button("换一批") {
                clearCurrent()
                clearCache()
                state.refresh()
                describe()
                loadPage(false)
            }
        val typeChoices = listOf(recommendButton, comicButton, realButton, refresh)
        typeChoices.forEachIndexed { i, b ->
            types.addView(
                b,
                lp(0, dp(TvStyle.BUTTON_COMPACT + TvStyle.SOFT_BAND * 2)).apply {
                    weight = 1f
                    rightMargin = dp(8)
                },
            )
            b.nextFocusUpId = nav.first().id
            b.nextFocusDownId = stage.id
            b.nextFocusLeftId = typeChoices.getOrNull(i - 1)?.id ?: b.id
            b.nextFocusRightId = typeChoices.getOrNull(i + 1)?.id ?: b.id
        }
        addView(types, lp(-1, dp(TvStyle.BUTTON_COMPACT + TvStyle.SOFT_BAND * 2 + 8)))
        val content = LinearLayout(activity)
        content.clipChildren = false
        content.clipToPadding = false
        stage.isFocusable = true
        stage.isFocusableInTouchMode = true
        stage.isClickable = true
        stage.setPadding(
            dp(TvStyle.SOFT_BAND + 6),
            dp(TvStyle.SOFT_BAND + 6),
            dp(TvStyle.SOFT_BAND + 6),
            dp(TvStyle.SOFT_BAND + 6),
        )
        stage.background = TvStyle.card(activity, false)
        stage.clipChildren = true
        stage.addView(playback.view, FrameLayout.LayoutParams(-1, -1))
        tag.setPadding(dp(14), dp(7), dp(14), dp(7))
        tag.background = TvStyle.shape(activity, 0xCC1A1A1A.toInt(), radius = TvStyle.RADIUS_PILL)
        stage.addView(
            tag,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(TvStyle.SOFT_BAND + 10), dp(TvStyle.SOFT_BAND + 10), dp(10), 0)
            },
        )
        status.gravity = Gravity.CENTER
        status.setPadding(dp(16), dp(11), dp(16), dp(11))
        status.background = TvStyle.shape(activity, 0xCC1A1A1A.toInt(), radius = TvStyle.RADIUS_PILL)
        stage.addView(
            status,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(
                    dp(TvStyle.SOFT_BAND + 10),
                    0,
                    dp(TvStyle.SOFT_BAND + 10),
                    dp(TvStyle.SOFT_BAND + 10),
                )
            },
        )
        content.addView(
            stage,
            lp(0, -1).apply {
                weight = 2.2f
                rightMargin = dp(22)
            },
        )
        val info =
            LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(0, dp(14), 0, dp(10))
            }
        // 最近一条播放历史放在正文最上方：进首页按方向键就能碰到，不必先进「观看历史」页。
        // 用凹陷的底槽而不是凸起按钮，跟下面「观看全集」那排主操作区分开。
        resumeButton =
            label("", 13.5f, TvStyle.accent).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minHeight = dp(TvStyle.BUTTON_COMPACT)
                setPadding(dp(18), dp(6), dp(18), dp(6))
                background =
                    TvStyle.well(
                        activity,
                        TvStyle.selectedWell,
                        TvStyle.RADIUS_PILL,
                        TvStyle.SOFT_BAND,
                        depth = 1.1f,
                    )
                setOnFocusChangeListener { view, _ ->
                    paintResume()
                    TvStyle.focus(view, view.hasFocus(), 1.02f)
                }
                setOnClickListener {
                    val shortcut = resumeShortcut() ?: return@setOnClickListener
                    // 与「观看全集」同一条路：先记下这一屏的试播进度，再离开首页。
                    remember()
                    pause()
                    shortcut.open()
                }
            }
        info.addView(resumeButton, lp(-1, -2).apply { bottomMargin = dp(12) })
        title.typeface = Typeface.DEFAULT_BOLD
        // 标题最多三行、简介最多四行：底部操作按钮出现时右侧高度只有 300dp，
        // 这两段的上限压住之后，最后一行按键提示才不会贴着卡片边缘甚至溢出。
        title.maxLines = 3
        title.ellipsize = TextUtils.TruncateAt.END
        info.addView(title)
        meta.maxLines = 2
        meta.setPadding(0, dp(12), 0, dp(12))
        info.addView(meta)
        intro.maxLines = 4
        intro.ellipsize = TextUtils.TruncateAt.END
        info.addView(intro)
        info.addView(Space(activity), lp(1, 0).apply { weight = 1f })
        info.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // 按键与说明分两列：按键固定宽度，说明才不会因为「↑ ↓」「确定」宽窄不一同列对不齐。
                listOf("↑ ↓" to "换一部", "确定" to "打开操作", "返回" to "顶部导航")
                    .forEach { (key, action) ->
                        addView(
                            LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(
                                    label(key, 12.5f, TvStyle.textDim).apply {
                                        minWidth = dp(44)
                                        includeFontPadding = false
                                    }
                                )
                                addView(label(action, 12.5f, TvStyle.muted))
                            },
                            lp(-2, -2).apply { topMargin = dp(1) },
                        )
                    }
            }
        )
        content.addView(info, lp(0, -1).apply { weight = 1f })
        addView(content, lp(-1, 0).apply { weight = 1f })
        pauseButton = button("暂停试播") { toggle() }
        watchButton =
            button("观看全集") {
                state.current?.let {
                    remember()
                    pause()
                    watch(it)
                }
            }
        favoriteButton =
            button("账户收藏") {
                state.current?.let {
                    state.userPaused = true
                    playback.pause()
                    remember()
                    paintActions()
                    favorite(it, favoriteButton)
                }
            }
        buttons += listOf(pauseButton, watchButton, favoriteButton)
        buttons.forEachIndexed { index, b ->
            actions.addView(
                b,
                lp(0, dp(TvStyle.BUTTON_COMPACT + TvStyle.SOFT_BAND * 2)).apply {
                    weight = 1f
                    if (index < buttons.lastIndex) rightMargin = dp(10)
                },
            )
            b.nextFocusLeftId = buttons.getOrNull(index - 1)?.id ?: b.id
            b.nextFocusRightId = buttons.getOrNull(index + 1)?.id ?: b.id
            b.nextFocusUpId = stage.id
            b.nextFocusDownId = b.id
        }
        actions.setPadding(0, dp(10), 0, 0)
        actions.visibility = View.GONE
        addView(actions)
        stage.nextFocusLeftId = stage.id
        stage.nextFocusRightId = stage.id
        stage.nextFocusUpId = stage.id
        stage.nextFocusDownId = stage.id
        nav.forEach {
            it.nextFocusDownId =
                (if (state.recommendation) recommendButton
                    else if (state.category == DramaCategory.COMIC) comicButton else realButton)
                    .id
        }
        stage.setOnFocusChangeListener { view, focused ->
            stage.background = TvStyle.card(activity, focused)
            TvStyle.focus(view, focused, 1.01f)
        }
        stage.setOnClickListener { showActions() }
        stage.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                        move(if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) showActions()
                    true
                }
                else -> false
            }
        }
        playback.status = { value ->
            if (!closed && clip != null)
                when (value) {
                    TrialStatus.BUFFERING -> {
                        status.visibility = View.VISIBLE
                        status.text = "正在加载第一集…"
                    }
                    TrialStatus.READY -> {
                        status.visibility = if (state.userPaused) View.VISIBLE else View.GONE
                        failed = false
                        status.text = if (state.userPaused) "试播已暂停" else "第一集试播 · 喜欢就看全集"
                        paintActions()
                        prefetchUpcoming()
                    }
                    TrialStatus.ENDED -> {
                        status.visibility = View.VISIBLE
                        remember()
                        state.ended = true
                        state.userPaused = true
                        status.text = "本集试播结束 · 看全集或按 ↓ 换一部"
                        paintActions()
                        if (active && stage.hasFocus()) showActions()
                    }
                    TrialStatus.ERROR -> {
                        status.visibility = View.VISIBLE
                        playback.pause()
                        failed = true
                        status.text = "试播暂不可用 · 可重试、换剧或查看全集"
                        paintActions()
                    }
                }
        }
        describe()
        paintActions()
    }

    // 设置进入首页时的初始焦点，并在应用位于前台时启动试播流程。
    fun start(foreground: Boolean, focusNav: Boolean) {
        if (focusNav) nav[0].requestFocus() else stage.requestFocus()
        if (foreground) resume()
    }

    private val prefetchPoll =
        object : Runnable {
            override fun run() {
                if (!active || closed) return
                prefetchUpcoming()
                main.postDelayed(this, 250)
            }
        }

    fun resume() {
        if (closed || active) return
        active = true
        main.removeCallbacks(prefetchPoll)
        main.postDelayed(prefetchPoll, 250)
        if (clip != null) {
            if (!state.userPaused && !state.ended) playback.resume()
            paintActions()
            prefetchUpcoming()
        } else if (state.current != null) loadCurrent() else loadPage(false)
    }

    fun pause() {
        if (closed) return
        remember()
        active = false
        main.removeCallbacks(prefetchPoll)
        playback.pause()
        cancelRequest()
        cancelPrefetch()
        paintActions()
    }

    fun back(): Boolean {
        if (actions.visibility != View.VISIBLE) return false
        actions.visibility = View.GONE
        if (resumeAvailable) {
            resumeButton.visibility = View.VISIBLE
            stage.nextFocusRightId = resumeButton.id
        }
        stage.requestFocus()
        return true
    }

    fun refreshAccountLabel() {
        if (!closed) paintActions()
    }

    // 遥控器确定键或点击视频后显示操作栏，并将焦点移到可用操作。
    private fun showActions() {
        // 操作栏一出现，右侧只剩约 300dp：先收起「继续观看」，否则按键提示会被挤出卡片。
        // 这时「观看全集」本来就在手边，收起这一行不会少一条路；返回时再放回来。
        if (resumeAvailable) {
            resumeButton.visibility = View.GONE
            stage.nextFocusRightId = stage.id
        }
        actions.visibility = View.VISIBLE
        paintActions()
        (if (watchButton.isEnabled) watchButton else pauseButton).requestFocus()
    }

    private fun paintActions() {
        pauseButton.text =
            when {
                failed || state.current == null -> "重试试播"
                state.ended -> "重新试播"
                state.userPaused -> "继续试播"
                else -> "暂停试播"
            }
        watchButton.isEnabled = state.current != null
        favoriteButton.isEnabled = state.current != null
        favoriteButton.text = state.current?.let { favoriteLabel(it.id) } ?: "账户收藏"
        recommendButton.isSelected = state.recommendation
        comicButton.isSelected = !state.recommendation && state.category == DramaCategory.COMIC
        realButton.isSelected = !state.recommendation && state.category == DramaCategory.REAL
        paintButton(recommendButton)
        paintButton(comicButton)
        paintButton(realButton)
        stage.keepScreenOn = active && clip != null && !state.userPaused && !state.ended && !failed
    }

    private fun describe() {
        val series = state.current
        title.text = series?.title ?: "发现下一部好剧"
        meta.text =
            if (series == null) "第一集试播"
            else
                listOf("第一集试播", series.badge, series.tags)
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
        intro.text =
            series?.description?.ifBlank { "先试试看，喜欢这部作品就选择观看全集。" } ?: "选择漫剧或真人剧，上下换一部，找到想追的故事。"
        tag.text = if (state.recommendation) "推荐 · 第一集试播" else "${state.category.label}推荐 · 第一集试播"
        paintActions()
        refreshResume()
    }

    private fun remember() {
        if (clip != null) state.position = playback.position()
    }

    private fun cancelRequest() {
        generation++
        scope?.cancel()
        scope = null
        job?.cancel(true)
        job = null
        loading = false
    }

    private fun cancelPrefetch() {
        prefetchGeneration++
        prefetchScope?.cancel()
        prefetchScope = null
        prefetchJob?.cancel(true)
        prefetchJob = null
        prefetchTarget = null
        attemptedPrefetch.clear()
        attemptedBatch = 0
    }

    private fun closeCached(cached: CachedTrial) {
        playback.remove(cached.clip.detail.series.id)
        cached.clip.close()
    }

    private fun clearCache() {
        cachedTrials.values.forEach(::closeCached)
        cachedTrials.clear()
    }

    private fun clearCurrent(
        retain: Boolean = false,
        keepPlayer: Boolean = false,
        keepPrefetch: Boolean = false,
    ) {
        remember()
        cancelRequest()
        if (!keepPrefetch) cancelPrefetch()
        if (!keepPlayer) playback.clear()
        clip?.let {
            if (retain && !failed) {
                cachedTrials.put(it.detail.series.id, CachedTrial(it))?.let(::closeCached)
            } else it.close()
        }
        clip = null
    }

    private fun takeCached(id: String): TrialClip? {
        val cached = cachedTrials.remove(id) ?: return null
        if (android.os.SystemClock.elapsedRealtime() - cached.savedAt > 90_000) {
            closeCached(cached)
            return null
        }
        return cached.clip
    }

    // 后台发起请求，主线程更新界面；页面关闭、切换或暂停后丢弃过期结果。
    private fun <T> request(action: () -> T, discard: (T) -> Unit = {}, done: (T) -> Unit) {
        cancelRequest()
        loading = true
        val token = generation
        val requestScope = RequestScope()
        scope = requestScope
        job =
            worker.submit {
                try {
                    val result = requestScope.run(action)
                    main.post {
                        if (closed || token != generation || !active) discard(result)
                        else {
                            loading = false
                            done(result)
                        }
                    }
                } catch (error: Exception) {
                    main.post {
                        if (closed || token != generation || !active) return@post
                        loading = false
                        failed = true
                        status.visibility = View.VISIBLE
                        val message =
                            error.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(90)
                        status.text = "暂时无法加载 · ${message.ifBlank { "请重试或换一部" }}"
                        paintActions()
                    }
                }
            }
    }

    // 按当前推荐模式请求下一页；结果写入状态后，再加载当前作品的试播资源。
    private fun loadPage(advance: Boolean) {
        if (!active || closed || loading) return
        if (state.page >= 100 || (!state.hasMore && state.current != null)) {
            status.visibility = View.VISIBLE
            status.text = "本批作品已看完，可切换类型继续发现"
            return
        }
        status.visibility = View.VISIBLE
        status.text = "正在读取热门作品…"
        val page = state.page + 1
        val category = state.category
        val recommendation = state.recommendation
        val cursor = state.recommendationCursor
        request({
            if (recommendation) backend.recommendations(page, cursor)
            else backend.categoryRecommendations(category, page, cursor)
        }) { result ->
            state.append(result, page)
            if (advance) state.move(1)
            if (state.current == null) {
                status.text = "暂时没有可试播的作品，可切换类型或重试"
                failed = true
                paintActions()
            } else loadCurrent()
        }
    }

    // 优先复用缓存或正在进行的预加载，避免为同一部作品重复请求。
    private fun loadCurrent() {
        val series = state.current ?: return
        if (!active || closed) return
        // 记下展示过的作品：冷启动时用它跳过重复内容，而不是每次都从同一部开始。
        noteDisplayed(series)
        clearCurrent(keepPrefetch = prefetchTarget == series.id)
        failed = false
        describe()
        status.visibility = View.VISIBLE
        status.text = "正在准备第一集试播…"
        trimCache()
        val cached = takeCached(series.id)
        if (cached != null) attach(cached)
        else if (prefetchTarget == series.id && prefetchJob != null) {
            // Promote the in-flight download instead of cancelling and resolving the same URL
            // again.
            loading = true
            android.util.Log.i("HGTV", "Home promoted in-flight preload")
        } else request({ backend.trial(series) }, { it.close() }, ::attach)
    }

    private fun noteDisplayed(series: Series) =
        backend.noteDisplayed(
            series,
            state.recommendationCursor,
            if (state.recommendation) null else state.category,
        )

    private fun attach(next: TrialClip) {
        clip = next
        val series = next.detail.series
        meta.text =
            "第一集试播 · 共 ${next.episodeCount} 集" +
                (series.tags.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        if (series.description.isNotBlank()) intro.text = series.description
        val autoplay = active && !state.userPaused && !state.ended
        playback.setCurrentIndex(state.offset + state.index)
        if (!playback.select(next, state.position, autoplay)) {
            playback.load(next, state.position, autoplay)
            cachedTrials.values.forEach { queueCached(it.clip) }
        }
        paintActions()
    }

    private fun queueCached(clip: TrialClip) {
        val index = state.items.indexOfFirst { it.id == clip.detail.series.id }
        if (index >= 0) playback.queue(clip, state.offset + index)
    }

    private fun trimCache() {
        val allowed =
            state.items
                .subList(
                    (state.index - 1).coerceAtLeast(0),
                    minOf(state.index + 4, state.items.size),
                )
                .map { it.id }
                .toSet()
        val now = android.os.SystemClock.elapsedRealtime()
        val stale =
            cachedTrials
                .filter { it.key !in allowed || now - it.value.savedAt > 90_000 }
                .keys
                .toList()
        stale.forEach { cachedTrials.remove(it)?.let(::closeCached) }
    }

    /** One speculative download at a time, after the current player is ready. */
    private fun prefetchUpcoming() {
        if (
            closed ||
                !active ||
                clip == null ||
                failed ||
                loading ||
                prefetchJob != null ||
                !playback.canPrefetch()
        )
            return
        trimCache()
        val upcoming = state.items.drop(state.index + 1).take(3)
        val next = upcoming.firstOrNull { it.id !in cachedTrials && it.id !in attemptedPrefetch }
        if (next == null) {
            if (
                upcoming.size < 3 &&
                    state.hasMore &&
                    state.page < 100 &&
                    attemptedBatch != state.page + 1
            )
                prefetchBatch()
            return
        }
        attemptedPrefetch += next.id
        prefetchTarget = next.id
        val token = prefetchGeneration
        val requestScope = RequestScope()
        prefetchScope = requestScope
        prefetchJob =
            worker.submit {
                val prepared =
                    runCatching { requestScope.run { backend.preload(next) } }.getOrNull()
                main.post {
                    if (closed || !active || token != prefetchGeneration) prepared?.close()
                    else {
                        prefetchJob = null
                        prefetchScope = null
                        prefetchTarget = null
                        if (state.current?.id == next.id && clip == null) {
                            loading = false
                            if (prepared != null) attach(prepared) else loadCurrent()
                        } else if (prepared != null) {
                            cachedTrials.put(next.id, CachedTrial(prepared))?.let(::closeCached)
                            queueCached(prepared)
                        }
                        prefetchUpcoming()
                    }
                }
            }
    }

    private fun prefetchBatch() {
        val page = state.page + 1
        attemptedBatch = page
        val category = state.category
        val recommendation = state.recommendation
        val cursor = state.recommendationCursor
        val token = prefetchGeneration
        val requestScope = RequestScope()
        prefetchScope = requestScope
        prefetchJob =
            worker.submit {
                val result =
                    runCatching {
                            requestScope.run {
                                if (recommendation) backend.recommendations(page, cursor)
                                else backend.categoryRecommendations(category, page, cursor)
                            }
                        }
                        .getOrNull()
                main.post {
                    if (closed || !active || token != prefetchGeneration) return@post
                    prefetchJob = null
                    prefetchScope = null
                    if (result != null && page == state.page + 1) {
                        state.append(result, page)
                        prefetchUpcoming()
                    }
                }
            }
    }

    private fun move(delta: Int) {
        if (closed || !active) return
        if (delta < 0 && state.index == 0) {
            status.visibility = View.VISIBLE
            status.text = "已到本次浏览的最早作品"
            return
        }
        if (delta > 0 && state.next == null) {
            if (loading) return
            if (!state.hasMore) {
                status.visibility = View.VISIBLE
                status.text = "本批作品已看完，可切换类型继续发现"
                return
            }
            clearCurrent()
            loadPage(true)
            return
        }
        val target = state.items.getOrNull(state.index + delta) ?: return
        val ready = takeCached(target.id)
        clearCurrent(
            retain = true,
            keepPlayer = ready != null,
            keepPrefetch = target.id == prefetchTarget,
        )
        if (state.move(delta)) {
            if (ready != null) {
                noteDisplayed(target)
                failed = false
                describe()
                status.visibility = View.VISIBLE
                status.text = "正在切换作品…"
                attach(ready)
                trimCache()
            } else loadCurrent()
        }
    }

    private fun toggle() {
        if (loading) return
        if (failed || state.current == null || clip == null) {
            if (state.current == null) state.refresh()
            state.userPaused = false
            state.ended = false
            if (state.current == null) loadPage(false) else loadCurrent()
            return
        } else if (state.ended) {
            state.position = 0
            state.ended = false
            state.userPaused = false
            playback.restart()
        } else {
            state.userPaused = !state.userPaused
            if (state.userPaused) {
                remember()
                playback.pause()
            } else if (active) playback.resume()
        }
        status.visibility = if (state.userPaused) View.VISIBLE else View.GONE
        status.text = if (state.userPaused) "试播已暂停" else "第一集试播 · 喜欢就看全集"
        paintActions()
    }

    private fun changeType(category: DramaCategory) {
        if (!state.recommendation && state.category == category) return
        clearCurrent()
        state.select(category)
        clearCache()
        actions.visibility = View.GONE
        stage.requestFocus()
        describe()
        if (state.current != null) loadCurrent() else loadPage(false)
    }

    override fun close() {
        if (closed) return
        main.removeCallbacks(prefetchPoll)
        clearCurrent()
        clearCache()
        closed = true
        active = false
        stage.keepScreenOn = false
        playback.release()
        backend.close()
        worker.shutdownNow()
    }
}
