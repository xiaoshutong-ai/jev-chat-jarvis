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
