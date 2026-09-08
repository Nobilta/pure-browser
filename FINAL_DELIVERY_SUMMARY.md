# Pure 浏览器交付摘要

更新时间：2026-09-08。

签名安装包：[PureBrowser-v0.3.1-release.apk](./PureBrowser-v0.3.1-release.apk)。
支持 Android 10+ arm64-v8a，versionCode 4，大小 2,041,658 bytes（约 1.95 MiB）。SHA-256：
`05020b81fc04ac42b297ddfbd81b9094330926a379622fcbe81a84bced95c68d`。

新增首页快捷入口统一编辑窗口，可编辑标题、地址、图片/文字图标并在同一窗口删除；
图片选择取消、重复地址、损坏图片和保存失败都会保留原数据。新增链接/图片长按、后台延迟打开、系统分享、图片保存、标签搜索和批量关闭确认。
书签/历史改为数据库分页搜索，书签可直接编辑并防止重复网址覆盖；主菜单集中页面操作图标。
不增加最近关闭记录，重访页面继续使用历史记录。

移除缩略图重复编码和默认 Rust cache 库，修复 WebView 恢复标签及位图/监听器生命周期问题。
保留已有六类设置、三语言、网页视频增强全屏手势及旧 Provider 回退；视频继续由 WebView 解码。

本次正式启用「青叶 P」图标，移除网页悬浮倍速/投屏入口。标准视频确认交接后使用增强控件，
YouTube 与自定义容器全屏保留网页控件；投屏选择窗口可以在增强全屏内打开和取消。
当前视频加载地址可补充嗅探遗漏的媒体候选，签名参数保持完整；Blob 和无关资源不会通过此路径生成候选。
完整 [播放器与投屏方案](./design/video-playback-and-casting.md) 区分已实现行为与后续远端会话设计。

49 项 Rust、180 项 Android、25 项网页协议测试通过，414 个三语言资源键校验通过；
lint 为 0 errors / 3 warnings，签名 v2 及 API 29、API 34 覆盖安装通过。
当前签名产物的设备覆盖见回归报告；此前首页、浏览流程、设置和下载的通过记录保留为历史结果。
最终包通过 Android 10/14 的标准视频、自定义网页播放器和跨域回归，以及 Android 14 Blob 全流程。
本轮没有完整性能基准，真实 YouTube 在线页面、实体 DLNA、第三方 DRM 和低端真机仍需验证。

构建：`./build-and-test.sh`。安装：`./install_and_test.sh`。
功能与依赖见 [README](./README.md)，实际证据与未验证范围见
[回归报告](./EMULATOR_TEST_REPORT.md)。

源码分支为 `feat/browser-productivity-20260907`；修改前备份为
`backup/pre-home-shortcut-editor-20260907`（`bf75a92`，0.3.0）。视频修改前另有检查点
`checkpoint/pre-video-redesign-20260908`（`04f6628`）。当前图标和旧版对比预览见
[`design/app-icon/README.md`](./design/app-icon/README.md)。签名凭据、APK、日志和截图不纳入 Git。
