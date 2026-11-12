# 小书童·知言 — 发布准备与合规现况（2026-11-11）

**状态：SOURCE_ONLY / 内测 Debug；不是商业发行批准、Google Play 上架批准或运行时正式发布。**

唯一仓 `xiaoshutong-ai/jev-chat-jarvis`，父 Draft [PR #1](https://github.com/xiaoshutong-ai/jev-chat-jarvis/pull/1)，本记录起点为源码 `768963c736d79bbfd039ba751c089e3d7156c26b`。原唯一 writer `EmilioJI` / 5070TI-JAY / `D:\GitHubWork\jev-session-binding-pr21-20261110`。本次许可证/隐私信息调整不触发模型计费、凭据迁移、自动微信操作或生产部署。

## 已有证据与边界

| 事项 | 2026-11-11 实况 |
| --- | --- |
| 原仓许可证 | 上游 `jev-chat/jev-chat-jarvis` 的 MIT License 已独立回读；当前仓根目录 `LICENSE` 和 `NOTICE` 都存在，且与上游 MIT 文本一致。此前独立审核 `OPTIONAL: missing LICENSE/NOTICE` 是事实性误判，不据此新增许可证门禁 |
| 源码中保留声明 | 当前 `app/src/main/assets/LICENSE` 与 `NOTICE` 从根文件逐字节复制，用于在 App「设置 → 关于 → 查看开源许可」完整展示。正式向外分发 APK 的发布材料仍应附带同两文件，不以手机按钮取代合法的版权声明 |
| WeFlow | 其上游 `hicccc77/WeFlow` 明确使用 CC BY-NC-SA 4.0 非商业许可证；本 JEV Android APK 无 WeFlow 依赖、未打包、未自动安装。Windows `desktop_bridge` 仅可选兼容外部安装的本机 HTTP API，已停止作为日常产品推进；若后续把 WeFlow 本体打包/商用，须另行确认授权及分发条件 |
| ML Kit | Android 依赖 `com.google.mlkit:text-recognition-chinese:16.0.1`，本地图像识字内容与结果在端侧处理；Google 官方条款说明 SDK 仍可能发送性能/使用指标、接收修复与兼容性数据。隐私政策必须分别披露内容处理和 SDK telemetry，不声称完全无 Google 网络活动。依据 https://developers.google.com/ml-kit/terms |
| Accessibility | `app/src/main/res/xml/config_accessibility.xml` 声明 `isAccessibilityTool=false`，packageNames 仅 QQ/飞书/X，**不含微信**；微信采用用户主动剪贴板、可选通知、用户选择截图 OCR；IME 需点选才填入、不自动发送 |
| Google Play 规范 | 若选择 Google Play 渠道，非残障辅助类的 AccessibilityService 需要独立醒目披露/肯定同意、Play Console 权限声明及合规演示视频。官方 https://support.google.com/googleplay/android-developer/answer/10964491 。该渠道验收与自行发 APK 的部署路线分开，不将未选渠道门禁强加给内测 |
| 数据去向 | 用户自己配置的判断/回复/视觉服务商可能收到相应请求内容；手动 20 条上下文在内存中限时、风格 Prompt 在本机持久保存且生成/排序时会交给对应服务。服务商处理、保留、训练策略以各自服务条款及账号设置为准 |
| 隐私材料 | `docs/PRIVACY_POLICY_DRAFT.md` 已更新 2026-11-11 源码行为及 ML Kit 指标说明，但仍是 **草稿**。Publisher、隐私联系邮箱、稳定 HTTPS 公共 URL 等明确占位符没有值，禁止冒充发布完成 |
| Android 构建 | 试点 Debug 包名 `com.jev.probe.guofeng`，版本 `1.3-guofeng-debug` / code 4，和当前主项目 release `com.jev.probe` 不是一个 Android package；因此正式发行版**不是**直接覆盖安装 Note9 的 Debug 包。release keystore 配置由仓外属性文件提供，未在本阶段验证正式签名 |
| SOURCE_ONLY / 真机 | 父 PR #1 前一源码 HEAD `768963c` 有砚宝 `xst-yan` 正式独立 SOURCE_HIGH APPROVED `5858484276`，Note9 安装包 SHA `16CA25EA20AB393F795F2DB11CA35377228FFC9D9B2E4E789DBDC5C8DC20946A`、安全存储 dummy 真机 E2E PASS。文档和关于页新增后仅重测受影响的许可 UI/编译，不机械重跑前述业务路径 |
| GitHub CI | 当前 Actions `workflow_dispatch` 曾返回 HTTP 422 「Actions has been disabled for this repository」；仓库权限读出 `enabled=true`，组织策略读接口 403，无新 Run，**CI=NOT_RUN**。已有 JEV WSL2 self-hosted runner 在线，网络禁用 Docker 本地的 Android 测试/构建 PASS 不等于 GitHub CI。不得另建 runner 或换账号/接口绕过禁令 |

## 下一必需动作（按真实发布目标选择，不附加理论 Gate）

**当前内部手机试用**：以已安装 Debug 包继续功能体验即可。修改了源码的版本若需安装，先通过该增量对应的编译/签名与设备权限核实；无关旧场景不重复测试。

**父 PR #1 主线合并**：需恢复同 HEAD 官方 Actions 可运行状态，执行现有必需工作流并回读 runner 名称、job、SHA、真实终态；然后按治理合并，不能以 SOURCE_ONLY Review 或本地容器测试冒充 Actions SUCCESS。GitHub 管理员须核对组织/仓库 Actions 策略与 422 拒绝条件。仓库 Actions 常规设置允许不等于组织层策略一定放行。

**首次公开/商业发行**：另需确定发行主体及隐私联系方式、发布一份稳定 HTTPS 隐私政策（应用内可访问）、明确发行渠道（直接 APK 还是 Google Play）、检查该渠道的权限声明、确定正式包名和签名/升级策略，并随分发材料附 LICENSE/NOTICE。若通过 Google Play，按其规则准备数据安全表单和 Accessibility 演示视频；对于已验证且没有变更的 Note9 场景，不为了“更保险”重跑全矩阵。

## 已识别的不一致

- 旧 `docs/PLAY_RELEASE_COMPLIANCE_CHECKLIST.md` 的 2026-09-22 历史条目曾说 `isAccessibilityTool=true` 可用于兼容候选、微信 Accessibility 读取是正式必要路径。**这已被后来 2026-09-23 的安全架构迁移覆盖**，不得继续依据旧文本判定当前实现或生产许可。
- 旧审查曾误称仓库没有 LICENSE/NOTICE；本次已在 GitHub 源仓和当前 worktree 分别确认其存在。缺的是**移动应用内显示与发布素材随附**，不是源仓本身没有许可证。
- 没有证据证明 WeFlow、模型提供商、ML Kit 的全部商用条件“自动放行”。逐项区分源码 MIT 权利、外部未打包工具以及 SDK/服务商自己的条款。

## 官方一手资料

- 上游 Jev MIT：https://github.com/jev-chat/jev-chat-jarvis/blob/main/LICENSE
- WeFlow CC BY-NC-SA 4.0：https://github.com/hicccc77/WeFlow/blob/main/LICENSE
- ML Kit 服务条款：https://developers.google.com/ml-kit/terms
- Google Play Accessibility：https://support.google.com/googleplay/android-developer/answer/10964491
