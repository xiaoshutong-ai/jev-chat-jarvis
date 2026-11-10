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

## WeFlow 兼容性边界

`--weflow-url` 只允许本机 loopback，必须显式指定 `--allow-real-chats` 和 `--chat-id` 白名单；其 API Token 仅来自环境变量。该适配器只轮询文本消息，不处理图片、语音、文件或发送。当前逻辑按每个白名单会话最近 40 条读取，并对可见窗口内的重复消息去重：**突发超过源窗口、消息撤回、历史回补均未完成可靠性验收**，不能宣传为全量或零遗漏。

WeFlow 可能依赖额外的非官方解密组件，本仓没有也不会自动下载。开源许可、微信账号风险和聊天数据授权须在接入真实个人消息之前单独确认。

## 安全与测试边界

- 手机微信不请求无障碍树、截屏，也不修改微信程序。
- Android 接收后先验证消息所属账号与 profile；自动生成候选必须取得用户单独允许，候选点击确认后才允许 `InputConnection.commitText`；发送由用户决定。
- 服务与索引仅使用内存；消息、联系人、Token 不写 Git、日志或调试输出。
- 本阶段可以验证合成消息的接收、去重和会话归属；真实微信数据与手机真机体验均未验收。
