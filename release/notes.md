## 0.14.1 验证范围

被测产物：`PureBrowser-v0.14.1-release.apk`（versionCode 42），本地签名 Release 候选。
设备：`PureBrowser_API37`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。
本次从重构后的工作区构建，安装包与下列设备记录的 SHA-256 一致；包大小、校验和与签名信息见生成的 `outputs/release/` 附件。

- `./build-and-test.sh --release` 通过：Android/JVM 625 项、Node 97 项、Rust 63 项、Python 发布工具 14 项；三语言资源 787 项一致。
- Rust fmt/clippy、R8、v2 单签名、16 KiB zipalign、包名及 ARM64 ABI 校验通过；Android lint 为 0 error、47 warning、1 hint。
- 同一个 APK 完成设备阶段 `block-element`：验证真实 WebView 中元素选择器的触摸选择、预览、规则写入、重新加载生效，以及长按资源规则流程。
- 松手坐标变化但没有新移动事件的边界由 Node 回归覆盖；设备阶段未单独注入这种事件序列。
- 本地证据位于 `validation/results/api37-picker-release-coordinates-suite.json`，设备为 `PureBrowser_API37`（Android 17 / API 37，WebView 145.0.7632.218）。

未覆盖：其他 Android 版本与真机、完整功能矩阵、鼠标输入的设备验证、人工 TalkBack 播报、真实线上更新下载安装链路。
本次尚未推送，未运行新提交的远端 CI，也未创建或发布 GitHub Release。
