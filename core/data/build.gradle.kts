// :core:data —— 题库与用户数据层:Room 双库(quiz.db 可重建 / user.db 永不破坏性变更)+ 合同 §5 实现。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.me.jiakao.core.data"
    compileSdk = 35
    // 本环境镜像仅提供 build-tools 34.0.0(AGP 8.7.3 默认 35.0.0);见 out/CONTRACT_ISSUES.md #2
    buildToolsVersion = "34.0.0"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

ksp {
    // Room schema JSON 导出到版本库(合同验收:exportSchema=true)
    arg("room.schemaLocation", "$projectDir/src/main/schemas")
    arg("room.incremental", "true")
}

dependencies {
    api(project(":core:model"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
    // androidTest 运行所需的 androidx.test 基础设施(合同 §8 未包含,理由见 out/CONTRACT_ISSUES.md #3)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.withType<Test>().configureEach {
    // Robolectric 运行期需要下载 android-all 平台 jar;默认官方 Maven Central,
    // 受限网络可通过环境变量 ROBOLECTRIC_REPO_URL 指向镜像(如 https://maven.aliyun.com/repository/public)。
    System.getenv("ROBOLECTRIC_REPO_URL")?.let { systemProperty("robolectric.dependency.repo.url", it) }
    System.getenv("ROBOLECTRIC_REPO_ID")?.let { systemProperty("robolectric.dependency.repo.id", it) }
}
