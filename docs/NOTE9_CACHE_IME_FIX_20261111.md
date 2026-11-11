# Redmi Note9 — 候选填入与重复模型调用修复（2026-11-11）

## 用户实测输入

用户在 Redmi Note9 已安装修复版 `9D995DF0…53AB0668` 后确认：
- 3 条候选回复实际出现；
- 点击“快捷填入”未成功进入微信编辑框；
- 复制的同一段已分析文字不应该因再次点悬浮球而重复请求收费模型。

以上为用户直接观察，**不等于 IME 自动写入通过**。本轮延续 Draft PR #21 和原唯一 writer `EmilioJI`，在独立工作树修改；不触及微信 Accessibility、内部节点、自动发送、WeFlow 或密钥处理。

## 根因及改变

1. **输入法切换不是写入动作**：Note9 已启用 JEV IME，但实时默认仍为 `com.sohu.inputmethod.sogou.xiaomi/.SogouIME`。原“快捷填入”仅 `showInputMethodPicker()`，没有标准 Android 能力替用户静默更换默认输入法；用户必须在系统选择器选 JEV，再点击 JEV 输入法上标明的候选，才能调用 `InputConnection.commitText`。此外旧 `ReplyHandoff.consumeArmed()` 只读取一次，MIUI 重启键盘 View 可使待填入候选消失。
2. **本轮输入法修复**：`ReplyHandoff.peekArmed()` 在 60 秒的显式选择有效期内跨多次 `onStartInputView` 保留候选，仅成功写入后清理；最终 `commitText` 仍须点击可见候选、检验微信包名与当前编辑器 epoch，绝不自动发送。
3. **可靠回退**：候选卡片原“快捷填入”改为“切换键盘填入”。用户点它时，先将明确选择的候选复制为 `jev_reply` 剪贴板，再尝试打开系统输入法选择器；若 MIUI 不显示选择器，可直接在微信输入框手动粘贴。App 不直接接管键盘、不控制微信 UI，必须由用户点击系统输入法/手动发送。
4. **缓存修复**：原 `RecentExplicitCopy` 只有连续 10 秒的去重窗，过期或先分析另一条文字后，又会再次触发付费模型。现由 `ClipboardAnalysisCache` 按复制内容指纹及模型路由识别，最多保存 20 条**仅内存**结果。进行中重复点击不发新请求；成功分析重复点击直接重新展示判断和候选；新内容/换模型路由才重新分析。用户明确点击“重新分析（会产生费用）”才能废弃已完成的本条缓存；失败运行清理 pending 允许重试。服务进程销毁后不保留缓存，不写入文件、聊天正文、模型 Key 或联系人身份。
5. **现存限制**：主动复制无法提供微信当前联系人 ID。相同文字可能来自不同聊天，复用结果始终带“联系人未核验”的提示；用户必须确认适用于当前聊天。此能力不能视为微信 A/B 的自动联系人绑定。

## 实际验证

- 原 Windows 5070TI-JAY / PowerShell 7.6.6 / JDK 17 / Android SDK 36、缓存 Gradle 8.13（仓库 Wrapper 固定 8.11.1，故不冒称固定 Wrapper 通过）。
- `:app:testDebugUnitTest --tests com.jev.probe.core.ClipboardAnalysisCacheTest --tests com.jev.probe.ime.ReplyHandoffTest --tests com.jev.probe.core.ExplicitClipboardPolicyTest --offline`：**BUILD SUCCESSFUL，15/15 测试，0 failures / errors**。
- `:app:assembleDebug --offline`：**BUILD SUCCESSFUL**，默认 arm64-v8a、包名 `com.jev.probe.guofeng`、`versionCode=4`、`versionName=1.3-guofeng-debug`；APK 28,926,846 bytes，SHA256 `BCCE2EA11002F716E0BE3944296E963E652FA8F895E9940C5074C8CF2DB8AC32`。
- `apksigner verify` v2 **PASS**；证书 SHA256 `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f`，与 Note9 已安装的试点 Debug 签名一致。
- APK 在原 Windows 设备离线保存：`D:\DevCaches\jev-session-binding-check-20261110\JEV_Note9_CacheImeFix_20261111_BCCE2EA1.apk`。
- 通过 ADB **仅传输文件**到 Note9：`/sdcard/Download/JEV_Note9_CacheImeFix_20261111_BCCE2EA1.apk`；手机 `sha256sum` 与上述 SHA256 完全一致。**不尝试绕过 MIUI 用户确认安装限制**。现安装仍为先前版，升级确认、真实 IME 和付费调用零重复的真机验收尚未执行。
- 未新增权限、未改变用户系统默认搜狗输入法、未读取微信正文/数据库/密钥，也未触发任何新的付费模型请求或微信发送动作。

## 下一动作与验收

用户在 **Redmi Note9 文件管理器 → 下载** 中打开 `JEV_Note9_CacheImeFix_20261111_BCCE2EA1.apk`，由 MIUI 标准安装器自行确认同签名更新。保持现有 API Key 和应用数据。

随后用一条非敏感测试消息：第一次点悬浮球应生成候选；第二次直接点悬浮球应回显原结果（若是 JEV 自己复制的回复，系统仍会拒绝将回复作为新输入）；点“切换键盘填入”，从系统选择器选“小书童·快捷填入”，在 JEV 键盘再次点击对应候选，应仅写入微信编辑框，**不发送**。若系统选择器不出现，候选已复制，可手动粘贴；此为明确的可靠回退，不冒称 IME 直写成功。

当前门禁：`TARGETED_UNIT=PASS_15_15`、`APK_BUILD=PASS`、`PHONE_FILE_HASH=PASS`；`NEW_APK_INSTALL=NOT_VERIFIED`、`NOTE9_IME_COMMIT=NOT_RUN`、`NOTE9_REPEAT_PAID_MODEL=NOT_RUN`、`CI=NOT_RUN`、`RELEASE=NOT_RUN`。PR #21 保持 Draft。
