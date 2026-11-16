# ─────────────────────────────────────────────────────────────────
# R8 / ProGuard 规则（release 构建启用压缩混淆时生效）
#
# ⚠️ 为什么需要这个文件：R8 会把类名、方法名**全部改名**，
#    而 pdfbox 与 sherpa-onnx 都有"按名字反射/动态加载"的用法，
#    改错了症状很隐蔽：编译通过、App 能启动，只有真机用到那个功能时才崩
#    （典型：PDF 抽不出文字、模型加载失败）。
#
# 规则遵循"宁多勿少"：多留一个类名只是 APK 大几十 KB，
# 少留一个就是线上功能崩掉。
# ─────────────────────────────────────────────────────────────────

# ── pdfbox-android ──────────────────────────────────────────────
# 它用反射加载字体与加密处理器，还会在运行时读 jar 里的资源。
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# BouncyCastle（pdfbox 处理加密 PDF 时用）
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ── sherpa-onnx（离线 TTS 引擎）────────────────────────────────
# JNI 层按固定名字回调 Kotlin/Java，方法名被改就找不到。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class top.readaloud.app.KokoroTts { *; }
-keep class top.readaloud.app.WavWriter { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ── Media3 / ExoPlayer ─────────────────────────────────────────
# 用反射读 @UnstableApi 注解，也按类名做 ServiceLoader。
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Kotlin 协程 ────────────────────────────────────────────────
# kotlinx.coroutines 用反射构造 ContinuationImpl。
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.coroutines.Continuation { *; }

# ── 保留行号，方便线上崩溃日志能定位到源码 ─────────────────────
# 官方推荐写法：混淆类名但保留源码行号映射表
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile