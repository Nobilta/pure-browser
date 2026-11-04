## 0.19 本地测试包验证范围

被测产物：`PureBrowser-v0.19-release.apk`（versionCode 71），本地签名 Release，尚未发布。
本次修复媒体回退跳转的来源策略、播放转附件下载的来源传递，以及书签快速向上跨行拖拽。

自动检查通过：772 项 JVM、69 项 Rust、129 项 Node、14 项发布工具测试；三种语言的 1013 条字符串一致。
Rust fmt/clippy、Android lint、R8、Release 构建、签名及 zipalign 校验通过；lint 无错误，83 条警告、3 条提示。
构建日志：`validation/results/media-fixes-release-build.log`。

最终签名包在 ARM64 模拟器 emulator-5580、Android 14 / API 34、WebView 113.0.5672.136 上通过：

- 媒体回退 5 项：no-referrer／origin 跳转与 HTML 刷新、直接附件、显式无来源附件、重定向附件的下载来源。
- 书签 9 项：同级文件夹排序、多选保留、拖动取消与视口恢复、一次位移向上跨多行、50 条后的边缘分页、75 条完整持久排序、搜索及缓存图标。
- 媒体来源 8 个场景：普通播放、重定向、origin／no-referrer、跨主机 Cookie／Set-Cookie、1 GiB 和接近 2 GiB 的稀疏 MP4、无痕播放；检查真实播放、拖动／Range、刷新、返回及离开页面后的来源清理。

详细结果为 `validation/results/media-fallback-emulator-5580.json`、`bookmark-drag-emulator-5580.json`、
`cast-referrer-emulator-5580.json`，三份记录的 APK 校验值均与最终交付包一致。
初轮模拟器暴露的 null 请求头读取崩溃已修复，新增 JVM 用例并通过最终回退回归；这不代表此前其他 JNI 崩溃已修复。

未验证真机、平板、折叠屏、新版 WebView 和多 Profile 无痕隔离；本轮未重跑广告首帧、实体投屏、
未知长度／压缩响应及超过 2 GiB 的流接口兼容性。大型夹具验证定位和解码，不代表持续大码率传输。
