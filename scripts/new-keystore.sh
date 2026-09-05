#!/usr/bin/env bash
#
# 深脑安卓 · 新建正式签名 keystore（旧的口令丢了，2026-09-06）
#
# 由你亲手跑，不由 agent 跑：它会生成一个随机口令。口令只落在两处，都在你本机、都是 600 权限：
#   ~/Developer/android-toolchain/shennao-release.password.txt   ← 抄进密码管理器后可以删
#   shennao-app/local.properties 的 shennao.ksPass=               ← 构建时 Gradle 读，已 gitignore
# 旧 keystore 改名留着，不删。
#
# 用法：bash scripts/new-keystore.sh
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/Developer/android-toolchain/jdk/Contents/Home}"
DIR="$HOME/Developer/android-toolchain"
KS="$DIR/shennao-release.jks"
PW="$DIR/shennao-release.password.txt"

[ -f "$KS" ] && mv "$KS" "$KS.old-lost-password-$(date +%m%d)" && echo "旧 keystore 已改名留存"
sed -i '' '/^shennao\.ksPass=/d' "$ROOT/local.properties"

openssl rand -base64 24 | tr -d '\n' > "$PW"; chmod 600 "$PW"
printf 'shennao.ksPass=%s\n' "$(cat "$PW")" >> "$ROOT/local.properties"
grep -qE '^shennao\.ksPath=' "$ROOT/local.properties" || printf 'shennao.ksPath=%s\n' "$KS" >> "$ROOT/local.properties"

"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 -alias shennao \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Shennao, OU=Zaowuyun, O=Zaowuyun, L=Hangzhou, ST=Zhejiang, C=CN" \
  -storepass:file "$PW" -keypass:file "$PW"
chmod 600 "$KS"

DIGEST=$("$JAVA_HOME/bin/keytool" -list -v -keystore "$KS" -storepass:file "$PW" | grep -m1 'SHA256:' | awk '{print $2}' | tr -d ':' | tr 'A-F' 'a-f')
echo
echo "✓ 新 keystore：$KS（30 年）"
echo "✓ 口令在：$PW（抄进密码管理器，然后可以删这个文件）"
echo "✓ 证书 SHA-256：$DIGEST"
echo
echo "把上面那行 SHA-256 发给 agent，它会写进 scripts/publish-android.sh 再发布。"
