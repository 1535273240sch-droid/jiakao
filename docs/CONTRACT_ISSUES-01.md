# CONTRACT_ISSUES · 01-app-core

按合同约定记录问题与偏离;均为**最小假设继续**,由人在拼装阶段统一裁决。

## #1 交付环境无法直连境外构建源(gradle.org / google.com / maven.org 全部不可达)

- **现象**:`services.gradle.org`、`dl.google.com`、`repo.maven.apache.org` 在本环境网络下均无响应;阿里云/腾讯云镜像可用。
- **已做的偏离**:
  1. `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指向腾讯云镜像(`https://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip`)。拼装后若网络正常,建议改回官方 `https://services.gradle.org/distributions/gradle-8.10.2-bin.zip`。
  2. 本机构建通过 `gradlew -I mirrors.init.gradle.kts`(init 脚本在 out/ 之外)在官方仓库前插入阿里云镜像,**仓库声明保持合同原样(google/mavenCentral/gradlePluginPortal),最终仓库不需要任何改动**。
  3. Robolectric 运行期的 android-all jar 下载:`core/data/build.gradle.kts` 支持环境变量 `ROBOLECTRIC_REPO_URL`/`ROBOLECTRIC_REPO_ID` 覆盖下载源,默认仍为官方 Maven Central(不设环境变量时行为不变)。
- **建议**:拼装阶段由人决定是否在仓库内固化镜像开关。

## #2 AGP 8.7.3 默认 build-tools 35.0.0 在可用镜像上不存在

- **现象**:腾讯云 AndroidSDK 镜像有 `build-tools_r34-windows.zip`(34.0.0),无 35 系列。
- **最小假设**:`core/data` 显式 `buildToolsVersion = "34.0.0"`。build-tools 不属于合同 §8 依赖清单,仅影响构建工具链,不影响产物兼容性(minSdk 28 / compileSdk 35 不变)。
- **建议**:拼装环境若能安装 build-tools 35.0.0,可删掉该行使用 AGP 默认值。

## #3 androidTest 需要 androidx.test 基础设施,合同 §8 未包含

- **现象**:TASK 要求 `core/data/src/androidTest` 性能基准;instrumented 测试必须依赖 `androidx.test:runner` + `androidx.test.ext:junit`(AndroidJUnitRunner/AndroidJUnit4),而 §8 版本目录没有这两个条目。
- **最小假设**:在 `core/data/build.gradle.kts` 中以字符串坐标引入 `androidx.test:runner:1.6.2`、`androidx.test.ext:junit:1.2.1`(仅 androidTest configuration)。未改动 `libs.versions.toml`(保持 §8 逐字一致)。
- **建议**:拼装时把这两个条目并入版本目录(例如 `androidxTestRunner`/`androidxTestExtJunit`),并同步给 02(其 baselineprofile/app 的 instrumented 测试同样需要)。

## #4 TASK 的 question 列清单缺 FTS4 内容列

- **现象**:TASK 规定 `question_fts(stem, options_text)` 且"同步维护 FTS";FTS4 外部内容表(`@Fts4(contentEntity)`)要求内容列存在于 question 表,但 TASK 的 question 列清单没有 `options_text`。
- **最小假设**:question 表增加 `options_text` 派生列(全部选项文本以空格拼接),Room 生成触发器在 INSERT/UPDATE/DELETE/REPLACE 时自动同步 FTS,无需手工维护("同步维护"由触发器保证);`replaceAll` 时仍按 TASK 要求显式清理 FTS 表。
- **建议**:由人确认此列是否接受;若否,需改用手工维护 FTS 的方案(在 applyPack 事务内自行 INSERT/DELETE question_fts)。

## #5(提示,非缺陷)合同 §5 `QuestionStore.applyPack` 的 records 为惰性序列

- **现象**:`Sequence<PackRecord>` 由 04(网络流)构造,01 内部在**单事务内**迭代消费;若 04 的序列在事务内做网络/慢 IO,会拉长事务持有时间。
- **建议**:04 侧应在调用前把 jsonl.gz 全量解码为内存/临时文件序列(3000 题量级可接受),不要在 applyPack 的迭代里做下载;任务书 §3 已约定"下载校验通过后导入",与该用法一致。
