## 0.15.1 验证范围

被测产物：`PureBrowser-v0.15.1-release.apk`（versionCode 56），本地签名 Release 候选。
SHA-256：`0a8f7b70a48636512ffb17b8e0879769cfa207fee5c7b7d0d6905e1c79f25c92`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

- 本次改动：外部应用跳转从固定 scheme 白名单改为「除必须留在浏览器内的 scheme 外均可按站点询问后跳转」。
  `file:`、`content:`、`javascript:`、`data:`、`blob:`、`about:`、`ftp:`、`chrome:` 等仍然拒绝；
  组件、包名和 URI 授权的清理不变。
- Android/JVM 单元测试 665 项全部通过（含新增的任意应用 scheme、`scheme://` 地址栏输入、
  `intent://` 内层受控 scheme 与 `bocmbankpsn://` 用例）。
- 模拟器回归：安装声明 `bocmbankpsn` scheme 的测试应用，在本地页面点击
  `bocmbankpsn://startwith/N26K365Mci`，浏览器弹出「Open another app? … using bocmbankpsn」，
  选择 Allow 后测试应用被拉起；启动 Intent 为
  `act=VIEW cat=[BROWSABLE] dat=bocmbankpsn://startwith/N26K365Mci flg=0x10000000`，
  未携带页面指定的 component 或 package。
- APK 通过 v2 单签名校验，包名 com.mybrowser、versionName 0.15.1、versionCode 56、arm64-v8a。

未覆盖：真机与中国银行 App 的真实联调（`mbs.boc.cn` 页面未在模拟器打开）、其他 Android 版本与
真机、完整设备功能矩阵、真实线上更新下载与系统安装链路。
