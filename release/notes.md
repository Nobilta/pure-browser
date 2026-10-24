## 0.15.0 验证范围

被测产物：`PureBrowser-v0.15.0-release.apk`（versionCode 55），本地签名 Release 候选。
SHA-256：`980ca8644e571587a1da74afc30096fd0a71c79b15a1b4d143685fffbe27c738`。

设备：`emulator-5554`，Android 14 / API 34、arm64-v8a，WebView 113.0.5672.136。

- `./build-and-test.sh --release` 通过：三语言资源 836 项一致，Python 发布工具 14 项、Node 119 项、
  Rust 67 项、Android/JVM 661 项全部通过；Android lint 为 0 error、50 warning、1 hint。
- APK 通过 v2 单签名和 16 KiB zipalign 校验，包名 com.mybrowser、versionName 0.15.0、
  versionCode 55、arm64-v8a；更新清单 `update.json` 与 `SHA256SUMS` 已生成。
- 已用该 APK 覆盖安装到模拟器并启动 MainActivity，冷启动 `am start -W` TotalTime 1993ms。

未覆盖：完整设备功能矩阵、其他 Android 版本与真机、真实线上更新下载与系统安装链路。
