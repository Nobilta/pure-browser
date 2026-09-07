# 测试与验证指南

## 自动化检查

```bash
cd rust
cargo fmt --all -- --check
cargo test --all
cargo clippy --workspace --all-targets -- -D warnings

cd ..
node --test validation/playback-probe.test.cjs
python3 validation/check-localization.py
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
./gradlew :app:assembleRelease --console=plain
```

Android 单元测试使用 Robolectric，覆盖 URL/导航、数据库、下载设置/Range/HTTP 引擎、文件名、媒体候选和 DLNA 解析；
Rust 测试覆盖规则匹配、URL 工具、LRU、legacy 下载分片和文件名解析。
网页协议测试覆盖视频目标隔离、临时倍速恢复、全屏切换与消息回执；字符串检查覆盖三语言键和格式参数。
当前数量及最近一次执行结果见 [README](./README.md) 和 [回归报告](./EMULATOR_TEST_REPORT.md)。

## APK 检查

```bash
APK=PureBrowser-v0.2.0-release.apk
apksigner verify --verbose "$APK"
unzip -l "$APK" | rg 'lib/|AndroidManifest.xml'
```

默认只应看到 `libmybrowser_adblock.so`、`libmybrowser_cache.so`、
`libmybrowser_url_utils.so`（以及 AndroidX 自带库）；不应看到 database、downloader 或
filename_parser，除非显式 opt-in。

## 设备回归

```bash
./install_and_test.sh
./diagnose.sh
```

以下是完整验收清单；在 API 29、API 34 arm64 模拟器和真机上按需执行，实际完成项单独记录：

- 启动、WebView 页面加载、前进/后退、主页、刷新/停止
- 地址栏访问/搜索按钮、建议、外部协议交接
- 标签、书签新增编辑、历史、无痕清理
- 系统 Download/SAF 自定义目录、线程设置持久化、Range/单线程回退、后台下载、重名文件
- 下载打开/重试，以及仅删除记录和同时删除对应本地文件
- 页面内查找、文件上传、全屏、JS 对话框和权限回调
- 视频媒体候选、当前播放标记、悬浮投屏按钮、DLNA 设备发现/投送
- 当前视频 0.5×–3× 倍速、跨域 iframe 播放器和同页媒体源重载后的倍速保持
- 全屏亮度/音量/进度手势、临时倍速、锁定返回、横竖屏和退出状态恢复
- 设置分类与弹层返回、主题持久化、大字体与宽屏双栏、应用语言切换

可控设备回归脚本、测试服务器启动命令和性能对照方法见
[回归报告](./EMULATOR_TEST_REPORT.md)。测试时只运行一台模拟器，避免与 Gradle 构建并行。

## 负面与边界测试

- 过长/损坏的偏好 JSON 不应导致崩溃或无限内存。
- `javascript:`, `file:` 和未知外部 scheme 不应从偏好恢复或被任意启动。
- 超大 DLNA XML/SOAP 响应、过长下载 header 和 NativeCache key/entry 应被截断/拒绝。
- renderer 崩溃后页面应重建，logcat 不应出现 `FATAL EXCEPTION`、`UnsatisfiedLinkError`
  或 `SIGSEGV`。

没有实体 DLNA renderer 时，投屏只能验证候选选择和发现 UI；实际播放必须在同网真机设备
上确认。
