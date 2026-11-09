// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.media

import com.hgtv.core.PublicComments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray

/** 一条公开评论；接口里只有这些字段是稳定的。 */
data class CommentItem(
    val author: String,
    val content: String,
    val likes: Long,
    val created: Long,
)

/**
 * 公开评论读取。
 *
 * 走的是官网播放页里的 `seriesSocialInfo` / `episodeSocialInfo`，与 TV 端同一个接口，
 * 只读、匿名。这里把「本集评论」排在前面、「整部剧评」排在后面合并成一条列表——
 * 短剧用户更关心本集。
 */
object CommentsLoader {
    suspend fun load(http: OkHttpClient, seriesId: String, episodeId: String): List<CommentItem> =
        withContext(Dispatchers.IO) {
            val payload =
                try {
                    PublicComments(http).read(seriesId, episodeId)
                } catch (problem: java.io.IOException) {
                    // 官网还没上这一集时播放页返回 404：这不是故障，只是还没有评论。
                    // 真的网络问题继续往上抛，界面会给出重试入口。
                    if (problem.message?.contains("HTTP 404") == true) return@withContext emptyList()
                    throw problem
                }
            val out = ArrayList<CommentItem>()
            out += parse(payload.optJSONArray("comments"))
            out += parse(payload.optJSONArray("reviews"))
            out
        }

    private fun parse(rows: JSONArray?): List<CommentItem> {
        if (rows == null) return emptyList()
        val out = ArrayList<CommentItem>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val content = row.optString("content").trim()
            if (content.isEmpty()) continue
            out +=
                CommentItem(
                    author = row.optString("author").ifBlank { "匿名用户" },
                    content = content.take(500),
                    likes = row.optLong("likes", 0).coerceAtLeast(0),
                    created = row.optLong("created", 0).coerceAtLeast(0),
                )
        }
        return out
    }
}
