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

