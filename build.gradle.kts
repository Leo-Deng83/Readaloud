// 顶层构建脚本：只声明插件版本，不写具体业务
plugins {
    id("com.android.application") version "9.4.1" apply false
    // 注意：AGP 9.0 起 Kotlin 已内置，不再需要 org.jetbrains.kotlin.android 插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
