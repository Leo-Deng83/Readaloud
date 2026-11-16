// ⚠️ 这里必须 import：Kotlin DSL 里 `java` 是 Gradle 的 Java 插件扩展（java.io.File 会被
//    解析成"java 扩展下的 io"而报 Unresolved reference），所以用 import 后的短名。
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    // AGP 9.0 起 Kotlin 内置，不需要再声明 org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── release 签名（PRD S7）─────────────────────────────────────────
// ⚠️⚠️ 铁律：**密码绝不写在这个文件里** —— 它会进 Git 仓库。
//    做法：只在这里读"凭据文件"，凭据文件本身放在仓库外（本机是 D:\dev\keys）。
//    查找顺序：
//      1. 环境变量 READALOUD_KEYSTORE_PROPS 指向的 properties 文件
//      2. 项目根目录的 keystore.properties（本地开发习惯，已加进 .gitignore）
//    两个都没有 → **不签名**（照常出包，只是包不能安装），并在构建日志里明确提示。
//    这样 CI/别人的机器上构建不会因为缺密钥而失败。
val signPropsFile: File? = run {
    val fromEnv = System.getenv("READALOUD_KEYSTORE_PROPS")
    val f = when {
        !fromEnv.isNullOrBlank() -> File(fromEnv)
        else -> rootProject.file("keystore.properties").takeIf { it.isFile }
    }
    f?.takeIf { it.isFile }
}
val signProps: Properties? = signPropsFile?.let { p ->
    Properties().apply { p.inputStream().use { load(it) } }
}
val haveReleaseKey = signProps?.getProperty("storeFile")?.let {
    File(it).isFile && !signProps.getProperty("storePassword").isNullOrBlank()
} == true

android {
    namespace = "top.readaloud.app"
    // 新版依赖库（androidx.core 1.19 / Compose 1.12）要求 API 37 以上
    // SDK 里装的目录名是 android-37.2，所以这里写 "37.2"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.readaloud.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "0.5.0"
    }

    signingConfigs {
        if (haveReleaseKey) {
            create("release") {
                storeFile = File(signProps!!.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
                // ⚠️ 三个签名方案都要开，别只靠 AGP 默认：
                //   v1（JAR/MANIFEST 签名）—— 老设备（Android 6/7）只认这个，漏了装不上
                //   v2（Android 7+ 主力方案）—— 防"改一个字节重新签名"的经典攻击
                //   v3（Android 9+）—— 换密钥/轮换签名用，官方应用市场都要求
                // 验证方式：`apksigner verify --verbose --print-certs 包名.apk`
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 有密钥才签名；没有就出"未签名包"（不能装，但至少能验证体积/R8 会不会崩）
            if (haveReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "[签名] 未找到 keystore 凭据，本次 release 包**未签名**（装不了）。" +
                        "设置环境变量 READALOUD_KEYSTORE_PROPS 指向 keystore.properties，" +
                        "或在项目根目录放一份 keystore.properties。"
                )
            }

            // ── R8：代码压缩 + 混淆（v0.3.3 启用）────────────────────
            //
            // 好处：Kotlin/Java 代码体积大约能降到 1/3~1/4，装机更快、内存更省。
            //
            // ⚠️ 为什么必须写 proguard-rules.pro：
            //   R8 会把类名和方法名**全部改名**，而本项目有两个"按名字找类"的依赖：
            //     · pdfbox  —— 反射加载字体/加密处理器，读 jar 里的资源
            //     · sherpa-onnx —— JNI 层按约定名字回调 Kotlin/Java
            //   改错了的共同特点是：**编译通过、App 能启动、只有真机用那个功能才崩**。
            //   所以规则要"宁多勿少"（多留一个类名只多几十 KB）。
            //
            // ⚠️ 开了之后必须做的真机回归（见 docs/发布签名说明.md）：
            //   抽文本 / 合成 / 跳章 / 点句高亮 / 睡眠定时 / 书签目录。
            //   **这不是可选的** —— 没有回归测试就开压缩，等于把风险留给用户。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ── S7：两个变体 ────────────────────────────────────────────
    //   full = 内置 Kokoro 模型（约 200MB，装完即用，不用管模型）
    //   lite = 不带模型（APK 只有十几 MB，用户自己导入一份）
    //
    // ⚠️ 两个变体的 applicationId 不同（lite 带 .lite 后缀），
    //    所以手机上可以**同时装、同时用**，互不干扰 ——
    //    对普通用户来说这一点比省 200MB 更重要。
    flavorDimensions += "tts"
    productFlavors {
        create("full") {
            dimension = "tts"
            // 不加后缀：保持 top.readaloud.app，装机脚本与调试入口都不用改
        }
        create("lite") {
            dimension = "tts"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
        }
    }

    // 模型放在 src/full/assets/kokoro/ 里（不是 src/main/assets）。
    //
    // ⚠️⚠️ 为什么不能放在 main：变体的 assets 是**叠加**而不是覆盖，
    //    放 main 的话两个变体都会被打进 200MB 的模型，Lite 就白做了。
    //    （AGP 的 flavor sourceSet 只能"加"目录，不能"减"掉 main 的。）
    //
    // ⚠️⚠️ **AGP 9 不再自动识别 `src/<变体名>/assets` 这个约定**（AGP 8 时代的行为）。
    //    必须在这里显式声明，否则模型**根本不会被打进 APK** ——
    //    症状特别隐蔽：编译成功、App 能启动，只有真机合成时报
    //    "未找到模型。这个安装包是 Lite 版（不含模型）"（v0.3.3 首次装 release 时踩到）。
    //    debug 版之前看不出来，是因为手机上早就有从电脑推过去的模型文件。
    sourceSets {
        getByName("full") {
            assets.srcDir("src/full/assets")
        }
        getByName("lite") {
            // Lite 不声明任何 assets：包里就是不含模型，用户自己导入
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // 只打包 64 位 ARM 一种 CPU 架构。
    // sherpa-onnx 自带 x86 / x86_64 / armeabi-v7a 的原生库，光这些就占一百多 MB，
    // 而我们的真机（以及现在绝大多数手机）都是 arm64-v8a。
    // （AGP 9 里 ndk.abiFilters 已经不能用了，改用 splits）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    // 模型放在 src/full/assets/ 里（不是 src/main/assets）——
    // ⚠️ 为什么不能放在 main：变体的 assets 是**叠加**而不是覆盖，
    //    放 main 的话两个变体都会被打进 200MB 的模型，Lite 就白做了。
    //    （AGP 的 flavor sourceSet 只能"加"目录，不能"减"掉 main 的。）
    // 变体本身的 assets 不用在这里配置，放对目录即可：
    //    full → src/full/assets 有模型 → 开箱即用
    //    lite → 没有这个目录 → 用户自己导入一份
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Media3（ExoPlayer + 后台播放 + 通知栏控制），S3 用
    // 版本查自 Google Maven 的 maven-metadata，别手写
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // 离线语音合成引擎（Apache-2.0）：Kokoro / MeloTTS / Piper 都靠它在手机上跑
    // ⚠️ 安卓必须用这个带点的 group：com.github.k2-fsa.sherpa-onnx（产物是 .aar）
    //    写成 com.github.k2-fsa 会拉到 JVM 版，还会顺带下载 Windows/Linux/Mac 的原生库
    // 版本号前面的 v 不能省，JitPack 要求与 GitHub tag 一致
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")

    // PDF 文本抽取（Apache-2.0，S6 用）。
    // ⚠️ 为什么必须用这个库：Android 自带的 PdfRenderer 只能把页面渲染成位图，
    //    **取不到文字**，而我们要的是能朗读的文本。
    // 它会顺带引入 BouncyCastle（处理加密/签名 PDF），许可证同样没问题。
    // 包名是 com.tom_roush.pdfbox（下划线），不是仓库地址里的连字符。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // 系统文件选择器（SAF）的目录遍历封装 —— Lite 版导入模型要用。
    // 用它而不是自己写 ContentResolver 查询：官方维护、代码量少不易出错。
    implementation("androidx.documentfile:documentfile:1.0.1")
}
