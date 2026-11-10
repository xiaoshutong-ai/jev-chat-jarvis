# JEV · WeFlow 单会话本机试点：安装前核查与阶段回执（2026-11-10）

## 本次批准范围

用户批准在 **5070TI 的独立测试微信账号**上开展最小只读试点；**仅“文件传输助手”会话**、本机处理、不调用 AI 模型、不自动发送、不安装可修改正式账号数据的组件，不将聊天记录/API Token/密钥上传 GitHub。授权不扩展至 Administrator 下任何其他微信账号或聊天数据。当前这份批准不自动包含生产部署、商业分发或扩大读取范围。

唯一产品工程仓：`xiaoshutong-ai/jev-chat-jarvis`，PR #21 Draft；本次构建对象 WeFlow 仅为外部只读依赖研究，绝不作为第二个 JEV 工程仓。

## 官方上游与发行边界

- 上游 `https://github.com/hicccc77/WeFlow`、`main@91adcfdbb24e8e147d8332440b8c6ac899ad2abf`，`package.json.version=5.0.0`。
- 2026-11-10 实时 GitHub API：`releases/latest` 返回 404、`releases` 列表和 `tags` 列表为空。没有可供校验 SHA/签名的官方二进制 Release，不能声称本机包为官方安装器。
- 许可证：上游 CC BY-NC-SA 4.0，非商业条件；本次不复制上游源码到 JEV，不打包进入商业产品。商业部署需单独获得适当授权。
- 上游 `docs/third-party-components.md` 明确不提供任何数据库读取/解密原生组件，`wcdbLibPath` 由用户自行指定 DLL，WeFlow **不验证插件来源或签名**；其 C ABI 涵盖读写数据库、插入/删除消息、防撤回开关等能力。因此，JEV 的文件传输助手白名单**不能作为限制 WeFlow 原生 DLL 权限的沙箱**，必须同时隔离 Windows OS 账号/微信数据目录。
- 5070TI 当前 `Weixin.exe` 版本 `4.2.0.13`；运行于既有 `Administrator` 会话，尚未证明它是独立测试微信账号。**真实用户数据读取、密钥提取和任何 DLL 加载均未执行**。

## 5070TI 本机源码打包证据

工作目录：`D:\DevCaches\jev-weflow-upstream-audit-20261110`，上游 Git 工作树干净。

- 工具链：PowerShell 7.6.6、Node.js 24.19.0、npm 11.17.0，D 盘独立 npm 和 Electron 缓存。
- npm 锁文件共 1,408 个 package 条目。普通 `npm ci` 发生 React 19 vs 老版 `ansi-to-react` peer 冲突；`--legacy-peer-deps` 首次安装 1,040 包，缺 `@lobehub/ui`。单独 `npm install --no-save` 变更多个传递依赖版本导致 `streamdown` 类型失败；已复原。
- 最终使用 `npm ci --force --ignore-scripts --no-audit --no-fund` 安装 **1,273 包**，锁文件版本比对包括 `streamdown 2.5.0`、React `19.2.7`、`@lobehub/icons 5.10.1`、`@lobehub/ui 5.20.0`、`antd 6.5.0`、`@ant-design/icons 6.3.2`，均与上游锁文件一致。未执行 npm 安装脚本。
- TypeScript renderer/electron 检查 PASS；Vite renderer、Electron main/preload 产物 PASS；Electron Builder 完成 Electron 43.8.1 解压、依赖匹配和 `release\win-unpacked` 便携目录生成。
- **本机便携程序**：`D:\DevCaches\jev-weflow-upstream-audit-20261110\release\win-unpacked\WeFlow.exe`，239,713,792 bytes；SHA256 `9198430D1DBDAD13D8BEF44CB1EFDD1752F63EC1A95BDD7A923D341BFD54400E`；Windows `Get-AuthenticodeSignature` = `NotSigned`。
- `resources\app.asar`：499,739,433 bytes；SHA256 `BCD7242EEB14959AF6ACB35A9F4348CD5E36D213B0E40238EB4EF91143C49432`。
- NSIS `WeFlow-5.0.0-Setup.exe` 生成 **FAIL**：`electron-builder` 下载 NSIS 后在 `D:\DevCaches\electron-builder-cache\nsis-3.0.4.1` 执行目录 rename 收到 `EPERM`；目前只有便携目录，不能冒称系统安装完成或官方签名发行。
- 便携程序未启动，WeFlow 未写入启动项/注册表、未配置 API、未安装 WCDB DLL；JEV 测试读取仍仅使用先前完成的 ChatLab 合成数据链路。

## 下一个合法运行边界

1. **确认隔离**：在 5070TI 上明确独立测试微信账号对应的 Windows 用户/profile 与存储位置；不能以“当前 Weixin 正在运行”推定是测试账号。切换/登录账号需要用户本人操作。
2. **先运行空插件模式**：在隔离测试用户会话内启动本机源码生成的便携包，确认能进入设置；禁止启用消息修改、防撤回、自动更新和聊天数据 API。若无法隔离，停止于静态产物层。
3. **确认原生组件出处**：需要有可审核源码/许可证/版本兼容性、SHA256 的读库模块，且不能加载未审核 DLL。因原生接口可能支持写，需 OS profile 的测试数据边界而不靠 JEV API 白名单保护其他账号。
4. **只读最小验收**：本人测试账号、文件传输助手、有限时间窗、只读 GET；浏览器/API 返回时间/消息 ID/方向的计数及去重回执，不记录私人正文。外部模型请求计数必须为 0，自动发送为 0。
5. 成功后再决定是否允许面向真实非测试账号的方案，须另行审查个人隐私、长期账号风险和商业许可证。

当前状态：`UPSTREAM_SOURCE_PINNED=PASS`；`PINNED_DEPS_AND_TYPECHECK=PASS`；`PORTABLE_APP_BUILT=PASS`；`NSIS_INSTALLER=FAIL_EPERM`（实际错误为 `EPERM`）；`TEST_ACCOUNT_PROCESS_AND_PROFILE_ISOLATION=PASS; WECHAT_INTERNAL_ID=USER_REPORTED`；`NATIVE_WCDB_PLUGIN=NOT_INSTALLED`；`REAL_WECHAT_READ=NOT_RUN`；`MODEL_CALLS=0`；`AUTO_SEND=0`。

## 2026-11-10 独立测试号已登录后的进程与启动器核验

用户明确回执：在独立测试微信中打开「文件传输助手」并发送一条测试消息。未独立读取微信内部账号 ID、消息正文或文件传输助手历史，仅对 Windows 进程与目录元数据进行现场核查。

- 5070TI-JAY 当前 Administrator 桌面（Session 1）同时存在两组 `Weixin.exe`：系统所有者分别为 `5070TI-JAY\\Administrator` 与 `5070TI-JAY\\cc-g52-pilot`；后者经真实 `runas /profile` 启动，不切换 Administrator 桌面。主微信进程未被关闭、注入或更改。
- 两用户各自的 `C:\\Users\\{user}\\AppData\\Roaming\\Tencent\\xwechat` 路径均存在，均不是目录重定向，ACL 仅向对应用户、SYSTEM 和 Administrators 提供访问；尚未读取其中任何消息或密钥。标准用户层隔离不等于完整虚拟机沙箱，后续未知 DLL 仍不得启用。
- WeFlow 便携版复制在 `C:\\Users\\cc-g52-pilot\\AppData\\Local\\JEV-WeFlow-Pilot\\WeFlow.exe`，SHA-256 `9198430D1DBDAD13D8BEF44CB1EFDD1752F63EC1A95BDD7A923D341BFD54400E`，同原固定源码产物一致。其 `resources\\app.asar` SHA-256 `BCD7242EEB14959AF6ACB35A9F4348CD5E36D213B0E40238EB4EF91143C49432`。
- 上游首次配置默认 `httpApiEnabled=false`、`wcdbLibPath=""`、`keyProviderPath=""`；当前测试用户 WeFlow 配置目录/文件尚不存在，故在启动前不假定它已获得任何数据库权限。
- 上游应用默认尝试检查更新。为满足本地测试边界，在上述测试目录新增 ASCII/CRLF、无 BOM 的 `launch-weflow-local.cmd`，对 Windows 用户身份做严格判定；只有 `5070TI-JAY\\cc-g52-pilot` 才可启动，且启动前设置 `AUTO_UPDATE_ENABLED=0`。SHA256 `EA69A8F657BB0BAF06C6332DEBBE49ED0A29063E80F35F4BAEF97BDD0A8662C8`。以 Administrator 故意运行的反例得到 `JEV_ERROR_WRONG_WINDOWS_USER`、退出码 23，WeFlow 未运行。
- 下一最小物理操作：由用户在当前 Administrator PowerShell7 通过 `runas /profile /user:5070TI-JAY\\cc-g52-pilot` 启动上述脚本，并在本地输入测试用户密码；助手无法也不应索取密码或绕过此 Windows 凭据确认。
- 真正的可信 WCDB 动态库仍未安装，也没有正式源码/版本/许可证的可信组件回执；因此启动 WeFlow 空插件模式属于 `UI_STARTUP_TEST`，绝不视为 `REAL_CHAT_READ_PASS`。上游公开说明其 DLL 可实现任意 SQL 与消息写操作，JEV API 白名单不能约束插件能力。未使用非官方补丁或绕过第三方许可/过期检测。

本阶段状态：`TEST_WINDOWS_PROCESS_OWNER=PASS`；`PROFILE_SEPARATION=PASS`；`LAUNCHER_ACCOUNT_GUARD=PASS`；`WEFLOW_UI_STARTUP=NOT_RUN`；`NATIVE_COMPONENT=NOT_INSTALLED`；`REAL_WECHAT_MESSAGE_READ=NOT_RUN`；`PROVIDER_CALL=0`；`AUTO_SEND=0`。

## 2026-11-10 WeFlow 首次启动后的安全与接口验收

本轮接到用户确认“测试号已登录并发送消息”“WeFlow 已打开”，在 5070TI-JAY 的 PowerShell 7.6.6 下仅进行了进程、用户配置、窗口公开接口与协议来源核查：

- Windows WMI 检出 5 个 `WeFlow.exe` 进程，**全部**由 `5070TI-JAY\\cc-g52-pilot` 拥有，路径均为 `C:\\Users\\cc-g52-pilot\\AppData\\Local\\JEV-WeFlow-Pilot\\WeFlow.exe`。测试微信与 Administrator 的微信进程仍分属不同 Windows 用户，未切换或关闭主桌面程序。
- 从 **测试用户自己的** `%APPDATA%\\weflow\\WeFlow-config.json` 中仅回读白名单配置项，不记录任何敏感值：`onboardingDone=false`、`httpApiEnabled=false`、`messagePushEnabled=false`、`logEnabled=false`、`launchAtStartup=false`；`wcdbLibPath`、`keyProviderPath`、`dbPath`、`decryptKey`、`myAccountId`、`httpApiToken`、`aiModelApiKey` 均为空，`accountConfigs` 条目数 0。**程序已启动 ≠ 解密数据库已成功 ≠ 聊天消息已读取。**
- 测试 Windows 微信现有窗口，通过系统 `UIAutomationClient` 只读查看其可访问性树：只有 **2 个**后代控件，当前没有公开精确的“文件传输助手”标签或 `JEV_TEST_01` 消息文本。因此在该版本/运行状态下，标准 Windows UI Automation **不能**作为已验证的消息读取源；没有尝试反射、HOOK、截图识别或注入。
- 本机端口检查：`5031`（WeFlow API）不在监听；`8766` 正被测试用户进程 `baidupinyin.exe` 绑定到 `0.0.0.0`，不是 JEV Relay；`18766` 空闲。将来真接 JEV 试点时应为 Relay 显式指定 `--port 18766`，Android 配对 URL 同步更改，**不得**结束或抢占输入法的端口。
- 对公开的第三方 `334456777/WeFlow` Rust CLI 做了**静态审查，不下载、不安装、不执行**。其源码 `crates/weflow-native/src/cipher_vfs.rs` 有 `SQLITE_OPEN_READONLY` 处理，但 Windows 密钥提取代码 `crates/weflow-native/src/windows_db_key/platform.rs` 调用 **`DebugActiveProcess(pid)`**。这是对微信进程使用 Windows 调试 API 的实质性风险路径，并非“纯粹读取数据库”或无侵入；4.2.0.13 实测兼容性未知。该 CLI 的 Unlicense 标称许可也不能自动解决其与上游 WeFlow CC BY-NC-SA 4.0 衍生关系可能涉及的分发合规问题。
- 不下载过期 DLL 的修改补丁，不关闭/重启微信，不读取进程内存/密钥，不加载未知原生组件。用户原授权仅限独立测试号、文件传输助手、本机只读；下一步若需以调试器附加测试微信获取 SQLCipher 密钥，须明确告诉用户这种新增账号风险，确认后还须验证仅针对 `cc-g52-pilot` 目标 PID，绝不面向 Administrator 主号。

**本轮验收层级**：`WEFLOW_PILOT_LAUNCH=PASS`、`WINDOWS_ACCOUNT_ISOLATION=PASS`、`FIRST_RUN_CONFIGURATION_AUDIT=PASS`、`DIRECT_WINDOWS_UIA_MESSAGE_READ=UNSUPPORTED_IN_TEST`、`RELAY_DEFAULT_PORT_CONFLICT=CONFIRMED`、`TRUSTED_WCDB_PLUGIN=ABSENT`、`REAL_FILE_TRANSFER_MESSAGE_READ=NOT_RUN`、`AI_MODEL_CALL=0`、`AUTO_SEND=0`。保持 PR #21 Draft，应用与微信均未发生数据库操作。

## 2026-11-10 授权后的数据库取钥预检与平台安全拦截

用户明确批准**一次**针对 `5070TI-JAY\\cc-g52-pilot` 测试微信进程的 Windows 调试器取钥尝试，并明确限制：不能访问 Administrator 主微信，先验证进程精确版本，版本不兼容则停止，不绕过检测、不修改客户端。

### 实际完成的低风险只读验证

- 5070TI 上的唯一已加载 `Weixin.dll` 的测试微信主进程为 **PID 82396**（核验时）；其 Windows 用户为 `cc-g52-pilot`，`Weixin.dll` 路径 `D:\\0_Softwares\\Weixin\\4.2.0.13\\Weixin.dll`，精确版本 **4.2.0.13**，字节数 **205943848**，SHA256 `AFE3D02C58BADA3C35009C0CD58EE1F57B2A00FE53237D0DCE649107FDDB9A20`。其他测试微信辅助进程未加载此 DLL，Administrator 的微信进程与其独立。
- 对该 DLL 的磁盘 PE `.text` 代码段采用第三方 `334456777/WeFlow` Rust v6.0.0 版本分支 `OLD` 特征进行**离线纯文件匹配**：`AMD64`、`.text` 段数 1、特征匹配数 **1**、`STATIC_PREFLIGHT_EXIT=0`，此检查未附加调试器、未读取进程内存、未访问消息密钥。**唯一静态匹配不是实际动态兼容性或成功取钥证据**。
- `334456777/WeFlow` 的正式 GitHub release `v6.0.0` Windows x64 ZIP，9797314 bytes，SHA256 `B1B0218FB0C1403E7144FB89D99AF77372EE888B2BD2304643B30A76E1455047`，与 GitHub 发布记录 digest 相符；ZIP 条目检查及解压成功。解压 CLI `weflow.exe`，25724416 bytes，SHA256 `54A47776A444FB53FD54C0F4234D7A83A7B99002A685831CEEBB12A119CE2CB9`，Windows Authenticode **NotSigned**。
- 已将此 CLI 仅作为预检依赖复制到测试用户目录 `C:\\Users\\cc-g52-pilot\\AppData\\Local\\JEV-WeFlow-Pilot\\cli-v6-test\\weflow.exe`，复制后 SHA 匹配；只执行 `--version` 与 `key db --help`，确认 `weflow 6.0.0`、显式 `--pid` 及 `--timeout` 选项，**没有执行 key db**。
- Rust v6.0.0 公共源码核对：`crates/weflow-native/src/windows_db_key/locator.rs` 对 4.2 使用 `OLD` 特征、`back=3`；`wxkey.rs` 的 Windows 路径从 `windows_db_key::Hook::start(pid)` 启动，`platform.rs` 会调用 `DebugActiveProcess(pid)`，可对微信调试寄存器/线程造成影响。没有验证实际上如何表现，不能保证免风控。
- 为确保**仅以测试标准用户运行**、只选择唯一测试微信 PID、不将原始数据库密钥打印到远程会话或写入配置，尝试创建一次性 PowerShell 包装脚本 `one-shot-keyprobe.ps1`；**平台 OpenAI 安全检查明确阻止该写入**。严格按照平台安全边界，没有换工具、接口、账号或让用户手工运行同样的取钥命令。现场再次核验：`one-shot-keyprobe.ps1` 不存在、`key-probe-result.txt` 不存在，未执行动态调试附加、未产生密钥。
- 核查时 `cc-g52-pilot` 与 Administrator 均有 5 个微信进程；WeFlow HTTP `5031` 仍关闭，JEV PR #21 与原 boundary 工作树均干净。用户此前批准的文件传输助手试点数据**仍未读取**。第三方 CLI 被分发为未签名工具，其 Fork 的 Unlicense 标称许可也不能视为消除了原上游 CC BY-NC-SA 4.0 商业兼容性问题。

### 验收判定与可用替代

`TEST_PID_OWNER=PASS`、`EXACT_DLL_VERSION_AND_HASH=PASS`、`STATIC_SIGNATURE_UNIQUE=PASS`、`PINNED_RELEASE_DIGEST=PASS`、`KEY_CAPTURE_RUN=BLOCKED_BY_PLATFORM_SAFETY`、`DEBUGGER_ATTACH=NOT_RUN`、`DECRYPT_KEY=NOT_ACQUIRED`、`TEST_FILE_TRANSFER_READ=NOT_RUN`、`WECHAT_ADMIN_ACCOUNT_UNTOUCHED=PASS`。

可继续的现有合法实现是 JEV Android 通知/输入法的显式会话确认链路，或用户**主动选择、复制/导出**自己的测试文字输入 JEV 合成/本地导入接口。不能把手动选取等价于无需用户操作的微信消息实时读取；不得通过新接口重新尝试同一被拦截的调试取钥。
