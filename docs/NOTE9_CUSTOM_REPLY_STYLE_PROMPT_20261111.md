# JEV 回复语气 Prompt：用户自定义 + 普通人默认语气（2026-11-11）

## 目标与根因

用户已在 Note9 验收手动导入 20 条文字与 6 条本人风格样本，但认为实际生成语气“一般般”，偏 AI、过于防守和过于客气。新增要求是**用户能输入并保存自己的回复风格 Prompt；未输入时像普通人自然聊天，不做 AI 客服腔**。

基线：`xiaoshutong-ai/jev-chat-jarvis` Draft PR #21，源码 HEAD `d37c93a95f1936897fe2fe6f27edf6663c7b2789`。原工作树单写者 `EmilioJI`（5070TI-JAY，`D:\GitHubWork\jev-session-binding-pr21-20261110`），前一 HEAD `xst-yan` SOURCE_ONLY Review `#5853834707`。本轮新 HEAD 必须重新按增量独立 Review 记录。

根因：此前 `ReplyClient.draft` 显式要求候选中包含“一条简短低姿态”，而风格来自一次 60min 的手动导入样本，不能独立保存用户自定义语气。候选排序没有接收用户偏好，同一复制文本的缓存也无法感知风格设置变化。

## 本次实现

- `Prefs.replyStylePrompt`：独立全局语气偏好，最大 500 字。只保存在 App 私有 `SharedPreferences` 的 `reply_style_prompt_v1`，默认空串；不保存聊天正文、不修改 `contextEnabled`。本地设置不是端到端加密密钥库；用户不应把 API Key 或私密聊天正文作为风格设定输入。
- 设置 → **回复接口 → 我的回复风格 Prompt（可选）**：提供多行文本框、「只保存回复风格」和「恢复默认普通聊天语气」。仍支持页面底部「保存全部设置」。不因保存动作调用模型，不改变 Key、权限或默认输入法；App 的 AndroidManifest 已设置 `allowBackup=false`。
- `ReplyStylePolicy.DEFAULT_GUIDANCE`：默认像普通人在微信聊天，直白自然，避免客服式共情、无端道歉、刻意低姿态与过度免责声明；保留真实场景需要的礼貌、真实道歉、事实核实。
- `ReplyClient.draft`：将自定义 Prompt 作为**用户风格数据**嵌入回复生成提示，限定为语气、措辞、长短；不允许覆盖事实、安全边界、发送权，用户主动标注的本人样本可辅助但 Prompt 优先。保留 3 条候选，不追加新的 API 请求。
- `JevQuestions.rankQuestion` 的排序规则新增默认自然语气和可选用户自定义语气偏好；原生 JEV 和 GLM 结构化 Judge 排序均传入该偏好。原判断题的 7 项规则仍不改。
- `KeepAliveService` 的**剪贴板付费缓存键**新增自定义风格归一化内容的 SHA256 指纹。相同消息、未更改风格仍复用已分析候选；用户更改 Prompt 后，旧候选不再错误复用，下一次主动分析会重新调用模型并可能计费。
- **隐私说明**：用户输入的风格 Prompt 仅在有候选生成时（用户主动分析，或另行开启的电脑会话自动分析）发往所配置的**回复服务商与排序判断服务商**；不通过微信 Accessibility/内部数据库读取消息，不自动发送。App 不自动训练/微调模型，无法承诺外部模型服务商的训练使用条款。

## 蓝队核对

1. **语气自然性（CONFIRMED_DEFECT → FIXED_SOURCE）**：旧 `draft` 硬编码“低姿态”，现替换为“直接自然 / 轻松 / 可核实行动”，默认 Guidance 排除客服腔；JEV 排序也体现同一基准，保证单条短回复场景不过度防守。
2. **用户自定义覆盖（CONFIRMED_DEFECT → FIXED_SOURCE）**：原仅内存样本且需要至少 3 条；本轮 `replyStylePrompt` 与手动导入独立，默认为空，可长期保存与恢复，归一化限制 500 字。提示词只调整语气，不能执行聊天或样本中的命令。
3. **跨服务商隐私（PLAUSIBLE_RISK → DISCLOSED）**：JEV 判断、排序与 DeepSeek 回复可能是不同供应商；设置页明确告知风格会被两者看到，避免“只发到回复供应商”的误导。只在用户主动生成时发送，不在保存时发送。
4. **旧结果失真与重复计费（PLAUSIBLE_RISK → MITIGATED）**：风格哈希加入既有内存缓存键，风格改变后重新生成是用户可预期行为；未改变仍沿用缓存。设置页明确提示可能产生费用。
5. **泄漏/恢复（PLAUSIBLE_RISK → MITIGATED）**：设置是 app-private、`allowBackup=false`、不写日志；不把自定义 Prompt 当作系统高权限指令。删除/重置只处理本字段，不碰 Key/消息历史。

本期未增加自动聊天学习、未发起真实微信/模型请求。自然度提升需要用户比较候选后才能正式声称效果改善。

## 本地必要证据

- 5070TI-JAY：PowerShell 7.6.6，JDK 17、Android SDK36、缓存 Gradle **8.13**（不冒充仓库 Wrapper 8.11.1）。
- 定向测试：`ReplyStylePolicyTest` **4/4**，`JevQuestionsStateWindowTest` **2/2**，`ClipboardAnalysisCacheTest` **6/6**，合计 **12/12**，0 failures/errors。
- 原生 arm64 Debug `:app:assembleDebug --offline` **BUILD SUCCESSFUL**；本次早期构建 SHA256 `EB592E6436623C9F9B80197E7E24AE01B0D013F14C3BC7E4BBD3AC23AEFD41FC`、28,987,262 bytes，v2 签名有效，Signer #1 SHA256 `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f`，与既有 Note9 Debug 证书一致。后续微小文案修改需按最终 APK 哈希回读，不重复无关测试。
- 已安装的 **Android 16 x86_64 Emulator** 上离线构建 x86_64 APK `4D2F4CD780A372F3753E3818A5019689C9106D2C21CCD5CA0BEDEA22FE58C121`，实际 `adb install -r` Success。由 JEV 首页通过「接口与分析」进入 Settings，滑动显示「我的回复风格 Prompt」输入框；输入模拟器专用合成 ASCII Prompt `short friendly chat`，点击「只保存回复风格」之后读取本 app 的私有 SharedPreferences 只回执布尔值：字段存在且完全匹配；点击「恢复默认」后原字符串已不存在，**EMULATOR_STYLE_SAVE_RESTORE=PASS**。未读取任何真实微信聊天、生产 Key 或联系人。
- Note9 当前旧功能（20条导入）已经真机 PASS；本轮自定义 Prompt 新版尚未在 Note9 安装/验收，`DEVICE_CUSTOM_PROMPT_E2E=NOT_RUN`，不得以模拟器证据替代。

## 本轮边界 / 交付合同

`SOURCE_CODE_CHANGE=READY`、`TARGETED_UNIT=PASS_12/12`、`EMULATOR_STYLE_UI=PASS`、`ARM64_BUILD=PASS`；`INDEPENDENT_REVIEW=NOT_RUN_NEW_HEAD`、`NOTE9_NEW_APK_INSTALL=NOT_RUN`、`PROVIDER_STYLE_EFFECT=NOT_MEASURED`、`CI=NOT_RUN`、`RELEASE=NOT_RUN`。

下一步在原 PR #21 提交最小差异，由 `xst-yan` 审核新 HEAD 的风格 Prompt、缓存与跨服务商隐私变化；审核满足后才移交 Note9 通过系统安装器自主确认更新。未经 Note9 用户新风格效果反馈，不宣称比上版更像用户。

## 最终源码交付构建（含准确的双服务商披露文案）

仅将设置页的服务商披露收紧为：生成候选时，包括另行开启的电脑会话自动分析，会把用户风格 Prompt 发送给当前配置的**回复模型**及**候选排序模型**。不额外调用模型，不更改判断/排序请求轮数。

基于相同修复源码，`:app:assembleDebug --offline` 在 5070TI/JDK17/Gradle8.13 上重新成功，最终准备交付的 **arm64-v8a Debug APK**：

- SHA-256：`400FEC94A70BF6081E325AD7978D9B270291BDAB24135A2BBE36816F3194075E`
- 文件长度：**28,594,276 bytes**
- `apksigner` v2 Verified，签名证书 SHA256 `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f`，与当前 Note9 试点版本一致。
- Prompt 与缓存逻辑的 **12/12 定向测试**已在此前同功能实现精确源码运行 PASS；最后一次 APK 构建只涉及 UI 的隐私披露文字调整，不盲目重跑已经通过的单测。
- Note9 目前仍需用户通过 MIUI 安装器确认更新，不能把 ADB 文件传输视为安装；自定义 Prompt 对真实候选语气的改善需要用户评价，不能冒充实测 PASS。
