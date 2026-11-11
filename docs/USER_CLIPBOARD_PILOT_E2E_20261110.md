# JEV 用户主动复制「文件传输助手」消息：真实来源单次 E2E 验收

验收日期：2026-11-10，5070TI-JAY，Windows PowerShell 7。唯一仓库 `xiaoshutong-ai/jev-chat-jarvis`，Draft PR #21。本轮**代码精确 HEAD** `0f91fee738bba2489e92b2683f96d61225657052`。

## 用户授权、来源和范围

用户在独立测试微信账号「文件传输助手」中，主动右键复制了自己此前发送的精确测试文字 `JEV_TEST_01`，并在聊天中确认“重新复制好了”。本轮仅由当前 Administrator 进程通过公共 Windows 剪贴板**一次性读取用户选择的文字**；不读取或解密微信数据库，不附加调试器，不注入微信，不访问其他聊天或隐私数据，也不调用任何付费大模型。

此来源属于 **USER_SELECTED_TEXT（用户主动选择文字）**，不是已认证的微信 ChatLab/DB API。会话标签为 `manual-pilot-filehelper`，不能推断或宣称为官方微信会话 ID。

## 实际端到端回执

在 5070TI-JAY 执行已预先通过 PowerShell Parser 静态检查的本机验收脚本：

`D:\DevCaches\jev-session-binding-check-20261110\run-user-clipboard-one-shot.ps1`

脚本 SHA-256 `2B72CAB9C0D4590CCCDF70AE8A4E1FD027F274197605B8EF285A25766408DA6A`。脚本与当前源码 HEAD 绑定；检查已有的 x86_64 Debug APK 精确 SHA-256 `84C20927F40213DDEAC0B9281384C325E4D76285D310E2BDC89B27F29ADF4499`，无需重建或新安装 APK。

| 检查点 | 实际回执 | 判定 |
|---|---|---|
| Windows 剪贴板用户主动选取标记 | `USER_SELECTED_MARKER=MATCH` | PASS |
| Windows 本地 Relay 接收 | `WINDOWS_USER_CLIPBOARD_TO_RELAY=PASS` | PASS |
| 消息数量 | `RELAY_MESSAGE_COUNT=1` | PASS |
| 会话 | `RELAY_SESSION=manual-pilot-filehelper` | PASS |
| 方向 | `RELAY_DIRECTION=me` | PASS |
| Android 模拟器 Debug Activity 实际后台状态 | `ANDROID_SYNC_STATE=SYNC=PASS sessions=1 analyzed=0 messages=1` | PASS |
| Windows → Android 模拟器同步 | `USER_SELECTED_TEXT_TO_ANDROID_EMULATOR=PASS` | PASS |
| 本人发送不触发 AI 分析 | `OUTGOING_MARKER_AI_ANALYSIS=ZERO` | PASS |
| 付费模型/API | `MODEL_CALLS=ZERO_SYNTHETIC_ONLY` | PASS |
| 临时资源清理 | `RELAY_AND_ADB_TEST_RESOURCES_CLEANED=YES` | PASS |

任务在 5070TI 的已连接远程执行中，以 **exit code 0** 结束（实际进程 PID 71776）。使用本地监听 `127.0.0.1:18766` + 临时随机 Bearer Token，经已授权 ADB reverse 映射到 Android 模拟器；脚本不会记录未匹配剪贴板正文，完成后停止 Relay 和 JEV 测试进程、撤销临时 ADB 映射。

## 仍未完成的产品边界

- `ACTUAL_USER_SELECTED_CLIPBOARD_SOURCE=PASS`，不等于无需操作的微信聊天自动读取或历史同步。
- `WECHAT_DATABASE_DECRYPTION=NOT_RUN`，Windows 调试器取钥操作此前被平台明确拦截，未通过换通道或让用户代执行绕过。
- `REAL_WECHAT_A_B_ACTIVE_CHAT_ID=NOT_VERIFIED`。Android 模拟器的合成页面不是微信 A/B 真机，不证明标准 IME 能获得微信当前联系人 ID。
- `PRODUCTION_MODEL_GENERATION=NOT_RUN`、`AUTO_SEND=NOT_RUN`、`SIGNED_RELEASE=NOT_RUN`、`MERGE=NOT_RUN`。PR #21 继续 Draft。

**验收结论**：用户主动选择的真实来源文字已通过 JEV 本地 Relay 到 Android 模拟器完成单次 E2E，接收 1 条、仅 1 个测试会话、AI 请求 0。
