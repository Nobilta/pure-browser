## 0.14.1 验证范围

被测产物：`PureBrowser-v0.14.1-release.apk`（versionCode 42），本地签名 Release 候选。
设备：`PureBrowser_API37`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。
本次从工作区构建，安装包与下列设备记录的 SHA-256 一致；包大小、校验和与签名信息见生成的 `outputs/release/` 附件。

- `./build-and-test.sh --release` 通过：Android/JVM 610 项、Node 93 项、Rust 63 项、Python 发布工具 14 项；三语言资源 787 项一致。
- Rust fmt/clippy、R8、v2 单签名、16 KiB zipalign、包名及 ARM64 ABI 校验通过；Android lint 为 0 error、47 warning、1 hint。
- 同一个 APK 完成 7 个设备阶段：`blob-download`、`menu-navigation`、`download`、`bookmarks`、`features-dialogs`、`settings-back`、`settings`。
  下载/书签覆盖长按进入、取消/重新选择、返回退出，以及唯一选择目标的无障碍状态；书签还验证 SAF 导入、去重和导出。
- 本地证据位于 `validation/results/`：`api37-release-0141-review-suite.json` 的 Blob/菜单阶段、
  `api37-release-0141-selection-suite.json` 的下载阶段、`api37-release-0141-regression-suite.json` 的其余四阶段。
  初次回归暴露了测试脚本对 UI 探针属性、文件选择定位和长按时序的假设；修正脚本后相关阶段通过，失败日志仍保留。
- 只读核对 GitHub v0.14.0：版本号递增，上一版 APK 完整验签通过且证书与候选包一致。

未覆盖：其他 Android 版本与真机、完整功能矩阵、人工 TalkBack 播报、真实线上更新下载安装链路。
本次尚未推送，未运行新提交的远端 CI，也未创建或发布 GitHub Release。
