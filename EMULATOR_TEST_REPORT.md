# 0.7.0 验证报告

日期：2026-09-11。回归使用签名 Release，模拟器 UI 操作串行执行。
安装包哈希用于区分交付验证和开发阶段记录；旧包的通过项不计入本版设备覆盖。

## 安装包与自动检查

- `PureBrowser-v0.7.0-release.apk`，versionCode 11，包名 `com.mybrowser`。
- Android 10+（minSdk 29）、targetSdk 37、arm64-v8a；4,683,015 bytes，约 4.47 MiB。
- SHA-256：`6941e47a3a1b5177bc0cf1e1033b8ee99d941b5ab81fd0701dccf05120e8f072`。
- APK v2 签名、16 KiB zipalign、打包后的播放器脚本与源码逐字节一致检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，延续旧版签名。
- 321 项 Android/Robolectric 测试，0 failures、0 errors、0 skipped；包含真实 host JNI 检查。
- 58 项 Rust、51 项 Node 测试及 619 项三语言资源检查通过；Rust fmt/clippy、R8 和 Release 构建通过。
- lint 为 0 errors、11 warnings、2 hints，主要涉及兼容性提示、工具版本、Compose 约定、英文复数、
  arm64 交付范围及代码简化建议；没有将其表述为零警告。

最终构建为 `build6`。它在 `build5` 已通过的逻辑上，清除三种语言中残留的离线文章提示；随后重新执行完整构建检查。
原始依据：[构建结果](validation/results/release-0.7.0/build6.json)、
[构建日志](validation/results/release-0.7.0/build6.log)、
[交付清单](validation/results/release-0.7.0/delivery.json)、
[签名](validation/results/release-0.7.0/signature.txt)。APK 和生成的验证记录不提交 Git。

## 本版重点

### 下载交互

完成行本身是打开入口，浏览器发送不指定 MIME 的 `ACTION_VIEW`、可读 `content:` URI 和临时读取授权，
由 Android 根据内容提供方解析文件类型。没有独立“打开/安装”按钮，也没有浏览器自己的 APK 安装或来源授权分支。

下载专项核对未知总长时已下载字节的变化、拒绝通知权限后仍完成下载、最终文件 SHA-256，
并通过实际触摸进入系统图片查看器和安装器。缺失文件不会启动外部 Activity；Toast 已通过截图复核。
安装器的来源授权由 Android 设置处理。完成通知可在进程已结束时打开浏览器并定位到对应下载行。

截图中的系统安装授权不是浏览器的二次确认。部分进度截图抓取时下载已经完成，
进度变化以执行时连续读取的无障碍文字和字节校验为依据，不能把完成截图当作进行中进度的证明。

### 内嵌与全屏播放器

内嵌专项覆盖标准、自定义容器、Blob、160×90 小视频和跨域 frame，核对真实播放状态、
原节点/来源保留、网站控制层隔离、倍速、跳转、静音、显式恢复、滚动、尺寸变化、弹窗遮挡、
样式丢失、视频替换、全屏进出和设置即时开关。CSP 或 Provider 能力不足时保留网页播放器。

全屏与系统媒体的独立阶段继续检查标准/自定义/Blob/跨域/CSP 路径、手势、锁定、旋转、
画中画、系统暂停和显式后台播放。具体脚本与边界见 [播放器验收说明](design/enhanced-player-validation.md)。

### 功能精简与私密生命周期

阅读/离线文章、Google Cast、本地整体备份恢复及双窗口的界面、实现、专用依赖和回归阶段已删除。
普通标签、书签 HTML、打印/PDF、DLNA、脚本及系统媒体继续保留。

同时修复真实的私密会话隔离问题：WebView 145 拒绝删除当前进程已加载的 Profile，
旧实现复用固定名称会使下次私密会话重新读到上次 Cookie/localStorage。
现在每次会话使用新名称，退出时清理数据并先退役名称；重启时清理专用前缀下的遗留 Profile。
新增 5 项单元检查覆盖删除失败、配置重建、重启清理及连续会话名称不复用，模拟器另核对真实站点数据。

## 模拟器结果

交付 APK 的 Android 17 套件正在执行，Android 10 升级及兼容性验证尚未完成。
当前逐阶段记录见 [Android 17 套件](validation/results/api37-release-070-delivery-suite.json)。
此段将在全部选定阶段结束后按实际结果更新。

## 失败与重跑记录

- 私密 Profile 删除失败属于应用缺陷，已修复；原始复现见
  `validation/results/release-0.7.0/private-profile-before-fix.log`。
- 默认浏览器入口曾在系统角色弹窗尚未出现时立即断言失败；后续实际界面正常，脚本改为有界等待系统窗口。
- Android 17 的系统媒体脚本曾在全屏转场尚未稳定时按 Home，并只等待 1 秒。
  同一 APK 在播放稳定后可以进入 PiP；脚本改为等待实际全屏/横屏状态，再有界等待系统 pinned 模式，仍只发送一次 Home。
  原始失败与诊断保存在 `validation/results/release-0.7.0/media-home-during-fullscreen-transition/`，重跑由 suite 的 `priorAttempts` 保留。
- 系统安装器、Toast 与权限弹窗的无障碍暴露和动画时序依 Android 版本而异。
  使用窗口/实际 Activity/文件字节和截图交叉核对，不把缺少无障碍 Toast 节点写成已自动识别提示。

## 覆盖边界与复现

复现入口为 [测试指南](TESTING_GUIDE.md)，套件 JSON 保存实际选定阶段、包哈希、WebView 版本、退出码和日志。
只能在 APK、AVD、阶段选择完全相同时使用 `--resume`；失败记录不能用旧包结果替换。

本版没有使用实体 DLNA 接收器、真实账号、低端真机，也未逐站测试全部生产视频服务、DRM、
HLS/DASH 直播、MSE 或鉴权 CDN。静态 Blob 夹具不能代表全部流媒体加载实现。
无法访问的视频节点、封闭 Shadow DOM、Canvas 或无法隔离的布局，恢复网页播放器属于预期行为。
