## 0.14.1 验证范围

被测产物：`PureBrowser-v0.14.1-release.apk`（versionCode 42），本地签名 Release 候选。
SHA-256：`0d292d4253cf866e9a08e4eeaf774df9de732d6861b3b6fa8c70c0e38872ef21`。
设备：`MyBrowser_Pixel7`，Android 14 / API 34、arm64-v8a，WebView 113.0.5672.136。

- `./build-and-test.sh --release` 通过：Android/JVM 635 项、Node 97 项、Rust 66 项、Python 发布工具 14 项；三语言资源 788 项一致。
- Rust fmt/clippy、R8、v2 单签名、16 KiB zipalign、包名及 ARM64 ABI 校验通过；Android lint 为 0 error、47 warning、1 hint。
- 同一个 APK 完成设备阶段 `omnibar`（117.2s）、`resident`（34.2s）、`settings`（47.4s）。
- 同一个 APK 完成优化后的 `settings-back`（143.6s）：分类返回轮换系统/工具栏 Back，两个边缘手势方向各一次，并覆盖字体缩放、旋转、横屏和嵌套设置返回；这次没有运行旧的高循环压力值。
- 本地证据位于 `validation/results/api34-final-fast-suite.json` 和 `validation/results/api34-final-settings-back-suite.json`。
- 模拟器 `settings-back` 从旧记录的 475–510s 降到 143.6s；原因包括分类返回不再每类双向重复、默认回退循环降到 2 次，以及启动后固定等待从 1s 降到 0.2s。

未覆盖：其他 Android 版本与真机、完整功能矩阵、API 37（本机缺少对应 system image）、鼠标输入的设备验证、人工 TalkBack 播报、真实线上更新下载安装链路。
本次尚未推送，未运行新提交的远端 CI，也未创建或发布 GitHub Release。
