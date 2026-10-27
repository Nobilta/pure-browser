## 0.15.2 验证范围

被测产物：`PureBrowser-v0.15.2-release.apk`（versionCode 57），本地签名 Release 候选。
SHA-256：`f0c9fc5a6f762c5c564bf7a2d9fe278ee143089eb8b80c6e8b61ac5e78cf1d29`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

- 本次改动：外部应用确认框先解析目标应用并在标题显示其名称，例如 `Open “DeepLinkStub”?`；
  多个应用可处理时不指名，交给系统选择；不显示图标。名称与真正启动使用同一个净化后的 Intent，
  不会出现「说的是 A、打开的是 B」。解析名称需要读取已安装应用，因此声明 `QUERY_ALL_PACKAGES`，
  只用于这次查找。0.15.1 的任意应用 scheme 跳转修复一并包含。
- Android/JVM 单元测试 669 项全部通过，新增 `ExternalTargets` 4 项：唯一处理器显示其 label、
  多处理器不指名、无处理器、必须留在浏览器内的 scheme 不被解析。
- 模拟器回归：安装声明 `bocmbankpsn` 的测试应用（label `DeepLinkStub`），在本地页面点击
  `bocmbankpsn://startwith/N26K365Mci`，弹窗标题为 `Open “DeepLinkStub”?`（见
  `outputs/validation/handoff-dialog-named-api37.png`），选择 Allow 后该应用被拉起，
  `mCurrentFocus=com.example.deeplinkstub/android.app.Activity`。
- APK 通过 v2 单签名校验，包名 com.mybrowser、versionName 0.15.2、versionCode 57、arm64-v8a。

未覆盖：真机与中国银行 App 的真实联调（`mbs.boc.cn` 页面未在模拟器打开）、其他 Android 版本与
真机、完整设备功能矩阵、真实线上更新下载与系统安装链路。
