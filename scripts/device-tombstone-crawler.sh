#!/system/bin/sh
# 设备端 tombstone 采集器（非 root / user 构建可用）
#
# 背景：/data/tombstones 与 /data/system/dropbox 目录都不可读，但
# `dumpsys dropbox --print <日期> <时间> <TAG>` 能打印完整墓碑（含 backtrace），
# `dumpsys dropbox` 能列出条目。本脚本轮询列表，发现新条目就整条落盘到 /sdcard。
#
# 用法（从主机）：
#   adb push scripts/device-tombstone-crawler.sh /sdcard/hgtv-tomb.sh
#   adb shell "setsid sh /sdcard/hgtv-tomb.sh </dev/null >/dev/null 2>&1 &"
#
# 产物：/sdcard/hgtv-tombstones.txt（累积）、/sdcard/hgtv-tomb.seen（去重游标）
# 注意：/sdcard 在重启后仍保留，但本进程会随重启消失，需要重新拉起。

OUT=/sdcard/hgtv-tombstones.txt
SEEN=/sdcard/hgtv-tomb.seen
INTERVAL=20

[ -f "$SEEN" ] || : > "$SEEN"

while true; do
    dumpsys dropbox 2>/dev/null \
        | grep -E '^20[0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9] ' \
        | awk '{print $1" "$2" "$3}' \
        | while read -r d t tag; do
            key="$d $t $tag"
            grep -qxF "$key" "$SEEN" 2>/dev/null && continue
            {
                echo ""
                echo "================= $key ================="
                echo "captured: $(date '+%Y-%m-%d %H:%M:%S')"
                dumpsys dropbox --print "$d" "$t" "$tag" 2>&1
            } >> "$OUT"
            echo "$key" >> "$SEEN"
        done
    sleep "$INTERVAL"
done
