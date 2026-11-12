# Vivo A/B 微信通知归属与去重 — 2026-11-12

唯一仓 xiaoshutong-ai/jev-chat-jarvis，父 Draft PR #1，原 HEAD 9882fdd904e32275d338cfcbc9592d6959902f12，原 writer EmilioJI / 5070TI-JAY 的同一原工作树。

## 本次系统真实通知证据
用户确认通过 Note9 独立测试微信向 Vivo 微信 A / 分身 B 发送 JEV_TEST_A_1111 / JEV_TEST_B_1111。
vivo V2366HA / Android16 上只读 dumpsys notification 发现：
- com.tencent.mm user 0：1条活跃通知，when 2026-11-12 00:14:34.352 +08
- com.tencent.mm user 999：1条活跃通知，when 2026-11-12 00:14:44.625 +08
两条时间与用户发送时间吻合，系统层 A/B 用户来源隔离 PASS。但系统 dump 并未显示对应两条测试标记，不能直接认定是指定测试内容，也不能因为系统收到通知就宣称 JEV listener 消费成功。
vivo JEV 只安装在 user 0（通知监听、A11y、IME enabled），未安装在 user 999。user999 的 JEV 通知监听和 IME 均未开启，B 原生快捷填入不能宣称 PASS。JEV DiagnosticsStore 不记录通知正文或临时 inbox；当前未取得能证明 JEV 同时消费 A/B 的回执。无任何微信聊天树读取、私有数据库、对话正文导出、自动发送或付费模型请求。

## 真实跨用户去重缺陷及最小修复
WeChatNotificationListenerService.onNotificationPosted 原去重签名只有标题 + 正文，没有 Android UserHandle。若 A/B 在 2.5 秒内收到完全相同通知，一方会被误压制。
新增纯 Kotlin WeChatNotificationDedupKey.of(userLabel,title,text)，采用用户标签与长度前缀，确保同一用户相同通知仍去重，不同 Android 用户相同内容互不压制。仍为进程内去重，未新增磁盘记录/额外权限/模型请求。同步移动 userLabel 提取位置到去重之前。
定向 JVM 测试三项：同用户相同消息相同 key、跨 0/999 用户同内容不同 key、标题与正文分隔符不碰撞。先观察新增测试在旧源码因 helper 未定义 RED，补代码后 WeChatNotificationDedupKeyTest 3/3、NotificationClickTrackerTest 5/5、SessionBindingResolverTest 8/8，总计16/16 PASS。Windows PowerShell7/JDK17/缓存 Gradle8.13 离线 :app:assembleDebug BUILD SUCCESSFUL 23s。Debug APK SHA256 FE5A59BB08320E5EFC9EBB3E44CB81601700587AD0C687D35827EBF403C372E1，28,595,911 bytes。

## 验收边界
系统用户归属 = PASS；JEV 用户0监听权限 = PASS；JEV 对两条真实测试通知的接收 = NOT_VERIFIED；微信B的 JEV 直接一键填入 = NOT_RUN，B 目前仅可按产品定义走主动复制/手动粘贴。新源码增量须由独立 reviewer 审核及现有 self-hosted runner 精确 HEAD CI，未经同 HEAD 真机确认不能冒称 vivo B E2E PASS。原 PR 继续 Draft，不改 main、不公开发布、不擅自为分身 user999 开权限。