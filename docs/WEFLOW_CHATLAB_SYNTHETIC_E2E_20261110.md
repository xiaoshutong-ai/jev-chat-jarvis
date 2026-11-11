# JEV · WeFlow ChatLab 兼容适配与 Android 合成验收

验收日期：2026-11-10。唯一仓库：`xiaoshutong-ai/jev-chat-jarvis`，Draft PR #21。
源码精确 HEAD：`b84f5ef6f7741aebe4b82011995ff4a9f8de06eb`。

## 变更范围

- 桌面端从 WeFlow `/api/v1/messages?limit=40` 最近消息查询，切换为 `GET /api/v1/sessions/:id/messages` 的 ChatLab 增量分页协议。
- 精确会话 ID 白名单（最多十个）、显式发送者 ID、默认回看 60 秒（最多 3600 秒）；避免第一次接入时无意扫描整个账号历史。
- 通过 `since/offset/nextSince/nextOffset` 接续，同一分页窗口由 `end=watermark` 固定上界；源页只有完整通过校验并进入桌面消息队列后才 ACK 上游游标。
- 源数据缺稳定消息 ID、类型不正确、账号方向不确定、失效水位线等情况拒绝该页，避免静默跳过；Relay 环形队列覆盖旧记录明确返回 `gap=true`。
- 限制 Android 轮询响应字节预算约 180 KiB（Android 最大响应 256 KiB），长聊天文本分批交付。
- 测试上游 `desktop_bridge/tests/mock_chatlab_server.py` 只在本机返回 `sim-alpha` 与 `sim-beta` 合成会话，**绝不访问真实微信或 WeFlow 数据库**。
- Debug 模拟器测试页新增本地状态回执 `files/jev_synthetic_state.txt`，仅保留 `SYNC=...` 和纯测试计数、错误类名，不包含消息正文、账户凭据、API Token 或真实聊天。该 Activity 仍不进入 Release。

## 实际执行证据

1. Windows 5070TI-JAY / PowerShell 7.6.6：在 HEAD `e9f386968b784f19406f1206b267598d7c9729c3` 运行 `python -m unittest discover -v -s desktop_bridge/tests -p 'test_*.py'`，**14/14 PASS，退出码 0**。之后仅增加 Debug Activity 诊断代码，没有修改已通过的桌面协议与测试。
2. Python 实际 loopback HTTP 合成验证：使用随机临时 `WEFLOW_API_TOKEN` 与 `JEV_RELAY_TOKEN`，模拟 WeFlow ChatLab GET API 成功返回两个独立会话，JEV 桥输出 `sim-alpha,sim-beta`，`MOCK_TO_DESKTOP_RELAY=PASS`。未安装第三方解密组件。
3. Android 16 / API36 / x86_64 已有 `emulator-5554`：使用缓存 Gradle 8.13、工程固定 AGP 8.10.1/Kotlin Gradle Plugin 1.9.24，显式 Debug ABI 编译 `:app:assembleDebug`，**BUILD SUCCESSFUL**。精确 HEAD `b84f5ef6f7741aebe4b82011995ff4a9f8de06eb` 的 APK SHA-256：`84C20927F40213DDEAC0B9281384C325E4D76285D310E2BDC89B27F29ADF4499`，字节数 29,109,947。
4. 相同 APK 在模拟器中 `adb install -r` 返回 PASS。通过 ADB reverse 从手机连接到本机只读桥，Debug Activity 的应用内状态文件依次回读：
   - `SYNC=NOT_RUN`（页面初始化后）
   - `SYNC=CONNECTING`（接收进行中）
   - **`SYNC=PASS sessions=2 analyzed=2 messages=2`**（后台工作线程最终回执）
   - `CHATLAB_ANDROID_WORKER_E2E=PASS`
5. 以前使用 `uiautomator dump` 与屏幕截图检查时，模拟器曾长时间显示 `SYNC=CONNECTING` 或返回空 UI 根节点；这次借助 Debug 文件独立读取后台状态成功，**仅证明合成数据业务链路成功，不证明共享模拟器 UI 帧率或真实微信行为**。
6. 结束时执行 `SYNTHETIC_RUN_CLEANUP=COMPLETE`，模拟 WeFlow 与 Relay 测试进程停止，ADB reverse 移除，JEV 测试进程停止；未修改微信客户端、账号、数据库、其他应用和正式服务器。

## 未验收项与法律/产品边界

- **WEFLOW_REAL_SOURCE = NOT_RUN**：未安装、启用或调用真实 WeFlow；既有微信 4.2.0.13 的账号私聊未被读取。
- **WECHAT_A_B_DEVICE = NOT_RUN**：模拟器自有 Debug 页面不具备个人微信当前会话 ID，无法证明 vivo 微信 A/B 会话识别及平台安全性。
- **SOURCE_RESTART_BACKFILL = LIMITED**：内存游标重启后按用户批准的回溯时间初始化，未做跨长期断线的持久历史校准，不宣称零遗漏。
- **LICENSE = REQUIRES_INDEPENDENT_REVIEW**：WeFlow 的 CC BY-NC-SA 4.0 不允许直接作为商业 JEV 的可再分发组件；本 PR 仅独立实现公开 HTTP 协议，不分发 WeFlow 及其依赖；未来真实数据读取还需另行确认相关组件许可、隐私授权及账号风险。
- **REAL_SOURCE_APPROVAL = PENDING**：启用前需要用户确定本人授权的具体会话、保留范围、是否允许给模型、批准期限；默认模型自动分析关闭，不使用真实微信聊天触发模型。
- **CI、正式签名、合并及发布 = NOT_RUN**；PR #21 保持 Draft，原 PR #1/主分支不动。

验收判定：`CHATLAB_PROTOCOL_PYTHON=PASS_14_14`；`CHATLAB_MOCK_HTTP_WINDOWS=PASS`；`CHATLAB_MOCK_TO_ANDROID_SYNTHETIC=PASS`；`PRODUCTION_WECHAT_DATA=NOT_READ`。
