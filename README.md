# 驾考通 (Jiakao) · 原生 Android 刷题学习终端

<div align="center">

![Platform](https://img.shields.io/badge/Platform-Android%20(SDK%2035)-3DDC84?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=flat-square&logo=kotlin)
![UI Toolkit](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose)
![Architecture](https://img.shields.io/badge/Architecture-Modular%20(5%20Cores)-orange?style=flat-square)
![Database](https://img.shields.io/badge/Room-Double%20DB%20%2B%20FTS-009688?style=flat-square&logo=sqlite)
![Tests](https://img.shields.io/badge/Unit%20Tests-204%20Passed-brightgreen?style=flat-square)

<p align="center">
  <b>面向机动车驾驶员科目一 / 科目四考试的现代化原生 Android 刷题应用</b><br>
  严格遵循合同驱动的模块化解耦架构 · 双 Room 本地高并发存储 · Coil + Media3 流畅媒体缓存 · 增量断点离线题库更新
</p>

</div>

---

## 🏗️ 模块化工程架构

项目由 `jiakao-kit` 规范的 5 个核心子模块并发解耦开发，并通过严格的冻结合同拼装集成：

| 模块名称 | 职责边界与技术栈 | 架构说明 |
|:---|:---|:---|
| **`:core:model`** | 冻结合同核心实体 | 跨模块接口抽象、`Question` / `MediaRef` / `PackRecord` 等不可变数据模型（纯 Kotlin/JVM） |
| **`:core:data`** | 业务与存储引擎 | **Room 双数据库架构**（题目静态库 `quiz.db` + 用户做题历史 `user.db`）、FTS 检索加速、全真模拟考试引擎 |
| **`:core:media`** | 媒体渲染与缓存管线 | 高清图/动态动图/考题视频异步解码加载与多级缓存、预取调度、全屏沉浸式预览（Coil + AndroidX Media3） |
| **`:core:update`** | 题库同步引擎 | 题库在线增量拉取、断点续传恢复、散列校验、原子事务导入、离线整包离线同步（基于 WorkManager） |
| **`:app`** | 交互展现与全局导航 | Jetpack Compose 全声明式 UI、转场微动效、自适应主题、考点导航与偏好设置 |
| **`:baselineprofile`** | 性能基准配置 | Macrobenchmark 深度基准测试场景，生成优化冷启动的 Baseline Profile |
| **`tools/pipeline`** | 数据清洗流水线 | Python 自动化题库工具链（CSV/JSON 导入 $\rightarrow$ 结构规范化 $\rightarrow$ 媒体自适应转码 $\rightarrow$ 签名打包发布） |

> 规范文档均归档于 [`docs/`](docs/)：包含冻结合同 `CONTRACT.md`、拼装执行手册 `ASSEMBLY.md` 及拼装前质量评审报告 `REVIEW.md`。

---

## 🛠️ 构建与环境要求

### 环境基线
- **JDK**：OpenJDK 17
- **Android SDK**：Platform API 35
- **Build Tools**：34.0.0

### 构建命令
```bash
# 1. 编译并输出 Debug APK (输出在 app/build/outputs/apk/debug/)
./gradlew :app:assembleDebug

# 2. 运行全模块自动化单元测试 (204 项单测)
./gradlew testDebugUnitTest --continue

# 3. 编译发布版 Release APK
./gradlew :app:assembleRelease
```

### 自签名本地安装测试
```bash
# 生成测试签名密钥库
keytool -genkey -v -keystore jiakao.jks -alias jiakao -keyalg RSA -keysize 2048 -validity 10000

# 签名并使用 adb 一键安装至连接的真机或模拟器
./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 🔄 自动化持续集成 (CI)

仓库已集成 GitHub Actions 工作流 [`.github/workflows/android.yml`](.github/workflows/android.yml)，在每次代码推送（Push）与合并请求（PR）时：
- 自动执行全套 204 项单元测试；
- 自动化构建 Debug 与 Release 双包；
- 自动归档测试报告与构建产物（Artifacts）。

---

## 📚 自定义私有题库流水线

> [!NOTE]
> 为遵循开源规范，本仓库**不随包内置任何商业驾校真题数据**。您可以使用内置的 `tools/pipeline` 快速生成并接入您自己的题库。

最短接入路径：
```bash
cd tools/pipeline

# 1. 初始化流水线环境
python tasks.py setup

# 2. 导入与规范化数据 (支持 CSV / JSON / SQLite)
python -m jiakao_pipeline import   --adapter csv --input ./raw/questions.csv --images ./raw/img --out ./work
python -m jiakao_pipeline normalize --in ./work --chapters chapters.yaml --state ./state

# 3. 多线程媒体转码压缩与打包发布
python -m jiakao_pipeline media     --in ./work --out ./dist --jobs 8
python -m jiakao_pipeline build     --in ./work --dist ./dist --state ./state
```

将产出的 `dist/` 目录托管在任意静态 HTTP 服务器后，在 App **「设置 → 题库源地址」** 填入服务器链接（模拟器请填写 `http://10.0.2.2:8000/`，**结尾必须带 `/`**），点击「检查更新」即可无缝同步全量题目。

---

## 📌 当前演进路线与待办清单

- [ ] **真机高负载指标压测**：对齐合同 §7 的 5 项性能红线（冷启动 $\le$ 600ms、列表滑动 P95 帧时 $\le$ 8ms、3000 道考题本地导入 $\le$ 3s、APK 包体 $\le$ 25MB）；
- [ ] **生成发布版 Baseline Profile**：通过实体机执行 `./gradlew :app:generateBaselineProfile` 生成预编译 DEX 映射以最大化启动速度；
- [ ] **羊皮卷沉浸视觉质感升级**：计划为 Material 3 主题引入复古羊皮纸微质感（严格遵守轻量级着色器实现，避免大图破坏滑动帧率预算）。
