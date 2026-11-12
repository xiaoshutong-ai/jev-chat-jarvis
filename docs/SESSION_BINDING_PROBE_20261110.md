# JEV 会话匹配安全原型（2026-11-10）

## 目标
在微信手机端不进行截屏、无障碍窗口树读取、Hook 或自动发送的前提下，将 Windows 端已经获得并明确授权的聊天上下文与 Android 输入框关联，优先保持自动生成建议的便捷度，同时杜绝“猜测联系人后直接填入”。

基线：Draft PR #1 的 `feature/guofeng-ui`，精确父提交 `3218b2b50acefeab5b95042741dbc9ea46636fc7`。本增量采用独立分支，**不修改原 PR #1 分支**。

## 本次已实现：SOURCE_ONLY
`SessionBindingResolver` 为纯 Kotlin 判定器，不访问微信运行时：

- 手机点击微信通知只产生 **suggestion（建议目标）**，绝不直接授权自动填入；
- 只接受通知移除原因 `REASON_CLICK`，拒绝手动清除/应用自动取消；
- 点击时间至输入框聚焦最多 15 秒、候选消息时间最多 120 秒；
- Android profile 必须精确一致，微信 A/B（包括不同 Android user）不得混用；
- 消息指纹匹配到**两个会话**即返回 Ambiguous，不按昵称或最近活跃度选一个；
- 只有用户确认某个稳定 session ID 后，才能把绑定限定到当前 editor epoch；
- 后续填入权限检查绑定的 profile、session ID、editor epoch、60 秒有效期；
- 该组件不会导出正文、密钥、账号，亦没有自动发送逻辑。

本轮已在独立 Kotlin/JVM 环境编译此源文件，并运行 23/23 条断言；另有 7 项 JUnit 兼容定向场景测试通过。**未执行 Android Gradle、CI、真机或微信集成验收。**

## 本次新增 Android 事件采集源（SOURCE_ONLY）
- 在原 `WeChatNotificationListenerService` 上接线：监听标准 `onNotificationRemoved(..., reason)`，仅 `REASON_CLICK` 留下一个短期提示。
- `NotificationClickTracker` 在内存中关联 Android 通知 key、profile 和消息摘要的 SHA-256 指纹；其他原因移除会被丢弃；不输出日志、原文或执行微信 UI 操作。
- 该提示**只允许生成建议，不能把点击事件当作当前聊天的身份证明**；下一次在微信内手动换聊天后仍需再次确认。
- 已在隔离 JVM 运行 `NotificationClickTracker` 的 11/11 条定向断言；应用级编译和真实通知事件仍 NOT_RUN。

## 下一项接线（无需新增风控权限）
1. 对已接入的通知点击事件，在 vivo 微信 A/B 上验证 `REASON_CLICK` 是否真实产生，并只在需要时补充 `shortcutId` / `locusId` 存在性诊断。
2. 从现有 Windows 侧使用者已授权的数据入口导入结构化 `accountScope/sessionId/messageId/timestamp/direction/content`。历史与实时事件按稳定消息 ID 去重；不把微信解密/注入组件打包到 JEV；许可与数据边界单独评估。
3. 在 JEV IME `onStartInput` 获取应用 package 和 Android profile，并为每次聚焦分配新的 editor epoch；随后按纯判定器建议候选。**不可将输入框 ID、同名联系人或时间邻近视为可靠身份确认。**
4. 候选 UI 显示目标联系人与来源；用户在不确定时点一次确认。候选绑定/失效后才能逐步接到写入链，不改变“用户手动发送”的既有约束。

## 最小必要真机验证
使用已存在的 Android 16 vivo / 微信 A、微信 B，比较：通知点击、通知清除、前台聊天、从微信内手动切换已读会话。只记录匿名状态与布尔值，不截图、不查询微信内部资源。必须用这些结果决定是否启用自动预选；无法确认时保持未绑定。

**已识别缺口（蓝军）**
- `EditorInfo` 不是通用微信会话 ID 接口；只有 package、输入类型等信息是标准契约。
- `REASON_CLICK` 是系统级事实，但微信、vivo/分身场景是否上报为同一事件尚未实测；缺失时不能冒充成功。
- 原 PR #1 的 `ReplyHandoff`/IME 候选当前只绑定文本，尚未接入 sessionId 校验；本原型**未修改该运行逻辑，不能据此宣称已修复跨聊天误填**。
- 电脑端微信需要在线，且非官方本地数据库方案带有版本、安全和许可证风险，不作为默认发布依赖。

BLUE_STATUS: BLUE_FINDINGS（关键风险是错会话填入、采集来源信任边界与未实测的 OS 提示语义；此处只完成候选判定的可逆源代码原型）。

## 2026-11-10 5070TI-JAY 本机验证记录（RDC 498006168@qq.com）

- 设备 `5070TI-JAY` 在线；PowerShell 7.6.6；JDK 17.0.20；Android 36 SDK + Build Tools 35 已缓存，ADB 37.0.1。
- 已在 `D:\\GitHubWork\\jev-session-binding-pr21-20261110` 创建独立 detached worktree，检出 `80057877525e5c3a1a9735796ce9dbb1455e9ded`，原 `D:\\GitHubWork\\jev-chat-jarvis-boundary` 的 `ops/canonical-public-runtime-fragments` 工作树未改动。
- 使用本机缓存 Kotlin compiler embeddable **2.1.20** / JDK 17 / Android 36 `android.jar`，对 PR #21 的 `SessionBindingResolver.kt`、`NotificationClickTracker.kt`、`WeChatNotificationListenerService.kt` 和两份 JUnit 测试做离线定向编译；仅 `Prefs` / `KeepAliveService` 用临时桩代码（存于仓库外 `D:\\DevCaches\\jev-session-binding-check-20261110`）。结果 **COMPILE_EXIT=0**。
- 使用 JUnit **4.13.2** + Hamcrest 1.3 实际运行两份测试：**OK (12 tests)，JUNIT_EXIT=0**。
- 尝试本机 Gradle 8.13 + `--offline :app:compileDebugKotlin :app:testDebugUnitTest`，**配置阶段失败**：Windows 默认 Gradle 缓存没有工程固定的 AGP 8.10.1 和 Kotlin Gradle Plugin 1.9.24。此失败不能计为源码测试失败，亦不能计为正式 BUILD_PASS。
- GitHub 专用 self-hosted runner `JAY-WSL2-JEV-CHAT-JARVIS`：Online、not busy；本轮没有触发 GitHub-hosted workflow。当前 workflow 的 `runs-on` 为 `ubuntu-latest`，不应未经批准直接运行它。
- vivo `V2366HA` 经 ADB 识别为 Android 16/API 36；默认输入法仍为搜狗，未变更手机权限、未安装/启动新 APK、未触发任何真实微信交互。
- 验收层级：`SOURCE_COMPILE_PASS (with stubs / Kotlin 2.1.20)`、`TARGETED_JUNIT_PASS 12/12`；`PINNED_ANDROID_BUILD=NOT_RUN`、`DEVICE_APP_INTEGRATION=NOT_RUN`、`RELEASE=NOT_RUN`。


## 2026-11-10 后续源码推进与定向验收

本轮继续使用唯一仓库 `xiaoshutong-ai/jev-chat-jarvis`，继续沿本 Draft PR #21 的独立分支推进，未修改原 PR #1、`main` 或原 JEV boundary 工作树。

### 已提交的运行源码

- `DesktopMessageIndex.kt`：严格按 Android profile、桌面 account ref、稳定 session/message ID 归类并去重；每会话最多 40 条、最多 50 会话，内存保存。
- `DesktopBridgeClient.kt`：只允许 HTTPS 或本机 USB/ADB reverse 可用的 loopback HTTP；Bearer Token 必须至少 32 字符，拒绝重定向、账户不匹配、格式错误、未来时间与超大响应；检测消息缺口时停止推进。
- `DesktopSyncPipeline.kt`：接入有效数据后合并上下文并建立进程内配对状态，只有用户明确开启自动分析时才调用外部注入的生成器；停止时仅清除对应 profile/account。
- `DesktopAnalysisCoordinator.kt`：仅对最近收到且在数据索引中存在的消息生成候选，同一消息不重复触发模型，未授权时调用计数为零。
- `DesktopReplyInbox.kt`：每个联系人有单独的、最长 10 分钟有效的候选池，不按“最近聊天”静默替换目标。
- `ZhiyanInputMethodService.kt`：微信内禁止原有 armed reply 静默自动填入；已配对且有消息/回复候选时按联系人展示，**用户点击明确标名候选**才经 `SessionBindingResolver.confirm/canInsert` 写入；其他 App 原有明确一键填入路径不受影响。旧 `commitText` false 返回值已不再误报成功。
- `NotificationClickTracker.kt`：只在内存保留点击线索，文字指纹改为进程随机 HMAC-SHA256，避免对简短聊天的固定 SHA-256 字典猜测。
- `desktop_bridge/relay.py`：Python 3 标准库的 loopback-only HTTP 桥；固定 Bearer 鉴权、游标/代际与缺口标记，默认只读合成 JSON。可选 `--weflow-url` 必须显式开通真实会话白名单，不包含任何微信客户端逆向、密钥提取或自动发送。

### 实际已运行证据

- 5070TI-JAY 本机缓存 Kotlin compiler **2.1.20** + JDK17 + Android SDK36 + JUnit4.13.2；受影响模块（含 IME、通知回调、HTTP 解析器、Pipeline）的离线桩编译 `COMPILE_EXIT=0`、**33/33 JUnit PASS**，源码 HEAD `69271250693fbde85678f2432f1099cd1b445989`；后续仅变更桌面 Python 源及测试、文档，未重做无关 Kotlin 检查。
- Windows Python 3.12.10：**8/8 unittest PASS**，HEAD `e497abeb9fc2a83fd95a5a9450a4b944dfa09f6c`。
- 真实 Windows loopback HTTP 合成数据验证：`/health` 返回 ok、无 Token 为 401、有效 Bearer 返回两条不同会话的合成消息；`DESKTOP_LOOPBACK_E2E=PASS`，测试服务已停止，未处理个人微信消息。
- 原 JEV worktree `D:\\GitHubWork\\jev-chat-jarvis-boundary` 与本隔离 worktree `D:\\GitHubWork\\jev-session-binding-pr21-20261110` 均无未提交修改。
- 5070TI 的官方 Windows 微信进程存在；当前未发现本机 WeFlow API (5031) 或已安装 WeFlow 目录，未授权安装数据库解密组件。
- vivo `V2366HA` / Android 16 已经通过无线 ADB 恢复连接，尚未更改其输入法、微信设置或安装 JEV APK。

### 尚未完成的真正运行时步骤

1. 用户界面里的**显式配对授权与 Token 的 AndroidKeyStore 加密存储**，以及前台服务中单实例定时轮询 `DesktopSyncPipeline`；目前类已实现、尚未在 App 生命周期启用。
2. 在 JEV 真实分析引擎调用中传入正确的 profile/session 和联系人档案；真实消息调用模型必须先获用户单独允许，默认关闭。
3. 5070TI/WSL 现有缓存未命中本工程固定的 AGP 8.10.1、Kotlin Gradle Plugin 1.9.24、Gradle wrapper 8.11.1，完整 Android build 在配置期被离线缓存缺失阻塞，**不能声称 APK 编译通过**。
4. 微信 A/B 的 `NotificationListenerService.REASON_CLICK` 真实返回行为、IME 切换联系人 epoch 与错填保护，必须在新 APK 获得可验证签名后做一次真机 E2E。
5. 个人微信完整历史数据源可能依赖非官方解密组件。当前仅实现可替换、只读、显式白名单的桥接契约，真实数据授权和第三方许可未扩展；JEV 不应自动安装或解密微信数据库。

**验收状态**：`SOURCE_COMPILE_WITH_STUBS=PASS`，`TARGETED_JUNIT=PASS_33_33`，`PYTHON_TESTS=PASS_8_8`，`DESKTOP_LOOPBACK_SYNTHETIC=PASS`，`PINNED_APK_BUILD=NOT_PASS`，`WECHAT_A_B_DEVICE_E2E=NOT_RUN`，`RELEASE=NOT_RUN`。

**BLUE_STATUS: BLUE_FINDINGS**。联系人切换时若微信没有公开可靠的当前 session ID 信号，系统不能推断真实当前聊天；候选标签与用户明确点击仍是必要的最后一道确认。

## 2026-11-10 Android 全模块构建与安装边界

**源代码 HEAD：** `5b8f78594cd0d49720d7f662071b2460f09e56f5`，对应 Draft PR #21。此 HEAD 在 Windows 5070TI-JAY 独立工作树中完成以下验证，原 PR #1 和原 boundary 工作树未改动。

- **实际源代码修复**：IME 点击候选时重新验证桌面账号及候选版本/时效，拒绝配对切换后遗留的按钮；`DesktopPairingState` 的所有权与轮询器实例绑定，旧轮询器清理不再覆盖同账号新实例；前台服务停止时由独立 cleanup worker 执行清理。
- **Kotlin/JVM 定向**：本机 Kotlin 2.1.20、JDK17、Android 36 SDK；编译受影响模块和 NotificationListener、IME，JUnit `35/35 PASS`（外围依赖使用仓库外临时桩），HEAD 同上。
- **前台服务源码定向编译**：真实 `KeepAliveService.kt` 与全部会话模块通过 Kotlin/Android SDK 36 编译，外围 UI/KB/网络 client 使用仓库外临时桩：`KEEP_ALIVE_COMPILE_EXIT=0`。
- **Gradle Android 全模块源码与单测**：5070TI 已缓存 Gradle **8.13**（与仓库 wrapper 固定的 8.11.1 不同），自动使用工程固定 AGP **8.10.1**、Kotlin Gradle Plugin **1.9.24** 与本机 Android SDK；`:app:compileDebugKotlin :app:testDebugUnitTest` **BUILD SUCCESSFUL**；读取 XML 测试报告 **65 tests / 0 failures / 0 errors**。
- **完整 APK**：相同 HEAD 执行 `:app:assembleDebug --offline` **BUILD SUCCESSFUL**；`app/build/outputs/apk/debug/app-debug.apk`，28,512,185 bytes，SHA-256 `7B128E47A39BC3ED3E1E729A899811D3D75E8C59B005C13245668321CDE46534`。包名 `com.jev.probe.guofeng`、versionCode 4、versionName `1.3-guofeng-debug`、target/compileSdk 36。
- **签名**：本机 `apksigner verify --verbose --print-certs` 成功，APK Signature Scheme v2、1 个签名者，证书 SHA-256 `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f`。此为本机 **debug 签名**，不属于可以保证覆盖升级的正式签名，不用于公开发布。
- **静态安全回读**：无 SelectToSpeak 伪装类、微信未列入 Accessibility 订阅、`isAccessibilityTool=false`、本 App 自有 AccessibilityService、Android Network Security 的 base cleartext=false、loopback 例外明确。6/6 检查均为 true。
- **桌面 Python**：同项目 Windows/WSL2 自带 Python 环境的 `desktop_bridge/tests` **8/8 PASS**，且 loopback HTTP 合成数据实测 `DESKTOP_LOOPBACK_E2E=PASS`（无 Token 401、合法 Token 回两条不同会话；测试服务已停止）。
- **模拟器适配边界**：已有 emulator-5554 是 x86_64/API36，正式产品 APK 仅 arm64-v8a 原生库，安装返回 `INSTALL_FAILED_NO_MATCHING_ABIS`；不表示源码或包损坏，不为可选模拟器验证额外下载 AVD/系统镜像。
- **真机**：vivo V2366HA 当前未通过 ADB 在线（`adb mdns services` 也未发现设备），因此 **DEVICE_PASS / WECHAT_A_B_E2E = NOT_RUN**。未在手机安装本 APK、开启任何新权限、采集真实聊天或发送微信消息。

**剩余业务验收**：Windows 的结构化真实消息来源目前未启用（WeFlow/其他第三方解密组件未安装/未授权）；Android 与 Windows 的公开网络安全配对尚未做正式运营配置；手机当前联系人无官方确定性 ID，因此 IME 仍采用显式标签确认，不能宣称零交互全自动。PR #21 继续 Draft，不合入、不公开发布。


## 2026-11-10 x86_64 现有模拟器验收

代码 HEAD `0880a249707d0a3fdfdebd62078f8e639839788b`。本阶段只调整 `app/build.gradle.kts` 的 ABI 打包选择：默认 Debug 和 Release 仍为 `arm64-v8a`；仅执行 `-PjevEmulatorAbi=x86_64` 时，Debug 改为 `x86_64`。该选项不修改微信适配/权限/输入法运行时逻辑，也未新增 AVD 镜像或 SDK 下载。

- 测试环境：5070TI-JAY、PowerShell 7.6.6、JDK17、已缓存 Gradle 8.13 / AGP 8.10.1 / Kotlin Gradle Plugin 1.9.24 / Android 36 SDK。模拟器 `emulator-5554`：Android 16/API36、`ro.product.cpu.abilist=x86_64`。
- **x86_64 Debug 构建 PASS**：`:app:assembleDebug --offline -PjevEmulatorAbi=x86_64`，构建结果 `BUILD SUCCESSFUL`，aapt `native-code: 'x86_64'`；APK 29,073,766 bytes，SHA-256 `71858FA16ACD1A3425AB68601AD45DFC3CB8F54744C549D1ECFA4E8E28B0BA3E`。保留测试包 `D:\\DevCaches\\jev-session-binding-check-20261110\\jev-pr21-emulator-x86_64-0880a.apk`。
- **安装 PASS**：`adb -s emulator-5554 install -r` 返回 `Success`；`pm path com.jev.probe.guofeng` 返回实际安装的 `base.apk`。
- **首页渲染有限 PASS**：首次 `am start -W` 约 78 秒后返回 `LaunchState: UNKNOWN`、timeout；Android event log 明确记录 JEV `failed to complete startup` ANR。仅 force-stop **JEV 自身**后再启动，`MainActivity` 进入 Resumed，界面树读取到 52 个本 App 节点、24 个文字节点（“小书童·知言”等），此处只说明**重新启动后界面实际渲染**，不将首次冷启动评价为 PASS。模拟器同时间另有其他应用和系统服务 ANR；根因归属未确认，非源码回归的证据亦不足。
- **IME 系统注册与选择 PASS**：系统 `ime list -a` 列出 `com.jev.probe.guofeng/com.jev.probe.ime.ZhiyanInputMethodService`；通过 emulator shell `ime enable` 和 `ime set` 成功切换为 JEV，随后恢复原来的 `com.android.inputmethod.latin/.LatinIME` 并禁用临时启用的 JEV 输入法。没有改变其他已安装应用的状态。
- **设置页交互未验收**：`SettingsActivity` 的 Manifest `android:exported=false`，ADB shell 直接启动时被操作系统按预期拒绝，不能误称 Activity 故障；尝试在首页通过滑动寻找设置入口时，后续 `uiautomator dump` 返回 `null root node`。未证明设置界面输入与保存 E2E。
- **输入内容及聊天会话端到端未验收**：模拟器没有执行微信 A/B 的真实会话读取、没有合成数据触发 IME 文字插入的现场 UI 回执、没有执行真实模型调用。此前 Python 8/8、Kotlin/JUnit35/35 与 Android JVM65/65 成功证据继续有效，不代替本层真机/模拟器完整 UI 测试。
- **默认手机 ABI 回归 PASS**：随后不带 `jevEmulatorAbi` 重新 `:app:assembleDebug --offline`，`BUILD SUCCESSFUL`，aapt `native-code: 'arm64-v8a'`，SHA-256 恰为修改前同代码版本 `7B128E47A39BC3ED3E1E729A899811D3D75E8C59B005C13245668321CDE46534`。故设备调试专用构建未改变默认 arm64 APK 字节。

### 验收判定

`EMULATOR_BUILD=PASS`，`EMULATOR_INSTALL=PASS`，`EMULATOR_WARM_UI=PASS_WITH_LIMITS`，`IME_REGISTRATION_SELECTION=PASS`，`EMULATOR_COLD_START=ANR_OBSERVED`，`SETTINGS_AND_CANDIDATE_INSERT_E2E=NOT_VERIFIED`，`WECHAT_A_B_DEVICE_E2E=NOT_RUN`，`RELEASE=NOT_RUN`。保持 PR #21 Draft，未合并、未改变实际微信账号或设备权限。
