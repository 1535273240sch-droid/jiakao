// 根工程:聚合 + 统一插件版本声明(apply false),避免各子模块以 plugins{} DSL 重复加载 KGP。
// 模块内用 `alias(libs.plugins.xxx)` 应用、不再带版本号。
plugins {
    // —— 拼装追加(2026-09-28):下面 4 行是 02 的 :app / :baselineprofile 需要的插件。
    // 必须在此统一声明 apply false:AGP 是同一个 artifact(com.android.tools.build:gradle)
    // 承载 com.android.application / com.android.library / com.android.test 三个插件 id,
    // KGP 同样承载 kotlin.android / kotlin.jvm / kotlin.compose / kotlin.serialization。
    // 只要有一个 id 先在别处上了 classpath,子模块再用带版本的 alias 请求同 artifact 的另一个
    // id 就会报 "The request for this plugin could not be satisfied because the plugin is
    // already on the classpath with an unknown version"。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
