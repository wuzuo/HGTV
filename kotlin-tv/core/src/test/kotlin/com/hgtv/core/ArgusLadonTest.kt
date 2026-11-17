package com.hgtv.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Argus/Ladon 移植校验。
 *
 * 期望值由 `hongguo-open-player-main/src/server/native/argus.js`（上游 fqapp/N3urda）在同一组
 * 固定输入下算出；随机量全部写死，所以可以逐位比对。
 */
class ArgusLadonTest {
    private val query =
        "aid=8662&device_id=2697580482416720&iid=2697580482420816&version_name=7.1.3.32" +
            "&_rticket=1763250000000"
    private val body = """{"mixed_video_id_map":{"1004":["7696131808160648216"]}}""".toByteArray()
    private val ts = 1763250000
    private val licenseId = ArgusSigner.LICENSE_ID

    @Test
    fun ladonMatchesUpstreamOracle() {
        assertEquals(
            "AQIDBI7dF+jquuRd1xKu9kU1043F8APHUjBLe2ja98bNrRn+",
            LadonSigner.encrypt(ts, licenseId, 8662, hex("01020304")),
        )
    }

    @Test
    fun argusMatchesUpstreamOracle() {
        val stubHex = digest("MD5", body).hex()
        assertEquals("51943f1ce92341bbd09adb372130be26", stubHex)
        assertEquals(
            "8oHu8LnZm0OtJJIaSr1427D+yeH70h5aZTVjoSW7MfFi/pHoCFnFN0fPuHo4PfnwY5hZ1DZ2Lgq/0LWW1oaJ" +
                "vy7QyK2DoC/3apRHJRv/ITmQAcaTH3RN0jJ4/MZy9W67q+ppqMfGBo1fr/sgOztLzOKp3WJfoxCZouXQdP" +
                "l3wIgblkE0eYJa8qjC64c7Xm+z8vUpw5uB1Z+NIKFyRklMB+uLA7N5q9tjNjYkl2MuXvxjZ/bAheTKQZk" +
                "PAaRriA6/br25ZNU8fTocgdKS3d849UJC+uKVbLhez46G1x+w9S16oQ==",
            ArgusSigner.sign(
                query = query,
                stubHex = stubHex,
                ts = ts,
                aid = 8662,
                licenseId = licenseId,
                deviceId = "2697580482416720",
                installId = "2697580482420816",
                randField = 1234567,
            ),
        )
    }

    @Test
    fun argusMatchesUpstreamOracleWithoutBody() {
        assertEquals(
            "8oHAH26Sbp9WBxIQWA8MN9eox+aFcBalk7RNICCaGHil3Y3nEcI2VZX8OjXuebIvd4uaZD4gZlrAQy85E2j" +
                "+6/bSNGdBs3Ty0B5cpoPYK5Vf/IGH5bo3Jzpw+wAqJfn6pKYr3M7YkK0hNOhVAFpwRMLCCNZfRAms0UCf" +
                "ykkWxa8uadLWJIcgPhsR7OP+mT9OKwQzWG3WyxAEenjFxlPMjeV5xH4xRW0RgD8/J4etuRmd/4jHSAIhFV" +
                "+kL8fVY94Yd45t6OZOMH3eL9K7gMaGSfuzWEYAestQWrN0DQrdb0Ddzw==",
            ArgusSigner.sign(
                query = query,
                stubHex = "",
                ts = ts,
                aid = 8662,
                licenseId = licenseId,
                deviceId = "2697580482416720",
                installId = "2697580482420816",
                randField = -7,
            ),
        )
    }

    /** 播放请求不能带 medusa/helios，其它接口保持原样。 */
    @Test
    fun playbackOmitsMedusaAndHelios() {
        val signer = Signer({ ts.toLong() * 1000 }, random = { 0.25 })
        val values = linkedMapOf("aid" to "8662", "device_id" to "1", "iid" to "2")
        val playback = signer.sign("https://example.com/player", values, body, playback = true)
        assertEquals(null, playback.headers["x-medusa"])
        assertEquals(null, playback.headers["x-helios"])
        // 占位值只有 8 个字符（4 字节），真实签名是几百字符。
        assert(playback.headers["x-argus"]!!.length > 100)
        assert(playback.headers["x-ladon"]!!.length > 30)

        val normal = signer.sign("https://example.com/player", values, body)
        assert(normal.headers.containsKey("x-medusa"))
        assert(normal.headers.containsKey("x-helios"))
    }
}
