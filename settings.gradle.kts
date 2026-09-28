// 驾考题库 App · 根工程
// 模块所有权见 CONTRACT.md §1;02/03/04/05 的模块目录尚不存在时用 exists() 守卫,
// 拼装阶段目录落齐后自动纳入构建。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jiakao"

include(":core:model")
include(":core:data")

listOf(
    ":app" to "app",
    ":baselineprofile" to "baselineprofile",
    ":core:media" to "core/media",
    ":core:update" to "core/update",
).forEach { (projectPath, dir) ->
    if (file(dir).exists()) include(projectPath)
}
