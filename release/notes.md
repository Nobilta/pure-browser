## 0.14.1 验证范围

被测产物：`PureBrowser-v0.14.1-release.apk`（versionCode 42），本地签名 Release 候选。
设备：`PureBrowser_API37`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。
本次从重构后的工作区构建，安装包与下列设备记录的 SHA-256 一致；包大小、校验和与签名信息见生成的 `outputs/release/` 附件。

- `./build-and-test.sh --release` 通过：Android/JVM 625 项、Node 93 项、Rust 63 项、Python 发布工具 14 项；三语言资源 787 项一致。
- Rust fmt/clippy、R8、v2 单签名、16 KiB zipalign、包名及 ARM64 ABI 校验通过；Android lint 为 0 error、47 warning、1 hint。
- 同一个 APK 完成 7 个设备阶段：`private-lifecycle`、`blob-download`、`resident`、`navigation-recovery`、`download`、`settings-back`、`settings`。
  覆盖无痕生命周期、后台页面恢复、Blob 与 HTTP 下载、失败重试和停止后的 POST、设置选择器、重建、手势、横竖屏与持久化。
- 本地证据位于 `validation/results/`：`api37-refactor-0141-suite.json` 的前三个通过阶段、
  `api37-refactor-0141-finish-suite.json` 的导航与下载通过阶段、`api37-refactor-0141-settings-final-suite.json` 的两个设置阶段。
  初次回归暴露了测试脚本对外部链接复用标签的旧假设、重建等待过短及阶段超时；修正脚本后对应阶段通过，失败记录仍保留。

未覆盖：其他 Android 版本与真机、完整功能矩阵、人工 TalkBack 播报、真实线上更新下载安装链路。
本次尚未推送，未运行新提交的远端 CI，也未创建或发布 GitHub Release。
