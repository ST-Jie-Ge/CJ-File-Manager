# third_party/media3 恢复说明

`third_party/media3/` 不在 git 仓库里（它是上游 google/ExoPlayer 的独立 git 仓库，主仓库无法直接跟踪）。
本项目基于 **media3 1.4.1**（上游 commit `c35a9d6` "Bump media3 version to 1.4.1"）。

## 恢复步骤

1. 克隆上游并切到 1.4.1：

   ```bash
   git clone https://github.com/androidx/media third_party/media3
   cd third_party/media3 && git checkout 1.4.1
   ```

2. 用本目录下的两个文件**覆盖**上游同名文件（这是本项目对 decoder_av1 的全部本地修改）：

   | 本目录文件 | 覆盖到 |
   |---|---|
   | `decoder_av1-build.gradle` | `libraries/decoder_av1/build.gradle` |
   | `decoder_av1-AndroidManifest.xml` | `libraries/decoder_av1/src/main/AndroidManifest.xml` |

   修改内容：压缩上游 build.gradle（去掉无关插件/发布配置，compileSdk 36 / minSdk 29 /
   仅 arm64-v8a / CMake Release + BUILD_TESTING=OFF），依赖走
   `androidx.media3:media3-exoplayer:1.4.1` 二进制而非本地 project 引用。

3. 补齐 jni 下的第三方源码（按上游 AV1 模块的构建脚本原样放置）：

   ```bash
   # libgav1（C++ AV1 解码器）
   git clone https://chromium.googlesource.com/codecs/libgav1 \
       third_party/media3/libraries/decoder_av1/src/main/jni/libgav1
   # cpu-features（NDK CPU 特性探测）
   git clone https://github.com/google/cpu_features \
       third_party/media3/libraries/decoder_av1/src/main/jni/cpu_features
   ```

   （如上游 media3 仓库里有 vendor 脚本/说明，以其为准。）

4. `settings.gradle` 已把 `:media3-decoder-av1` 指到该目录，Gradle 同步后即可编译。
