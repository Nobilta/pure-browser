# Pure 浏览器交付摘要

更新时间：2026-09-07。

当前签名安装包为 [PureBrowser-v0.2.0-release.apk](./PureBrowser-v0.2.0-release.apk)，
支持 Android 10+ 的 arm64-v8a 设备，大小 2,131,247 bytes（约 2.03 MiB）。
SHA-256：`591cc04ace4138ed8daa182e22189453891dbb28bf2747ae8abc2b3badff2908`。
签名 v2 验证及 API 29、API 34 安装通过。

设置已按六类重新组织，选择项使用弹层，宽屏同时显示分类与详情。视频增加原生全屏控件、
亮度/音量/进度手势、临时倍速、锁定和方向恢复，解码仍由网站和 WebView 完成。
支持简体中文、繁体中文和英文；旧 WebView 的跨域播放器保留网站自身控件。

49 项 Rust、150 项 Android、15 项网页协议测试全部通过，392 个三语言资源键校验通过；
lint 为 0 errors / 3 warnings。两套系统完成设置、视频、下载及基础浏览回归。
同一 API 34 模拟器的 7 次进程冷启动中位数为原版 586 ms、新版 556 ms，未见明显退化；
该对照不代表低端真机帧率、耗电或所有 WebView 兼容性。

安装：`./install_and_test.sh`。构建：`./build-and-test.sh`。
完整功能、依赖和维护入口见 [README](./README.md)；设备证据、性能方法和未验证范围见
[模拟器回归报告](./EMULATOR_TEST_REPORT.md)。

源码位于 `feat/android10-settings-video-20260906`，原始回滚点为
`backup/pre-android10-settings-video-20260906`（`b838e47`）。签名凭据、APK、日志和截图均不纳入 Git。
