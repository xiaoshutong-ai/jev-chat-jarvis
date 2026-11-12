# 手机文案与电脑端范围收敛（2026-11-11）

## 当前授权、设计与实施计划

用户要求：手机文案去掉无用暴露，必须告知的内容易懂；电脑端只在有明确增量价值时保留。
基线：PR #21 / 5bc6dc9087f03ddcfee80f78475e977ff7175a59。唯一 writer EmilioJI，在 5070TI-JAY 的 D:\GitHubWork\jev-session-binding-pr21-20261110，detached checkout 对应 feature/session-binding-probe-20261110；恢复时 clean，远端 SHA 相同。原 main 的 AGENTS.md 已实时读取；本工作树不包含该文件，不伪称读到。

设计：继续手机独立使用。直接改现有 Kotlin 文案，不重做 UI 框架、模型调用或存储。对已经授权的模式保持准确披露，不把通知摘要称为完整历史，不把模型置信分数称为正确概率，不暗示自动识别当前联系人。系统权限、数据传给哪些模型服务、可能收费、导入保存 60 分钟、删除不可恢复、由用户决定发送必须保留。模型名、服务地址、API Key 属于当前必需配置；协议和程序内部名从常规说明去除。

方案比较：仅换几个标题不能解决误导性的权限/历史说明；全面重做设置导航会扩大范围；选择沿用布局、完整梳理相关说明、停止推广未证实有增量价值的电脑桥。

电脑端事实：desktop_bridge/README.md 记载当前产品路径仍需用户手动复制、选择收发方向、填写会话和配对；可靠真实微信完整历史尚未验收。手机已有同类手动导入与候选生成功能。结论：停止将现有电脑桥作为独立日常产品推进，不发布电脑安装包，也不新增采集/配对能力。手机不再向未配置用户展示电脑配对卡片；已启用或已有配置的用户仍能查看、关闭连接，不暗中改其配置。实验源码保留作历史记录，不能把源码保留称为电脑端交付。

潜在重启条件：实际证明授权文件批量导入能显著省步骤，或已验证的本地模型能在不牺牲效果时降低成本；目前均不是已交付功能，不据假设启动新工程。

## 任务与验证

- [x] 实读当前 PR/main authority、唯一工作树、可见文案、电脑桥文档和 CI；目标 commit 无 check runs，现有模拟器在线，Note9 当前未连接。
- [x] 先添加 tools/test_mobile_copy_contract.py；原源码 6 项断言全部 FAIL、0 error，确认覆盖真实缺口。
- [x] 修改 MainActivity、SettingsActivity、ManualContextActivity、OverlayController、输入法和主动复制状态文案，保留同一逻辑与操作授权。
- [x] 未配置电脑桥不展示卡片，原有已配置用户保留管理入口和所有保存逻辑。
- [x] 文案契约测试转绿；定向编译、相关 Kotlin 回归，复用既有 Gradle8.13/SDK 缓存。固定 Wrapper8.11.1 的正式 CI 无证据不冒称通过。
- [x] 原 x86_64 模拟器核对主页、设置、补充对话页布局及电脑卡片的默认隐藏；不触及真实微信/Key/付费模型。
- [ ] 汇总提交、构建 arm64 候选并记录准确 hash/证书；独立评审与真机、发布层级分别记录。

## 不改变的边界

不修改模型 Prompt、供应商端点、Key、通知/无障碍授权、缓存、最近20条解析、标准输入法填入的会话保护；不自动发送、不读取微信内部数据。停止电脑产品推广不是删除用户文件或撤销其已有权限。

没有合并、正式 CI、独立 Review 或 Note9 新版真机证据时，只交付所完成的源码/构建/模拟器层级。

## 结果与验证（本轮实际执行）

生产改动限 8 个 Kotlin 页面/显示相关文件；另修改 1 个现有测试的文案断言、桌面桥 README 的停止推广说明，新增 7 项源文案契约检查。逻辑变化限于旧电脑配置卡片对未配置用户隐藏、去掉模型分数的 UI 百分比、空知识库提示不显示；数据采集、权限、存储、匹配、缓存、模型请求、发送权不扩大。

- 文案测试：首轮 6 项在旧源码全部 FAIL/0 errors；补充备注页披露断言时 7 项中仅该项 FAIL；修改后 **7/7 PASS**。
- 相邻回归：`ManualConversationContextTest` **6/6 PASS，0 failures/errors**。只更新返回 note 的显示文字和相应断言，匹配与 TTL 逻辑未改。
- 构建环境：Windows 5070TI-JAY、PowerShell 7.6.6、JDK17、缓存 Gradle **8.13**、Android SDK36；全部 `--offline`。没有下载新工具或镜像，没有改 CI workflow；不冒称 Wrapper8.11.1 的正式 CI。
- x86_64 Debug 构建/安装 **PASS**，SHA256 `AE1065C8C19F56B3255041037E93FDC0E4AD54E03D6818B6B967F17FA5DEA43F`。现有 Android16 模拟器 320×640 从正常首页进入补充对话/联系人备注/设置，五项 UI 检查 PASS；现有电脑配置为 false，卡片未出现，保存全部设置按钮仍可见。
- UI 初次脚本滑动点击区域误触微信通知披露，未点击同意/系统设置；改为外侧滚动区域后同一构建完整通过。属于测试交互问题，未改业务源码来迎合脚本。
- 实际打开检查了主页与补充对话页截图；源证据保存在 `D:\DevCaches\jev-session-binding-check-20261110\mobile-copy-ui-20261111`，含 `result.json`、`home.png`、`context.png`。UI 检查未触发生成/连通测试按钮，未使用真实聊天或付费模型。
- 最终 arm64-v8a Debug 构建 **PASS**，28,577,892 bytes，SHA256 `BCB3A95DADD3ED22C581EB174ADAAA2E6F373D83BA6B123B6813A6848EAF2FE1`；v2 签名有效，证书 SHA256 `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f` 与既有试点版一致。
- `git diff --exit-code` 验证模型实现目录、Prefs、Manifest 和 ClipboardAnalysisCache 与基线未变。电脑接收/自动分析开关与保存逻辑未改，老配置不会静默删除。
- Note9 当前 ADB 不在线；本轮未安装手机，未把模拟器或构建通过当作 Note9 PASS。候选不属于公开正式发布。

## 电脑端价值结论

| 路径 | 当前可证实的增量 | 决策 |
| --- | --- | --- |
| 复制文字到 Windows 再配对传手机 | 手机可直接完成，反而多会话标注、配对与常开电脑 | 当前形态停止推广和新增开发 |
| 可靠自动完整历史 | 当前没有真实来源/联系人身份的闭环证据 | 不把未实现能力列为卖点 |
| 大批授权记录导入、编辑 | 可能适合桌面，但未证实需求与明显省时 | 只保留未来假设，不开新工程 |
| 本地模型替代云模型 | 可能降低高频成本，但未测效果/运维成本 | 不因此增加电脑运行依赖 |

最终范围：手机是唯一日常产品。电脑桥源码作为历史实验保留，不发布电脑安装包；未配置用户不见电脑入口，已配置用户仍能管理/关闭连接。不是删除用户文件、密钥、历史记录或已经授予的权限。

当前证据层级：SOURCE_COPY=IMPLEMENTED、COPY_CONTRACT=PASS_7/7、TARGETED_JUNIT=PASS_6/6、EMULATOR_UI=PASS、ARM64_BUILD_SIGNATURE=PASS；NEW_HEAD_INDEPENDENT_REVIEW=NOT_RUN、PINNED_SELF_HOSTED_CI=NOT_RUN、NOTE9_INSTALL=NOT_RUN、PUBLIC_RELEASE=NOT_RUN。

## 2026-11-11 17:xx 北京时间：砚宝审查收口与真机复验

- `xst-yan` 对 HEAD `9868119bec31d2abb26b1320f371730884e973ae` 的新增手机文案 SOURCE_MEDIUM 审核回执已收到：**Critical 0 / MUST_FIX 0 / SHOULD_FIX 1 / OPTIONAL 2**。其正式 GitHub Review 尚未提交；以前的 Review 仅绑定旧 HEAD。这是源码审查意见，不是运行时验收。
- S1 修复：自定义判断服务地址的说明从「通常以 https:// 开头」改为强制性的「必须以 https:// 开头（仅本机调试地址例外），并填写完整路径」，与既有服务端地址限制一致，未更改实际 URL 校验。
- O1 修复：历史电脑连接入口显示条件也包含仅保存了配对 token 的旧用户，以便其管理/关闭；没有配置电脑的用户仍不会看到电脑入口。既有桌面连接逻辑、Prefs 数据及权限没有改动。
- O2 修复：无障碍说明中重复的截图主动触发句去重；保留实际权限范围、默认手机端识字/可选云端识字、文本发送模型、不会自动发送微信消息等必要披露。
- TDD：先在原代码上新增 3 条单元文案契约，观测 **10 tests / failures=3**，再做上述三处定向修复，最终 **10/10 PASS**。构建：5070TI-JAY / PowerShell 7.6.6 / JDK17 / 复用本地 Gradle 8.13，离线 `:app:assembleDebug` **BUILD SUCCESSFUL**；arm64 Debug APK **28,985,794 bytes**、SHA-256 `D11691385F4456291C7B8BF07D5BAD5418EA0B46747191C479D5ADB78A19A2AA`，v2 签名有效、签名证书与已安装 Note9 同一 digest `e92e4d40ce7694bce8d54dec2eb7e23afcd1a871891fe11e50cfec5ce57afa3f`。
- Note9 已在上一 HEAD `9868119` 上完成实际安装哈希核对与首页、补充对话、设置页的交互检查（从 JEV 首页正常进入，读取本应用 UI，未调用模型或采集微信消息）；新 APK 安装及新 HEAD 独立增量审查为单独验收边界。
- Note9 USB：Windows 同时识别 Redmi Note 9 MTP、ADB 和 USB Composite，16:49:22 最后到达记录，此轮短时间 ADB 连接连续正常。MIUI 的 `uiautomator dump` 会提示缺少 `/data/system/theme_config/theme_compatibility.xml`，但实际 UI dump 返回成功、JEV 自身 Activity 正常；不将该警告冒充 USB 断连或应用崩溃。未改 USB 驱动/Windows 电源配置。
