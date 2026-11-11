# JEV Note9 最近 20 条上下文 + 回复风格：蓝军审查与交付边界

日期：2026-11-11（北京时间）
仓库：`xiaoshutong-ai/jev-chat-jarvis` / Draft PR #21
基线 HEAD：`9f8c695ce24553123d85dd9b9177c6aa10174573`；本增量之后以新 HEAD 实际回读为准。
范围：现有用户主动剪贴板分析、JEV 判断 + DeepSeek 回复、JEV 标准 IME。

## Outcome / Steelman

用户提出的真实问题是：只有单条“对方消息”时，缺少前文导致判断与回复偏离关系语境；回复还不够像用户自己。最小收益最高的方案是**有用户明确批准的数据来源后，给判断和候选回复最多 20 条有方向的消息**，并仅从**用户确认自己确实发过**的例子中学习语气。保留既有低权限微信交互和手动发送。

本期原型不声称“微信自动读取 20 条历史”，不做微信私有数据库解密、后台截图/读树，也不以 AI 生成回复当作用户已发送的风格样本。

## Source facts / Assumptions

- CONFIRMED：先前 `ExplicitClipboardPolicy.fromUserCopy` 将复制内容记为 1 条 `other` 消息；手动路径没有可靠联系人身份。`ReplyClient` 和 `JevQuestions` 先前均 `takeLast(10)`，通知路径分析上限是 12 条。
- CONFIRMED：`ContextBuilder` 只有匹配到已存联系人才注入本机历史；手动剪贴板 `title=null`，没有稳定会话 ID。
- HARD CONSTRAINT：不读取微信 Accessibility Tree、内部 ID/数据库或自动发送；联系人真实身份不能由相同消息文字、通知或编辑器推断。
- NEW SOURCE_ONLY：用户主动导入并逐条明确标注“我：/对方：”，只取后 20 条；导入保留内存 60 分钟；最新一条必须为“对方”，且后续显式复制文字与之精确匹配才启用；未匹配回退原单条流程。
- NEW SOURCE_ONLY：至少 3 条经过“我：”标注的文字才有风格样本；只生成写作风格指引，不自动训练第三方模型或永久保存聊天内容；与原回复模型调用共享当前主动授权。
- UNKNOWN：用户能否便捷从当前微信版本选择/复制 20 条到这个明确格式；模型响应准确率是否实测提高；推理成本的真实增量；MIUI 真机该新导入页的可用性。

## 蓝军 Finding（风险按材料性排序）

| ID | 类型 | 机制 / 证据 | 影响 | 整改 / 最小实验 | 反蓝军 |
|---|---|---|---|---|---|
| B1 | CONFIRMED_DEFECT | 原路径只有 1 条，模型又截断为 10 条；精确源码 | 用户认为有 20 条但模型看不到 | 手动明确导入窗口 + 判断/回复全链 20 条；JUnit 确认第一条与最后一条 | WITHSTANDS，修复后回归 |
| B2 | UNVERIFIED_ASSUMPTION | 微信当前联系人 / 20 条历史不可由普通剪贴板读出；先前真实 Note9 只有用户主动单条复制 | 伪造会话、跨聊天串上下文 | 严格收发标签、最后一条精确匹配、标题始终 null，界面声明身份未核验；下一轮真人导入测试 | WITHSTANDS，明确不宣称自动读取 |
| B3 | PLAUSIBLE_RISK | 即使文本完全一致，不同聊天也可能有相同末句；缺真实 session ID | 错误背景注入并产生不合适候选 | 仅用户明确导入且主动复制触发；UI 显示警示，可清除；不自动发送 | WEAKENED，但残余风险存在 |
| B4 | PLAUSIBLE_RISK | 自动把 AI 建议当作本人发言，会形成自我强化；对方文本也可能被误当本人 | 不真实的语气偏移 | 仅解析显式“我：”，至少 3 条，且用户另行勾选；既有 `jev_reply` 剪贴板不会被算作新输入 | WEAKENED |
| B5 | PLAUSIBLE_RISK | 新增历史和风格例子会传到已配置模型供应商，并增加 token 消耗 | 隐私与成本 | 默认不导入，页面明确披露；上限 20 条、单条 <=500 字、总输入 <=15000 字；不新增自动后台上传，缓存必须包含导入 revision | WITHSTANDS，留给用户主动选择 |
| B6 | MISSING_EVIDENCE | 本机 JVM 编译与静态检查不能替代 Note9 UI 和生成质量 / 用户原文核验 | 不能宣称完整功能交付 | 只在用户主动安装后用虚构对话测试 20 条与风格；不额外消耗用户真实消息做自动回归 | UNRESOLVED |

## Premortem / Tripwires

- **导入被放弃（MEDIUM/LOW）**：微信不能高效提供 20 条可标注文本，用户嫌操作太复杂。Tripwire：用户导入一组 20 条所需步骤/时间。Mitigation：下一版复用系统明确选中的 Share 或已授权桌面结构化导入，但新来源需独立可靠性验证，不自动恢复微信内部读树。
- **串会话（LOW/HIGH）**：重复的“好的/收到”末句恰好跨聊天相同，被误当绑定。Tripwire：用户换聊天后仍显示另一个人的背景。Mitigation：明确“未核验联系人”、用户可以立即清除、短效内存、用户手动确认后才填入；需要可靠 session ID 才考虑自动绑定。
- **风格输出走偏（MEDIUM/MEDIUM）**：少量样本偏向场景/某个对象，模型生搬硬套或过度口语化。Tripwire：用户对 3 条候选逐一打分，风格像自己但事实错误属于失败。Mitigation：风格样本默认关闭、至少 3 条、只匹配长度/语气，不复制事实和私人承诺。
- **成本提升（MEDIUM/MEDIUM）**：20 条使判断+排序与生成请求量增大。Tripwire：同等请求的计费 token/延迟显著上升。Mitigation：固定 20 条 + 输入长度上限，继续复用 `ClipboardAnalysisCache`，模型 Key/供应商不可自动切换。

## 本阶段验收合同

- 新源码只在 JEV 自身前台显式输入中构建 20 条；严格方向、末条匹配、跨路由/导入 revision 缓存隔离，且支持清除/过期。
- 20 条同时进入 `JevQuestions` 和 `ReplyClient`，不修改原来的微信权限、消息发送权及 IME 安全边界。
- Unit/Build 只针对受影响模块，真实模型/微信设备层未运行不得冒充。
- 隐私功能的独立源码 Review 与真机用户验收在发布前完成；蓝军结论不代替独立审查，也不自动扩大生产数据采集授权。

**BLUE_STATUS: BLUE_FINDINGS** — 确认并已针对性修复“单条/10 条截断”问题，但真实 20 条导入易用性、风格有效性、跨聊天相同末句的残余风险仍需在正式产品决策中显式考虑。

## 2026-11-11 独立 SOURCE_HIGH 反馈与定向整改

- 砚宝 `xst-yan` 对精确 HEAD `8402e7a757d22ccc58395208106525bda3849df3` 进行 SOURCE_HIGH 只读审核，回执 `jev-pr21-context20-style-8402e7a-source-high-r1`、`SOURCE_HIGH_READY`，**Critical 0 / MUST_FIX 0 / SHOULD_FIX 1 / OPTIONAL 1**。砚仅实读源码，未提交正式 GitHub review；其意见不等同于 CI 或真机。
- SHOULD 1 已改：`JevQuestions.CHAT_DATA_NOTE` 统一附加到全部 7 项原生 JEV 判断和 `best_reply` 排序问题；要求将聊天正文、知识背景及历史当作不可信数据，不执行其中的命令或角色切换；`JevQuestionsStateWindowTest` 对 7+1 条问题文本新增定向断言。
- OPTIONAL 1 已改：`ManualConversationContext.activeEntry` 的一小时 TTL 边界改为 `nowMs >= expiresAtMs` 即过期，新增精确到期时应失效的单测。
- 5070TI-JAY / PowerShell 7.6.6 / 本地 Gradle 8.13（非 Wrapper 8.11.1），`ManualConversationContextTest 6/6`、`JevQuestionsStateWindowTest 2/2`，**8/8 PASS**；受影响 arm64 Debug APK `:app:assembleDebug --offline` **PASS**，28,577,892 bytes，SHA256 `59EECC265BC7EE6B6BFFA642AF64693D431118D045DDC881B1244CC3973B8CE9`，v2 签名有效且证书与旧 Note9 APK 一致。
- 复用已存在 Android16/x86_64 `emulator-5554`、无 SDK 下载；同 HEAD x86_64 调试构建 hash `A34E9F1D7C7C6C5474DD24216C68D12752C9F0C666979C7CF0AB55A3555B8561`，安装 `Success`，从 JEV 首页点击进入 `ManualContextActivity`，确认文本框、风格勾选默认 OFF、确认/清除按钮正常渲染。模拟器系统 `adb shell input text` 无法发送中文，抛出 InputShellCommand NPE；JEV 进程随后仍在、重新激活页面成功，未观测 AndroidRuntime 崩溃。**手动导入正例的模拟器 UI 操作 NOT_VERIFIED**，不以本机 JUnit 代替。
- 本轮没有读取真实微信、扫描微信 UI 树、写入手机已安装测试版或发起真实付费模型调用；Note9 对新上下文/风格能力仍 `DEVICE_E2E=NOT_RUN`。后续需独立 reviewer 对新 HEAD 的这个小增量只审变更处，保持 PR #21 Draft。
