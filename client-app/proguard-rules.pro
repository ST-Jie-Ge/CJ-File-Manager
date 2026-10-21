# ============================================================================
# CJ 文件管理器 —— R8 规则（1.08 起 release 启用 minify + resource shrink）
#
# 目标：只做「代码压缩 + 名称混淆 + 资源裁剪」，不改变任何运行时行为。
# 本工程特点决定了 keep 面极小：
#   * 无反射（全工程无 Class.forName / getMethod / 反射调用）
#   * 无 getIdentifier 之类的动态资源名查找
#   * 无 Gson / Jackson 等反射式序列化（配置持久化全部手写 JSONObject）
#   * 换肤走 AppCompatDelegate + values / values-night 静态资源
# 因此这里只登记少数「名称即契约」的入口，其余交给 AGP 默认规则与各 AAR
# 自带的 consumer rules。
# ============================================================================

# ---- 1) 自定义 View：由 XML 全类名反射实例化，构造器签名必须保留 ----
#      （AGP 默认规则已覆盖 View 子类，此处显式冗余声明，防未来默认规则变动）
-keep class com.cj.music.ImageEditorView { public <init>(android.content.Context); public <init>(android.content.Context, android.util.AttributeSet); }

# ---- 2) AndroidManifest 中声明的组件：类名即外部入口，禁止改名或裁剪 ----
-keep class com.cj.music.MainActivity { *; }
-keep class com.cj.music.ImageEditorActivity { *; }
-keep class com.cj.music.VideoPlayerActivity { *; }
-keep class com.cj.music.ArchiveOpenActivity { *; }

# ---- 3) 枚举：常量名会以字符串形式落盘（Protocol.name()），name()/valueOf 必须可用 ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public java.lang.String name();
}

# ---- 4b) jaudiotagger 已随音乐模式删除 ----
#      依赖（build.gradle）、ID3 反射 keep、assets 的 source.jar/LICENSE/NOTICE 全部移除。
#      275 个类 + 约 393KB class_data 曾仅靠 -keep enum org.jaudiotagger.** 存活于 release dex。
#      如未来重新引入音频标签编辑，需恢复：
#        -dontwarn java.awt.image.BufferedImage / javax.imageio.ImageIO / javax.imageio.stream.ImageInputStream
#        -keep class org.jaudiotagger.tag.id3.** { *; }
#        -keep class org.jaudiotagger.tag.reference.** { *; }
#        -keep class org.jaudiotagger.tag.datatype.** { *; }
#        -keep enum org.jaudiotagger.** { *; }
#      （jaudiotagger 按类名反射加载 FrameBodyXXX，类名与构造器都不能被 R8 改名。）

# ---- 4) 第三方库的可选依赖：编译期存在于 classpath、运行期不会走的路径 ----
#      commons-compress 会引用一批可选编解码器（zstd / brotli / snappy / lz4 / hadoop 等），
#      本工程只使用 zip / tar / gz / xz / 7z 的直连类，这些分支不会被执行。
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.junrar.**
-dontwarn net.lingala.zip4j.**
-dontwarn org.apache.commons.io.**
-dontwarn org.apache.commons.net.**
-dontwarn androidx.media3.**
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.slf4j.**

# ---- 5) SMB（SMBJ + BouncyCastle + SLF4J）----
# SMBJ 通过 BCSecurityProvider 直接 new BouncyCastle 的轻量 crypto 类（MD4/MD5/SHA/HMAC/RC4/DES/AES 等），
# 走的是「直接引用 + 静态初始化里的匿名工厂」，无 JCA provider 注册、无算法名反射。
# 因此只需 keep SMBJ 入口，R8 的 shrink 可达性分析会从 SMBJ 自动追踪并保留其用到的 BC 类，
# 无需整包 keep org.bouncycastle（那会把未用的 pqc/后量子等约 4MB 一并带进来）。
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontnote org.bouncycastle.**
# mbassador 事件总线：SMBJ 用它做内部事件分发（SMBEventBus），按 @Handler 注解 + 反射查找订阅方法；
# 注解与方法名即契约，整包 keep（仅约 100KB）。
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**
# asn-one：纯 ASN.1 解析（SPNEGO/Kerberos 认证），无反射，靠 shrink 从 SMBJ 自动追踪即可。
# SLF4J 仅门面，Android 端用 no-op 绑定（见 client-app 依赖），这里不引入日志后端。
-dontwarn org.slf4j.**
# SLF4J 2.x 通过 META-INF/services/org.slf4j.spi.SLF4JServiceProvider（ServiceLoader）绑定。
# 保留 ServiceLoader 发现的 provider 与服务描述文件条目，避免 R8 把 provider 剥掉后
# 退回 NOP 并打 "No SLF4J providers were found" 警告。
-keep class org.slf4j.nop.** { *; }
-keep class org.slf4j.spi.SLF4JServiceProvider { *; }
# BC provider resolves algorithm implementations by name; BCJSSE selects API variants reflectively.
# Provider is local to each FTPS context and never registered as the application's global provider.
-keep class org.bouncycastle.jsse.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.naming.**
-keep class org.libtorrent4j.swig.** { *; }
# Preserve wrapper contracts for JNI callbacks and release instrumentation.
-keep,allowobfuscation class org.libtorrent4j.** { *; }
-keep,allowobfuscation class com.cj.music.DownloadLink { *; }
-keep,allowobfuscation class com.cj.music.LinkDownloadEngine** { *; }
-keep,allowobfuscation class com.cj.music.LinkDownloadStore** { *; }
# ED2K wire containers instantiate packet/tag classes reflectively.
-keep class org.dkf.jed2k.protocol.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn org.codehaus.mojo.animal_sniffer.**
# FFmpegKit：native 层通过 JNI 回调 Java（日志 / 统计 / 完成回调都在 com.arthenica.ffmpegkit 包里
# 按类名+签名查找），这些类被 R8 改名后回调会直接失效（转码卡住、进度永远 0）。
# aar 自带的 consumer 规则不够全，这里整包无条件保留；体积代价只有几十 KB。
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**
# NextLib FFmpeg Media3 扩展：Renderer/Decoder 由 ExoPlayer 通过反射/类名发现，且 JNI 方法签名不可混淆。
-keep class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.** { *; }
-dontwarn io.github.anilbeesetti.nextlib.**
