## 0.19 本地测试包验证范围

被测产物：`PureBrowser-v0.19-release.apk`（versionCode 71），本地签名 Release；本次保留 0.19 版本号，
包含投屏列表「复制」改为新标签「播放」的改动，尚未发布。

自动检查通过：742 项 JVM、69 项 Rust、127 项 Node、14 项发布工具测试；三种语言的 1007 条字符串一致。
Rust fmt/clippy、Android lint、Release 构建和签名校验通过；lint 无错误，保留 79 条警告和 3 条提示。

设备：隐藏窗口运行的 Pixel 7 ARM64 模拟器，Android 14 / API 34，WebView 113.0.5672.136；签名包安装成功。
`validation/cast-play-regression.py` 核对普通视频显示播放／下载、清单仅供投屏选择、下载确认取消后投屏选择不变，
播放打开前台新标签且保留完整查询参数、视频进入播放状态、系统返回原网页，以及返回后投屏资源行仍可选择。
详细结果及被测 APK 校验值保存在 `validation/results/cast-play.json`。

本次未验证实体 DLNA 接收器、鉴权媒体、所有音视频编码、无痕模式和全屏播放器内的投屏入口；
未覆盖其他 Android / WebView 版本、真机、平板和折叠屏。
