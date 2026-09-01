#!/usr/bin/env bash
# 在真实 Android 运行时上验一遍录音链路的客户端那一半。
#
# 为什么需要它：单测和 Robolectric 界面测试都不启动真正的服务，也不碰
# AudioRecord 和 MediaCodec。2026-09-01 第一次跑它就抓到一个它们抓不到的崩溃
# （startForeground 在后台被拒、未接住 → 整个 App 崩）。
#
# 验三件事，每一件对着一个真实发生过的事故：
#   1. 装得上、起得来、不崩
#   2. 录音真的产出分片（不是「服务起来了但一个字节没写」）
#   3. **时间轴严丝合缝**——每片的开始等于上一片的结束。
#      2026-08-31 这里差了 160 毫秒，整整一天没有一条录音传上去过。
#
# 不验上传：那需要账号，而账号是用户的。
set -euo pipefail

PKG=com.qiuyiwu.shennao
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
RECORD_SECONDS="${RECORD_SECONDS:-200}"

[ -f "$APK" ] || { echo "✗ 找不到安装包 $APK"; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "✗ 没有连上的设备（先启动模拟器）"; exit 1; }

echo "▶ 安装并清空上次的数据"
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell pm clear $PKG >/dev/null 2>&1 || true
"$ADB" shell pm grant $PKG android.permission.RECORD_AUDIO || true
"$ADB" shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

echo "▶ 启动（前台）"
"$ADB" logcat -c
"$ADB" shell am start -n $PKG/.MainActivity >/dev/null
sleep 5
if [ "$("$ADB" logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c FATAL || true)" -gt 0 ]; then
  echo "✗ 启动就崩了："; "$ADB" logcat -d -s AndroidRuntime:E | tail -12; exit 1
fi

echo "▶ 录 ${RECORD_SECONDS} 秒"
"$ADB" shell am start-foreground-service -n $PKG/.record.RecordingService -a start >/dev/null
sleep "$RECORD_SECONDS"
"$ADB" shell am startservice -n $PKG/.record.RecordingService -a stop >/dev/null 2>&1 || true
sleep 8

if [ "$("$ADB" logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c FATAL || true)" -gt 0 ]; then
  echo "✗ 录音期间崩了："; "$ADB" logcat -d -s AndroidRuntime:E | tail -15; exit 1
fi

echo "▶ 校验落盘的分片"
"$ADB" shell "run-as $PKG find files/recordings -type f" 2>/dev/null > /tmp/shennao-segs.txt || true
python3 - <<'PY'
import re, sys
names = [l.strip().split('/')[-1] for l in open('/tmp/shennao-segs.txt') if l.strip()]
segs = []
for n in names:
    m = re.match(r'seg-(\d{6})-(\d{9})-(\d{9})\.(\w+)$', n)
    if m: segs.append((int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)))
segs.sort()

# 空的不是「通过」，是没测到。一条空跑而一直绿的检查比没有检查更糟。
if len(segs) < 2:
    print(f"✗ 只落了 {len(segs)} 段——录音没真正产出内容"); sys.exit(1)

bad = []
for i, (seq, st, en, ext) in enumerate(segs):
    prev = segs[i - 1][2] if i > 0 else 0
    if st != prev: bad.append((seq, prev, st))
    if en <= st: bad.append((seq, st, en))
    print(f"   seq={seq}  {st:>8} → {en:>8}  ({en - st} 毫秒, .{ext})")

if bad:
    print("\n✗ 时间轴有缺口——服务端会拒绝冻结清单，整场都传不上去：")
    for seq, a, b in bad: print(f"    seq={seq} 期望从 {a} 开始，实际 {b}")
    sys.exit(1)
unsealed = [s for s in segs if s[3] == 'pcm']
if unsealed:
    # .pcm 表示这一段还没封。它的名字里是「计划结束」而不是真实结束，
    # 所以时间轴校验对它是弱的——说出来，别让人以为验得比实际更严。
    print(f"\n⚠ 有 {len(unsealed)} 段没封段（.pcm）：停止指令多半没送到"
          f"（安卓 8+ 禁止后台 startService）。这几段的时间轴校验偏弱。")
print(f"\n✓ {len(segs)} 段，时间轴严丝合缝")
PY
echo "▶ 全部通过"
