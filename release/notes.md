## 0.14.1 验证范围

被测产物：`PureBrowser-v0.14.1-release.apk`（versionCode 42），本地签名 Release 候选。
SHA-256：`f48dbc3161c381f6062787421c7ba1dc2a82f228e43e0a3d1871a0c88a995371`。
设备：`MyBrowser_Pixel7`，Android 14 / API 34、arm64-v8a，WebView 113.0.5672.136。

- `./build-and-test.sh --release` 通过：Android/JVM 642 项、Node 97 项、Rust 67 项、Python 发布工具 14 项；三语言资源 788 项一致。
- Rust fmt/clippy、R8、v2 单签名、16 KiB zipalign、包名及 ARM64 ABI 校验通过；Android lint 为 0 error、46 warning、1 hint。
- 同一个 APK 完成设备阶段 `omnibar`（83.0s）、`resident`（33.3s）、`settings`（45.8s），合计 162.1s。
- 优化后的 `settings-back`（141.2s）在上一候选 `62802da3…` 上通过：分类返回轮换系统/工具栏 Back，两个边缘手势方向各一次，并覆盖字体缩放、旋转、横屏和嵌套设置返回。本候选只改站点草稿基线、手写规则导出判定和 SAF 回退日志，未改返回链，但未在本 APK 上重跑该阶段。
- 本地证据位于 `validation/results/api34-final3-fast-suite.json` 和 `validation/results/api34-final2-settings-back-suite.json`。
- 模拟器 `settings-back` 从旧记录的 475–510s 降到 141.2s；启动后固定等待从 1s 降到 0.2s，菜单/设置默认返回循环分别为 4/2 次。
- SAF 自定义目录仅在支持重命名的 provider 上先写临时文档、完成后重命名；不支持重命名或能力查询失败时仍回退到直接写最终文件，该回退路径仍可能留下半截文件。

未覆盖：其他 Android 版本与真机、完整功能矩阵、API 37（本机缺少对应 system image）、鼠标输入的设备验证、人工 TalkBack 播报、真实线上更新下载安装链路。
本次尚未推送，未运行新提交的远端 CI，也未创建或发布 GitHub Release。
