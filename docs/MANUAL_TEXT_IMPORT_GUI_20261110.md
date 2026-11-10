# JEV 手动选取聊天文字：Windows 本地导入窗口（2026-11-10）

适用范围：独立测试微信账号，或其他用户主动提供的文字。无微信数据库密钥、OCR、Accessibility 读取、窗口注入、自动发送；不宣称识别了微信正在打开的真实联系人。工程唯一仓库：`xiaoshutong-ai/jev-chat-jarvis`，Draft PR #21。

## 为何需要这个入口

之前的 `JEV_TEST_01` 是**本人发送**消息，因此 Android 收到后 `analyzed=0` 是正确行为。实际回复场景必须先由用户确认这是“对方发来的消息”，以及它属于哪个自己标注的会话。

新的 `desktop_bridge/manual_import.py` + `desktop_bridge/relay.py --manual-gui` 以明确用户动作处理复制文字，而非后台读微信或监控全局剪贴板。

## 5070TI 实际使用

Windows PowerShell **7** 中，在已有 PR #21 隔离工作树上直接执行：

```powershell
& 'D:\GitHubWork\jev-session-binding-pr21-20261110\desktop_bridge\start_manual_gui.ps1' -AccountRef 'manual-pilot' -Port 18766
```

脚本用系统 CSPRNG（安全随机数生成器）创建 32 字节随机 Bearer Token，仅在当前进程环境变量保存；关闭 GUI 时恢复/清理该变量。服务只监听 `127.0.0.1:18766`（规避当前本机百度输入法占用的 8766），不开放公网端口。

**用户交互：** 在微信中主动复制所需文字 → 在 JEV 小窗口点击「1. 读取剪贴板」并审阅预览 → 填写会话标签（例如“学习群-张同学”，仅代表用户命名）→ 必须选「我发送的」或「对方发送的」→ 点击「2. 确认导入 JEV」。每段文本最多 4,000 字符，原文只进入内存队列；同一账号、标签、方向和正文在 30 秒内重复点击不会再次导入。用户也可直接在预览输入框粘贴并编辑；关闭窗口即停止本机 Relay 服务。

在 GUI 中点击「复制本机配对 Token（敏感）」才会把 Token 放到 Windows 剪贴板，用于合法配对的 Android JEV Debug 设置；不向日志或 GitHub 输出 Token。Android 配对时账号标识与本地 GUI 一致，例如 `manual-pilot`，本机调试地址为 `http://127.0.0.1:8766`（**Android 侧**），在经过授权的 USB/ADB 环境，需临时配置 `adb reverse tcp:8766 tcp:18766`；完成后删除该映射。没有 USB 反向转发或受支持的安全 HTTPS 通道，手机不能直接访问电脑的 loopback 地址。

Android「设置 → 电脑设备消息同步」已具有服务器地址、账号标识、配对 Token 和两个独立开关：「接收电脑端消息」「新消息自动生成候选」。第二个开关开启时，Android **可能调用用户当前已配置的模型并产生实际费用**，Windows Relay 本身不调用模型。建议首次测试只开启接收，核对会话后才开启分析。候选不会自动填入或发送微信，必须在 JEV IME 上手动确认；手动来源提示“联系人未核验”，**不参与通知自动匹配**。

## 安全边界与验证

| 检查 | 验收 |
|---|---|
| Windows GUI 代码为 Python 标准库 Tkinter，只有在按钮点击时读取公共剪贴板 | 代码已核，Tk 8.6 隐藏窗口布局测试 PASS |
| PowerShell 7 启动脚本 | 静态 Parser 0 错误，ASCII + CRLF PASS |
| 手动输入参数、用户声明的会话/方向、账户前缀、长度/控制字符、30 秒去重 | Python **25/25 PASS**，包括实际子进程本机 HTTP 请求 |
| 安卓手动会话不得通过通知指纹自动认领微信联系人 | 受影响 Kotlin 定向测试在 Gradle 8.13 offline **BUILD SUCCESSFUL** |
| 用户选择方向为“对方”，Android 明确启用测试分析后生成候选 | Windows synthetic stdin → 真实 localhost Relay → Android16 模拟器 `SYNC=PASS sessions=1 analyzed=1 messages=1`，PASS |
| 新版 Kotlin Debug APK 目标架构 | x86_64 参数 `-PjevEmulatorAbi=x86_64`，Debug `BUILD SUCCESSFUL`，安装模拟器成功，哈希核对一致 |
| 真实付费模型调用、微信自动发送和数据库读取 | 均未执行 |

新版 x86_64 APK SHA-256：`E71121A269F34D02F4F17863CBAD3A70A8E0C33C02102F84E2E0BD20D1512410`；仅安装到 `emulator-5554`，未升级 vivo 或发布正式签名包。默认 arm64 Debug APK 在同一源码 HEAD 编译成功，SHA-256 `5F54A70FE1733200B08086CC65CC109B312FF470FABA400D9261D3A291719D96`。

现有真实数据证据：用户在 Windows 测试微信中主动复制 `JEV_TEST_01`，Windows → Android 模拟器 `SYNC=PASS sessions=1 analyzed=0 messages=1`。新的**对方消息**流程目前使用合成文本端到端验证，尚未从用户真实“对方”微信消息做现场验收，不应混淆证据等级。

## 待完成与使用边界

Windows GUI 的 Tk 隐藏窗口创建与控件初始化已验证，**尚无真人可见界面的视觉验收**。Android 官方微信 A/B 的联系人身份、真机候选填入与分身用户配置未验证。PR #21 仍然 Draft；本次无自托管 CI 正式 Gate 终态和正式 Release 签名。

用户此前批准的取钥试点遇平台明确安全拦截，已记录于 `docs/WEFLOW_TEST_ACCOUNT_PILOT_20261110.md`。本文的手动导入完全不依赖该取钥行为；商业分发也不包含 WeFlow 的非商业授权代码。
