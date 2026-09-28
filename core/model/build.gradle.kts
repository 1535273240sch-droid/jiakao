// :core:model —— 纯 Kotlin/JVM 模块,内容 = 合同 §5 原样(Contract.kt),禁止修改。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Contract.kt 的接口大量使用 kotlinx.coroutines.flow,对下游(api)可见
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
