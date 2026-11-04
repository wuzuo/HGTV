#!/usr/bin/env bash
# 启动 TV 调试用模拟器（Android TV 1080p / android-36）。
#
# AVD 放在 D:\avd（不在默认的 ~/.android/avd），所以必须先设 ANDROID_AVD_HOME，
# 否则 emulator 会报 "Unknown AVD name"。
#
# 用法：
#   scripts/start-tv-emulator.sh            # 快照启动，约 25 秒
#   scripts/start-tv-emulator.sh --cold     # 冷启动，快照异常时用，约 1-3 分钟
#   scripts/start-tv-emulator.sh --window   # 带窗口（默认无窗口，配合截图/录屏调试）
set -euo pipefail

AVD_HOME="${HGTV_AVD_HOME:-D:\\avd}"
AVD_NAME="${HGTV_AVD_NAME:-hgtv-tv}"
PORT="${HGTV_EMU_PORT:-5556}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
EMULATOR="$SDK/emulator/emulator.exe"

[ -x "$EMULATOR" ] || EMULATOR="$SDK/emulator/emulator"
[ -x "$EMULATOR" ] || { echo "找不到 emulator，请设置 ANDROID_HOME" >&2; exit 1; }

export ANDROID_AVD_HOME="$AVD_HOME"

extra=(-no-audio -no-boot-anim -no-snapshot-save)
case "${1:-}" in
  --cold)   extra+=(-no-snapshot-load) ;;
  --window) ;;
  *)        extra+=(-no-window) ;;
esac

echo "启动 $AVD_NAME（端口 $PORT，AVD_HOME=$AVD_HOME）"
"$EMULATOR" -avd "$AVD_NAME" -port "$PORT" "${extra[@]}" &
emu_pid=$!

echo "等待开机完成…"
for _ in $(seq 1 90); do
  if [ "$(adb -s "emulator-$PORT" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    echo "已就绪：emulator-$PORT"
    echo "后续命令请显式带 -s emulator-$PORT（当前 adb 上还挂着其它设备）"
    wait "$emu_pid"
    exit 0
  fi
  sleep 3
done

echo "开机超时，试 --cold 冷启动，或查看 emulator 输出" >&2
wait "$emu_pid"
