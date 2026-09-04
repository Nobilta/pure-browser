# Pure 浏览器交付摘要

更新时间：2026-09-05

## Release APK

- 文件：[PureBrowser-v0.1.0-release.apk](./PureBrowser-v0.1.0-release.apk)
- 构建源：`app/build/outputs/apk/release/app-release.apk`
- ABI：`arm64-v8a`
- minSdk：34；targetSdk：37
- 大小：9,963,930 bytes（约 9.50 MiB）
- SHA-256：`47ba21f0c026b1fea879f22447ddab077d9476375f2d92732679a3d3de2a1ec0`
- 签名：使用项目外部配置的 release keystore，须通过 `apksigner verify --verbose`

## 本轮完成

- 对 Kotlin/Rust 源码、目录分层、JNI 边界、异步任务、持久化和构建脚本进行了系统审查。
- 菜单已按四个任务域分组；HTTPS 弹窗展示当前 WebView 的真实证书身份和有效期。
- 新增原生 Material 3 导航首页及固定网址模式，设置可持久化选择并兼容旧主页配置。
- 书签编辑器可同步创建 favicon 快捷入口；首页长按移除不会删除对应书签。
- 下载元数据现在有严格的长度、条目数、协议、文件名和 header 清理；损坏偏好会被丢弃。
- 下载设置新增系统 Download/SAF 自定义目录选择和 1–16 线程配置；HTTP Range 分段引擎
  在服务端不支持 Range 时自动回退单线程，并传递 WebView Cookie、UA 与 Referer。
- 下载由 `dataSync` 前台服务承接，Activity 重建不会中断；列表可重试失败/暂停任务，并可
  选择仅删除记录或同时删除对应本地文件。MediaStore 与 DocumentsProvider 使用各自正确
  的发布和删除 API，重名文件会自动编号。
- 无痕 Cookie 清除支持完成回调，清除完成后才刷新/提示；退出共享存储降级无痕时不会
  在清理完成前加载普通页面。
- NativeCache 的句柄访问与关闭共用生命周期锁，Rust/Kotlin 两侧都有 key/entry 上限。
- DLNA SOAP、SSDP、设备描述响应均有有界读取；投屏搜索任务取消竞态已消除。
- 标签持久化、SQLite repository 生命周期、WebView 池容量计算和 Rust clippy 问题已修正。
- 构建/安装/诊断脚本改为基于项目目录，移除了旧临时路径和全局配置写入。
- 清除浏览数据增加确认并保留书签/下载文件；过滤器偏好、书签草稿和位图入口增加边界。
- 清理重复 UI、无调用包装器和 69 个未使用资源，统一 29 处 KTX 调用；lint 从 101 条
  降至 2 条经过评估保留的工具链/ABI warning（0 errors）；Android 119 项单元测试通过。

## Rust 决策

默认 APK 只包含三个实际使用的项目 JNI 库（另有 Compose 依赖自带的
`libandroidx.graphics.path.so`）：

| 库 | 用途 | 状态 |
|---|---|---|
| `libmybrowser_adblock.so` | 规则解析与请求匹配 | 默认打包 |
| `libmybrowser_cache.so` | 有界字节 LRU | 默认打包 |
| `libmybrowser_url_utils.so` | URL/搜索分类快速路径 | 默认打包，Kotlin 回退 |

`rust/downloader` 和 `rust/filename_parser` 仅在显式 `-Pmybrowser.includeLegacyRust=true`
时加入；`rust/database` 不属于 workspace，也不进 APK。WebView、Compose、SQLite、系统
下载和页面内查找保留在 Android/Kotlin，避免无收益的 JNI、约 3 MB Rust 网络/TLS 依赖
和第二套生命周期。

## 验证命令

```bash
./build-and-test.sh
```

该脚本运行 Rust 格式/测试/clippy、Android 单元测试、lint、Release 构建、签名检查，并复制最终 APK。手工等价
命令和目录说明见 [README.md](./README.md)。模拟器回归记录见
[EMULATOR_TEST_REPORT.md](./EMULATOR_TEST_REPORT.md)。

本轮模拟器已实际验证：SAF 创建并选择 `PureDownloads`、8 线程设置跨重启持久化、约
9.9 MB 文件由 8 个 Range 分片下载合并、重名自动编号、系统 Download 与 SAF 两种目标、
“仅删除记录”和“删除记录和文件”两条路径，以及下载期间前台服务存在并在完成后退出。
最终 Release APK 已重新安装并冷启动，无崩溃。

## 诚实边界

本机模拟器可以验证 UI、候选排序和设备发现；没有实体 DLNA 接收器时，不能把 SOAP 实际
播放宣称为已验证。两个用户直播 URL 在最终回归时均为 HTTP 404；本轮媒体证据来自项目内
可复现夹具。Release 当前只提供 arm64-v8a，x86_64 仅用于显式调试构建。
