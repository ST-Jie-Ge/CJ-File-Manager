#!/usr/bin/env bash
# ============================================================================
# 用官方 aar 的 Java 层 + 自编的瘦身 .so 重新打包 ffmpeg-kit aar
#
# 为什么这么做：官方 aar 的 classes.jar / AndroidManifest / R.txt 与本项目
# 依赖的 6.0.LTS 完全一致（同一个 tag 编出来的 JNI 才能配对），只需要把
# jni/arm64-v8a 下 9 个 .so 换成自己编的。这样不用装完整 Android SDK 跑
# ffmpeg-kit 仓库里那套 gradle 打包流程，也杜绝了 Java 层版本错配。
#
# 各块 .so 的来源：
#   libav*/libsw*        -> prebuilt/android-arm64/ffmpeg/lib/     （FFmpeg 本体）
#   libffmpegkit*.so     -> android/libs/arm64-v8a/                （JNI 包装层，ndk-build 产物）
#   libc++_shared.so     -> 保留官方 aar 里那份（就是 NDK 自带的运行时，不必换）
#
# 用法（WSL 里）：
#   bash /mnt/d/apk/ftpshare/FileManager/ffmpeg-kit-build/repack-aar.sh
# ============================================================================
set -euo pipefail

SRC=/opt/build/ffmpeg-kit
PROJ=/mnt/d/apk/ftpshare/FileManager
BASE_AAR="$PROJ/ffmpeg-kit-build/base/ffmpeg-kit-full-gpl-6.0.LTS.aar"
OUT_DIR="$PROJ/ffmpeg-kit-build/out"
WORK=/opt/build/repack
OUT_AAR="$OUT_DIR/ffmpeg-kit-slim-arm64.aar"

[ -f "$BASE_AAR" ] || { echo "缺少基础 aar: $BASE_AAR"; exit 1; }
mkdir -p "$OUT_DIR"

echo "== 1/4 收集自编 .so =="
FFMPEG_LIB_DIR="$SRC/prebuilt/android-arm64/ffmpeg/lib"
WRAPPER_LIB_DIR="$SRC/android/libs/arm64-v8a"
[ -d "$FFMPEG_LIB_DIR" ] || { echo "找不到 FFmpeg 产物: $FFMPEG_LIB_DIR"; exit 1; }
[ -d "$WRAPPER_LIB_DIR" ] || { echo "找不到包装层产物: $WRAPPER_LIB_DIR"; exit 1; }

mapfile -t SO_FILES < <(ls "$FFMPEG_LIB_DIR"/*.so "$WRAPPER_LIB_DIR"/*.so 2>/dev/null | sort)
echo "  共 ${#SO_FILES[@]} 个："
for f in "${SO_FILES[@]}"; do printf "    %-32s %8s\n" "$(basename "$f")" "$(du -h "$f" | cut -f1)"; done

echo "== 2/4 解包官方 aar =="
rm -rf "$WORK" && mkdir -p "$WORK"
unzip -q "$BASE_AAR" -d "$WORK"

echo "== 3/4 替换 jni/arm64-v8a（其余 ABI 一并去掉，工程只打 arm64）=="
rm -rf "$WORK"/jni
mkdir -p "$WORK/jni/arm64-v8a"
# libc++_shared.so 从官方 aar 里取一份（NDK 运行时，内容与自编无关）
unzip -p "$BASE_AAR" "jni/arm64-v8a/libc++_shared.so" > "$WORK/jni/arm64-v8a/libc++_shared.so"
for f in "${SO_FILES[@]}"; do cp "$f" "$WORK/jni/arm64-v8a/"; done
ls -la "$WORK/jni/arm64-v8a/"

echo "== 4/4 重新打包 =="
rm -f "$OUT_AAR"
( cd "$WORK" && zip -q -r -X "$OUT_AAR" . )
echo ""
echo "完成：$OUT_AAR"
ls -la "$OUT_AAR"
echo ""
echo "aar 内的 jni："
unzip -l "$OUT_AAR" | grep -E "\.so"
