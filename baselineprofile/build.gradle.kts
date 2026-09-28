// 02 · baselineprofile 模块:Macrobenchmark 生成 Baseline Profile。
// 用法(需连接设备/模拟器):./gradlew :app:generateBaselineProfile
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.me.jiakao.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// 生成的 profile 由插件默认写入 :app 的 src/main/generated,随 release 构建生效

dependencies {
    implementation(libs.benchmark.macro.junit4)
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        // 只启用 benchmark 变体
        variant.enable = variant.buildType == "benchmark"
    }
}
