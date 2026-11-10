# JEV Desktop Bridge · 只读消息桥

阶段：**SOURCE_ONLY / 测试数据**。此目录不是微信客户端、没有个人微信登录或解密能力，不包含微信数据库读取、进程注入或自动发送；只是把**已获用户授权、已由独立数据源提供**的结构化消息转换为 Android JEV 可用的事件流。

## 已实现

- 纯 Python 3.11+ 标准库服务，固定监听 `127.0.0.1:8766`，不对公网开放。
- `GET /health` 不含消息内容；`GET /v1/poll?cursor=N&generation=G&limit=100` 使用 `Authorization: Bearer <token>`。
- 会话以 `account_ref + session_id` 标识，消息携带 `message_id / display_name / direction / text / time_ms`，单批不超过 100 条。
- Ring buffer（内存环形队列）最多 500 条；游标与进程代际标识防止重启后错位；`gap=true` 表示存在消息缺口，Android 客户端会拒绝当成完整数据。
- 默认仅合成数据。可选读取已单独安装、已获权限的 **本机 WeFlow HTTP API**，只访问用户明确允许的会话 ID，默认不启用，不自动安装 WeFlow 或处理数据库密钥。
- Android 端 `DesktopBridgeClient` 仅允许 HTTPS 或 USB/ADB reverse 的 localhost HTTP，限制内容大小与超时，拒绝跳转、错误账号和无效字段；本次尚未加入正式 App 的配对设置界面与自动轮询后台服务。

## 合成数据自检（PowerShell 7）

```powershell
$env:JEV_RELAY_TOKEN = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
python desktop_bridge/relay.py --account-ref fixture-account --fixture desktop_bridge/fixtures/two_chats.json
```

在另一个 PowerShell 7 窗口中（使用同一测试 Token）：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8766/health'
Invoke-RestMethod 'http://127.0.0.1:8766/v1/poll?cursor=0&limit=10' -Headers @{ Authorization = "Bearer $env:JEV_RELAY_TOKEN" }
python -m unittest discover -s desktop_bridge/tests -p 'test_*.py' -v
```

可选的本机 USB 研发连接：`adb -s <device-serial> reverse tcp:8766 tcp:8766`，这不意味着 JEV 正式版本已支持配对。手机离开 USB 后该隧道自然失效。远程生产连接必须另行提供可信 TLS、密钥生命周期、配对撤销及明确的隐私授权。

## WeFlow ChatLab Pull 兼容适配（默认关闭）

2026-11-10 已对照 WeFlow 的公开 `docs/HTTP-API.md`，JEV 改用只读
`GET /api/v1/sessions/:id/messages`，不再用“最近 40 条”冒充增量监听：

- 仅访问命令行显式白名单中的会话 ID（最多 10 个）；`--allow-real-chats` 必须明确设置，实际使用前由用户确定数据范围与授权期限。
- 每会话使用公开契约的 `since`、`offset`、`limit`，分页期间用 `end=watermark` 固定上界，避免消息持续增加时导致游标分页漂移；不存在稳定 ID 的纯文本消息视为源错误，不自行合成会话身份。
- 读取 `ChatLab messages[].platformMessageId/sender/timestamp/type/content`，仅 `type=0` 纯文本进入 JEV；须提供 `--self-sender-id` 作为本人发送者标识，以免把自己发出的消息误当新收到的消息。
- **默认只回溯启动前 60 秒**；`--lookback-seconds` 允许 1–3600 秒。为隐私不持久化聊天全文、WeFlow token 或游标；若停机超过已许可回溯时间，历史缺口仍需单独授权补齐，不能声称全量或零遗漏。
- 只在整个 ChatLab 页通过检查并被 JEV 内存队列接受后确认上游 `nextSince/nextOffset`；异常则原页重试。Relay 消费端 500 条环形队列发生覆盖时返回 `gap=true`，要求使用者回补，不能静默当作完整历史。
- 手机 Relay API 返回体限制在约 180 KiB（Android 端上限 256 KiB），大量长消息自动分次返回；未读完时 `has_more=true`。
- 不调用 WeFlow POST、发送接口、朋友圈 API、Windows 微信进程/数据库或任何解密/注入逻辑；JEV 仅独立实现公开 HTTP 数据契约。

**一手资料**：[WeFlow HTTP API](https://github.com/hicccc77/WeFlow/blob/main/docs/HTTP-API.md)。
WeFlow 本体为 CC BY-NC-SA 4.0（非商业），其本地数据库连接还可能依赖额外非官方组件；**JEV 不打包、安装或重新授权 WeFlow**。真实用户数据接入之前仍需单独核查第三方许可证、采集范围、聊天联系人隐私及平台风险。

### 完全合成的上游验收

`desktop_bridge/tests/mock_chatlab_server.py` 是 Python 标准库测试服务，只响应 `sim-alpha`、`sim-beta` 两个虚构会话，不需要安装或登录微信。测试时由执行器生成随机临时 `WEFLOW_API_TOKEN` 与 `JEV_RELAY_TOKEN`，在各自进程环境中传入，不保存到源代码或日志。

先启动本机模拟上游 `python desktop_bridge/tests/mock_chatlab_server.py --port 5031`，再用独立进程启动 `desktop_bridge/relay.py --account-ref fixture-account --weflow-url http://127.0.0.1:5031 --allow-real-chats --chat-id sim-alpha --chat-id sim-beta --self-sender-id synthetic-self`，通过 `/v1/poll` 的 Bearer Token 验证两个独立会话。Android 模拟器使用已经授权的 `adb reverse tcp:8766 tcp:8766` 接入合成源。

真正的 WeFlow 数据接口不在该合成测试范围；没有经用户明确指定会话与数据处理权限前，不使用真实微信聊天记录替代模拟会话。

## 用户主动复制的单次文件传输助手试点（不使用数据库密钥）

此模式用于先核验已经发到**独立测试微信号**「文件传输助手」的文本 `JEV_TEST_01` 的 Windows → JEV 输入链路。它不是微信自动历史同步，也不能从微信界面或数据库自动读取。

1. 在 5070TI 上切到测试微信窗口，找到自己发给「文件传输助手」的 `JEV_TEST_01`，右键 **复制**。此时只是 Windows 公共剪贴板中由用户主动选择的一段内容；不要复制其他聊天。
2. 用户回复聊天窗口 **“已复制测试消息”** 后，执行者才从 Windows 剪贴板读取一次并验证文本**严格等于** `JEV_TEST_01`；不轮询、不后台监听、不写剪贴板正文文件。
3. JEV Relay 的 `--manual-stdin-test` 仅允许 `--account-ref fixture-account`，只在标准输入接收这段特定测试文字；消息标记为 `session_id=manual-pilot-filehelper`、`display_name=文件传输助手（手动选择）`、`direction=me`，绝不冒称官方微信 wxid/未读通知。由于是本人发送，JEV 的自动回复协调器**不得触发模型调用**。
4. 使用随机 Bearer Token、`127.0.0.1:18766`（与本机百度输入法占用的 8766 分开）；Android 现有调试模拟器若需接入，使用 `adb reverse tcp:8766 tcp:18766` 做临时授权映射，结束时清理。
5. 未取得调试取钥授权路径的执行回执：之前平台明确阻止一次性调试器取钥脚本，不能要求用户手动执行被拦截的命令，也不改用其他执行通道绕过。本路径只处理**用户主动提供的文字**。

作为独立 CLI 的示例，以下在 PowerShell 7 的项目根目录运行（需已显式复制测试标记）：

```powershell
$env:JEV_RELAY_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
Get-Clipboard -Raw | python desktop_bridge/relay.py --account-ref fixture-account --port 18766 --manual-stdin-test
```

该命令启动的是内存只读 HTTP 服务，用户或执行者可在验收后按 Ctrl+C 关闭。它不会自动向 Android 发送或调用模型，其他程序不能在缺少 Bearer Token 的情况下轮询。

**2026-11-10 的实测证据**：新增测试后 Python `17/17 PASS`；5070TI 在代码 HEAD `13db678430c8f4550909873d0fc2f82b6a2ad847` 的真实本机 HTTP 测试中，将预设 `JEV_TEST_01` 通过 stdin 输入新模式，验证 `HTTP_IMPORT_COUNT=1`、`HTTP_IMPORT_DIRECTION=me`、`HTTP_IMPORT_SESSION=manual-pilot-filehelper`、未认证 GET 返回 401，`MANUAL_BRIDGE_HTTP_E2E=PASS`。**这不是从用户微信实际剪贴板复制的回执**；后一环等待用户明确复制。

## 安全与测试边界

- 手机微信不请求无障碍树、截屏，也不修改微信程序。
- Android 接收后先验证消息所属账号与 profile；自动生成候选必须取得用户单独允许，候选点击确认后才允许 `InputConnection.commitText`；发送由用户决定。
- 服务与索引仅使用内存；消息、联系人、Token 不写 Git、日志或调试输出。
- 本阶段可以验证合成消息的接收、去重和会话归属；真实微信数据与手机真机体验均未验收。
