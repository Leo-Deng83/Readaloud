pluginManagement {
    repositories {
        // 国内镜像优先（实测比官方快），官方源兜底
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // sherpa-onnx 只发布在 JitPack 上（官方没有传到 Maven Central）
        // 用 content{} 限定只从这个仓库取 k2-fsa 的包，避免供应链污染
        maven {
            url = uri("https://jitpack.io")
            // 注意：sherpa-onnx 的原生库用的是 "com.github.k2-fsa.sherpa-onnx" 这个 group，
            // 和主库 "com.github.k2-fsa" 不是同一个，所以要用正则把两个都放行
            content { includeGroupByRegex("""com\.github\.k2-fsa.*""") }
        }
    }
}

rootProject.name = "Readaloud"
include(":app")
