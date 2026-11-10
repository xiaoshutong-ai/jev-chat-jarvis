# PR #21 · 模拟器合成会话端到端验收（2026-11-10）

## 对象与边界

唯一仓库：`xiaoshutong-ai/jev-chat-jarvis`。本轮源码精确 HEAD `1237106f7454e45cabea5d06787030909df490a7`。本验收不使用个人微信账号、真实聊天消息、非官方数据库解密、截图读取微信、微信无障碍树或模型 API；全部文字为确定性的模拟消息及回复。

模拟器：5070TI-JAY 上现有 `emulator-5554`，Android 16 / API 36、x86_64。远程控制：已连接的 Remote Desktop Commander `498006168@qq.com`、PowerShell 7.6.6。

## Debug-only 工程接线

- `app/src/debug/AndroidManifest.xml` 注册 `SyntheticChatActivity`，只进入 Debug 变体；Activity 还拒绝非模拟器设备。
- `app/src/debug/java/com/jev/probe/debug/SyntheticChatActivity.kt` 提供两个人工输入框（甲/乙）、合成消息同步按钮、配对撤销按钮。Debug Activity 经 ADB 获取临时 Token，调用生产 `DesktopSyncPipeline`，生成器返回确定性文字 `SIM_REPLY_<sessionId>`，**不调用外部付费模型**。
- `ZhiyanInputMethodService.kt` 增加仅 `ApplicationInfo.FLAG_DEBUGGABLE`、应用自己 `packageName` 和精确 `EditorInfo.privateImeOptions=com.jev.probe.debug.SYNTHETIC_CHAT_V1` 同时成立才生效的测试会话入口。测试会话走与真实微信相同的候选确认、`SessionBindingResolver`、`InputConnection.commitText` 逻辑；正式微信身份匹配逻辑未改变。
- 原仓主工作树及 PR #1 分支没有修改。

## 构建与安装

- 本机已缓存 JDK 17、Gradle 8.13、工程 AGP 8.10.1/Kotlin Gradle Plugin 1.9.24、Android SDK 36；不新增模拟器或大系统镜像。
- `-PjevEmulatorAbi=x86_64 :app:assembleDebug --offline`：**BUILD SUCCESSFUL**。Debug APK SHA-256：`1A4881BADF54A7CD439FD98C97E175219FA2237BF4BF27CB09D3679CB12549EE`，保存为 `D:\\DevCaches\\jev-session-binding-check-20261110\\jev-pr21-synthetic-x86_64-1237106.apk`。
- `adb -s emulator-5554 install -r` 返回 **Success**。
- Android `:app:processReleaseManifest`：**BUILD SUCCESSFUL**；合并的 Release Manifest **不包含** `SyntheticChatActivity`，仍包含正式 `ZhiyanInputMethodService`，`RELEASE_TEST_ISOLATION=PASS`。
- 重新执行同一源码 HEAD 的默认 arm64 构建 + JVM 单元测试：`:app:testDebugUnitTest :app:assembleDebug --offline` **BUILD SUCCESSFUL**；aapt `native-code: arm64-v8a`，测试报告 **65 tests / 0 failures / 0 errors**；默认 Debug APK SHA-256：`93A512E459FF1685C5169DDB1A06A51E5C29CB4C084ED60861F9EAEB0221D210`。默认构建不混入 x86_64 库。

## 实际端到端回执（模拟器）

使用本机 `desktop_bridge/relay.py` 和临时生成的两条**当前时间戳** JSON 合成消息，通过 loopback-only `127.0.0.1:8766` + Bearer Token + `adb reverse tcp:8766 tcp:8766` 到达 Android。

| 场景 | 实际观察 | 判定 |
|---|---|---|
| 桌面 → Android 自动接收 | `SYNC=PASS sessions=2 analyzed=2 messages=2`，合成页显示 `FOCUS=A` | PASS |
| 输入法展示不同联系人 | 模拟器截图看见 `SIM_REPLY_sim-alpha`、`SIM_REPLY_sim-beta`，标注“电脑会话 · 模拟联系人甲/乙”；`dumpsys input_method` 为 `mInputShown=true` | PASS |
| 用户点击甲回复 | `EDITOR_0='SIM_REPLY_sim-alpha'`，乙输入框仍为空 | PASS |
| 切换至乙并点击乙回复 | `EDITOR_0='SIM_REPLY_sim-alpha'`、`EDITOR_1='SIM_REPLY_sim-beta'` | PASS |
| 主动撤销配对 | `SYNC=REVOKED`，再次点击旧候选位置后 `EDIT_COUNT=2 POST_REVOKE_FILLED=0` | PASS |
| 测试隔离与恢复 | 每次测试完成后默认输入法恢复 `com.android.inputmethod.latin/.LatinIME`、`show_ime_with_hard_keyboard` 还原、ADB reverse 移除、临时 relay 服务停止 | PASS |

测试期间模拟器原 `show_ime_with_hard_keyboard=0`，所以最初 UIAutomator 只返回 App 页面而非输入法层级；临时开启软键盘后系统确认 IME 窗口可见，通过模拟器截图直接核对候选，再以真实触摸坐标调用 `InputConnection.commitText`，以两个 `EditText` 的 UI 层级原文作完成判据。截图保留在 5070TI 的 `D:\\DevCaches\\jev-session-binding-check-20261110`，未上传用户聊天或截图文件到 GitHub。

## 不能替代的验收与已知边界

- **手机微信 A/B 真机 E2E = NOT_RUN**。本模拟器只证明 JEV 自有合成页面/输入法/网络链路；Android 标准输入法不保证可从微信获取当前会话 ID，微信内切换聊天仍需要清晰的联系人确认与最终真机验证。
- **真实 Windows 微信数据读取 = NOT_RUN**。桌面桥默认合成源，未安装 WeFlow/数据库解密组件或处理真实微信记录；不宣称微信平台许可、免风控或账号安全已获保证。
- **共享模拟器 JEV 主界面冷启动稳定性仍未收口**：上一轮观察过首次冷启动 ANR，本轮针对原目标使用 Debug 合成 Activity 完成正反例，未重复全量启动/其他项目测试。不把这两类验收混淆。
- **CI / 正式签名 / 发布 = NOT_RUN**。本次是 5070TI 本地通过的 Debug 验收，PR #21 保持 Draft，不合并原 PR #1 或 main。

结论：`SYNTHETIC_DESKTOP_TO_ANDROID=PASS`；`SYNTHETIC_2_CHAT_IME_INSERT=PASS`；`SYNTHETIC_REVOKE_DENY=PASS`；`RELEASE_DEBUG_ISOLATION=PASS`；`DEFAULT_ARM64_AND_65_TESTS=PASS`；`WECHAT_REAL_DEVICE=NOT_RUN`。

## 2026-11-10 增量：同步游标确认顺序修复

**代码 HEAD**：`0e88298ddb7aefbf1f246bc62a6debeeec81d848`（基于此前合成双会话输入法 E2E 通过的代码继续增量修改）。

### 根因与修复

- 已验证旧 `DesktopBridgeClient.pollOnce()` 在校验 HTTP 消息基本字段后立刻更新 `generation/cursor`；此时 `DesktopSyncPipeline` 尚未完成按账号/profile 的消息入库，因此某条非法消息即使导致下游拒绝，也可能被下一轮跳过。
- 现 `DesktopBridgeClient.pollOnce()` 仅取得并验证本批数据、暂存本次响应引用；`DesktopSyncPipeline` 先调用 `DesktopMessageIndex.acceptsTrustedBatch()` 校验全批次，完成目标 profile/account 的入库及用户已授权的候选处理后，才调用 `acknowledgeProcessed(batch)` 推进游标。
- 无效消息、跨账号、非法字段拒绝推进游标；重复已入库消息及空批次允许合法确认。旧响应不得在后一次响应读取之后被确认。无需新增微信权限、私有数据库/进程读取、付费模型调用或自动发送。

### 真实执行证据

- **5070TI Windows 本机定向 Gradle/JUnit**：本机缓存 Gradle 8.13、AGP 8.10.1、Kotlin Gradle Plugin 1.9.24、Android SDK 36；`:app:testDebugUnitTest` 定向执行 `DesktopSyncPipelineTest`（7 项）、`DesktopBridgeClientTest`（5 项）、`DesktopMessageIndexTest`（6 项），总计 **18/18 PASS，0 FAILURE**。
- 首次定向构建暴露新测试夹具使用 `jdk.httpserver` 模块但 Android Gradle 测试类路径没有该模块，已改用标准 `java.net.ServerSocket` 实现独立本地 HTTP 回放，**未修改工具链配置**。
- **同 HEAD x86_64 Debug APK**：`:app:assembleDebug --offline -PjevEmulatorAbi=x86_64` `BUILD SUCCESSFUL`；29,090,293 bytes，SHA-256 `01D29CCEC5396C2F13C039DB68FC882CCC0A7DA8CC116681F575995659EFCFC2`，aapt `native-code: x86_64`。
- Android 16 `emulator-5554` 已确认安装的是该精确 APK。第一次本轮复验的 `uiautomator dump` 返回 `null root node`，脚本安全清理；第二次模拟器冷启动慢，截图短时显示 `SYNC=CONNECTING`，同样未计 PASS。随后同代码/同安装包，保留合成数据源并在 UI 稳定后核验，实际回读 **`SYNC=PASS sessions=2 analyzed=2 messages=2`**，`CURSOR_ACK_SYNTHETIC_RUNTIME=PASS`。
- 该轮临时本地 Token、Python relay 进程和 ADB reverse 均已清理；模拟器默认 LatinIME 未改变，JEV 测试进程已停止。没有读写个人微信聊天。

### 验收分层

`CURSOR_ACK_UNIT=PASS_18_18`、`CURSOR_ACK_SYNTHETIC_EMULATOR=PASS`、`SYNTHETIC_IME_2_CHAT_E2E=PRIOR_HEAD_PASS_SOURCE_UNCHANGED`、`WECHAT_REAL_A_B=NOT_RUN`、`WINDOWS_REAL_CHAT_SOURCE=NOT_RUN`、`SELF_HOSTED_CI=NOT_RUN`、`PUBLIC_RELEASE=NOT_RUN`。本次不把模拟器合成数据当作个人微信/正式服务验收，也不把“可重试游标”误称为无限历史完整性。
