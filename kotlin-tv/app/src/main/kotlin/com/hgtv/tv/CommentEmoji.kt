// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import com.hgtv.core.DanmakuEmoji

/**
 * 输入框里已输入文本的即时预览：把 `[笑哭]` 这类标签换成表情图。
 *
 * 图片与标签表都跟弹幕层共用 [DanmakuEmoji]，所以输入框里看到的表情和飘过去的同一条弹幕
 * 是同一张图；表里没有的标签原样保留。
 */
class CommentEmoji(private val context: Context) {
    private val cache = mutableMapOf<String, Bitmap>()

    private val pattern =
        Regex(
            """\[([^\[\]【】［］\r\n]{1,16})\]|【([^\[\]【】［］\r\n]{1,16})】|［([^\[\]【】［］\r\n]{1,16})］"""
        )

    fun render(text: String, size: Float): CharSequence {
        val result = SpannableStringBuilder(text)
        for (match in pattern.findAll(text)) {
            val name = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
            val asset = DanmakuEmoji.assetFor(name) ?: continue
            val bitmap =
                cache[asset]
                    ?: runCatching {
                            context.assets.open("danmaku_emoji/$asset").use {
                                BitmapFactory.decodeStream(it)
                            }
                        }
                        .getOrNull()
                        ?.also { cache[asset] = it }
                    ?: continue
            val drawable =
                BitmapDrawable(context.resources, bitmap).apply {
                    setBounds(0, 0, size.toInt(), size.toInt())
                }
            result.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return result
    }
}
