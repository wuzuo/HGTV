package com.hgtv.core

import java.io.IOException
import java.util.zip.GZIPInputStream
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelatedWorksTest {
    private val data =
        """{"code":0,"data":[{"show_type":547,"cell_name":"相关作品","video_data":[
      {"series_id":"111","title":"当前季","content_type":1004,"episode_cnt":80},
      {"series_id":"222","title":"下一季","content_type":1004,"episode_cnt":60,"season_index":2},
      {"series_id":"333","title":"第三季","content_type":1007,"episode_cnt":0,"season_index":3,"disable_play":false},
      {"series_id":"444","title":"原著小说","content_type":1},
      {"series_id":"555","title":"不可播放","content_type":1004,"episode_cnt":20,"disable_play":true},
      {"series_id":"222","title":"重复季","content_type":1004,"episode_cnt":60}
    ]}]}"""

    private fun http(answer: (Request) -> String) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(answer(chain.request()).toResponseBody())
                    .build()
            }
            .build()

    @Test
    fun parsesOfficialSeasonTypesAndExcludesCurrentNovelBlockedAndDuplicates() {
        val rows = RelatedWorks.parse(data, "111")
        assertEquals(listOf("222", "333"), rows.map { it.series.id })
        assertEquals("第2季", rows[0].season)
        assertFalse(rows[0].upcoming)
        assertEquals("第3季", rows[1].season)
        assertTrue(rows[1].upcoming)
    }

    @Test
    fun sourceUsesOfficialRelatedCellAndEmptyResponseFailoverWithExplicitRefresh() {
        var calls = 0
        val repository =
            ContentRepository(
                http { request ->
                    assertEquals("/reading/bookapi/plan/v:version/", request.url.encodedPath)
                    assertEquals("10", request.url.queryParameter("scene"))
                    assertEquals("detail_page_more_related", request.url.queryParameter("from"))
                    assertEquals("111", request.url.queryParameter("book_id"))
                    calls++
                    if (calls == 1) "" else data
                }
            )
        assertEquals(2, repository.related("111").size)
        assertEquals(2, calls)
        repository.related("111")
        assertEquals(2, calls)
        repository.related("111", force = true)
        assertEquals(3, calls)
    }

    @Test
    fun unavailableSourceFailsAfterBoundedRetriesRatherThanPretendingNoRelatedWorks() {
        var calls = 0
        val repository =
            ContentRepository(
                http {
                    calls++
                    ""
                }
            )
        assertThrows(IOException::class.java) { repository.related("111") }
        assertEquals(3, calls)
    }

    @Test
    fun subscriptionOnlyWritesRequestedFutureSeriesWithNumericIdAndCompressedJson() {
        var calls = 0
        val client =
            CloudAccountClient(
                http { request ->
                    calls++
                    assertEquals("POST", request.method)
                    assertEquals(
                        "/reading/bookapi/search/uncover_subscribe/v1/",
                        request.url.encodedPath,
                    )
                    assertEquals("sessionid=test-only", request.header("Cookie"))
                    assertEquals("gzip", request.header("Content-Encoding"))
                    val buffer = okio.Buffer()
                    request.body!!.writeTo(buffer)
                    val bytes = buffer.readByteArray()
                    val json =
                        JSONObject(GZIPInputStream(bytes.inputStream()).bufferedReader().readText())
                    assertTrue(json.get("item_id") is Number)
                    assertEquals(333L, json.getLong("item_id"))
                    assertEquals(1, json.getInt("op_type"))
                    assertEquals(3, json.getInt("item_type"))
                    """{"code":0}"""
                }
            )
        client.reserve(
            AccountCredentials("sessionid=test-only"),
            RelatedWorks.parse(data, "111")[1],
        )
        assertEquals(1, calls)
        assertThrows(IllegalArgumentException::class.java) {
            client.reserve(AccountCredentials("test-only"), RelatedWorks.parse(data, "111")[0])
        }
        assertEquals(1, calls)
    }

    @Test
    fun rejectedReservationIsNeverAccepted() {
        val client = CloudAccountClient(http { """{"code":100103}""" })
        assertThrows(IOException::class.java) {
            client.reserve(AccountCredentials("test-only"), RelatedWorks.parse(data, "111")[1])
        }
    }

    // ─── 跨季连播：站在某一季上，下一季是谁 ───────────────────────────────

    private fun work(id: String, title: String, order: Int, upcoming: Boolean = false) =
        RelatedWork(
            Series(id, title, badge = "全 10 集"),
            if (order > 0) "第${order}季" else "同IP",
            upcoming,
            order,
        )

    @Test
    fun nextSeasonIsTheNearestHigherSeasonNotTheNextRow() {
        // 接口顺序不可信：第 5 季排在第 3 季前面，选中途那一部会跳错季。
        val rows =
            listOf(
                work("1", "剧名第五部", 5),
                work("2", "剧名", 0),
                work("3", "剧名第三部", 3),
                work("4", "同IP的另一部", 0),
            )
        assertEquals("3", RelatedWorks.nextSeasonOf(rows, "剧名第二部")?.series?.id)
    }

    @Test
    fun firstSeasonWithoutNumberInTitleCanStillAdvance() {
        // 实测《十八岁太奶奶驾到》第一部标题里没有任何序号，按 0 算就等于永远进不到第二部。
        val rows = listOf(work("1", "十八岁太奶奶驾到", 1), work("2", "十八岁太奶奶驾到第二部", 2))
        assertEquals(0, RelatedWorks.seasonOrderOf("十八岁太奶奶驾到"))
        assertEquals("2", RelatedWorks.nextSeasonOf(rows, "十八岁太奶奶驾到")?.series?.id)
    }

    @Test
    fun lastSeasonHasNoNextAndUpcomingIsSkipped() {
        val rows = listOf(work("1", "第一部", 1), work("2", "第二部", 2))
        assertNull(RelatedWorks.nextSeasonOf(rows, "第二部"))
        // 待上线的季不能跳：进去只会是一个空播放页。
        val reserved = listOf(work("1", "第一部", 1), work("2", "第二部", 2, upcoming = true))
        assertNull(RelatedWorks.nextSeasonOf(reserved, "第一部"))
    }

    @Test
    fun singleSeasonShowNeverJumpsIntoOtherDramas() {
        val rows = listOf(work("8", "同IP甲", 0), work("9", "同IP乙", 0))
        assertNull(RelatedWorks.nextSeasonOf(rows, "一部单季剧"))
    }

    @Test
    fun seasonNumberIsOnlyReadFromSeasonMarkers() {
        // 没有「第X季/部」的数字不是季号，否则「18岁太奶」会被当成第 18 季。
        assertEquals(0, RelatedWorks.seasonOrderOf("18岁太奶在线训孙"))
        assertEquals(5, RelatedWorks.seasonOrderOf("十八岁太奶奶驾到，重整家族荣耀第五部"))
        assertEquals(12, RelatedWorks.seasonOrderOf("某剧 第12季"))
    }
}
