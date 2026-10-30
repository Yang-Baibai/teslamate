#!/usr/bin/env bash
# 一键发版：构建 App → 部署 APK → 升版本号 → 重启 API → 冒烟验证。
# 前置：代码已改完并构建过一次（本脚本不负责写代码）。
# 用法：bash scripts/release.sh "更新说明一句话"
set -euo pipefail
cd "$(dirname "$0")/.."
MSG="${1:-}"
[ -z "$MSG" ] && { echo "用法: bash scripts/release.sh \"更新说明\""; exit 1; }

APP_DIR=/c/tm-app/teslamate-app   # canonical 构建目录（ASCII 路径，原因见 README）
echo "══ 1/6 构建 App ══"
cd "$APP_DIR"
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot' \
       ANDROID_HOME='C:\Users\<用户名>\AppData\Local\Android\Sdk' \
       GRADLE_USER_HOME='C:\gradle\gradle-home'
cmd //c "C:\gradle\gradle-8.10.2\bin\gradle.bat assembleDebug --console=plain" | tail -2

NEW_CODE=$(grep versionCode app/build.gradle.kts | grep -oE '[0-9]+')
NEW_NAME=$(grep versionName app/build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"')
echo "   构建产物：v$NEW_NAME (code $NEW_CODE)"

echo "══ 2/6 部署 APK（先放包再改元数据，顺序不能反）══"
ROOT="C:/Users/<用户名>/OneDrive/文档/teslamate"
cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/apk/teslamate-app.apk"
echo "   APK 已就位 $(stat -c%s "$ROOT/apk/teslamate-app.apk") bytes"

echo "══ 3/6 同步源码到工作区镜像 ══"
cd "$ROOT"
cmd //c "robocopy C:\tm-app\teslamate-app\app\src\main app-android\app\src\main /E /XD build .gradle .kotlin /NFL /NDL /NJH /NJS /NC /NS /NP" >/dev/null 2>&1 || true
cp "$APP_DIR/app/build.gradle.kts" app-android/app/build.gradle.kts

echo "══ 4/6 升 .env 元数据 ══"
# ⚠️ **v0.91.0**：原来用 `sed "s/.../.../"` 改这三行 ——
# **更新说明里只要出现一个 `/`（太常见了：「标题/告警文案/轮子颜色」），
# sed 就把替换文本里的 `/` 当成自己的分隔符** ⇒ `unknown option to 's'`，
# 而脚本是 `set -e` ⇒ **发版流程在第 4 步直接中断**，
# 后面的「重建镜像 + 冒烟验证」一步都没跑 ——
# 又一次「以为发出去了，其实没发」（本轮真的踩到了）。
#
# 现在改成**逐行整行重写**：任何字符（`/` `&` `|` `\` 空格）都安全。
# ⚠️ 用 `cat >` 而不是 `mv`：`mv` 会把 .env 的权限换成 mktemp 文件的 600。
ENV_TMP="$(mktemp)"
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
        APP_VERSION_CODE=*)  printf 'APP_VERSION_CODE=%s\n'  "$NEW_CODE" >> "$ENV_TMP" ;;
        APP_VERSION_NAME=*)  printf 'APP_VERSION_NAME=%s\n'  "$NEW_NAME" >> "$ENV_TMP" ;;
        APP_RELEASE_NOTES=*) printf 'APP_RELEASE_NOTES=%s\n' "$MSG"     >> "$ENV_TMP" ;;
        *)                   printf '%s\n' "$line" >> "$ENV_TMP" ;;
    esac
done < .env
cat "$ENV_TMP" > .env
rm -f "$ENV_TMP"
grep "^APP_VERSION" .env

echo "══ 5/6 重启 API（先重建镜像）══"
# ⚠️ **v0.90.0**：`docker compose up -d api` **不会**重建已存在的镜像 ——
# 它只是拿旧镜像重新起一个容器。本机实测：改了 api/*.go 跑完整个发版流程，
# 线上**仍然**在返回旧文案（容器是新的，镜像还是 6 小时前那版）——
# 于是「以为发出去了，其实没发」。
#
# 所以必须显式 `build`；源码没变时命中 Docker 层缓存，几秒就完。
docker compose build api 2>&1 | tail -1
docker compose up -d api 2>&1 | tail -1
sleep 3

echo "══ 6/6 冒烟验证 ══"
bash scripts/smoke.sh

echo
echo "🎉 v$NEW_NAME 发版完成。手机端杀掉 App 重开即会收到更新弹窗。"
echo "   别忘了 git add -A && git commit 记录本次变更。"
