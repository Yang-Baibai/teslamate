#!/usr/bin/env bash
# Android 开发环境一键安装（JDK17 + cmdline-tools + SDK 包 + AVD）
# 产物：C:\Users\<user>\AppData\Local\Android\Sdk + Temurin JDK 17
# 本脚本幂等：已装过的步骤会自动跳过。日志见 setup-android-sdk.log
set -uo pipefail

SDK_UNIX="$(cygpath -u "$LOCALAPPDATA")/Android/Sdk"
SDK_WIN="$(cygpath -w "$SDK_UNIX")"
JDK_DIR=""
CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
LOG="$(dirname "$0")/setup-android-sdk.log"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ---------- 1. JDK 17 ----------
log "== 步骤 1/5: JDK 17 =="
for d in "/c/Program Files/Eclipse Adoptium"/jdk-17* "/c/Program Files/Microsoft"/jdk-17*; do
  [ -d "$d" ] && JDK_DIR="$d" && break
done
if [ -z "$JDK_DIR" ]; then
  log "winget 安装 Temurin JDK 17 ..."
  winget install --id EclipseAdoptium.Temurin.17.JDK -e --silent \
    --accept-source-agreements --accept-package-agreements --disable-interactivity
  for d in "/c/Program Files/Eclipse Adoptium"/jdk-17*; do
    [ -d "$d" ] && JDK_DIR="$d" && break
  done
fi
[ -n "$JDK_DIR" ] || { log "FATAL: JDK 17 未安装成功"; exit 1; }
JAVA_WIN="$(cygpath -w "$JDK_DIR")"
log "JDK: $JDK_DIR"

export JAVA_HOME="$JAVA_WIN"
export PATH="$(cygpath -u "$JDK_DIR")/bin:$PATH"

# ---------- 2. cmdline-tools ----------
log "== 步骤 2/5: cmdline-tools =="
if [ ! -d "$SDK_UNIX/cmdline-tools/latest/bin" ]; then
  mkdir -p "$SDK_UNIX/cmdline-tools"
  TMP_ZIP="$(mktemp -d)/cmdline-tools.zip"
  log "下载 $CMDLINE_ZIP_URL ..."
  curl -fSL --retry 3 -o "$TMP_ZIP" "$CMDLINE_ZIP_URL" || { log "FATAL: 下载失败"; exit 1; }
  unzip -q -o "$TMP_ZIP" -d "$SDK_UNIX/cmdline-tools"
  rm -rf "$SDK_UNIX/cmdline-tools/latest"
  mv "$SDK_UNIX/cmdline-tools/cmdline-tools" "$SDK_UNIX/cmdline-tools/latest"
  log "cmdline-tools 就位"
else
  log "cmdline-tools 已存在，跳过"
fi

SDKM_WIN="$(cygpath -w "$SDK_UNIX/cmdline-tools/latest/bin/sdkmanager.bat")"
AVDM_WIN="$(cygpath -w "$SDK_UNIX/cmdline-tools/latest/bin/avdmanager.bat")"
SDKM_WIN_ESC="${SDKM_WIN//\\/\\\\}"

# ---------- 3. licenses + SDK 包 ----------
log "== 步骤 3/5: licenses =="
yes | cmd //c "$SDKM_WIN" --licenses >/dev/null 2>&1 || true

log "== 步骤 4/5: SDK 组件 (platform-tools / android-35 / build-tools 35 / emulator / 镜像) =="
cmd //c "$SDKM_WIN" --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" "system-images;android-35;default;x86_64" 2>&1 | tail -5

# ---------- 4. AVD ----------
log "== 步骤 5/5: 创建 AVD teslamate_test =="
if [ ! -f "$HOME/.android/avd/teslamate_test.avd/config.ini" ]; then
  echo no | cmd //c "$AVDM_WIN" create avd -n teslamate_test -k "system-images;android-35;default;x86_64" -d pixel_6 2>&1 | tail -2
else
  log "AVD 已存在，跳过"
fi

# ---------- 输出环境变量（供后续脚本 source） ----------
cat > "$(dirname "$0")/dev-env.sh" <<EOF
# source 本文件获得 Android 构建环境
export JAVA_HOME="$(cygpath -u "$JAVA_WIN")"
export ANDROID_HOME="$SDK_UNIX"
export ANDROID_SDK_ROOT="\$ANDROID_HOME"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
EOF

log "✅ 环境安装完成。用 source dev-env.sh 获取环境变量"
exit 0
