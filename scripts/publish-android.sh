#!/usr/bin/env bash
#
# 深脑安卓 · 发布到 shennao.zaowuyun.com 首页
#
# 做的事：正式签名构建 → 核对签名指纹和线上一致 → 算 sha256 → 上传 apk + latest.json（原子替换）→ 回读核对。
# 首页与 /download 页都读 /downloads/latest.json（主仓库 lib/android-release.ts），App 自己也读它查更新（Update.kt）。
#
# 前提（只有这一条需要人）：local.properties 里有
#   shennao.ksPath=/Users/qiu/Developer/android-toolchain/shennao-release.jks
#   shennao.ksPass=<口令>
# 口令不进仓、不进日志、不进这个脚本。
#
# 用法：bash scripts/publish-android.sh            # 构建 + 上传
#       DRY_RUN=1 bash scripts/publish-android.sh  # 只构建、核对、生成清单，不上传
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/Developer/android-toolchain/jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519_skill_deploy}"
SSH_HOST="${SSH_HOST:-ubuntu@122.51.221.171}"
REMOTE_DIR=/var/www/shennao-downloads
BASE_URL=https://shennao.zaowuyun.com/downloads
# 线上历史版本一直用的签名。换签名 = 所有人得卸载重装并丢掉本地凭证，所以这里写死、对不上就拒发。
EXPECTED_SIGNER=c1fb934ed207073275cebe4bb2be0621518a7fefe50bb66cad7bbb9684cba24b

fail() { printf '\n✗ %s\n' "$1" >&2; exit 1; }
ok()   { printf '✓ %s\n' "$1"; }

# ── 0. 签名配置在不在（只看键，不看值） ─────────────────────────────
grep -qE '^shennao\.ksPath=' "$ROOT/local.properties" 2>/dev/null || fail "local.properties 缺 shennao.ksPath——见脚本头部"
grep -qE '^shennao\.ksPass=' "$ROOT/local.properties" 2>/dev/null || fail "local.properties 缺 shennao.ksPass——见脚本头部"

# ── 1. 版本号从 gradle 读，不手打 ───────────────────────────────────
VERSION_NAME=$(grep -oE 'versionName = "[^"]+"' "$ROOT/app/build.gradle.kts" | cut -d'"' -f2)
VERSION_CODE=$(grep -oE 'versionCode = [0-9]+' "$ROOT/app/build.gradle.kts" | grep -oE '[0-9]+')
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || fail "读不到版本号"
FILE="shennao-$VERSION_NAME.apk"

# ── 2. 正式签名构建；没签上就是失败 ─────────────────────────────────
( cd "$ROOT" && ./gradlew --console=plain -q :app:testDebugUnitTest :app:assembleRelease ) || fail "构建或单测失败"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || fail "没有签名的 release 包（只有 app-release-unsigned.apk）：签名配置没生效"
ok "构建 $VERSION_NAME ($VERSION_CODE)"

# ── 3. 签名指纹必须和线上一致 ───────────────────────────────────────
APKSIGNER=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
SIGNER=$("$APKSIGNER" verify --print-certs "$APK" | grep -oE 'SHA-256 digest: [0-9a-f]+' | head -1 | awk '{print $3}')
[ "$SIGNER" = "$EXPECTED_SIGNER" ] || fail "签名指纹不对：$SIGNER ≠ 线上 $EXPECTED_SIGNER。发出去所有人都装不上。"
ok "签名与线上一致"

# ── 4. 清单 ─────────────────────────────────────────────────────────
OUT="$ROOT/dist"; mkdir -p "$OUT"
cp "$APK" "$OUT/$FILE"
SIZE=$(stat -f %z "$OUT/$FILE")
SHA=$(shasum -a 256 "$OUT/$FILE" | awk '{print $1}')
cat > "$OUT/latest.json" <<JSON
{
  "versionName": "$VERSION_NAME",
  "versionCode": $VERSION_CODE,
  "file": "$FILE",
  "size": $SIZE,
  "sha256": "$SHA",
  "url": "$BASE_URL/$FILE"
}
JSON
ok "清单 dist/latest.json（$((SIZE / 1048576)) MB · $SHA）"
[ "${DRY_RUN:-}" = "1" ] && { echo "DRY_RUN：不上传"; exit 0; }

# ── 5. 上传：先传 apk，再原子替换清单（清单先指向新包之前，新包必须已经在） ──
SSH="ssh -i $SSH_KEY -o ConnectTimeout=15 -o BatchMode=yes $SSH_HOST"
scp -i "$SSH_KEY" -q "$OUT/$FILE" "$SSH_HOST:$REMOTE_DIR/$FILE.part"
$SSH "cd $REMOTE_DIR && mv -f $FILE.part $FILE && chmod 644 $FILE"
scp -i "$SSH_KEY" -q "$OUT/latest.json" "$SSH_HOST:$REMOTE_DIR/latest.json.part"
# latest.json 属主是 root（历史遗留），ubuntu 改不了内容但目录可写：mv 替换目录项即可
$SSH "cd $REMOTE_DIR && mv -f latest.json.part latest.json && chmod 644 latest.json"
ok "已上传"

# ── 6. 回读核对：线上清单里的 sha 必须等于本地算的，且包能下回来 ──────
sleep 1
LIVE=$(curl -s -m 15 -H 'Cache-Control: no-cache' "$BASE_URL/latest.json?nocache=$RANDOM")
echo "$LIVE" | grep -q "\"versionCode\": $VERSION_CODE" || fail "线上清单版本不对：$LIVE"
echo "$LIVE" | grep -q "$SHA" || fail "线上清单 sha 不对"
LIVE_SHA=$(curl -s -m 120 "$BASE_URL/$FILE" | shasum -a 256 | awk '{print $1}')
[ "$LIVE_SHA" = "$SHA" ] || fail "下回来的包 sha 不对：$LIVE_SHA"
ok "线上核对通过：$BASE_URL/$FILE"
echo "首页 5 分钟缓存后显示 v$VERSION_NAME；App 内下次巡查会提示更新。"
