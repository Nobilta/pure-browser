## 0.20.0 本地测试包验证范围

被测产物：`PureBrowser-v0.20.0-release.apk`（versionCode 75），本地签名 Release，尚未发布。

- 本版是从 0.19 起全部媒体、下载、书签、过滤改动的汇总重建包；源码与上一轮验证的 0.19.3 一致，差异只有版本号与更新日志。
- 自动化：`./build-and-test.sh --release` 通过，包含 791 项 JVM、132 项 JavaScript、69 项 Rust、14 项发布工具测试，以及 1014 条三语资源一致性检查；Rust fmt/clippy、Android Lint（0 错误，85 条警告）、R8、APK 签名与 zipalign 校验通过。
- 设备回归：未在新的安装包上重跑。上一轮源码对应的 Android 14 / API 34 / WebView 113 与 Android 17 / API 37 / WebView 145 结果保留在 `validation/results/`，可作为该源码的参考记录，但不构成本安装包的实测结论。
- 未验证：实体设备、其他 Android 与 WebView 版本、Digest 与代理认证组合、真实 DLNA 接收设备；媒体格式兼容性仍受系统 WebView 与设备解码器限制。

详细日志、服务器请求与模拟器结果保存在忽略目录 `validation/results/`。
