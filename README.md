# Pure 浏览器

Pure 浏览器是一款面向 Android 14 及以上设备的轻量浏览器。界面与 Android 平台能力使用
Kotlin、Jetpack Compose 和 WebView 实现；只有规则匹配、字节缓存及纯 URL 逻辑保留在
Rust。项目追求体积可控、行为透明，以及在 Android 生命周期和存储规则下可验证地工作。

当前版本为 `0.1.0`，Release 仅提供 `arm64-v8a`。项目目录和 Gradle 根项目均命名为
`pure-browser`；为保持已安装应用的升级兼容，Android applicationId 暂时仍为
`com.mybrowser`。

## 功能概览

### 浏览与界面

- WebView 浏览、前进/后退、刷新/停止、主页、页面弹窗、全屏视频和多标签页。
- 地址栏自动判断网址或搜索词；只有聚焦输入框时，外侧才显示“访问”或“搜索”按钮。
- Material 3 界面，菜单按页面操作、浏览数据、隐私与安全、设置与工具分类。
- 页面内查找、桌面站点模式、文件上传、摄像头/麦克风/定位权限处理。
- HTTPS 安全弹窗展示证书主体、组织、签发者、有效期和当前有效状态。

### 首页、书签与历史

- 可在导航首页和固定网址主页之间切换。
- 书签支持新增、编辑和删除；新增时默认使用当前页面标题及 URL，也可手动修改。
- 添加书签时可同时添加到导航首页，快捷入口使用网页 favicon；长按只移除首页入口，
  不会删除书签。
- 历史记录合并重复访问，并为持久化内容和查询结果设置边界。

### 下载

- 下载位置可选择系统 Download 目录，或通过 Storage Access Framework 授权自定义目录。
- 下载线程数可在 1–16 之间设置，默认 4；设置会持久化。
- 支持 HTTP Range 分段下载，并校验响应范围和文件长度；服务端不支持 Range 时自动回退
  单线程。
- WebView 的 User-Agent、Referer 与 Cookie 会传递给下载请求；Cookie 只驻留进程内存。
- `dataSync` 前台服务负责后台传输；失败或系统中断的任务可从列表重试。
- 下载列表可以仅删除记录，也可以同时删除记录和对应的 MediaStore/SAF 本地文件。
- 同名文件自动编号；旧 Android DownloadManager 记录仍可查询、打开和删除。

### 媒体与投屏

- 检测页面视频、音频以及 HLS/DASH 地址。
- 页面出现可投屏媒体时，在右下角显示悬浮投屏按钮。
- 多视频直播页会追踪实际处于播放状态的 `<video>`，并在候选列表标注“正在播放”。
- 支持 SSDP 发现和 DLNA/UPnP AVTransport 控制。手机与接收设备必须处于同一局域网。

### 过滤与开发工具

- Rust 广告规则匹配，支持内置规则、自定义列表、开关和页面拦截统计。
- 有界控制台日志与网络请求日志。
- Safe Browsing、外部协议 allow-list、renderer 崩溃恢复和 WebView 实例池。

## 无痕模式的实际边界

Android WebView 没有一个在所有版本上都可用的统一“无痕开关”。Pure 浏览器会在运行时
选择以下模式，是否支持取决于当前 WebView Provider，而不只取决于 Android 系统版本：

| WebView 能力 | 实际行为 | 隔离级别 |
|---|---|---|
| 支持 `MULTI_PROFILE` | 为无痕 WebView 挂载独立 Profile，Cookie、缓存、localStorage 和 IndexedDB 与普通模式分离；退出时删除整个 Profile | 真正的站点存储隔离 |
| 不支持或 Profile 挂载失败 | 不写历史、禁用 HTTP 缓存、使用独立的临时标签栈，并在退出时清理共享 Cookie、WebStorage、认证、定位和实例状态 | 退出清除，不是会话期间的独立存储 |

进入时显示“已进入无痕模式”代表独立 Profile 已启用；显示“退出时清除数据”代表降级路径。
降级模式浏览期间可能看到普通模式的登录状态，退出后也会清除普通模式 Cookie。

无痕模式不会保存新的浏览历史或无痕标签，但书签、下载文件和下载记录属于用户明确创建的
持久数据；在无痕模式中主动添加书签或下载文件仍会保留。无痕模式也不隐藏公网 IP，不能
替代 VPN、Tor 或系统级匿名网络。

## 技术栈与语言边界

- Kotlin、Jetpack Compose、Android WebView：UI、页面生命周期、权限与系统集成。
- Android SQLite：书签和历史。
- Kotlin 协程与 Android 存储 API：下载、SAF、MediaStore 和前台服务。
- Rust JNI：`adblock`、`cache`、`url_utils`。

Rust 只用于输入输出边界清晰的纯计算模块。WebView、Compose、SQLite、下载存储和生命周期
编排留在 Kotlin/Android：这些能力依赖平台 API，改写为 Rust 会增加 JNI、另一套网络/TLS
依赖和约 3 MB 体积，却不能改善平台语义。`rust/downloader` 与
`rust/filename_parser` 仅为旧调用者保留，默认不会编译或打包。

## 项目结构

```text
pure-browser/
├── app/src/main/java/com/mybrowser/
│   ├── MainActivity.kt       WebView、ActivityResult 与生命周期编排
│   ├── core/                 导航、安全策略、WebView 配置与日志
│   ├── data/                 书签、历史与 NativeCache 门面
│   ├── download/             分段传输、目标写入、前台服务和记录管理
│   ├── filter/               广告过滤规则与控制器
│   ├── privacy/              独立 Profile 与退出清除策略
│   ├── search/               搜索引擎和 URL/搜索分类
│   ├── tabs/                 标签状态、恢复和缩略图
│   ├── home/                 首页模式、快捷入口和 favicon
│   ├── media/                媒体嗅探与当前播放追踪
│   ├── dlna/                 SSDP、设备描述与 AVTransport
│   ├── security/             页面安全信息模型
│   └── ui/                   Compose 页面、工具栏和弹窗
├── app/src/test/             Android/Robolectric 单元测试
├── rust/
│   ├── adblock/              默认打包
│   ├── cache/                默认打包
│   ├── url_utils/            默认打包
│   ├── downloader/           legacy，可选
│   ├── filename_parser/      legacy，可选
│   └── database/             隔离的早期实验，不进 APK
├── validation/               可复现页面/媒体夹具；生成的模拟器证据仅保留本地
├── build-and-test.sh         完整本地验证与 Release 构建
└── PureBrowser-*.apk         本地交付产物，不纳入 Git
```

`MainActivity` 是 Android/WebView 回调的生命周期编排器。数据库、下载、媒体识别、过滤和
DLNA 已按职责下沉；不为缩短文件行数而机械拆分 ViewModel。

## 开发环境

- macOS 或 Linux
- JDK 17 或更高版本
- Android SDK Platform 37、Build Tools 及 NDK
- Rust stable、`aarch64-linux-android` target
- Android 14+ arm64 真机或模拟器

项目通过 `local.properties` 或 `ANDROID_SDK_ROOT` 查找 Android SDK，通过
`rust/resolve-android-ndk.sh` 查找 NDK。项目脚本不会修改 shell profile 或全局 Cargo
配置。

`local.properties`、`keystore.properties`、JKS、构建目录和 APK 已加入 `.gitignore`。
Release 签名配置格式如下，真实密码不得提交：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## 构建与验证

完整验证并生成签名 Release：

```bash
./build-and-test.sh
```

该脚本执行：

1. Rust `fmt --check`、49 项测试及 `clippy -D warnings`；
2. Android/Robolectric 119 项单元测试；
3. Android lint；
4. R8、资源裁剪与 `arm64-v8a` Release 构建；
5. APK 签名、大小和 SHA-256 检查。

当前本地 Release 产物：

- `PureBrowser-v0.1.0-release.apk`
- 9,963,930 bytes
- SHA-256：`47ba21f0c026b1fea879f22447ddab077d9476375f2d92732679a3d3de2a1ec0`
- APK Signature Scheme v2：通过

只运行 Android 单元测试或 lint：

```bash
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
```

连接设备后安装并冷启动：

```bash
./install_and_test.sh PureBrowser-v0.1.0-release.apk
```

诊断设备与崩溃日志：

```bash
./diagnose.sh
```

最近一次模拟器验证覆盖系统 Download/SAF 目录、8 线程 Range 下载、重名文件、两种删除
方式、前台服务退出、主页、证书、无痕降级提示、媒体候选与悬浮投屏入口。没有实体 DLNA
接收器时，只能确认候选和设备发现流程，不能宣称实际投送成功。

## 相关文档

- [架构审查与 Rust 决策](./ARCHITECTURE_REVIEW.md)
- [模拟器回归报告](./EMULATOR_TEST_REPORT.md)
- [APK 测试指南](./APK_TEST_GUIDE.md)
- [测试与验证指南](./TESTING_GUIDE.md)
- [最终交付摘要](./FINAL_DELIVERY_SUMMARY.md)
- [更新日志](./CHANGELOG.md)

## 维护约定

README 是项目的入口和当前能力基线。后续每次修改用户可见功能、行为、架构、依赖、构建
方式、验证结果或交付 APK 时，都必须在同一次变更中同步更新本 README；不能让实现、测试
数量、环境要求或校验值与 README 脱节。
