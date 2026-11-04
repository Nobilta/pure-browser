## 0.19.1 本地测试包验证范围

被测产物：`PureBrowser-v0.19.1-release.apk`（versionCode 72），本地签名 Release，尚未发布。

- `./build-and-test.sh --release` 通过：780 项 JVM、129 项 JavaScript、69 项 Rust、14 项发布工具测试；三语资源、Rust fmt/clippy、Android Lint、R8、签名和 zipalign 检查通过。
- Android 14 / API 34 / WebView 113.0.5672.136 / ARM64（`emulator-5580`）：10 个媒体响应交接场景、9 个来源与播放场景、4 类媒体错误、9 项书签手势检查通过。覆盖一次性附件的单次 GET 与文件哈希、gzip/未知长度、HTTP 登录提示、来源策略、跨站 Cookie、无痕、1 GiB/2 GiB 边界及超过边界的拖动与刷新。
- Android 14 和 Android 17 / API 37 / WebView 145.0.7632.218 / ARM64（`emulator-5582`）：地址栏、标签驻留、无痕生命周期、导航恢复、常规下载、开发工具、设置、用户脚本、过滤订阅、手动元素屏蔽均通过。API 37 另通过连续两份 6 MiB Blob 文件的哈希校验、媒体候选切换及超过 2 GiB 的播放回归。

最终设备回归记录均绑定上述 APK 的 SHA-256，详细日志与修复前复现在 `validation/results`。初轮系统桌面/System UI 无响应、异步取样与裁切控件点击造成的失败保留记录，环境和回归夹具修正后已复测。

WebView 113 不提供 Blob 下载所需的二进制消息通道，该项未通过；WebView 145 已通过。尚未验证实体设备、实体 DLNA 接收器、HLS/DASH 分片播放、真实网站 HTTP 凭据登录成功及其他 Android/WebView 组合。大文件使用稀疏 MP4 验证定位和解码，不代表持续大码率传输。
