# 架构说明

Pure 浏览器使用 Kotlin / Compose 管理界面和 Android 生命周期，WebView 负责网页渲染，
Rust 处理过滤与解析，JavaScript 观察和控制网页中的媒体及用户脚本。
本文是查找代码和理解跨模块约定的入口，构建和运行见[贡献指南](CONTRIBUTING.md)。

## 代码地图

Kotlin 源码位于 [app/src/main/java/com/mybrowser](app/src/main/java/com/mybrowser/)。

| 入口或目录 | 职责 |
|---|---|
| `App` | 进程级仓库、更新检查、系统媒体及投屏控制器 |
| `MainActivity`、`core` | Android 回调、WebView 配置与池、导航、共享类型 |
| `ui` | Compose 界面，按 shell、settings、library、player 等功能组织 |
| `tabs`、`data`、`home`、`search` | 标签、书签与历史、首页和搜索 |
| `site`、`privacy`、`security` | 网站设置、权限、Profile、证书和外部协议 |
| `download`、`update` | 文件下载与续传；应用更新下载及 APK 校验 |
| `filter`、`userscript` | 过滤订阅、脚本安装与注入 |
| `media`、`dlna`、`qr` | 视频与系统媒体、局域网投屏、Camera2 / ZXing 扫码 |
| [rust](rust/) | `adblock`、`url_utils` 两个 JNI 库，共享 `site_identity` |
| [app/src/main/assets](app/src/main/assets/) | 媒体探针、用户脚本运行时、过滤规则 |
| [validation](validation/) | Node 测试、网页夹具、模拟器回归脚本 |
| [release](release/) | 校验签名 APK、生成更新附件、创建 Release 草稿 |

数据库、下载和权限留在 Android 层，便于直接使用 SQLite、SAF、MediaStore、Cookie 和系统回调。
共享类型放在 `core`；`ResourceType` 的枚举顺序属于 JNI 契约，修改时需同步 Rust。
`media` 使用 Compose 渲染全屏控件，因此它也依赖部分 UI 组件。

## 状态与异步回调

`App` 持有需跨 Activity 重建的仓库和操作，包括启动更新检查及待处理提示。
`BrowserSessionState` 保留当前会话的标签元数据和临时无痕设置，无痕状态不写入磁盘。

WebView、文档、弹层和媒体操作都有自己的实例或代次标识。异步回执到达时校验所有者，
避免导航、关闭、重新打开后的旧结果覆盖当前状态。网站授权、文件上传、证书提示和媒体命令尤其依赖这项约定。

书签和历史写入成功后才更新界面；异步搜索丢弃过期结果。
设置仓库在进程内共享写锁，文件存储采用有界读取和原子写入。

## WebView 与标签

`MainActivity` 管理当前页面，`RecentTabStore` 保存普通后台页面。
离开时暂停媒体，重新访问时取回原 WebView；驻留数量随标签数增长。
收到内存压力时按最早停放顺序回收，`TRIM_MEMORY_COMPLETE` 清空驻留页，`UI_HIDDEN` 本身不触发回收。

新窗口使用全新 WebView，交接完成后保留来源页。新实例配置失败时，先清理媒体追踪器和脚本运行时，
再从池中丢弃；内存不足重试前回收最老驻留页。归还池时解除监听，避免持有旧 Activity。

`TabManager` 保存当前会话的来源关系和访问顺序。返回先消费网页历史，再关闭子标签；
选择来源页或最近存活标签。关系不跨普通/无痕管理器，也不持久化。

同标签跨文档历史由 WebView 管理。`WebViewConfig.applyBackForwardCache` 按能力探测启用普通页缓存，
无痕关闭；开关可用不代表文档一定保留。测试应核对文档身份和表单状态，不能只检查 URL 或滚动位置。

## 菜单与窗口

`BrowserSheetNavigation` 只允许“菜单 → 一个子页面”，设置内部管理自己的分类和选项。
路由 key 恢复滚动与分类，每次 `Presentation` 提供新的回调身份。

`BrowserSheetHost` 在菜单路径内保留一个 `BrowserSheetWindow`，切换时只替换内容。
当前页面拥有返回处理，旧页面释放只能清理自己的回调。
嵌套选项和确认窗口停用父级返回；外部网址导航关闭临时页面，普通后台切回保留界面。

底部面板位置固定，列表关闭边界拉伸。动画参数集中在 `BrowserMotion`，
底部面板和全屏页由 `BrowserSheetWindow` 共用进入、换页和换容器逻辑。
地址栏建议放在 Activity 内，避免独立窗口打断输入法和编辑状态。

## 媒体与投屏

增强播放控制网页已经加载的 `<video>`，沿用 WebView 解码、网络凭据和音频焦点。
应用额外申请音频焦点可能抢占 Chromium 并使视频暂停，因此只管理系统媒体入口和生命周期。

`playback-probe.js` 负责逐 frame 观察和命令确认。`MediaPlaybackTracker` 校验 frame、视频身份及交接代次。
进入全屏后，先确认视频及网站控件能被隔离，再启用原生触摸层；CSP、布局、目标变化或超时导致失败时恢复网页控件。
退出时恢复 DOM 属性，不改写 `src`、不移动视频或额外调用 `load()`。
消息桥和文档开始注入分别按 WebView 能力检测，旧 Provider 使用可访问范围内的降级路径。

`FullscreenVideoView` 挂在 Activity content 容器，`PlayerControls` 在同一视图中绘制控件及倍速、投屏浮层。
安全边距只作用于控件，避免改变视频尺寸。PiP 复用原视图，使用进入前的画面区域。
暂停可保留继续播放入口；停止、视频消失、导航或关闭 PiP 时释放系统会话、通知和服务，
消失 frame 的旧状态也需超时清理。后台服务的启动与停止按请求顺序处理。

`MediaCandidateStore` 将网络线索和实际视频来源关联，保留完整签名参数。
DLNA 的 `CastController` 由进程持有，发现和轮询随面板可见性启停，控制动作串行执行。
SetURI / Play 被接受与设备返回 PLAYING 是不同状态；接收器直接读取媒体地址，应用不转码或转发网页凭据。

## 网站身份与隐私

网站设置和权限使用完整 origin。桌面显示偏好单独允许同协议、同端口的网站入口别名共享。
`site_identity` 提供 PSL / IDNA 判断，其中 PRIVATE 部分用于区分公共托管服务上的不同站点。
升级 PSL 时同步验证过滤和桌面别名，并重新构建两个 JNI 库。

无痕优先使用每次会话唯一的 WebView Profile。退出先擦除数据并停用该名称，
无法立即删除的 Profile 在下次进程启动时清理；清理期间阻止重新进入无痕。
降级模式共享存储的影响必须在界面说明。Autofill、WebAuthn 和用户脚本在无痕关闭。

WebAuthn 同时依赖 WebView 能力、来源权限和凭据提供方信任，能力开关为真不能证明真实账号可以登录。
用户脚本在网页环境运行，原生存储桥不等于隔离执行环境。

## 下载、更新与构建

下载恢复核对 validator、长度、最终 URL 和分段边界，不能确认同一实体时完整重下。
每个任务共享写入锁，取消和删除等待旧 writer 结束；文件发布成功后才显示完成。
Cookie 仅驻留内存，并且只向原 origin 发送。系统文件打开统一交给 Android 处理。

应用更新单独校验 GitHub 下载地址、文件摘要、包名、版本、SDK、ABI 和签名。
更新 APK 与相机输出使用不同的 FileProvider 组件类及目录，避免 URI 授权混用。

Gradle 和独立 Rust 构建共用 [rust/build.sh](rust/build.sh)，NDK 由
[resolve-android-ndk.sh](rust/resolve-android-ndk.sh) 解析。JNI 库从源码生成，依赖版本和校验值由 lockfile、
Gradle verification metadata 固定。检查入口见[测试指南](TESTING_GUIDE.md)，签名和发布见[发布指南](RELEASING.md)。
