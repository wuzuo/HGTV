# SPDX-License-Identifier: GPL-3.0-only
# 播放链路依赖反射拿解码器信息，R8 全量混淆后 MediaCodecInfo 的探测会退化成
# 「一律支持」，档位就不会降级了。这里保留 media3 与 okhttp 的反射入口。
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.decoder.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.jsoup.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
