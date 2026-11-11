# PR #1 self-hosted SOURCE_ONLY CI：现有 WSL2 Docker 离线构建

2026-11-11（北京时间）。唯一仓 `xiaoshutong-ai/jev-chat-jarvis`，父 Draft PR #1 目标 `feature/hardening-v1`，原始集成源码 HEAD `252fde275cf7adfb7523bd8ece787ba4f37d2be0`；父 PR 作者与原唯一 writer 均为 `EmilioJI`。本变更只调整原有 `.github/workflows/android-ui-pr-gate.yml`，并新增 `tools/ci/jev_selfhosted_offline.sh`，不新建 runner，不修改 JEV App 的业务源码。

## 运行原因和边界

- 旧工作流由父 PR 自动触发，但 `runs-on: ubuntu-latest`（GitHub-hosted）。唯一现有 JEV runner `JAY-WSL2-JEV-CHAT-JARVIS` 已经在线且空闲，标签 `self-hosted/Linux/X64/jev-chat-jarvis/android/wsl2`。
- 原 WSL2 `githubrunner` 用户拥有 Linux Android SDK 36、Build Tools 35.0.0、Linux Gradle 8.13 缓存，缺原生 Java；本机预存不可变 Docker image ID `sha256:c8fdb90ce00a66b4c0ba129d8dea684ba834fb93d5e53ae5557bd759e35a2bf1`（Temurin JDK 17）。Windows 原有 Gradle Maven artifact cache 包含 AGP 8.10.1 / Kotlin 1.9.24。
- 工作流 `runs-on` 改为唯一现有 JEV self-hosted；明确拒绝外部 Fork PR 在该 runner 执行，仅允许 **同仓 `feature/guofeng-ui` → `feature/hardening-v1`** 或维护者 `workflow_dispatch`。Workflow checkout 用 PR HEAD SHA 或手动 `github.sha`，脚本再次核实 `git rev-parse HEAD` 精确相同。
- 复用本地 Docker image `--pull never`，Gradle 容器 `--network none`；Android SDK 原生目录与 Windows Maven 依赖 cache **只读**挂载，WSL Gradle 8.13 分发及自有 cache 可写。项目用 `git archive` 输入容器临时文件系统，避免变更 runner checkout/本地工作树；不上传 APK、不触微信设备、不访问真实聊天数据或 Key。
- Android Gradle Plugin 8.10.1 默认需要 Maven Linux 版 aapt2 jar（未缓存）；本轮使用本机已安装的 Linux ELF `$HOME/.android/sdk/build-tools/35.0.0/aapt2`，以 `-Pandroid.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.0/aapt2` 显式覆盖。此为 AGP 标注 experimental 的已有选项，已通过一次完整本地容器构建验证；不自行下载对应 Maven aapt2。
- GitHub 安全架构检查沿用原有 WeChat Accessibility / IME fail-closed 静态守卫，并在原有 workflow 的 `:app:testDebugUnitTest :app:assembleDebug` 基础上继续运行全模块单测和 APK 编译；ABI arm64-v8a、包名、应用标签「小书童·知言」、target/compile 36 用 aapt 实际核验。
- **明确证据边界**：仓库 Gradle wrapper pin 为 **8.11.1**，本机/WSL 当前只缓存 Gradle **8.13**；所以新 job 的真实成功只能记 **自托管缓存 Gradle 8.13 的 SOURCE_ONLY CI**，不能冒称 `./gradlew` 固定版本 8.11.1 验证通过。正式冻结/发布之前仍须按既有发布条件处理对应版本工具链与商用第三方许可证/签名。无业务源变更不额外增加真机/Emulator 矩阵。

## 实测来源与发现

| 环节 | 真实回执 |
| --- | --- |
| 旧 WSL2 只用系统 PATH | `java: command not found`；Android 36/Build Tools 35 实际存在，不应猜成全套 SDK 缺失 |
| Docker 本地镜像 | Linux JDK 17.0.20 及 x86_64 ELF 原生 Android Build Tools 35，均无需网络下载 |
| 直接覆盖 Maven cache 到只读目录 | Gradle lock-file FileNotFound；改用官方 `GRADLE_RO_DEP_CACHE` 后可解析 AGP |
| 初次容器运行 | Java `user.home` 对未知 UID 为 `?`；通过显式 `JAVA_TOOL_OPTIONS=-Duser.home=/home/githubrunner` 与临时 `.android` 修复 |
| 没有 Maven Linux aapt2 8.10.1 jar | `:app:processDebugResources` offline 失败；改用已有 BuildTools 35 的 aapt2 ELF |
| 最终完整本地 Linux 离线验证 | 精确子 HEAD `da66219ecfbbb01d5e48fa2950d12a91098ebe39` 与父合并提交的 Git tree 均 `6f9c6333c2f790f6ba143e9ae3f07292dc3b0c7f`；`:app:testDebugUnitTest :app:assembleDebug --offline` **BUILD SUCCESSFUL in 2m40s**（33 task executed、9 FROM-CACHE）；Linux Debug APK SHA256 `c147e279da7575a3735e2320e47b4f65b2e206855b1a7af4043c79a3f531ae47`。此结果是本机容器执行，不是 GitHub Actions CI run。 |

## 最小验收

1. Workflow 文件 YAML 解析、fork guard、self-hosted labels、exact head checkout/校验、`--network none`、`--offline`、无自动 artifact upload，静态验证。
2. 原 runner 上使用 `workflow_dispatch` 对新父 HEAD 执行一次，确认 **GitHub run/job 精确 SHA、self-hosted runner 名、所有步骤与真实 exit status**；绝不把 skipped/cancelled 当 PASS。
3. 父 PR #1 独立 SOURCE_HIGH 总体审核 task `jev-pr1-integrated-252fde2-source-high-r1` 已发起，绑定原合并 HEAD；本新增 CI-only HEAD 只需做最小增量审查。不得凭子 PR #21 的部分 APPROVED 代替父 PR 总体批准。任何发布/合并 `main` 仍单独处理授权及产品 Gate。
