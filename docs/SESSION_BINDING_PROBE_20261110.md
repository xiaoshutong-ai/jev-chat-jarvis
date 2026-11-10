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

## 下一项接线（无需新增风控权限）
1. Android `WeChatNotificationListenerService` 只读记录 `onNotificationRemoved(..., reason=REASON_CLICK)` 的 **是否发生、时间、profile 和 notification key**；有值时记录 `shortcutId`/`locusId` 的**存在性**，不落盘聊天原文。
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
