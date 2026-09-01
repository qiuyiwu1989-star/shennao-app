#!/usr/bin/env bash
# 发版前在真实 Android 运行时上验一遍。
#
# 分两步，各自补一个别人补不了的缺口：
#   1. instrumented test —— 跑在 App 自己进程里，真正碰 AudioRecord 和 MediaCodec，
#      验分段的时间轴严丝合缝。JVM 单测和 Robolectric 都碰不到音频硬件。
#   2. 冷启动 —— 装上、打开、看崩不崩。
#      2026-09-01 第一次跑就抓到 startForeground 在后台被拒未接住导致的崩溃。
#
# 曾经用 `adb shell am start-foreground-service` 直接起服务——**那是侥幸**：
# 服务 exported=false，系统本来就该拒绝，后来它确实开始拒绝了。
# instrumented test 是这件事的正路。
set -euo pipefail

PKG=com.qiuyiwu.shennao
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"

"$ADB" get-state >/dev/null 2>&1 || { echo "✗ 没有连上的设备（先启动模拟器）"; exit 1; }

echo "▶ 真实运行时：录音与时间轴"
"$ADB" shell pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
./gradlew :app:connectedDebugAndroidTest -q

echo "▶ 冷启动不崩"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" logcat -c
"$ADB" shell am start -n $PKG/.MainActivity >/dev/null
sleep 6
if [ "$("$ADB" logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c FATAL || true)" -gt 0 ]; then
  echo "✗ 启动就崩了："
  "$ADB" logcat -d -s AndroidRuntime:E | tail -15
  exit 1
fi
echo "▶ 全部通过"
