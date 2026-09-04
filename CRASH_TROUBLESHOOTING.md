# Pure 浏览器故障排查

## 先收集信息

```bash
./diagnose.sh
adb logcat -d > validation/diagnose-logcat.txt
adb logcat -d | rg -n 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV|AndroidRuntime'
```

确认 Android SDK >= 34、设备 ABI 与 APK 匹配（Release 为 arm64-v8a），并检查 APK 签名：

```bash
apksigner verify --verbose PureBrowser-v0.1.0-release.apk
unzip -l PureBrowser-v0.1.0-release.apk | rg '\.so$'
```

## 常见问题

### 无法安装

- 卸载旧的 debug/release 包后重试：`adb uninstall com.mybrowser` 或
  `adb uninstall com.mybrowser.debug`。
- 检查 `adb shell getprop ro.product.cpu.abi`；x86_64 模拟器需要显式构建 x86_64 debug 包。
- 确认设备系统为 Android 14+，并使用同一签名升级。

### native 库加载失败

默认 APK 只需要三个库：`libmybrowser_adblock.so`、`libmybrowser_cache.so`、
`libmybrowser_url_utils.so`。如果其中一个缺失，重新运行：

```bash
./build-and-test.sh
```

不要把 `legacy/native-libs` 手工复制到 `app/src/main/jniLibs`；Gradle 会从 Rust staging
目录生成与 Kotlin JNI 声明匹配的库。Rust 库不可用时 URL/过滤/缓存会安全降级，但应记录
`UnsatisfiedLinkError` 以便修复构建环境。

### 启动后白屏或 renderer 崩溃

- 清除应用数据后重试：`adb shell pm clear com.mybrowser`。
- 检查 WebView/Chrome provider 是否启用并更新。
- 观察 logcat；renderer death 会由 WebViewPool 丢弃并重建，若仍有 `FATAL EXCEPTION`，
  保存完整堆栈和复现页面 URL。

### 投屏找不到设备

- 手机和 renderer 必须在同一局域网，关闭 VPN/热点隔离并允许局域网权限。
- 模拟器通常无法代表真实 DLNA 网络；先确认媒体候选和“正在播放”标记，再用真机验证。
- 设备描述或 SOAP 响应过大时会被有界读取拒绝，这是保护行为，不是崩溃。

### 无痕数据似乎仍存在

当前 provider 支持多 profile 时会删除独立 profile；不支持时是共享 Cookie 的退出清理
降级。清理是异步的，界面会在 Cookie 回调完成后才提示；不要在提示出现前强杀进程。

## 报告问题时附带

- APK SHA-256、设备型号/ABI/API、WebView provider 版本
- 复现步骤和页面 URL（去除敏感 query）
- `validation/diagnose-logcat.txt` 中相关堆栈和截图
