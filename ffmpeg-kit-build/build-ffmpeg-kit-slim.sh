#!/usr/bin/env bash
# ============================================================================
# 自编 ffmpeg-kit 瘦身版（只保留本项目实际用到的 6 个外部编码库）
#
# 实测：arm64 切片 27.7MB → 21.7MB，出包 APK 32.1MB → 29.2MB。
# FFmpeg 原生解码器全部保留 —— 格式工厂接受任意用户文件，裁解码器会静默缩窄
# 支持的输入格式。与官方 full-gpl 逐项对照后，只少了 4 个外部库，且都是我们
# 用不到的方向：libdav1d（AV1 解码，仍有原生 av1 解码器；播放走 ExoPlayer）、
# libtheora / libwebp / libtwolame（我们没提供这三种编码输出）。
#
# 用法（WSL / Ubuntu 22.04+）：
#   export ANDROID_NDK_ROOT=/opt/build/android-ndk   # r25b，官方 CI 用的版本
#   bash build-ffmpeg-kit-slim.sh
# 之后再跑 repack-aar.sh 打出可用的 aar。
#
# 国内网络注意：GitHub 直连会 GnuTLS 掉线。最省事的做法是在 Windows 侧用本机
# 代理 git clone，再 `cp -r` 到 /mnt/d 对应目录拷进 WSL（本项目 src-cache/ 即如此）。
# ============================================================================
set -euo pipefail

WORK="${WORK:-/opt/build}"
SRC_REPO="$WORK/ffmpeg-kit"
MIRRORS=("https://gh-proxy.com/" "https://ghfast.top/")

: "${ANDROID_NDK_ROOT:?请先 export ANDROID_NDK_ROOT（r25b）}"
mkdir -p "$WORK"
cd "$WORK"

echo "== 1/6 系统依赖（groff 供 libiconv 生成 man 页，zip 供重打包）=="
export DEBIAN_FRONTEND=noninteractive
apt-get install -y -qq autoconf automake libtool pkg-config curl git doxygen nasm \
  cmake gcc g++ gperf texinfo yasm wget unzip python3 make build-essential groff groff-base zip

echo "== 2/6 取 ffmpeg-kit 源码（tag v6.0.LTS，与 Maven 依赖严格同版本）=="
if [ ! -d "$SRC_REPO/scripts" ]; then
  rm -rf "$SRC_REPO"
  for m in "" "${MIRRORS[@]}"; do
    url="${m}https://github.com/arthenica/ffmpeg-kit/archive/refs/tags/v6.0.LTS.tar.gz"
    echo "  下载: $url"
    if curl -L --fail -m 900 -o ffmpeg-kit.tar.gz "$url"; then break; fi
  done
  tar xzf ffmpeg-kit.tar.gz && mv ffmpeg-kit-6.0.LTS "$SRC_REPO"
fi
cd "$SRC_REPO"

# android.sh 用 `git describe --tags` 取版本号：必须是 git 仓库且有 tag
if [ ! -d .git ]; then
  git init -q && git config user.email build@local && git config user.name build
  git add -A >/dev/null 2>&1 && git commit -qm "v6.0.LTS source" && git tag v6.0.LTS
fi

echo "== 3/6 补掉两个会卡住的依赖坑 =="
# (a) cpu-features 的 CMake 默认 BUILD_TESTING=ON，会去 clone googletest（国内必掉线）
python3 - <<'PY'
import pathlib
p=pathlib.Path("scripts/android/cpu-features.sh"); s=p.read_text()
if "BUILD_TESTING=OFF" not in s:
    s=s.replace("$(android_ndk_cmake) ||", "$(android_ndk_cmake) -DBUILD_TESTING=OFF ||")
    p.write_text(s); print("  已给 cpu-features 关掉 BUILD_TESTING")
PY
# (b) libiconv 的 git 版里 libcharset 是空子模块，且 gitsub.sh 要连 GNU 的 git 服务器。
#     换成官方发布包（自带 libcharset 与 gnulib 代码），再补 .gitmodules 与 gitsub.sh 占位。
#     注意：lame 依赖 libiconv（main-android.sh 里的 OK_libiconv），不能 --disable 绕开。
LI="$SRC_REPO/src/libiconv"
if [ ! -f "$LI/libcharset/configure" ]; then
  echo "  替换 libiconv 为官方发布包"
  rm -rf "$LI" && mkdir -p "$LI"
  for m in "" "${MIRRORS[@]}"; do
    if curl -L --fail -m 600 -o libiconv.tar.gz "https://ftp.gnu.org/pub/gnu/libiconv/libiconv-1.17.tar.gz"; then break; fi
  done
  tar xzf libiconv.tar.gz -C "$LI" --strip-components=1
  printf '#!/bin/sh\nexit 0\n' > "$LI/gitsub.sh" && chmod +x "$LI/gitsub.sh"
  : > "$LI/.gitmodules"     # 脚本开头会 sed 它，缺文件会 return 1
fi

echo "== 4/6 预取依赖源码（直连失败自动走镜像）=="
fetch() { # name url ref
  local name=$1 url=$2 ref=$3 dir="$SRC_REPO/src/$1"
  if [ -d "$dir" ] && [ -n "$(ls -A "$dir" 2>/dev/null)" ]; then echo "  [skip] $name"; return 0; fi
  for attempt in 1 2 3; do
    for m in "" "${MIRRORS[@]}"; do
      rm -rf "$dir"
      if git clone -q --branch "$ref" --depth 1 "${m}${url}" "$dir" 2>/dev/null && [ -d "$dir/.git" ]; then
        echo "  [ok] $name"; return 0
      fi
    done
    sleep 3
  done
  echo "  [FAIL] $name —— 改用 Windows 侧代理克隆后拷进来"; return 0
}
git config --global http.version HTTP/1.1
fetch ffmpeg     https://github.com/arthenica/FFmpeg   n6.0
fetch lame       https://github.com/arthenica/lame     RELEASE__3_100
fetch libogg     https://github.com/arthenica/ogg      v1.3.5
fetch libvorbis  https://github.com/arthenica/vorbis   v1.3.7
fetch libvpx     https://github.com/arthenica/libvpx   v1.13.0
fetch opus       https://github.com/arthenica/opus     v1.4
fetch x265       https://github.com/arthenica/x265     3.4
# x264 是 COMMIT 类型（不是 tag），单独处理
if [ ! -d "$SRC_REPO/src/x264/.git" ]; then
  for m in "" "${MIRRORS[@]}"; do
    rm -rf "$SRC_REPO/src/x264"
    if git clone -q "${m}https://github.com/arthenica/x264" "$SRC_REPO/src/x264" 2>/dev/null; then
      ( cd "$SRC_REPO/src/x264" && git checkout -q eaa68fad9e5d201d42fde51665f2d137ae96baf0 ) && echo "  [ok] x264" && break
    fi
  done
fi

echo "== 5/6 构建（只编 arm64；本机 12 核约 20-40 分钟）=="
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$WORK/android-sdk}"; mkdir -p "$ANDROID_SDK_ROOT"
./android.sh \
  --enable-gpl \
  --enable-x264 --enable-x265 \
  --enable-libvpx --enable-lame \
  --enable-opus --enable-libvorbis \
  --disable-arm-v7a --disable-arm-v7a-neon --disable-x86 --disable-x86-64

echo "== 6/6 原生库已就绪 =="
echo "  $SRC_REPO/prebuilt/android-arm64/ffmpeg/lib （FFmpeg 本体）"
echo "  $SRC_REPO/android/libs/arm64-v8a            （JNI 包装层）"
echo "  最后那步 gradle 打 aar 会因缺 Android SDK 报 failed，属预期；接着跑 repack-aar.sh"
