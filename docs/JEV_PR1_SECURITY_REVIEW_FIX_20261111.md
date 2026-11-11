# JEV 父 PR #1 SOURCE_HIGH 独立审核整改（2026-11-11）

唯一仓：`xiaoshutong-ai/jev-chat-jarvis`。父 PR #1 基线 `feature/guofeng-ui` HEAD `6f001672922862f1061c971c842ede815e94548e`，目标 `feature/hardening-v1`。原唯一 writer `EmilioJI` / 5070TI-JAY / `D:\GitHubWork\jev-session-binding-pr21-20261110`。此修改继续同一 worktree，非第二 writer；需独立 `xst-yan` 对新 HEAD 的源码增量复核。

## 来源与边界

砚宝对已合并子 PR #21 的父 PR #1 **完整 SRC_ONLY** 审核提交正式 GitHub Review **#5857408884**（绑定旧 HEAD `252fde275cf7adfb7523bd8ece787ba4f37d2be0`），结论 Critical 0、MUST_FIX 0、SHOULD_FIX 3、OPTIONAL 3。随后父分支有 CI-only 提交 `6f00167`；该提交通过本地 YAML/Bash 静态检查，但 GitHub Actions 派发回执 **HTTP 422 “Actions has been disabled for this repository”**。`GET repos/.../actions/permissions` 的 `enabled=true` 与此 422 矛盾；组织策略只读 API 对 `EmilioJI` 返回 403。**不能称 CI 已运行**；不得绕过平台拒绝、改账号执行同一派发或建立另一个 runner。

## 本轮定向整改

1. **S3 / High: API Key 与电脑 token 安全存储故障**
   - 原 `SecureSecretStore.write` 在 Keystore 加密或密文 `SharedPreferences.commit` 失败时，甚至会删除旧密文并回退 `putString(legacyPlainPref, value)`，实质落盘明文，与 UI 安全说明不一致。
   - 新增无 Android 依赖的 `SecretWritePolicy`：密文加密→往返解密验证→只提交密文→旧明文迁移清理。任何一步失败都抛异常，不提供写入明文的回调。清空已存在但暂不可解密的密文时，也拒绝误删除，保护既有 Key。
   - `SecureSecretStore` 移除所有新明文回退；对暂不可解密的旧密文不采用遗留明文替换；旧明文迁移失败返回不可用但保留源数据以备原位重试。旧 v1.2 配置直接迁移到加密字段，不再创建一份新的 v1.3 明文副本。
   - 正式、临时接口检测所用 `Prefs` 实例都用 AndroidKeyStore，加密检查不只覆盖 main prefs。设置页先尝试安全保存 4 类 Key（Judge/Reply/Vision/Desktop），失败即 Toast 明示并停止保存其余配置；不输出凭据、不清除既有数据、不修改模型提供商地址。部分密钥在故障前可能已单独保存成功，本期不声明跨四字段原子事务。
2. **S1 / Medium: IME 自动填入**
   - `ZhiyanInputMethodService.onStartInputView` 从此只 `peekArmed`，不再对非微信编辑器自动执行 `commitText`；所有编辑器均通过可见候选按钮触发，并在点选时核对编辑器 package 与当前 epoch。MIUI 重建 IME 页面不消耗候选，成功写入后仍由用户自己发送。不改变微信现有 fail-closed 联系人绑定。
3. **S2 / Medium: 手动读取已适配应用的窗口**
   - `ChatCaptureService.manualAnalyzeCurrentWindow` 旧 KDoc“normal WeChat A gets generic visible-text reader”与当前现行拒绝实现冲突。本次明确只有 QQ/X/飞书适配器可读取标准控件；`manualReadRoot` 只选择当前已适配包，不优先列举微信 root，也不回退到任意未知 App。微信 A/B 路径仍为通知、用户主动剪贴板及可选截图 OCR。

## 验证与发布边界

- 只涉及上述密钥存储、IME 候选、手动窗口根和设置页错误回显，无生产密钥读取/解密/写入。修改不触数据库、服务端、正式签名、GitHub org 权限或支付。
- `SecretWritePolicyTest` 对正例、加密失败、往返校验失败、密文 commit 失败、旧明文清理失败、临时不可解密时禁止清除等提供 7 条纯 JVM 正反例。相邻 `ReplyHandoffTest` 5 条和 `SessionBindingResolverTest` 8 条，在同一源码修改版本的 Windows 缓存 Gradle 8.13 / JDK17 上 **20/20 PASS**、`:app:assembleDebug --offline` SUCCESS；后续仅追加 KeyStore 解密失败的 fail-closed `return ""` 小修，应以最终 HEAD 的编译回执为准。
- 源码静态合同核验：`SecureSecretStore` 无 `putString(legacyPlainPref, value)`，正式与临时 `Prefs` 均加密；`onStartInputView` 无直接 `commitCandidate`；`manualReadRoot` 无 `WECHAT_PKG`/任意 root 回退；设置页异常捕获早于非密钥配置写入。
- **真实设备**：Note9 已验收旧版本保留；本次高风险存储路径先由独立 Reviewer 审查，再在原设备使用同签名 debug APK 进行一次无需真实 Key 内容的 AndroidKeyStore 自检、验证原配置字段仍存在。为防止破坏用户状态，不做故意损毁原 Keystore/清除旧 Key 的真机负例，JVM 注入失败单测提供负例证据。
- **CI**：父 PR #1 当前 head 的正式 GitHub Actions 仍 `NOT_RUN`（dispatch 422，组织读权限 403），既有 Linux offline local run 不冒充 CI。不要为了让 GitHub CI 成功而未经授权更改 organization policy、开启有偿 hosted runner 或注册新 runner。新 HEAD 的独立 Review 不替代正式发布、签名、商用许可审查及用户真正的微信 A/B 产品验收。

## 最终源码定向验证回执

- 原基线 `6f001672922862f1061c971c842ede815e94548e` 的同一新工作树源码：`SecretWritePolicyTest` 7/7；`ReplyHandoffTest` 5/5；`SessionBindingResolverTest` 8/8，三组共 **20/20 PASS**，0 failures/errors。
- 额外对「加密的旧 Key 暂无法解密时，不得回退为旧明文并覆盖新密文」加入 fail-closed 分支后，重新在相同 Windows JDK17 / Gradle 8.13 缓存 / `--offline` 构建：`SecretWritePolicyTest` **7/7 PASS**（0 failures/errors/skipped）、`:app:assembleDebug` **BUILD SUCCESSFUL in 54s**，42 tasks 9 executed/33 up-to-date。
- 新 Debug arm64 APK **28,991,354 bytes**，SHA-256 `BFDD8A8B233E38D1A38151B2CD89F957CA4FA70074CA37D7624BF530A376BF96`，Android APK Signature Scheme v2 PASS、原测试证书 digest `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f`。
- 静态安全合同检查 `STATIC_SECRET_IME_ACCESSIBILITY_SAFETY=PASS`（新写入不含任何 `putString(legacyPlainPref, value)`、非微信 IME View 创建不自动 `commitCandidate`、`manualReadRoot` 不枚举微信/未知 App、设置页密钥保存失败会中止其他配置写入）。
- 本次**没有读取、清除、写入任何真实用户 Key，也没有调用付费模型或访问微信消息**。新 Debug APK 只完成本机构建与签名，尚未安装 Note9；新的 KeyStore 行为应在独立 SOURCE_HIGH Review 后以无敏感数据的 `SecuritySelfCheck` 正向真机验收，负例由纯 JVM 故障注入覆盖。
