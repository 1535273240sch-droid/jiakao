# 驾考通（自用版）

科目一 / 科目四 刷题 App。原生 Android（Kotlin + Jetpack Compose），英文名 `jiakao`。

由 `jiakao-kit` 的 5 个模块并发开发后按 `ASSEMBLY.md` 拼装而成：

| 模块 | 内容 |
|---|---|
| `:core:model` | 冻结合同：跨模块接口、`Question`/`MediaRef`/`PackRecord` 等数据模型（纯 Kotlin/JVM） |
| `:core:data` | Room 双库（题库 `quiz.db` + 用户 `user.db`）、仓库实现、考试引擎、FTS 搜索 |
| `:core:media` | 图片/动图/视频加载与缓存、预取、全屏查看器（Coil + Media3） |
| `:core:update` | 题库在线增量更新、断点续传、校验、事务导入、离线整包导入（WorkManager） |
| `:app` | 全部界面、动效、导航、设置 |
| `:baselineprofile` | Macrobenchmark 场景，用于生成 Baseline Profile |
| `tools/pipeline` + `server` | Python 题库流水线（导入 → 规范化 → 媒体转码 → 打包 → 发布）与静态托管脚本 |

文档在 [`docs/`](docs/)：各模块 `DONE.md`、`CONTRACT_ISSUES.md`，以及
`CONTRACT.md`（冻结合同）、`ASSEMBLY.md`（拼装手册）、`REVIEW.md`（拼装前审核报告）。

## 构建

需要 JDK 17 + Android SDK（platform 35；build-tools 34.0.0，`core/data` 显式 pin 了该版本）。

```bash
./gradlew :app:assembleDebug            # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest --continue  # 204 个单测
./gradlew :app:assembleRelease          # release（未配 keystore 时产出 unsigned APK）
```

自用安装：

```bash
keytool -genkey -v -keystore jiakao.jks -alias jiakao -keyalg RSA -keysize 2048 -validity 10000
./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```

## CI

[`.github/workflows/android.yml`](.github/workflows/android.yml) 在 push / PR 时跑单测、构建
debug 与 release APK，并把 APK 与测试报告作为 artifact 上传。

## 接入自己的题库

仓库**不含任何真题数据**。`tools/pipeline` 提供 CSV / JSON / SQLite 三种导入适配器与样例数据，
用法见 `tools/pipeline/README.md`。最短路径：

```bash
cd tools/pipeline
python tasks.py setup
python -m jiakao_pipeline import   --adapter csv --input ./raw/questions.csv --images ./raw/img --out ./work
python -m jiakao_pipeline normalize --in ./work --chapters chapters.yaml --state ./state
python -m jiakao_pipeline media     --in ./work --out ./dist --jobs 8
python -m jiakao_pipeline build     --in ./work --dist ./dist --state ./state
cat dist/report.md
```

然后用 `make serve`（或任意静态服务器）托管 `dist/`，在 App 的「设置 → 题库源地址」里填入地址
（模拟器访问宿主机用 `http://10.0.2.2:8000/`，**必须以 `/` 结尾**），再「检查更新」。

## 尚未完成

- **真机验证**：合同 §7 的 5 个性能指标（冷启动 ≤600ms、滑动 P95 ≤8/12ms、3000 题导入 ≤3s、
  APK ≤25MB、同时动图解码器 ≤2）都还没在设备上实测。APK 体积在独立开发期实测为 3.1MB。
- **Baseline Profile**：`:baselineprofile` 模块与插件已就位，但 `baseline-prof.txt` 需要连接设备执行
  `./gradlew :app:generateBaselineProfile` 才会生成。
- **UI 视觉**：计划改为羊皮卷质感主题（当前为 Material3 驾校蓝 + 交警橙），改版需注意纸纹不能
  用大图平铺，否则会破坏滑动帧率预算。
- 合同语义待裁决项见各模块 `docs/CONTRACT_ISSUES-*.md`。
