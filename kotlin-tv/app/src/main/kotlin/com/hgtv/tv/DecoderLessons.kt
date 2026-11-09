// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.tv

/**
 * Learned decoder failures: capabilities the device claims but cannot actually decode. Keys are
 * codec plus coded size, so a failed portrait 1080x1920 never blocks landscape 1920x1080.
 */
internal class DecoderLessons {
    private val failed = HashSet<String>()

    fun key(codec: String, width: Int?, height: Int?): String? {
        val normalized = codec.trim().lowercase()
        if (normalized.isEmpty()) return null
        if (width == null || height == null || width <= 0 || height <= 0) return normalized
        return "$normalized|$width|$height"
    }

    fun remember(codec: String, width: Int?, height: Int?): String? {
        val exact = key(codec, width, height) ?: return null
        failed += exact
        return exact
    }

    fun fails(codec: String, width: Int?, height: Int?): Boolean {
        val normalized = codec.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (width == null || height == null || width <= 0 || height <= 0) {
            // 无尺寸的记录来自元数据缺失的那次失败，不再用来封杀整个编码。
            // 一次误判就会让该编码的所有清晰度全线不可播，代价远大于收益；
            // 真实不支持时静态探测（MediaCodecList）本来就会拦。
            return false
        }
        return failed.contains("$normalized|$width|$height")
    }

    /** Drops one learned verdict; true when it was actually there. */
    fun forget(codec: String, width: Int?, height: Int?): Boolean {
        val exact = key(codec, width, height) ?: return false
        return failed.remove(exact)
    }

    fun keys(): Set<String> = failed.toSet()

    fun restore(keys: Set<String>) {
        failed.clear()
        failed += keys
    }

    fun reset() = failed.clear()

    fun size() = failed.size
}
