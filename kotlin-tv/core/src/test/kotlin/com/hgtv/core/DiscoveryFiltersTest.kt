// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscoveryFiltersTest {
    @Test
    fun genreSceneMatchesTaxonomyForInitialAndFollowingRequests() {
        for (genre in listOf("short_play", "comic_series", "ai_series")) {
            val selection = DiscoverySelection(mapOf("genre" to genre))
            val expected = if (genre == "short_play") "default" else genre
            for (cursor in listOf(null, DiscoveryCursor(24, "test-cursor", 2))) {
                val request = selection.request(cursor)
                assertEquals(expected, request.getString("req_scene"))
                assertEquals(
                    genre,
                    request.getJSONObject("select_items").getJSONArray("genre").getString(0),
                )
            }
        }
    }

    private fun response(offset: Int = 24, more: Boolean = true) =
        JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONObject()
                    .put(
                        "selector_rows",
                        JSONArray()
                            .put(JSONObject().put("type", "genre").put("items", JSONArray()))
                            .put(
                                JSONObject()
                                    .put("type", "category_dim_role")
                                    .put(
                                        "items",
                                        JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put("selector_item_id", "cate_36")
                                                    .put("show_name", "重生")
                                            ),
                                    )
                            ),
                    )
                    .put(
                        "video_data",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("series_id", "123")
                                    .put("title", "作品")
                                    .put("episode_cnt", 20)
                            ),
                    )
                    .put("has_more", more)
                    .put("next_offset", offset)
                    .put("session_id", "test-cursor"),
            )

    @Test
    fun conditionsCombineAndFemaleZeroIsNotAnAllSentinel() {
        val choice =
            DiscoverySelection()
                .choose("category_dim_role", "cate_36")
                .choose("category_dim_epoch", "cate_1")
                .choose("gender", "0")
        val request = choice.request(null).getJSONObject("select_items")
        assertEquals("cate_36", request.getJSONArray("category_dim_role").getString(0))
        assertEquals("cate_1", request.getJSONArray("category_dim_epoch").getString(0))
        assertEquals("0", request.getJSONArray("gender").getString(0))
        assertEquals(
            0,
            choice
                .choose("gender", "")
                .request(null)
                .getJSONObject("select_items")
                .getJSONArray("gender")
                .length(),
        )
        assertEquals(
            mapOf("genre" to "comic_series"),
            choice.choose("genre", "comic_series").values,
        )
        assertThrows(IllegalArgumentException::class.java) { choice.choose("unknown", "bad") }
    }

    /**
     * 找剧列表没有搜索接口的 `hot_score_data`，热度藏在 `sub_title_list` 的火焰项
     * （`data_type == 27`）里；文案要补上「热度」，与排行榜、搜索结果的写法一致。
     */
    @Test
    fun heatComesFromTheFlameSubTitleAndStaysEmptyWhenAbsent() {
        fun videoOf(page: JSONObject) =
            page.getJSONObject("data").getJSONArray("video_data").getJSONObject(0)

        val raw = response()
        videoOf(raw)
            .put(
                "sub_title_list",
                JSONArray()
                    .put(JSONObject().put("content", "1335万").put("data_type", 27))
                    .put(JSONObject().put("content", "成长").put("data_type", 3)),
            )
        assertEquals(
            "1335万热度",
            FilteredDiscoveryParser.parse(raw.toString(), null).items.single().heat,
        )

        // 服务端已经带单位时不要叠加成「…热度 热度」。
        val already = response()
        videoOf(already)
            .put(
                "sub_title_list",
                JSONArray().put(JSONObject().put("content", "2087万热度").put("data_type", 27)),
            )
        assertEquals(
            "2087万热度",
            FilteredDiscoveryParser.parse(already.toString(), null).items.single().heat,
        )

        // 只有分类标签时没有热度，调用方回退到角标文案。
        val none = response()
        videoOf(none)
            .put(
                "sub_title_list",
                JSONArray().put(JSONObject().put("content", "都市").put("data_type", 3)),
            )
        assertEquals("", FilteredDiscoveryParser.parse(none.toString(), null).items.single().heat)
    }

    @Test
    fun cursorIsServerIssuedAndInvalidOrMissingDataCannotPretendToBeComplete() {
        val first = FilteredDiscoveryParser.parse(response().toString(), null)
        assertEquals("共 20 集", first.items.single().badge)
        assertEquals(24, first.next!!.offset)
        val next = DiscoverySelection().request(first.next)
        assertEquals("test-cursor", next.getString("session_id"))
        assertFalse(next.getBoolean("need_selector_panel"))
        assertEquals("only_content", next.getString("req_type"))
        assertFalse(first.next.toString().contains("test-cursor"))
        assertThrows(IOException::class.java) {
            FilteredDiscoveryParser.parse(response().toString(), first.next)
        }
        val missing = response()
        missing.getJSONObject("data").remove("video_data")
        assertThrows(IOException::class.java) {
            FilteredDiscoveryParser.parse(missing.toString(), null)
        }
        val noPanel = response()
        noPanel.getJSONObject("data").remove("selector_rows")
        assertThrows(IOException::class.java) {
            FilteredDiscoveryParser.parse(noPanel.toString(), null)
        }
        assertNull(FilteredDiscoveryParser.parse(response(more = false).toString(), null).next)
    }

    @Test
    fun repositoryPostsSignedReadOnlyFilterBodyWithoutChangingWebsiteDiscovery() {
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    assertEquals("POST", req.method)
                    assertEquals("/reading/distribution/category/landpage/v/", req.url.encodedPath)
                    assertEquals("73532", req.url.queryParameter("version_code"))
                    assertNotNull(req.header("x-gorgon"))
                    val buffer = okio.Buffer()
                    req.body!!.writeTo(buffer)
                    assertEquals(
                        "cate_36",
                        JSONObject(buffer.readUtf8())
                            .getJSONObject("select_items")
                            .getJSONArray("category_dim_role")
                            .getString(0),
                    )
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(response().toString().toResponseBody())
                        .build()
                }
                .build()
        val result =
            ContentRepository(http)
                .filteredDiscovery(DiscoverySelection(mapOf("category_dim_role" to "cate_36")))
        assertEquals("123", result.items.single().id)
        assertEquals(
            "重生",
            result.groups.single { it.type == "category_dim_role" }.options.single().label,
        )
    }
}
