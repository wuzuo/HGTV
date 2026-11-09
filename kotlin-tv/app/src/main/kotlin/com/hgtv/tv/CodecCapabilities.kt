// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaFormat
import com.hgtv.core.Rendition
import com.hgtv.core.VideoTracks

/**
 * Device decoder capability probe. TV boxes commonly cap AVC decoders near 1920x1088 with
 * width/height checked independently, so portrait sizes like 1080x1920 are rejected while landscape
 * 1920x1080 plays fine — and some boxes claim support they don't have. Learned runtime failures
 * (persisted across restarts) override the static probe.
 */
object CodecCapabilities {
    private val lessons = DecoderLessons()
    private var prefs: SharedPreferences? = null
    private val verdicts = HashMap<String, Boolean>()
    @Volatile private var logged = false

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.getSharedPreferences("native-decoder-lessons-v1", Context.MODE_PRIVATE)
        prefs = store
        lessons.restore(store.getStringSet("failures", emptySet()) ?: emptySet())
    }

    fun mime(codec: String): String? =
        when (codec.lowercase()) {
            "h264",
            "avc",
            "avc1",
            "avc3" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "bytevc1",
            "hvc1",
            "hev1",
            "h265",
            "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> null
        }

    fun supports(codec: String, width: Int?, height: Int?): Boolean {
        if (lessons.fails(codec, width, height)) return false
        return probe(codec, width, height)
    }

    /**
     * 只看设备静态能力，忽略运行时学到的失败记录。
     *
     * 学习记录可能来自误判 —— 尤其是把「进程被系统/原生库带走」当成「这套格式解不了」。
     * 一旦误记，[supports] 会让这一整类内容确定性地播不了。选流时如果严格判定把所有档位
     * 都过滤掉了，必须能用这个退回去试一次。
     */
    fun supportsIgnoringLessons(codec: String, width: Int?, height: Int?): Boolean =
        probe(codec, width, height)

    fun supportsIgnoringLessons(rendition: Rendition) =
        supportsIgnoringLessons(rendition.codec, rendition.width, rendition.height)

    fun supportsIgnoringLessons(track: VideoTracks.Track) =
        supportsIgnoringLessons(track.fourcc, track.width, track.height)

    private fun probe(codec: String, width: Int?, height: Int?): Boolean {
        val type = mime(codec) ?: return true
        if (width == null || height == null || width <= 0 || height <= 0) return true
        val key = "$type $width $height"
        synchronized(verdicts) {
            verdicts[key]?.let {
                return it
            }
        }
        val verdict =
            runCatching {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                        !info.isEncoder &&
                            runCatching {
                                    info
                                        .getCapabilitiesForType(type)
                                        .videoCapabilities
                                        .isSizeSupported(width, height)
                                }
                                .getOrDefault(false)
                    }
                }
                .getOrDefault(true) // A broken ROM probe must not block playback.
        synchronized(verdicts) { verdicts[key] = verdict }
        return verdict
    }

    fun supports(rendition: Rendition) =
        supports(rendition.codec, rendition.width, rendition.height)

    fun supports(track: VideoTracks.Track) = supports(track.fourcc, track.width, track.height)

    /**
     * Records a runtime decode failure; true when the static probe had claimed support
     * (over-reporting).
     */
    fun learnFailure(codec: String, width: Int?, height: Int?): Boolean {
        // 用静态探测而不是 supports()：后者已经把已有记录算进去了，重复记录时会把
        // 「设备声称支持」误报成 false。
        val overReported = probe(codec, width, height)
        val key = lessons.remember(codec, width, height) ?: return false
        prefs?.edit()?.putStringSet("failures", lessons.keys())?.apply()
        android.util.Log.w(
            "HGTV",
            "Learned decoder failure $key (static probe claimed support: $overReported)",
        )
        return overReported
    }

    /**
     * 撤销一条学习记录。宽松判定（只看静态能力）放行成功后调用 —— 严格判定说不行、
     * 实际却打开了，说明这条记录是误判，留着只会让下次又多走一轮。
     */
    fun forgive(codec: String, width: Int?, height: Int?) {
        if (!lessons.forget(codec, width, height)) return
        prefs?.edit()?.putStringSet("failures", lessons.keys())?.apply()
        android.util.Log.i("HGTV", "Forgave decoder failure $codec ${width}x$height")
    }

    fun resetLearned() {
        lessons.reset()
        prefs?.edit()?.putStringSet("failures", lessons.keys())?.apply()
    }

    fun learnedCount() = lessons.size()

    /** 诊断用：把具体条目读出来，只看条数无法判断是误记还是真不支持。 */
    fun learnedKeys(): List<String> = lessons.keys().sorted()

    fun logDeviceCapabilities() {
        if (logged) return
        logged = true
        val lines =
            runCatching {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS)
                        .codecInfos
                        .filter { !it.isEncoder }
                        .flatMap { info ->
                            listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
                                .filter { type ->
                                    runCatching { info.getCapabilitiesForType(type) }.isSuccess
                                }
                                .map { type ->
                                    val video = info.getCapabilitiesForType(type).videoCapabilities
                                    "${info.name} $type up to ${video.supportedWidths.upper}x${video.supportedHeights.upper}"
                                }
                        }
                }
                .getOrNull() ?: return
        android.util.Log.i("HGTV", "Decoder capabilities: ${lines.joinToString("; ")}")
    }
}
