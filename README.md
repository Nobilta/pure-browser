# Pure 浏览器

面向 Android 10 及以上设备的轻量浏览器，使用 Kotlin、Jetpack Compose、Android WebView 和 Rust。
当前版本 **0.7.2（versionCode 13）**，安装包仅支持 `arm64-v8a`，applicationId 保持 `com.mybrowser`，可覆盖升级。

本版移除菜单中的分享网页、复制链接、打印/PDF，以及最近关闭标签和撤销关闭；精简对应代码、资源和过时测试。
设置使用独立全屏窗口处理返回，网站设置固定弹层位置并关闭边界拉伸，修复返回残留和滚动抖动。
保留普通多标签、书签 HTML 导入导出、DLNA、系统密码集成、画中画及后台媒体。翻译和跨设备同步暂不实现。

## 功能

### 浏览、界面与标签

- WebView 浏览、前进/后退、刷新/停止、主页、网页弹窗、页内查找、文件上传和全屏视频。
- 地址栏识别网址与搜索词，可放在顶部或底部；支持搜索引擎选择、历史/书签建议、补全与明确搜索动作。
  未编辑时显示标题和站点，点击后编辑完整 URL；页面滚动时自动收起/展开顶部地址栏。
  建议与编辑器共用窗口，点击软键盘、清除或建议补全保持编辑；横竖屏切换保留草稿，提交、点击页面或返回结束编辑。
- Material 3 界面、系统/浅色/深色主题、Android 12+ 动态配色；简体中文、繁体中文和英文。
  Android 13+ 支持系统应用语言设置，旧系统跟随系统语言。
- 设置按浏览与启动、外观、隐私与过滤、下载、视频、关于分类，支持搜索定位；宽屏可显示分类与详情两栏。
  菜单和设置逐级返回，保留滚动与分类；Activity 重建保留当前来路，外部网址导航关闭临时面板。
  菜单只保留一个子页面，设置的画面与返回回调由同一个独立全屏窗口持有，退出时一起释放；开发者工具仅在菜单提供入口。
- 链接长按支持新标签、后台打开、复制和分享；图片支持打开、保存和复制地址，普通文字保留原生选区。
- 标签搜索、排序、命名分组、缩略图和批量关闭确认；关闭标签后释放其记录，最后一个标签关闭后回到新标签。
  升级时清理旧版最近关闭存档。
  后台新标签延迟加载；普通标签最多保留最近一个后台页面的 DOM/表单/SPA/滚动，低内存设备不驻留。
  切换标签暂停旧页媒体；进程结束后只恢复元数据，不承诺恢复 JavaScript 或未提交表单。
- 冷启动默认打开主页；可开启恢复普通标签的地址、标题与选中项。无痕标签不写入恢复文件。
  同进程的配置重建由单 Activity 的 `BrowserSessionState` 保留标签和临时私密状态。
- 长按返回查看当前标签历史，长按标签按钮新建标签，可选底栏滑动切换；支持 Ctrl+L/T/W/R/F/Tab。
- 系统默认浏览器设置、分享网址接收、手机桌面网站快捷方式；退出浏览器会移除最近任务。

### 首页、书签与历史

- 导航首页或固定网址主页；快捷入口可改标题、网址、文字图标或导入图片，长按统一编辑和删除确认。
  图片缩至最长边 96px，经系统文件选择器读取；编辑/删除快捷入口不修改书签。
- 书签增删改、文件夹、排序、移动和批量操作；最多 256 个目录、16 层，删除目录将内容移到父目录。
- 系统文件选择器导入/导出 UTF-8 Netscape HTML，最多 5,000 条/8 MiB；导入先预览，保留层级与空目录，
  跳过非法与重复地址，保留已有条目 ID/标题，整个批次使用 SQLite 事务。
- 书签、历史按数据库分页搜索，每页 50 条；历史合并重复访问、按日期分组，支持新标签打开、复制和删除。
  旧查询不能覆盖新搜索；写入成功才更新界面，失败可重试。

### 下载与系统打开文件

- 系统 Download 目录或经 Storage Access Framework 授权的自定义目录；同名文件自动编号。
- 列表显示下载进度、已下载/总大小、速度、预计剩余时间、排队、等待网络及保存状态。
  总大小未知时显示已下载字节和不定进度，列表及通知都不伪报百分比。
- **点击已完成的下载条目，直接调用 Android 打开文件。** 浏览器不增加“打开/安装”按钮，
  不判断是否为 APK，也不提供自己的安装确认或来源授权流程。使用可读 `content:` URI、`ACTION_VIEW`
  和临时读取授权，由系统根据内容提供方解析 MIME、选择查看器或安装器。
  系统仍可能显示应用选择、来源授权或安装确认。文件丢失、目录授权失效或没有可用应用时给出对应提示。
- HTTP Range 分段，线程设置 1–16（默认 4）；最多同时运行 3 个任务、每主机 2 个任务，
  连接总预算 8 个、每个实际连接主机 4 个，重定向也计入预算。
- 支持暂停/继续、取消、失败重试，以及仅删除记录或同时删除文件；文件删除失败时保留记录。
  正在运行的普通下载可在进程重启后恢复，主动暂停的任务保持暂停，无痕下载不跨进程自动恢复。
- 续传校验 ETag/Last-Modified、总长、最终实体 URL 与分段边界；无法确认同一实体则完整重下。
  不接受错误的 206/范围/长度，也不会把单流的未经请求部分响应作为完整文件。
- 网络中断等待恢复，临时错误有限退避重试；可设为仅不计流量网络下载。后台使用 `dataSync` 前台服务。
  MediaStore/SAF 发布完成后才显示完成，取消/删除等待写入结束再清理分段。
- Android 13+ 首次下载请求通知权限，拒绝不影响下载；普通任务完成后通知可返回对应条目。
  点击通知也能从冷启动进入下载列表；打开或删除记录会清理对应完成通知。
- 下载使用网页 User-Agent/Referer 及当前 Profile 的 Cookie；Cookie 只驻留内存，重定向只向原 origin 发送，
  最多 5 次跳转并拒绝 HTTPS 降级。保留旧 Android DownloadManager 记录的查询、打开和删除兼容。

### 视频播放器、系统媒体与 DLNA

- **增强控件默认尝试接管网页内和全屏视频**，网站自带播放器不再直接排除。
  控制原来已加载的 `<video>`，不复制媒体、不改写 `src`、不再启动一份解码器。
- 网页内提供播放/暂停、进度、倍速、静音、全屏和恢复网页控件；适配小至 160×90 CSS px 的可见视频。
  临时控制层随滚动、缩放和布局移动；屏幕外的视频释放控制层，每个文档最多同时接管 8 个。
- 接管时限定隐藏当前播放器范围内的原控件，验证原画面边界、样式和遮挡。
  无法隔离、样式受限/丢失、命令失败或目标失效时恢复网页；用户主动恢复后不反复抢回同一视频。
  新视频/新来源可以重新尝试。进入全屏先撤下内嵌层，退出后再恢复单个内嵌层。
- 全屏有播放、进度、0.5×–3× 倍速、旋转、锁定、网页控件和投屏入口。单击显隐；双击中央播放/暂停、
  两侧跳转 10 秒；横滑预览进度，左右竖滑分别调亮度/音量；长按临时 2×/3×，松开恢复。
  锁定后返回先解锁，退出恢复方向、亮度和常亮状态；闲置自动隐藏不被遥测刷新打断。
- HTTP(S)、签名链接和 Blob 视频使用相同原视频控制路径；MSE 仍由网站/WebView 管理。
  清晰度、弹幕、网站 DOM 字幕等专属操作可切回网页控件。没有可访问视频元素或无法隔离的播放器仍使用网页原控件。
- 支持逐 frame 消息和文档开始脚本的 WebView 可以控制跨域视频；旧 Provider 仅支持主文档及可访问同源 frame，
  无法控制的跨域播放器保留原状。能力由 WebView 特性决定，不只看 Android 版本。
  仅缺文档开始注入、仍支持消息桥的 Provider 在页面加载后注入并保留实时播放状态回传，避免快速切后台时因轮询延迟误暂停。
- 普通全屏视频支持 Android 画中画；系统媒体按钮可播放、暂停、停止、跳转。后台播放默认关闭，
  开启后当前普通页通过媒体前台服务继续播放；无痕隐藏时暂停且不发布系统媒体标题。
- 当前视频普通暂停可继续播放；结束、隐藏并暂停、卸载、移除、替换为待播视频、关闭标签或导航时，
  释放旧系统媒体会话、标题、通知和媒体前台服务。消失的跨域 frame 即使没有最后一条消息，也会在 4 秒未更新后失效。
  关闭系统画中画会暂停视频并释放会话，开启后台播放也不会让已关闭的小窗继续播放。
- 网络请求及已加载 `currentSrc` 提供有界媒体候选，保留完整签名参数，并标记实际匹配的当前播放来源。
  页面只有一个投屏入口，全屏时在控制栏内提供；缺少候选会明确说明。
- DLNA 支持 SSDP 发现、发送地址、播放/暂停/停止、进度及设备音量，区分命令被接受和设备报告正在播放。
  候选地址由接收器直接读取，不代理 Cookie/Referer、不转码；Blob、DRM 和鉴权媒体不保证能直投。
  本版未用实体接收器确认画面，也未宣称所有在线网站兼容。

具体控制权、回退和投屏边界见 [播放器说明](design/video-playback-and-casting.md)。

### 网站、隐私与系统能力

- 网站可单独设置 JavaScript、过滤、图片、第三方 Cookie、桌面模式、网页暗色、50%–200% 字号，
  桌面视口支持网页原值及 980/1024/1280/1440。其他设置按完整 origin 管理；
  桌面模式只在同站同协议/端口的裸域、m./mobile./www. 展示入口间共享，避免移动站重定向循环。
  网站表单与管理列表保持固定位置；滚动到上下边界不会拖动弹层，使用返回或取消关闭。
- 相机、麦克风、定位、受保护媒体标识和外部应用跳转分别管理；网站决定与系统授权分开，导航取消旧请求。
  单文件图片/视频 `input capture` 调用系统相机，普通文件上传使用系统选择器；取消或旧文档回执不会误上传。
- HTTPS 证书信息、忽略证书后的持续警告、Safe Browsing、外部协议限制与 renderer 崩溃恢复。
  同一标签 60 秒内最多自动恢复 renderer 两次，之后提供手动重试、主页和网站设置。
- 按当前/普通/全部 Profile、数据类型及历史时间段清理；单站清理显示实际可注册域范围。
  现代 WebView 支持 Cookie/IndexedDB/CacheStorage 等删除；旧 Provider 如实提示能力范围。
- 普通网页接入系统 Autofill；满足 WebView 能力和来源权限时启用 WebAuthn。无痕关闭这两项。
  密码/passkey 由系统提供方保管；提供方信任审核和真实账号登录未在本版模拟器测试中验证。

无痕边界取决于 WebView：支持 `MULTI_PROFILE` 时每次无痕会话使用新的独立 Profile，退出先清理其站点数据与 Cookie；
已载入内存的 Profile 可能无法当场删除，浏览器不再复用其名称，下次进程启动时按专用前缀清理遗留目录。
清理尚未完成时禁止重新进入无痕，避免新旧会话交错。
不支持时使用临时标签、不写历史、禁用 HTTP 缓存并在退出时清理共享站点存储。
降级模式浏览期间可能共享普通登录态，退出也会清除普通 Cookie，界面会持续提示实际模式。
无痕默认屏蔽截图，最近任务预览始终隐藏；显式创建的书签和下载仍会保留。无痕不隐藏公网 IP。

### 广告过滤、脚本与开发工具

- 默认内置 EasyList、EasyPrivacy、EasyList China 的 2026-09-09 完整快照，首次运行即可使用。
  支持来源启停、自定义 HTTP(S) 订阅、手动/每 7 天不计流量网络自动更新、ETag/Last-Modified 条件请求。
  无效/空/过大/HTML/离线响应保留上次有效规则，引擎在后台构建并原子切换。
- Rust 支持网络规则子集、基础 `##` 元素隐藏和 `#@#` 例外，使用索引与有界 CSS 缓存。
  `$third-party` 使用含 PRIVATE 的 PSL/IDNA 站点身份；不宣称完整 uBlock/AdGuard/scriptlet 兼容。
  来源、哈希与许可见 [第三方说明](THIRD_PARTY_NOTICES.md)。
- 用户脚本可通过网址、粘贴、文件或网页链接预览安装、启停、删除、查看和手动更新。
  支持 `@match/include/exclude/exclude-match/noframes`、start/end/idle、常用 GM 值存储、addStyle、log、openInTab、info，
  最多 8 个 `@require` 及 8 个各 256 KiB 的 `@resource`（文本和二进制资源 API）。
  单脚本 1 MiB、最多 24 个、源码/依赖/资源共 12 MiB，单脚本值存储 64 KiB/256 键。
- 无痕不执行脚本；旧 WebView 只执行页面加载后的 DOM 脚本，跳过依赖原生存储桥的脚本。
  脚本运行于网页环境，不是完整 Tampermonkey 或隔离世界；不提供 `GM_xmlhttpRequest` 和任意原生网络/文件接口。
- 菜单中的开发工具提供有界控制台/网络日志、规则命中解释、命令执行、源码及页面信息；只在可见时批量刷新。
  键盘避让、横屏输入、命令草稿和结果保留；过期页面回执被丢弃。

## 架构与开发

Android UI、生命周期、SQLite、下载、SAF/MediaStore、权限与系统集成保留 Kotlin；
网络过滤、元素隐藏、PSL/IDNA、URL/搜索和书签 HTML 解析使用 Rust JNI；DOM 控制使用 JavaScript。
`Application` 持有下载、过滤、网站设置和 DLNA 仓库；Activity ViewModel 保留单浏览会话。
详见 [架构说明](ARCHITECTURE_REVIEW.md) 和 [系统能力边界](design/system-integration.md)。

```text
app/src/main/java/com/mybrowser/
  core/ data/ home/ tabs/ ui/       浏览、导航、SQLite、标签和界面
  download/ filter/ userscript/    下载、过滤订阅及用户脚本
  privacy/ site/ security/         Profile、网站权限与安全
  media/ dlna/                    视频、系统媒体及 DLNA
app/src/main/assets/               播放控制、脚本运行时和内置规则
rust/                             adblock、site_identity、url_utils
validation/                       可复现页面、自动检查及模拟器工具
```

开发环境：macOS、JDK 17+、Android SDK Platform/Build Tools 37、NDK、Rust stable（arm64 Android target）、
Node.js 18+、Python 3.9+。`minSdk=29`、`compileSdk/targetSdk=37`；Rust 使用 API 29 NDK 链接器。
SDK 从 `local.properties` 或环境变量查找，NDK 由 `rust/resolve-android-ndk.sh` 解析；不修改全局 shell/Cargo 配置。
Gradle 堆上限 2 GiB、Metaspace 768 MiB；低内存机器需停止模拟器再构建。

Compose UI/Foundation/Runtime 1.9.4、Material 3 1.4.0、Activity 1.13.0、WebKit 1.17.0。
依赖图由 `app/gradle.lockfile`、`gradle/verification-metadata.xml`、`rust/Cargo.lock` 固定；
正常构建不自动刷新锁和校验值。验证用 Node 脚本无需第三方运行依赖。

Release 需要本地 `keystore.properties` 指定 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。
签名密钥、配置、`local.properties`、构建输出、APK 及验证结果均不提交 Git。

## 构建、验证与安装

```bash
./build-and-test.sh
./install_and_test.sh PureBrowser-v0.7.2-release.apk
./diagnose.sh
```

完整构建执行三语言资源校验、Node 协议测试、Rust fmt/test/clippy、真实 host JNI 的 Android/Robolectric 测试、
lint、R8 和签名验证。当前自动检查通过 **328 项 Android、58 项 Rust、55 项 Node 测试及 603 项三语言资源校验**。
lint 为 0 errors、10 warnings、1 hint。构建记录：`validation/results/release-0.7.2/build-final.json` 与同名日志。

```bash
python3 validation/qa-server.py --apk PureBrowser-v0.7.2-release.apk
# 另一个终端；专用模拟器只串行运行 UI 回归，构建期间不要同时运行：
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.7.2-release.apk \
  --label release-072 --stages menu-navigation settings-back site layout browser productivity
# 只有 APK、AVD、阶段选择相同时才能加 --resume
```

QA 仅监听本机，通过 ADB reverse 连接；回归会创建夹具书签、下载和文件。
若本机启用了 HTTP 代理，运行回归时为 `127.0.0.1,localhost` 设置 `NO_PROXY` 与 `no_proxy`，确保遥测请求直连本机。
QA 的 `--apk` 与回归参数使用同一个安装包，下载夹具直接读取交付文件，清理构建缓存后仍可复现。
辅助程序仅置于 `/data/local/tmp`，Release 不开放 WebView 调试。runner 按实际安装 APK SHA-256 记录阶段，
保留失败尝试，不把局部验证计作全部设备/网站覆盖。最终包在 Android 17 / WebView 145 上通过 8 个阶段，
Android 10 / WebView 91 上通过 5 个阶段、网站表单边界专项及覆盖升级；旧 WebView 的完整站点切换阶段出现渲染进程崩溃，未计为通过。
阶段、升级链和失败记录见 [回归报告](EMULATOR_TEST_REPORT.md)，
复现与手工验收见 [测试指南](TESTING_GUIDE.md)。

### 交付包

- `PureBrowser-v0.7.2-release.apk`：Android 10+、arm64-v8a，4,661,251 bytes（约 4.45 MiB）。
- SHA-256：`c738eb6c2d19e963ee0d1f00ec2a8d401d74df366e343eee1bd6b32c0180cbc2`。
- APK Signature Scheme v2 与 16 KiB 对齐检查通过，与上一版证书相同；versionCode 12 → 13。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`。

使用 R8 全模式、资源裁剪及压缩 DEX/native 库，只保留必要 JNI 规则。
本地交付只保留最新 APK 及必要验证记录；完整源码使用 `master` 普通提交维护，不创建备份或回滚分支。

## 维护资料

- [实施记录](design/implementation-status.md)、[更新日志](CHANGELOG.md)
- [播放器与 DLNA](design/video-playback-and-casting.md)、[播放器验证](design/enhanced-player-validation.md)
- [菜单返回](design/menu-navigation-20260910.md)、[Material 3 审查](design/md3-ui-audit.md)
- [系统能力边界](design/system-integration.md)、[架构说明](ARCHITECTURE_REVIEW.md)

README 是当前能力、构建与交付入口，后续行为、依赖、验证和产物变化须同步更新。
历史优化数字只说明当时的固定样本；低端真机的性能/功耗、实体 DLNA 画面、真实账号登录和所有在线视频站点不在本次已验证范围。
