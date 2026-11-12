# 小书童·知言：独立 Android 应用身份与签名

日期：2026-11-12。唯一仓库：xiaoshutong-ai/jev-chat-jarvis。唯一 writer EmilioJI，原工作树不变。从已合并的父 PR #1 merge commit 621e19c75741b8287504450cd17458b3209984bc 建立新分支。

## 确认的发行身份

- 正式 applicationId：com.xiaoshutong.zhiyan。
- Release 版本起点：1.0.0，versionCode 1。
- Debug 独立 applicationId：com.xiaoshutong.zhiyan.guofeng。
- Kotlin namespace：com.jev.probe 仍保留，不需要重命名所有源码或组件。
- Compile/target API：36；release 原生库为 arm64-v8a。
- Release 签名：仓外独立密钥，只通过 JEV_KEYSTORE_PROPS 环境变量指定四项签名配置；不再自动读取上游旧版 H: 盘路径。无明确配置则产物 unsigned，不可安装分发。

新包名与 com.jev.probe 和 com.jev.probe.guofeng 均不同，可在 Android 同时安装。旧版 AndroidKeyStore API Key、联系人笔记和历史数据不会因新包名自动继承。

## 旧版保留与清理条件

1. 不删除 vivo 现有 com.jev.probe.guofeng，直到新独立正式包完成内部安装、开启必要权限、配置模型服务且获得用户对数据保留方案的确认。旧版本中存在已验证的 API Key 配置与私有数据；未经审慎迁移不能清除。
2. 长期私钥只在 5070TI 仓外受限目录创建，使用高熵密码和 Windows ACL，不提交到 GitHub 或日志。仅在仓库记录签名证书的公开 SHA256。首次公开发行之前建立可恢复的异地/离线私钥备份，避免无法升级。
3. 禁止批量删除整个 D 盘、格式化系统盘或覆盖未经认领的目录；删除目标必须精确到 APK 包名或已识别旧缓存产物，且不得删除主仓库审核与历史证据。
4. 暂不部署、上架、对外发行；不修改上游公共镜像 main。待确认发行主体、隐私联系人、公开 HTTPS 隐私政策和渠道后再行发布。

依据 Android 官方：
https://developer.android.com/build/configure-app-module
https://developer.android.com/studio/publish/app-signing