# JEV · Note9 微信复制消息后一键分析（2026-11-11）

工程目标：以用户指定的 **Redmi Note 9（M2007J22C，Android 12/API 31）** 为测试机，微信中由用户主动复制一条消息，轻点 JEV 悬浮球分析，展示候选，用户确认后填入聊天输入框；无需 Windows 中转、微信数据库密钥、截图识别 API 或自动发送。

本轮工作仍属于 `xiaoshutong-ai/jev-chat-jarvis` 的 Draft PR #21，基于独立 5070TI 本地工作树；原 PR #1 与其他项目不更改。

## 源码修改

- `ExplicitClipboardPolicy` 把用户主动复制的内容当作**单条选取消息**（保留换行），不合成历史记录，也不读取微信当前联系人；最多 4,000 字，空值、过长或 NUL 字符拒绝。
- `KeepAliveService` 不再借用最近微信通知的联系人、昵称、配置空间或关系；使用 `com.tencent.mm` 通用范围，并在提示中声明**联系人及消息方向未核验**。文字暂按“对方消息”分析，但用户必须检查回复内容才能手动填入。
- `OverlayTapPolicy`：安全模式下，无新通知时单击悬浮球直达前台剪贴板读取；若近期收到微信通知，同时允许用户复制，改为出现**“分析刚复制的消息 / 分析刚收到的微信通知”** 二选一界面；通知超过 20 秒就不能作为快捷来源。
- `ClipboardImportActivity`：仅在活动真正得到窗口焦点后由用户触发读取，不后台监控剪贴板；拒绝 JEV 自己复制的候选，避免重复分析；因 Android 12 的动态广播可被伪造，改为向 Manifest `exported=false` 的 `KeepAliveService` 发送**应用内部显式 Service intent**。
- `RecentExplicitCopy` 内存保留 10 秒去重窗口；同一消息重复点击恢复当前候选，避免无意重复支付模型费用。允许用户通过界面主动重新分析。微信外部剪贴板消息不保存到日志/GitHub。
- 原有 JEV 候选仅在用户主动选择后经 IME 填入文字，不代用户点击微信发送。

## 实际测试与身份

- PowerShell 7.6.6，JDK 17，离线 Gradle 8.13（复用 5070TI 现有 SDK/缓存）。
- 本轮定向 Kotlin/JVM 测试：`ExplicitClipboardPolicyTest 4/4`、`OverlayTapPolicyTest 6/6`、直接依赖 `ChatSnapshotTest 5/5`，合计 **15/15 PASS**，`:app:testDebugUnitTest BUILD SUCCESSFUL`。
- x86_64 Android16 模拟器：`-PjevEmulatorAbi=x86_64 :app:assembleDebug` 构建 PASS，安装成功且 APK SHA256 与设备安装包一致；此证据不等于 Note9 真机验收。
- 设备实查 Note9 连接在线、微信安装在 Android 主用户 0，分身用户 999 虽存在但没有安装微信；vivo 不是本轮测试目标。
- 默认 arm64 Debug APK 构建成功；Note9 通过 ADB 首次安装返回 **`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`**，因此不能宣称该 APK 已安装。必须在 Note9 设备 UI 中由本人明确允许/安装，不通过隐藏开关或其他接口绕过安装阻止。
- 正式模型调用、Note9 微信实测发送/填入、设备 E2E、签名发布及 PR 合并均未执行。既有真机个人聊天记录、密钥和截图未读取，未运行调试器取钥。

## 使用方式（待 Note9 安装后）

1. 在 Note9 上通过手机系统明确安装并开启 JEV 测试版；按页面正常授予“悬浮窗”权限并启动助手。暂不授权更多权限。
2. 若要实际生成候选，需在 JEV 设置中由本人配置可用的模型供应商与 API Key。只做 UI 验收时可先不配置，但不会产生真实候选。启动前不要把任何 Key 发送到聊天或 GitHub。
3. 打开 Note9 微信，长按一条**对方**消息，点击“复制”。
4. 轻点悬浮 JEV：如果没有近期微信通知，就直接分析刚复制的文字；如果同时有通知，请明确选择“分析刚复制的消息”。
5. 在面板中看到候选回复后，阅读确认是否适合当前聊天，再点击“填入”走系统 IME，或点击“复制”后手动粘贴。**不会自动发送**。

本方案是**用户主动复制的前台分析**；仍不具备微信自动历史同步、准确自动识别联系人身份、无操作抓取微信 A/B 消息的能力。此前调试器取钥流程已被安全边界拦截，不在本任务中恢复。

## 2026-11-11 Note9 APK 用户确认安装交接

- 当前源码精确 HEAD：`527176fa234d012897577a1d9497f6cd54641bd3`；`feature/session-binding-probe-20261110` / PR #21 Draft。原工作树 `D:\GitHubWork\jev-chat-jarvis-boundary` 与隔离工作树 `D:\GitHubWork\jev-session-binding-pr21-20261110` 均干净，无第二 writer。
- Note9 的 ADB 序列号 `8txwr49pz9tsr4ei`，型号 `M2007J22C`，Android 12 API 31 / MIUI V14，`arm64-v8a`；当前窗口为 `com.tencent.mm/com.tencent.mm.ui.LauncherUI`。微信安装在 `user 0`，`user 999` 的 XSpace 分身环境没有安装微信。
- 5070TI 复用 Gradle 8.13 缓存，对精确 HEAD 执行默认 `:app:assembleDebug --offline`：`BUILD SUCCESSFUL`，APK 仅包含 `arm64-v8a` native libraries，包名 `com.jev.probe.guofeng`，版本 `1.3-guofeng-debug`，APK **28,545,096 bytes**，SHA-256 **`7C72420E1F04E247FD0D7B4C8A983F65B782768BBC4EB3021B1B74BD61DAA112`**。本机 `apksigner verify` 显示签名证书 SHA-256 `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f`（Debug，仅试点）。
- 因前次对 Note9 的直接 ADB 安装得到 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，此次**未再尝试直接 ADB 安装、未调整 MIUI 安全设置**。仅把精确 APK 经 ADB **push**（文件传输，不等于安装）到手机公共下载目录：
  `/sdcard/Download/JEV_Note9_Pilot_20261111_7C72420E1F04.apk`。
  手机上的 `sha256sum` 与本机字节哈希完全一致，回执 `MOBILE_APK_HASH_MATCH=True`。
- 待用户在 Note9 的「文件管理 → 下载」中主动打开此 APK、核对「小书童·知言」Debug 试用版后**通过标准系统安装确认**。在用户给出安装回执前，`DEVICE_INSTALL_PASS=NOT_VERIFIED`、`DEVICE_WECHAT_COPY_ANALYSIS_E2E=NOT_RUN`；不能把文件传输或模拟器证据当成真机安装成功。
- 2026-11-11 当前 HEAD 的 GitHub combined statuses 为空、associated PR workflow runs 为空：`CI_PASS=NOT_RUN`。避免为这次可逆安装准备额外运行无关 CI；既有安全门禁照常保留。
- 后续真机验收仅用测试微信里的合成聊天内容，不读取真实联系人私聊。手机上任何悬浮窗、输入法切换、模型 API 密钥、安装确认均需经用户本人明确操作或既有有效授权；绝不自动发送微信消息。

## 2026-11-11 Note9 悬浮窗授权与微信前台实机核验

用户现场回执“悬浮窗已允许”；下列检查均在授权设备 **Redmi Note9 / M2007J22C / Android 12 API31** 实时经 ADB **只读元数据**完成。基准 JEV GitHub 源码 `527176fa234d012897577a1d9497f6cd54641bd3`，此后无功能源码变更（文档提交不影响 APK）。

- 安装：主用户 `user 0` 已有 `com.jev.probe.guofeng`，`versionCode=4`、`versionName=1.3-guofeng-debug`，Android PackageManager 安装来源为 `com.miui.packageinstaller`，安装包 SHA256 **`7C72420E1F04E247FD0D7B4C8A983F65B782768BBC4EB3021B1B74BD61DAA112`**，与事先传到 Download 目录、用户通过系统安装器确认的候选包字节一致。分身 `user 999` 未安装 JEV。
- 权限：通过手机系统原生 `Settings$OverlaySettingsActivity` 打开小书童·知言的“允许显示在其他应用的上层”，首次检查开关 `checked=false`。用户本人打开后，再次查询 `cmd appops get com.jev.probe.guofeng SYSTEM_ALERT_WINDOW` 返回 **`allow`**。没有用 ADB `appops set`、`pm grant` 或隐藏方式代替用户授权。Android 包权限记录的 `granted=false` 与 AppOps 特殊权限 `allow` 是不同表示，以实际窗口创建及 AppOps 为准。
- 前台与服务：重新通过系统 ActivityManager 打开 JEV `MainActivity` 获得 `Status: ok`，应用进程保持运行，`KeepAliveService` 已在系统服务清单中。
- 实际悬浮球：WindowManager 与 JEV 过滤后的安全日志共同确认 `overlay added type=2038`、`ty=APPLICATION_OVERLAY`、`mHasSurface=true`、`isReadyForDisplay()=true`、`isVisible=true`；窗口框 `[22,512][165,655]`，未输出截图或用户聊天正文。
- **微信前台交叉核验**：调用官方启动 Intent 仅把已安装的 `com.tencent.mm/.ui.LauncherUI` 切到前台（没有进入联系人或发消息），随后确认 Note9 同一 JEV 悬浮窗窗口继续存在：`mViewVisibility=0x0`、`mHasSurface=true`、`isVisible=true`。同时 JEV 进程及 `KeepAliveService` 持续运行，`SYSTEM_ALERT_WINDOW: allow`。这是 **WECHAT_FOREGROUND_OVERLAY=PASS**，不同于微信 A/B 联系人识别/回复填入的 E2E。
- 用户原搜狗输入法仍未更改；未读取剪贴板、微信消息、联系人或数据库，也未调用 AI 模型/发送消息。下一步需用户在 Note9 选择**无敏感内容的测试微信消息**，主动复制，再轻点 JEV 悬浮球；检查前台剪贴板 Activity 的流转与不含 API Key 时的明示反馈。模型候选及真机 IME 填入仍 **NOT_VERIFIED**。
- GitHub PR #21 保持 Draft，未合并/发布；Note9 `INSTALL=PASS`、`OVERLAY_USER_GRANT=PASS`、`OVERLAY_SYSTEM_WINDOW=PASS`、`WECHAT_FOREGROUND_OVERLAY=PASS`、`CLIPBOARD_REAL_E2E=NOT_RUN`。

以上为对用户已授权的 JEV 安装和标准 Android 悬浮窗功能的必要验收，不扩大数据收集或自动化权限。

## 2026-11-11 Note9 剪贴板黑框缺陷定向修复

用户在 Redmi Note9 点击 JEV 悬浮球后报告“屏幕上边缘闪现一个黑框就立即消失、没有反应”。实时验收回读：

- 目标包仍为 `com.jev.probe.guofeng`，旧已装 SHA-256 `7C72420E1F04E247FD0D7B4C8A983F65B782768BBC4EB3021B1B74BD61DAA112`；窗口管理器已确认悬浮窗 `type=2038`、`isVisible=true`，APP_OP `SYSTEM_ALERT_WINDOW: allow`。
- 系统日志 2026-11-11 09:51 连续显示 `ClipboardImportActivity` 被 `ActivityTaskManager` 成功启动、约 130-160ms 后展示、继而退出，没有 JEV 崩溃记录。Note9 AppOps `READ_CLIPBOARD: foreground` 有前台访问时间，但这不足以证明成功取得消息正文。
- 通过 Android Debug 的 `run-as` 在设备本地程序化解析 JEV 专属 `shared_prefs/jev_assistant.xml`，**仅打印配置项是否非空**，不输出/保存任何秘钥值：`judge_key NONEMPTY=False`，`judge_key_enc_v1 NONEMPTY=False`；即设备缺少模型 Key，不可能发生付费分析调用。
- 源码确认 `OverlayController.showError` 先前只执行 `setContent(...)`，没有像 `showLoading` 一样在折叠时调用 `toggle()`。因此 `KeepAliveService` 处理缺 Key 时虽然调用 `overlay.showError(...)`，却可能只更新隐藏面板，造成黑框闪过后无反馈。**此为已确认的源码 UI 缺陷**，与 Android 前台剪贴板能力不足应区分。

### 修复及工程验收

原 PR #21 单写工作树上仅修改三份源码，提交 `dce504ea6a2a6cc8e6b373d662bd41beba4555e7`：

1. `OverlayController.showError` 自动展开提示面板，剪贴板失败时提供“重新读取复制内容”按钮。
2. `ClipboardImportActivity` 显式设置透明窗口背景/关闭动画，保持用户主动点击后前台聚焦读取，不监控剪贴板；针对 `NO_FOCUS`、`NO_TEXT`、`OWN_REPLY`、`TOO_LONG`、`UNAVAILABLE` 通过同包**非导出 KeepAliveService**回传固定状态码，原文只走既有单次内部消息链；仅日志记录阶段与异常**类型**，绝不记录消息、联系人、Token。
3. `KeepAliveService` 映射已知失败状态为人能理解的中文提示；成功读入后的缺模型密钥状态明确记录 `MODEL_KEY_MISSING` 并可见显示；没有暗中启用模型或其他后台权限。

复用 5070TI Gradle 8.13 + SDK36 离线缓存：受影响 `ExplicitClipboardPolicyTest`、`OverlayTapPolicyTest` 定向测试 `BUILD SUCCESSFUL`；`:app:assembleDebug --offline` arm64 构建 `BUILD SUCCESSFUL`、APK 仅 `arm64-v8a`，28,915,726 bytes；APK SHA-256 `9D995DF007CFDD27C3085ADA26F2E1A97E6BEFECECAC471E6F354EB053AB0668`。签名证书 SHA-256 `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f`，与此前手机候选包的 Debug 签名相同。

将新 APK 作为**文件传输**到 Note9 下载目录 `/sdcard/Download/JEV_Note9_ClipboardFix_dce504e_9D995DF0.apk`，手机本地 SHA-256 已独立回读一致。**截至本回执，新包尚未通过手机系统安装确认，Note9 仍运行旧包；DEVICE_FIX_E2E=NOT_RUN。** 需本人用 MIUI 文件管理器打开该 APK，在原应用上同签名更新，且保留系统提示的必要同意。前次 `INSTALL_FAILED_USER_RESTRICTED` 不使用 ADB 绕过。

待安装后仅用 `JEV_NOTE9_TEST_01` 合成测试文本核对 AppOps、`clipboard_state=...` 无正文日志、错误面板可见和微信前台悬浮窗；不调用付费模型、不向微信发送、不更改搜狗输入法或 vivo。PR #21 仍 Draft，CI、真实候选及填入验收未宣称通过。

## 2026-11-11 10:14 后修复版实际安装与悬浮球复验

用户回复“Note9 已更新”；5070TI-JAY 的 Remote Desktop Commander 已在本人授权 ADB 设备 `8txwr49pz9tsr4ei` 实时回读并核实：

- JEV `com.jev.probe.guofeng` `1.3-guofeng-debug` 新版 APK 的**实际已安装二进制** SHA-256 恰为 `9D995DF007CFDD27C3085ADA26F2E1A97E6BEFECECAC471E6F354EB053AB0668`，与修复版包完全一致；MIUI `com.miui.packageinstaller` 记录 `lastUpdateTime=2026-11-11 10:09:33`，原安装 `firstInstallTime=2026-11-11 09:23:55`。**更新安装 PASS**，不再是只有文件传输。
- 手机在 JEV 的 `SettingsActivity` 时 AppOps `SYSTEM_ALERT_WINDOW: allow`，`READ_CLIPBOARD: foreground`；`KeepAliveService` 正常运行，默认输入法仍为 `com.sohu.inputmethod.sogou.xiaomi/.SogouIME`。
- 利用 Debug 包自身 `run-as` 只在设备内解析应用偏好，**仅返回非空布尔值**，不泄露内容或账号：`judge_key NONEMPTY=False`、`judge_key_enc_v1 NONEMPTY=False`、助手启用设置 `DEFAULT_TRUE`。因此 Note9 当前无法调用真实候选生成，缺 Key 后应出现可见面板提示。
- 使用系统 ActivityManager 将既有微信 `com.tencent.mm/.ui.LauncherUI` 返回前台，仅核对 UI 系统元数据：`WINDOW_FOUND=True`、`WINDOW_IS_APPLICATION_OVERLAY=True`、`WINDOW_HAS_SURFACE=True`、`WINDOW_IS_VISIBLE=True`、`JEV_KEEPALIVE_RUNNING=True`、`IME_UNCHANGED=True`，没有阅读任何实际聊天记录。
- 在首次读取新版日志时尚无 `clipboard_state=...` 匹配记录；这是 **CLIPBOARD_STATUS_E2E=NOT_RUN**，不能以权限、安装、前台显示 PASS 推断“黑框闪现”缺陷已真正消失。下一步仅需用户在 Note9 测试微信的文件传输助手里主动复制无敏感测试文字 `JEV_NOTE9_TEST_01` 后轻点悬浮球一次；执行者只回读 `clipboard_state=` 枚举（不回读原文）、窗口可见性以及异常类型。预期 `TEXT_HANDOFF`、`MODEL_KEY_MISSING`，界面应清楚显示缺少模型密钥。
- 源码功能修复 HEAD `dce504ea6a2a6cc8e6b373d662bd41beba4555e7`；此后仅文档变更，PR #21 仍 Draft；CI / 微信实际候选填入 / 发布继续 NOT_RUN。

本回执不扩大访问范围、不自动发送微信、不安装其他设备、不调用模型。
