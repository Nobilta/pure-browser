# Pure 浏览器

Pure 浏览器是一款面向 Android 10 及以上设备的轻量浏览器。界面与 Android 平台能力使用
Kotlin、Jetpack Compose 和 WebView 实现；只有规则匹配及纯 URL 逻辑默认使用
Rust。项目追求体积可控、行为透明，以及在 Android 生命周期和存储规则下可验证地工作。

当前版本为 `0.3.1`（versionCode 4），Release 仅提供 `arm64-v8a`，不包含 32 位 Android。
项目目录和 Gradle 根项目均命名为
`pure-browser`；为保持已安装应用的升级兼容，Android applicationId 暂时仍为
`com.mybrowser`。

## 功能概览

### 浏览与界面

- WebView 浏览、前进/后退、刷新/停止、主页、页面弹窗、全屏视频和多标签页。
- 地址栏自动判断网址或搜索词；只有聚焦输入框时，外侧才显示“访问”或“搜索”按钮。
- Material 3 界面，菜单按页面操作、浏览数据、隐私与安全、设置与工具分类。
- 菜单顶部集中显示书签、分享、复制链接和页内查找图标，长按图标可查看名称。
- 长按网页链接可新标签打开、后台打开、复制或分享；长按图片可打开、保存或复制图片地址。
  图片链接同时提供两组操作。只处理有效 HTTP(S) 地址，普通文本仍使用 WebView 原生选区。
- 标签页支持标题/网址搜索、当前标记、缩略图及批量关闭确认。后台打开只新增元数据，
  选中后才加载网页。已访问页面通过历史记录找回，不另设最近关闭列表。
- 页面内查找、桌面站点模式、文件上传、摄像头/麦克风/定位权限处理。
- HTTPS 安全弹窗展示证书主体、组织、签发者、有效期和当前有效状态。
- 地址栏未编辑时显示网页标题和站点域名；点按标题区域进入完整 URL 编辑。页面向下滚动
  时自动收起顶部地址栏，向上滚动、回到页面顶部、编辑地址或打开查找时自动展开。
- 通过 Android `RoleManager` 请求设为默认浏览器；菜单末尾提供“退出浏览器”，退出时移除
  最近任务。

### 设置与主题

- 设置首页分为浏览与启动、外观、隐私与过滤、下载设置、视频播放、关于六类。
- 设置首页只显示分类列表；进入分类后，可用宽度达到 720dp 时同时显示分类导航和详情。
- 工具栏返回、系统返回键和边缘返回手势均逐级返回：选项弹层 → 原分类 → 设置首页 → 关闭设置。
  宽屏两侧的返回按钮遵循相同顺序，二级页不会直接关闭整个设置。
- 搜索引擎、主页、主题和默认倍速在弹层中选择；关闭弹层返回原分类。
- 字体或语言变化导致 Activity 重建时保留设置分类；用户冷启动仍遵循主页/恢复设置。
- 支持跟随系统、浅色和深色主题；状态栏与导航栏图标跟随应用主题。
- Android 12+ 使用动态配色，Android 10–11 使用内置配色。
- 当前应用图标已切换为「青叶 P」：青绿色渐变背景、字母 P 与叶片前景；同时提供 Android 13+ 主题单色图标。
- 应用提示、按钮及设置支持简体中文、繁体中文和英文；其他语言使用英文。
  Android 13+ 可在系统的应用语言页面选择语言，较早系统跟随系统语言。

### 首页、书签与历史

- 可在导航首页和固定网址主页之间切换。
- 书签支持新增、编辑和删除；新增时默认使用当前页面标题及 URL，也可手动修改。
- 添加书签时可同时添加到导航首页，快捷入口默认使用网页 favicon。
- 长按首页快捷入口打开统一编辑窗口，可修改标题、网址，从系统文件选择器导入图片或
  使用标题文字图标；窗口内提供删除按钮和二次确认。编辑和删除均不改变书签。
- 保存时保留快捷入口 ID、创建时间和排列位置；重复网址会提示冲突，取消不保存草稿。
  图标经 Android ImageDecoder 按方向解码并缩小到最长边 96px，再存入应用私有目录；
  无需申请整个相册的读取权限。自定义图标不会被之后添加书签时的网页 favicon 覆盖。
- 快捷入口信息在后台线程确认写入后才报告保存成功；替换图片先写新文件，成功后才清理旧图。
  写入失败会回滚标题、网址及图标选择，删除失败也会保留原入口和图片。取消图片草稿不写入图标文件。
- 历史记录合并重复访问，并为持久化内容和查询结果设置边界。
- 书签和历史记录搜索直接查询完整数据库，每次显示 50 条并可继续加载；快速输入取消旧查询，
  失败时可重试。历史按日期分组，条目菜单可新标签打开、复制和删除。
- 书签条目可直接编辑标题/网址，保留原 ID 和创建时间；网址与已有书签重复时保留双方数据。
  清空书签或历史均需确认。
- 设置中的“启动时恢复上次网页”默认关闭。关闭时冷启动打开导航首页或固定网址主页；开启
  后恢复普通模式的标签地址、标题和选中标签。无痕标签永不写入恢复数据。

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

- 根据网络请求路径和 Accept 请求头识别视频、音频及 HLS/DASH 候选地址。
- 当前视频已加载的 `currentSrc` 可补充嗅探遗漏的可识别 HTTP(S) 媒体候选，保留完整签名参数；
  尚未加载的 `src` 属性、页面资源线索和 Blob 地址不会通过此路径直接变成投屏候选。
- 投屏入口位于菜单和增强全屏播放器控制栏，不再覆盖网页右下角。
  控制栏内打开媒体/设备选择窗口不会退出全屏，取消后仍留在原播放器。
  关闭弹层或从后台返回时恢复沉浸全屏，避免旧系统导航栏覆盖播放器按钮。
- 多视频直播页会追踪实际处于播放状态的 `<video>`，并在候选列表标注“正在播放”。
- 页面存在视频时可从菜单打开倍速入口，暂停后仍可使用；网页内不显示悬浮倍速按钮。可选择 `0.5×`、`0.75×`、`1×`、
  `1.25×`、`1.5×`、`2×` 或 `3×`；记住速度默认关闭，长按临时加速不会写入偏好。
- 开启增强控件设置时，标准 `<video controls>` 自身全屏可交接给浏览器，提供播放/暂停、
  进度条、快退/快进 10 秒、倍速、旋转、投屏和锁定。网站自定义容器全屏、YouTube 及
  无法访问的跨域播放器保留网页控件，不叠加浏览器标题栏、播放栏或手势层。
- 增强全屏模式下，左侧上下滑动调整当前窗口亮度，右侧调整媒体音量；亮度退出后恢复，不修改系统亮度。
- 横向滑动预览进度，松手跳转。直播或没有可跳转范围的视频不启用进度跳转。
- 长按临时使用 2× 或 3×（不降低原有更快速度），松手、取消或切后台恢复原速度。
- 单击显示/隐藏控件，双击中央播放/暂停，双击两侧快退/快进 10 秒。
  锁定后触摸手势失效，返回键先解锁，再次返回才退出。
- 横向视频可自动横屏；方形/竖向视频保持进入时的方向，也可手动旋转，退出后恢复方向。
- 支持 SSDP 发现和 DLNA/UPnP AVTransport 控制。手机与接收设备必须处于同一局域网。

视频解码仍由网站与 WebView 完成，没有新增 ExoPlayer/FFmpeg 等独立解码依赖。
原生全屏界面只控制对应的 HTML 视频元素，网站登录、清晰度、字幕及 DRM 能力仍由网站负责。
兼容的标准视频可以切回“网页控件”；复杂网站直接使用自己的全屏按钮或系统返回退出。
网页确认交接后才显示浏览器播放栏；目标变化或退出时先撤下播放/手势层，再恢复视频原有控件。
临时隐藏样式仅作用于接管的视频元素，用于抑制 WebView 全屏时强制显示的内置控件；退出后移除。
播放遥测不会重置自动隐藏计时，过期回执不能重新开启已撤下的控件。

MSE/Blob 与控件归属独立，标准 Blob 视频仍可使用增强控件，但 Blob 地址不能交给 DLNA
接收器直接访问。YouTube 常使用 MSE、分离音视频及短期签名请求，通常没有本嗅探器支持的完整媒体地址；
没有候选时菜单显示“未检测到此视频可直接投送的媒体地址”。这不代表已经判断为 DRM。
识别到 HTTP(S) 候选也不保证投送成功：Cookie、Referer、地址有效期和接收设备格式支持都会影响结果。
当前仅实现 DLNA 直投，不包含 Google Cast 集成、远端会话控制界面或鉴权代理。
后续设计见 [播放器与投屏方案](./design/video-playback-and-casting.md)。

支持 `WEB_MESSAGE_LISTENER` 和 `DOCUMENT_START_SCRIPT` 的 WebView 通过逐 frame 消息与
确认回执控制跨域视频。旧 WebView 仅控制主文档及可访问的同源 iframe；不能访问的跨域播放器
保留网站自身控件。此能力按 WebView 特性检测，不单凭 Android 版本判断。

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
- Rust JNI：`adblock`、`url_utils`。
- 标签缩略图仅保留小尺寸显示位图，不再编码并复制到 Rust 缓存；位图由运行时管理，
  避免 Compose 仍在绘制时主动回收。WebView 归还池时移除长按与查找监听器。
- 恢复已访问标签的返回栈时使用尚未导航的新 WebView，并释放旧实例；恢复完成前不保存
  中间状态。普通后台标签继续只持有元数据和快照，不为每个标签常驻一个 WebView。

`minSdk=29`，`compileSdk/targetSdk=37`。降低最低安装版本不会让新系统进入旧 target SDK
兼容模式。Rust 使用 API 29 的 NDK 链接器，输出独立存放于 `rust/target/android-api-29`，
防止复用旧的高版本 native 库。新版 WebView 的无视频页面不保留周期扫描；旧版降级探测只在
应用前台运行。全屏原生界面退出后释放计时器、窗口亮度、屏幕常亮和方向设置。

Rust 只用于输入输出边界清晰的纯计算模块。WebView、Compose、SQLite、下载存储和生命周期
编排留在 Kotlin/Android：这些能力依赖平台 API，改写为 Rust 会增加 JNI、另一套网络/TLS
依赖和约 3 MB 体积，却不能改善平台语义。`rust/cache`、`rust/downloader` 与
`rust/filename_parser` 仅为旧调用者保留，默认不会编译或打包。
旧 JNI 集成可使用 `-Pmybrowser.includeLegacyRust=true` 或 `INCLUDE_LEGACY_RUST=1`。

## 项目结构

```text
pure-browser/
├── app/src/main/java/com/mybrowser/
│   ├── MainActivity.kt       WebView、ActivityResult 与生命周期编排
│   ├── core/                 导航、安全策略、WebView 配置与日志
│   ├── data/                 书签、历史与可选 NativeCache 门面
│   ├── download/             分段传输、目标写入、前台服务和记录管理
│   ├── filter/               广告过滤规则与控制器
│   ├── privacy/              独立 Profile 与退出清除策略
│   ├── search/               搜索引擎和 URL/搜索分类
│   ├── tabs/                 标签状态、恢复和缩略图
│   ├── home/                 首页模式、快捷入口和 favicon
│   ├── media/                媒体嗅探、播放追踪与原生全屏控件
│   ├── dlna/                 SSDP、设备描述与 AVTransport
│   ├── security/             页面安全信息模型
│   └── ui/                   Compose 页面、工具栏和弹窗
├── app/src/test/             Android/Robolectric 单元测试
├── rust/
│   ├── adblock/              默认打包
│   ├── cache/                legacy，可选
│   ├── url_utils/            默认打包
│   ├── downloader/           legacy，可选
│   ├── filename_parser/      legacy，可选
│   └── database/             隔离的早期实验，不进 APK
├── validation/               可复现页面/媒体夹具；生成的模拟器证据仅保留本地
├── design/                   图标源文件/预览、播放器与投屏方案
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
- Node.js 18+、Python 3.9+（仅本地验证工具，不打包进 APK）
- Android 10+ arm64 真机或模拟器

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
2. 25 项 Node 网页视频协议测试、414 项三语言资源及格式参数一致性检查（含复数资源）；
3. Android/Robolectric 180 项单元测试；
4. Android lint；
5. R8 全模式、资源裁剪、DEX/native ZIP 压缩与 `arm64-v8a` Release 构建；
6. APK 签名、大小和 SHA-256 检查。

Release 不使用包级 `-keep` 保留整个 Compose、数据层或标签页层，而是依赖 Android 默认
规则和各 AndroidX 依赖提供的 consumer rules，只保留实际可达代码。DEX 与已剥离符号的
arm64 native 库在 APK 内采用 ZIP 压缩，Android 10+ 安装时由 PackageManager 解压；这会
增加少量安装工作，且安装后磁盘占用可能略高，但不改变运行时代码和功能。

当前本地 Release 产物：

- `PureBrowser-v0.3.1-release.apk`
- 2,041,130 bytes（约 1.95 MiB）
- SHA-256：`a2b7d691526940e1f4496a69530651e6173983e544884b3f53f3848d7ba1e594`
- APK Signature Scheme v2：通过

安装包已使用「青叶 P」图标，包括自适应和主题单色资源。
[图标目录](./design/app-icon/README.md) 提供 [并排预览](./design/app-icon/comparison.png) 及可切换背景的 HTML；
旧图标原始 XML 作为设计快照保留，设计预览不进入 APK。

只运行 Android 单元测试或 lint：

```bash
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
```

连接设备后安装并冷启动：

```bash
./install_and_test.sh PureBrowser-v0.3.1-release.apk
```

诊断设备与崩溃日志：

```bash
./diagnose.sh
```

模拟器结果以 [回归报告](EMULATOR_TEST_REPORT.md) 记录的当前 APK 与系统版本为准。
本次最终包已通过 Android 10/14 的标准视频、自定义网页播放器和跨域场景回归，Android 14
另通过 Blob 全流程；真实 YouTube 在线播放与实体 DLNA 接收器尚未验收。
本地测试页及 Release APK 的真实触摸回归可重复运行：

```bash
python3 validation/qa-server.py
# 在另一个终端运行；替换为当前模拟器序号。
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/home-shortcut-regression.py --serial emulator-5554
python3 validation/productivity-regression.py --serial emulator-5554
ANDROID_SERIAL=emulator-5554 python3 validation/emulator-ux.py regress
python3 validation/settings-regression.py --serial emulator-5554
python3 validation/settings-back-regression.py --serial emulator-5554
python3 validation/download-regression.py --serial emulator-5554
python3 validation/video-regression.py --serial emulator-5554 --variant standard
# 其他媒体夹具：--variant square / blob / cross / custom
# Android 13+ 可额外验证应用语言切换：
python3 validation/locale-regression.py --serial emulator-5556
python3 validation/productivity-visual-regression.py --serial emulator-5556
```

测试服务器只监听本机的 8875/8876 端口，脚本自动设置 ADB 反向端口。
截图、遥测和性能结果位于 `validation/results/`，不纳入 Git。UI 读取辅助程序仅安装到模拟器
的 `/data/local/tmp`，Release APK 不开放 WebView 调试。

首页编辑回归仅接受可 root 的模拟器，会暂存并恢复其首页偏好，验证图片选取、校验与取消、
草稿隔离、图片文件清理、保存后重启、旋转与大字体、删除及书签保留；结果记录设备 APK 的
SHA-256。每个版本的实际执行范围见回归报告，旧版本的
下载、视频和性能结果不自动计入新版。
新增 productivity 回归只接受模拟器，会通过 `adb root` 写入 123 条测试书签/历史记录，
用于验证跨页搜索和分页；不要用于存有个人浏览数据的设备。

Lint 为 0 errors / 3 warnings：AGP 更新提示、ChromeOS x86_64 支持提示，以及 `localeConfig`
在 Android 13 以下不生效的提示；低版本仍通过语言资源正常跟随系统语言。
真实 DLNA 投送、厂商 WebView、摄像头/麦克风和第三方 DRM 网站仍需实体设备验证。

Android 10 支持始于 0.2.0，minSdk 降低本身不会改变新系统的运行路径。此前 0.1.0 与
0.2.0 的模拟器对照未观察到明显启动退化，属于历史结果，不能代表当前版本。
0.3.0 移除了切换标签时重复编码缩略图的路径；本轮未做完整性能基准，不宣称真机提速。

## 相关文档

- [架构审查与 Rust 决策](./ARCHITECTURE_REVIEW.md)
- [模拟器回归报告](./EMULATOR_TEST_REPORT.md)
- [APK 测试指南](./APK_TEST_GUIDE.md)
- [测试与验证指南](./TESTING_GUIDE.md)
- [最终交付摘要](./FINAL_DELIVERY_SUMMARY.md)
- [更新日志](./CHANGELOG.md)
- [第三方图标说明](./THIRD_PARTY_NOTICES.md)
- [播放器与投屏方案](./design/video-playback-and-casting.md)

## 维护约定

README 是项目的入口和当前能力基线。后续每次修改用户可见功能、行为、架构、依赖、构建
方式、验证结果或交付 APK 时，都必须在同一次变更中同步更新本 README；不能让实现、测试
数量、环境要求或校验值与 README 脱节。

本轮修改分支为 `feat/browser-productivity-20260907`。上个版本的回滚分支为
`backup/pre-home-shortcut-editor-20260907`（`bf75a92`，0.3.0）。本轮收尾前的未提交编辑器草稿
另保存在 `backup/pre-home-shortcut-completion-20260908`（`cdbd083`）。首页编辑器与新图标资源的
检查点为 `checkpoint/pre-video-redesign-20260908`（`04f6628`），本轮视频调整从该检查点继续。
设置返回修复前的图标/视频完成版本保存在 `checkpoint/pre-settings-back-fix-20260908`（`c78ac47`）。
