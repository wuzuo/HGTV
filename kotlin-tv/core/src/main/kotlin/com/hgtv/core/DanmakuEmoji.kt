// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

/**
 * 弹幕文本里的表情标签。
 *
 * 平台的弹幕里表情不是字形，而是一个方括号包起来的名字：`[笑哭]`、`【赞】`、`［狗头］`
 * （半角方括号、全角方括号与【】三种都出现过）。照原样画出来，屏幕上就是一串 `[笑哭]` 文字，
 * 看不出表情。
 *
 * 表本身**照抄红果官方的 53 个表情**（`emoji_config.json` version 5，size 53）：第 N 个标签
 * 对应 `assets/danmaku_emoji/NN.webp`。这样画出来的表情与 App 里看到的是同一套图，不会出现
 * 「同一个 [笑哭] 在两边长得不一样」。认不出来的名字**原样保留**，不会把用户打的字吃掉。
 *
 * 正文里直接打出来的 emoji（不带方括号）不在这里处理，交给系统字体画——官方客户端也是这样，
 * 因为那本来就不是平台表情。
 */
object DanmakuEmoji {
    /**
     * 官方表情表：标签名 → 兜底字形。**顺序即图片序号**（下标 + 1 对应 `NN.webp`），
     * 改动顺序会让所有表情错位，加条目只能加在末尾。
     */
    private val official =
        listOf(
            "微笑" to "😊",
            "偷笑" to "🤭",
            "笑" to "😄",
            "什么" to "😳",
            "害羞" to "🥰",
            "爱慕" to "😍",
            "飞吻" to "😘",
            "奸笑" to "😏",
            "尬笑" to "😅",
            "思考" to "🤔",
            "撇嘴" to "😒",
            "做鬼脸" to "🤪",
            "酷" to "😎",
            "翻白眼" to "🙄",
            "惊呆" to "😲",
            "震惊" to "😱",
            "送心" to "💕",
            "委屈" to "🥺",
            "快哭了" to "😢",
            "笑哭" to "😂",
            "哭" to "😭",
            "大笑" to "🤣",
            "舔屏" to "🤤",
            "怒" to "😡",
            "捂脸" to "🤦",
            "吐" to "🤮",
            "恐惧" to "😨",
            "抓狂" to "🤯",
            "敬礼" to "🫡",
            "石化" to "🗿",
            "OK" to "👌",
            "赞" to "👍",
            "爱心" to "❤️",
            "伤心" to "💔",
            "KISS" to "💋",
            "懂了" to "👀",
            "探究" to "🧐",
            "重拳出击" to "👊",
            "吃瓜" to "🍉",
            "盯" to "👁️",
            "你细品" to "🫣",
            "赶稿中" to "✍️",
            "注意" to "⚠️",
            "饱了" to "🫃",
            "码住" to "📌",
            "学会了" to "✅",
            "顶帖" to "🔝",
            "求爆更" to "🔥",
            "求关注" to "🙏",
            "爽" to "😆",
            "鼓掌" to "👏",
            "没看够" to "🥹",
            "送花" to "🌹",
        )

    /** 标签名 → 图片序号（1 起），与 [official] 的顺序一一对应。 */
    private val indexes: Map<String, Int> =
        official.mapIndexed { position, entry -> entry.first to position + 1 }.toMap()

    /** 标签名 → 兜底字形；名字大小写不敏感（`OK` / `okk` 这类混写由 [glyph] 兜底）。 */
    private val glyphs: Map<String, String> = official.toMap()

    /**
     * 一个标签：`[名字]` / `【名字】` / `［名字］`。
     *
     * 名字最长 16 个字符，且不含任何括号与换行——这样 `[前面高能]然后[笑哭]` 不会把两段连起来吃掉，
     * 正文里本来就有的方括号（`[1,2]`、`[2024]`）也大多会落到「认不出的名字」而原样保留。
     */
    private val tag =
        Regex("""\[([^\[\]【】［］\r\n]{1,16})\]|【([^\[\]【】［］\r\n]{1,16})】|［([^\[\]【】［］\r\n]{1,16})］""")

    /** 标签总数 / 图片总数：测试与资源核对用。 */
    val tagCount get() = official.size
    val assetCount get() = official.size

    /** 标签名 → 字形；认不出来返回 null（调用方保留原文）。 */
    fun glyph(name: String): String? =
        glyphs[name] ?: glyphs[name.lowercase()] ?: glyphs[name.uppercase()]

    /** 标签名 → `assets/danmaku_emoji` 下的文件名（`01.webp`…`53.webp`）；不在表里返回 null。 */
    fun assetFor(name: String): String? {
        val index = indexes[name] ?: indexes[name.lowercase()] ?: indexes[name.uppercase()]
        return index?.let { "${it.toString().padStart(2, '0')}.webp" }
    }

    /** 表里所有标签名（测试与资源核对用）。 */
    val tagNames: Set<String> get() = indexes.keys

    /** 片段缓存条数上限。 */
    private const val CACHE_LINES = 512

    /**
     * 把文本里的标签换成字形，其余原样保留。
     *
     * 纯文本场合用它（日志、测试、没有图片可画的降级路径）；播放画面上的弹幕走 [parts]，
     * 那一条会把表情切成独立片段、画成图片。
     */
    fun displayText(text: String): String = tag.replace(text) { match ->
        glyph(nameOf(match)) ?: match.value
    }

    /**
     * 切好的片段按弹幕原文缓存。
     *
     * 弹幕是**边拉边画**的：每拿到一个 30 秒窗口就整批重排一次轨道，同一批文本要切几十遍。
     * 缓存只按条数封顶（不按字节），一条弹幕的片段总和就是它自己的长度，512 条撑死几十 KB。
     */
    private val cache =
        object : LinkedHashMap<String, List<DanmakuPart>>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<DanmakuPart>>
            ) = size > CACHE_LINES
        }

    /**
     * 把一条弹幕拆成「文字片段 / 表情片段」。
     *
     * 只按标签切：标签在官方表里就换成图片段，不在就留作文字。表情片段的 [DanmakuPart.text]
     * 是兜底字形——图片还没解码好时按字形画字，屏幕上不会出现空档。
     *
     * 正文里不带方括号的 emoji 不会被切出来：官方客户端也只把这些当普通字符渲染，
     * 多切一层反而会和系统字体的字形风格打架。
     */
    fun parts(text: String): List<DanmakuPart> = synchronized(cache) {
        cache[text] ?: split(text).also { cache[text] = it }
    }

    /** 真正做切分的地方；[parts] 负责缓存。返回的列表不可变，调用方可以直接拿着用。 */
    private fun split(text: String): List<DanmakuPart> {
        val out = mutableListOf<DanmakuPart>()
        var last = 0
        for (match in tag.findAll(text)) {
            val name = nameOf(match)
            val glyph = glyph(name) ?: continue
            val asset = assetFor(name) ?: continue
            if (match.range.first > last) out += DanmakuPart(text.substring(last, match.range.first), null)
            out += DanmakuPart(glyph, asset)
            last = match.range.last + 1
        }
        if (last < text.length) out += DanmakuPart(text.substring(last), null)
        return java.util.Collections.unmodifiableList(out)
    }

    /** 三种括号写法里取到实际名字。 */
    private fun nameOf(match: MatchResult): String =
        match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
}

/**
 * 一条弹幕里的一段：[asset] 为空是文字（按系统字体画），非空是一个表情（画 `assets/danmaku_emoji` 里的图）。
 * [text] 始终是能直接画出来的字形：图片没到位时用它兜底。
 */
data class DanmakuPart(val text: String, val asset: String?)
