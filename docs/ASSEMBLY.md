# ASSEMBLY · 拼装与联调手册

## 0. 前置
5 个文件夹的 `out/DONE.md` 均已存在；`bash verify.sh` 通过。JDK 17+、Android SDK、Python 3.11+。

## 1. 一键拼装
```bash
bash assemble.sh ~/jiakao        # 建目录、按 01→05 顺序合并 out/、收集 DONE.md 到 docs/、git init
cd ~/jiakao
```
合并规则：01 先落地（独占根 Gradle 文件）；02–05 只合并各自目录；`_standalone/`、`DONE.md`、`CONTRACT_ISSUES.md`、`.gitkeep` 自动排除。

## 2. 最终目录
```
jiakao/
├─ settings.gradle.kts  build.gradle.kts  gradle/  gradle.properties     (01)
├─ core/model  core/data                                                  (01)
├─ core/media                                                             (03)
├─ core/update                                                            (04)
├─ app  baselineprofile                                                   (02)
├─ tools/pipeline  server                                                 (05)
└─ docs/  DONE-01.md … DONE-05.md  CONTRACT.md  CONTRACT_ISSUES-*.md
```

## 3. 处理 CONTRACT_ISSUES
逐个读 `docs/CONTRACT_ISSUES-*.md`，人工裁决后在**唯一位置**改（Kotlin 接口改 `core/model`，格式改 `tools/pipeline` 与 `core/update`），并同步更新 `docs/CONTRACT.md`。

## 4. 接线（Hilt）
- `:app` 依赖 `:core:model :core:data :core:media :core:update`；删除 `app/src/debug` 中所有 `Fake*` 绑定与 `QuizMedia` stub。
  （`assemble.sh` 现已自动排除 `app/src/debug/java/com/me/jiakao/fake/`，无需手工删；如需在拼装后的仓库里继续用假数据做 UI 开发，把该目录从 `_attic/` 或 `02-app-ui/out/` 拷回即可。）
- 确认存在且仅存在一份绑定：`QuizRepository/UserRepository/ExamService/QuestionStore`（01）、`MediaStore/MediaPrefetcher`（03）、`BankUpdater`（04）。
- `MainActivity`/`ViewModel` 均 `@AndroidEntryPoint/@HiltViewModel`；`UpdateWorker` 使用 `HiltWorkerFactory`，Application 实现 `Configuration.Provider`。
```bash
./gradlew clean :app:assembleDebug
```

## 5. 统一升级依赖
所有版本只在 `gradle/libs.versions.toml`。逐项升级到最新稳定版后重新 `./gradlew build`（AGP/Kotlin/KSP/Compose/Hilt 要成套升级）。

## 6. 端到端联调（核心链路）
```bash
# ① 生成并托管题库
cd tools/pipeline && python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt
make sample build && make serve &        # http://localhost:8000/
# ② 安装 App（模拟器）
cd ../.. && ./gradlew :app:installDebug
# ③ App：设置 → 题库源地址填 http://10.0.2.2:8000/ → 检查更新 → 更新
# ④ 模拟"发新版"：改一道题、删一道题、加一张动图 → make build → App 再次检查 → 应走增量
# ⑤ 离线包：adb push tools/pipeline/dist/bundle/bundle-vN.zip /sdcard/Download/ → 设置 → 导入离线包
```
### 联调验收表（全部打勾才算拼装完成）
- [ ] 首次全量更新成功：题数、章节、图片、动图均正常显示
- [ ] 发新版走**增量**（日志可见 `Plan=Deltas`），被删的题从题库消失，**错题本/收藏不丢**
- [ ] 更新途中杀进程 → 再次更新可续传，版本号未被推进的情况下题库仍是旧版且可用
- [ ] 媒体未就绪的题显示占位，下载完成后**自动刷新**
- [ ] 离线包导入成功，篡改包内 1 字节 → 明确报错且数据不变
- [ ] 备份导出 → 清数据 → 导入，学习进度恢复
- [ ] 模拟考试：科一 100 题/45 分钟、科四 50 题/30 分钟、90 分合格，时间到自动交卷

## 7. 性能验收（合同 §7）
| 指标 | 目标 | 实测 |
|---|---|---|
| 冷启动 | ≤ 600ms | |
| 滑动掉帧（P95） | ≤ 8/12 ms | |
| 3000 题导入 | ≤ 3s | |
| APK 体积 | ≤ 25MB | |
| 同时动图解码器 | ≤ 2 | |
命令见 `02-app-ui/COMMANDS.md`。不达标 → 先看 Baseline Profile 是否生成并随 release 打包。

## 8. 常见冲突与对策
| 现象 | 原因 | 处理 |
|---|---|---|
| `Duplicate class`/重复绑定 | Fake 实现未删 | 删除 `app/src/debug` 假绑定 |
| Hilt `MissingBinding` | 某模块未被 `:app` 依赖 | 检查 `app/build.gradle.kts` |
| `Unresolved reference: Contract` | `_standalone/model_stub` 被误合并 | 确认只保留 `core/model` |
| 媒体 404 | manifest `media_base` 或末尾 `/` | 题库源地址必须以 `/` 结尾 |
| 明文 HTTP 被拒 | release 变体禁止明文 | 联调用 debug；正式用 https |

## 9. 发布给自己用
```bash
./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```
自用请配置自己的 keystore（`keytool -genkey -v -keystore jiakao.jks -alias jiakao -keyalg RSA -keysize 2048 -validity 10000`），不要提交到仓库。
