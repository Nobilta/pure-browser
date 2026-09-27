# 架构说明

Pure 浏览器使用 Kotlin / Compose 管理界面和 Android 生命周期，WebView 负责网页渲染，
Rust 处理过滤与解析，JavaScript 观察和控制网页中的媒体及用户脚本。
本文是查找代码和理解跨模块约定的入口：用户可见的行为和限制见[功能介绍](FEATURES.md)，
构建和运行见[贡献指南](CONTRIBUTING.md)。

## 代码地图

Kotlin 源码位于 [app/src/main/java/com/mybrowser](app/src/main/java/com/mybrowser/)。

| 入口或目录 | 职责 |
|---|---|
| `App` | 进程级仓库、更新检查、系统媒体及投屏控制器 |
| `MainActivity`、`core` | Android 回调、WebView 配置与池、导航、共享类型 |
| `ui` | Compose 界面，按 shell、settings、library、player 等功能组织 |
| `tabs`、`data`、`home`、`search` | 标签、书签与历史、首页和搜索 |
| `site`、`privacy`、`security` | 网站设置、权限、Profile、证书和外部协议 |
| `backup` | 设置、书签与历史的导入导出编解码、预览与分组应用 |
| `download`、`update` | 文件下载与续传；应用更新下载及 APK 校验 |
| `filter`、`userscript` | 过滤订阅、脚本安装与注入 |
| `media`、`dlna`、`qr` | 视频与系统媒体、局域网投屏、Camera2 / ZXing 扫码 |
| [rust](rust/) | `adblock`、`url_utils` 两个 JNI 库，共享 `site_identity` |
| [app/src/main/assets](app/src/main/assets/) | 媒体探针、用户脚本运行时、过滤规则 |
| [validation](validation/) | Node 测试、网页夹具、模拟器回归脚本 |
| [release](release/) | 校验签名 APK、生成更新附件、创建 Release 草稿 |

数据库、下载和权限留在 Android 层，便于直接使用 SQLite、SAF、MediaStore、Cookie 和系统回调。
共享类型放在 `core`；`ResourceType` 和 `SourceToken` 的枚举顺序属于 JNI 契约，修改时需同步 Rust。
`url_utils` 还负责书签导入和源码词法高亮；高亮通过一次有界 JNI 调用返回 UTF-16 区间，Kotlin 负责文本分块与展示。
`media` 使用 Compose 渲染全屏控件，因此它也依赖部分 UI 组件。

## 状态与异步回调

`App` 持有需跨 Activity 重建的仓库和操作，包括启动更新检查及待处理提示；
`BrowserSessionState` 保留当前会话的标签元数据。
WebView、文档、弹层和媒体操作都有自己的实例或代次标识，异步回执到达时校验所有者，
避免导航、关闭、重新打开后的旧结果覆盖当前状态；网站授权、文件上传、证书提示和媒体命令尤其依赖这项约定。
书签和历史写入成功后才更新界面；当前页书签查询由 `BookmarkStatusTracker` 管理，写入成功会取消旧查询并递增代次。
设置仓库在进程内共享写锁，文件存储采用有界读取和原子写入。

## WebView 与标签

`MainActivity` 管理当前页面，`RecentTabStore` 保存普通后台页面：离开时暂停媒体，重新访问时取回原 WebView。
收到内存压力时按最早停放顺序回收，`TRIM_MEMORY_COMPLETE` 清空普通驻留页并终止后台窗口组，
保留标签 URL 供再次访问时重载；`UI_HIDDEN` 本身不触发回收。
`WebViewPool` 由 Activity 持有，直接使用该 Activity 创建实例，避免 Autofill 在初始化时绑定到 Application；
实例不跨 Activity 复用，销毁宿主时统一清理，待交接的原生窗口在 Chromium 消费 transport 后销毁。
新实例配置失败时，先清理媒体追踪器和脚本运行时，再从实例管理器丢弃。
`PageAcquisition` 统一分配失败清理和内存不足重试，`MediaTrackerRegistry` 管理每个 WebView 的媒体追踪器。
`PopupWebViewClient` 让后台窗口继续网络与导航，同时将 UI 回调与前台隔离；
`PopupSessionStore` 单独记录真实 JavaScript 窗口关系，与手动新标签的返回关系分开。
后台请求使用自身文档的过滤配置，不更新前台媒体、日志或过滤计数，也不弹权限框或启动外部应用；
原页面最后一个弹窗关闭后，若尚未回到原页面，继续保留以完成回调请求。
`TabManager` 保存当前会话的来源关系和访问顺序：返回先消费网页历史，再关闭子标签，
优先回到来源页或最近存活标签；关系不跨普通/无痕管理器，也不持久化。
同标签跨文档历史由 WebView 管理，`WebViewConfig.applyBackForwardCache` 按能力探测启用普通页缓存、
无痕关闭；开关可用不代表文档一定保留，测试应核对文档身份和表单状态，不能只检查 URL 或滚动位置。

## 菜单与窗口

`FullscreenPreferenceState` 在会话中保留立即退出的回退状态，跨 Activity 重建或恢复不会被旧磁盘值覆盖；
启用须确认保存，串行写入和代次检查防止旧启用回写。
`BrowserSheetNavigation` 只允许“菜单 → 一个子页面”，设置内部管理自己的分类和选项；
路由 key 恢复滚动与分类，每次 `Presentation` 提供新的回调身份。
`BrowserSheetHost` 在菜单路径内保留一个 `BrowserSheetWindow`，切换时只替换内容；
当前页面拥有返回处理，旧页面释放只能清理自己的回调，嵌套选项和确认窗口停用父级返回。
动画参数集中在 `BrowserMotion`，按"谁驱动这次动作"分成两类，用的是 Material 3 里各自对应的那一种：
用户主动唤起的转场用规范自己的缓动曲线与时长令牌，手指或滚动驱动的动效用弹簧。
这个划分不是风格选择，而是形状问题：`emphasised decelerate` 在时长的头 10% 里走完 62% 的距离（前置），
而弹簧从静止出发是二阶响应，到 90% 要用掉三分之二的时间（偏后）；把弹簧按最贴近的方式拟合到该曲线上，
头 10% 仍差 21% 的距离。所以 `panelArrive`/`windowArrive`/`pageArrive` 等直接用规范曲线，
`chromeSpatial`（滚动收起工具栏、列表行让位）与 `resume`（被打断或手势松手后接着走）用弹簧——
后者是两者之间的桥：只有弹簧能从一个速度开始，曲线中途重放会让表面先停住。
退出沿用规范的 accelerate 曲线（本身偏后，中点仅 15%），并按其"退出短于进入"的规则配更短的时长，
压力测试见 `BrowserMotionTest`（形状、时长、单调性、退出短于进入都断言）。
`BrowserSheetWindow` 的面板与遮罩共用同一条进度：遮罩直接由面板的进度算出。
给遮罩单独一条曲线是量过两次的错法：跑得更快的那条让它比要遮的表面早到很多，
与表面同长的线性那条则让表面走完 81% 行程时遮罩才到 19%——眼睛看到的是两件事。
入场因此用 `easing.emphasized`（与 `standard` 同一条曲线，头 10% 走 16%、三分之一处 74%），
面板与遮罩同源，不可能走散；遮罩值仍做 `coerceIn(0, 1)`，以免欠阻尼的收尾把它推过目标。
`ApplySheetSystemBars` 的窗口几何与 edge-to-edge 标志当帧应用一次、再按原样 post 一次（幂等），
后者用于压过库在组合之后写入的系统栏偏好；只放在 `view.post` 会让窗口出现后的第一帧仍带着错误的 inset 约束。
`BrowserSheetHost` 用「上一次退出是否已结束」（初始 true，打开路由时清除）而不是「正在关闭」来决定窗口是否保留。
后者只在 `LaunchedEffect` 里被置位，也就是清空路由的下一帧，于是中间那一帧没有页面可显示：
窗口被销毁、紧接着为播退出动画又重建，重建出来的那一帧用的是仍接近 1 的进度——关闭时遮罩闪一下就是这么来的
（逐帧量到关闭后最后一帧遮罩为 0.94）。前者不依赖任何「后写入」的值，从有路由到无路由的过渡里页面始终存在。
退出动画在协程被取消时也在 `NonCancellable` 内 `snapTo(0f)`，避免残留中间值成为窗口的最后一帧。
面板只有停稳后才接受手指（动效进行中的表面会拒绝手势），避免窗口动画与手指同时移动同一个值；
拖动关闭由手指直接写入表面位置，松手把位置与速度交给同一条退出弹簧，
`SheetWindowProgress` 负责这次交接；被打断的判断同步读取当前进度（动画结束正好落在端点上），
不依赖被取消协程的收尾，以免与接替它的动画抢时序。
底部面板与全屏页共用进入、换页和换容器逻辑。
由页面自己打开、靠卸载退出的面板没有可运行的动画，其退出仍由窗口动画承担，
`res/anim/browser_sheet_exit.xml` 必须与 Compose 驱动的退出同向同曲线。
滚到边缘的回弹保留系统默认值，只有确需不随之移动的表单在自己的滚动处关闭。

## 设置导入与导出

导入按分组独立提交，`ConfirmedPreferences.commitConfirmed` 检查磁盘写入结果，失败时恢复涉及键的内存值
并尝试恢复磁盘，恢复失败仍报告该组失败；分组之间不构成整体事务，结果明确报告成功与失败的分组。
失败的分组只上报标识，异常同时写入日志——用户无法处理堆栈，但排查需要它。
过滤订阅清单保存在 `filter_settings` 的 `subscriptions_manifest` 中，与全局过滤和自动更新开关一起提交，
成功后才发布状态；旧 `filter_subscriptions/subscriptions.json` 在首次成功写入时迁移。
`SettingsTransfer.collect` 等待订阅初始化并使用同一份列表快照；导入结果携带首次规则待下载数，
配置提交后由 App 的进程协程调用 `updateMissing`。备份的合并规则与上限见[功能介绍](FEATURES.md)。
导入元数据在解码时限制长度；`ImportPreviewText` 在格式化与排版前截断预览文本。

## 媒体与投屏

增强播放控制网页已经加载的 `<video>`，沿用 WebView 解码、网络凭据和音频焦点；
应用额外申请音频焦点可能抢占 Chromium 并使视频暂停，因此只管理系统媒体入口和生命周期。
`playback-probe.js` 负责逐 frame 观察和命令确认，`MediaPlaybackTracker` 校验 frame、视频身份及交接代次。
画面比例写在接管样式表里，随接管结束自动消失，页面不应留下被改过形状的视频。
镜像写在元素的行内样式上，因为全屏元素会被浏览器 UA 样式表的 `!important` 规则强制
`transform:none`（`rotate`、`filter` 同样被重置），作者来源的声明——包括行内 `!important`——都压不过它，
只有单独的 `scale` 属性不在重置范围内；探针按 `transform` → `scale` 的顺序逐项下发并回读计算值，
两项都被拒绝时才逐层上移（视频 → 祖先 → 接管根），全部失败则记录原因而不是假装已翻转。
进入全屏后先确认视频及网站控件能被隔离，再启用原生触摸层；失败时恢复网页控件，
退出时恢复 DOM 属性，不改写 `src`、不移动视频或额外调用 `load()`。
消息桥和文档开始注入分别按 WebView 能力检测，旧 Provider 使用可访问范围内的降级路径。
`FullscreenVideoView` 挂在 Activity content 容器，`PlayerControls` 在同一视图中绘制控件及浮层，安全边距只作用于控件。
PiP 复用原视图，使用进入前的画面区域；停止、视频消失、导航或关闭 PiP 时释放系统会话、通知和服务，
消失 frame 的旧状态需超时清理，后台服务的启动与停止按请求顺序处理。
`MediaCandidateStore` 将网络线索和实际视频来源关联，保留完整签名参数。
DLNA 的 `CastController` 由进程持有，发现和轮询随面板可见性启停，控制动作串行执行；
SetURI / Play 被接受与设备返回 PLAYING 是不同状态。
设备发现用 SSDP：向 `239.255.255.250:1900` 发 M-SEARCH（包含 `ssdp:all`），收单播回复，再取设备描述。
**`targetSdk` 固定在 35，不要顺手升到 36+**：Android 16 起对 `targetSdk` ≥ 36 的应用硬性拒绝
本地网络与组播流量——平台把 `ACCESS_LOCAL_NETWORK` 这个 app op 强制设为 `ignore`，连 shell 都改不动，
也不存在可声明或可申请的权限对应它，于是发往局域网地址与组播组的包全部在发送阶段被 `EPERM` 拒绝，
SSDP 一个包都发不出去、永远发现不了设备。在 API 37 上用同一份源码只改 `targetSdk` 实测：
34/35 正常，36/37 全部被拒；35 是仍强制 edge-to-edge 的最高值，所以界面不受影响。
将来 Android 提供可申请的权限后，声明并申请它，再把 `targetSdk` 升回去。

## 网站身份与隐私

网站设置和权限使用完整 origin；桌面显示偏好单独允许同协议、同端口的网站入口别名共享。
`site_identity` 提供 PSL / IDNA 判断，其中 PRIVATE 部分用于区分公共托管服务上的不同站点；
升级 PSL 时同步验证过滤和桌面别名，并重新构建两个 JNI 库。
网站设置的不可变快照包含桌面别名索引，读取时不再扫描所有站点；文件损坏时阻止普通写入并提供确认重置入口。
无痕优先使用每次会话唯一的 WebView Profile，退出先擦除数据并停用该名称，无法立即删除的 Profile 在下次进程启动时清理。
`PrivacyMode` 和 `PrivacyCleanupBarrier` 由 App 持有，清理期间加载/恢复入口等待，失败保持阻塞并允许重试；
新 Activity 订阅同一清理状态，销毁旧窗口不会取消清理。
`BrowserSessionState` 在冷启动、显式切换与最终清理时统一轮换/结束下载会话标识。
WebAuthn 同时依赖 WebView 能力、来源权限和凭据提供方信任，能力开关为真不能证明真实账号可以登录。
用户脚本在网页环境运行，原生存储桥不等于隔离执行环境。

## 下载、更新与构建

下载请求先经 `DownloadRequestCoordinator` 汇聚：确认、预算和拦截记录按标签文档保存，
前台只暴露所选标签状态，主文档导航或关页清理，后台窗口导航也重置自身状态。
`DownloadPresenter` 管理任务操作、文件打开及结果提示；WebView 提供的 contentLength 传至确认框。
`DownloadIdentity` 是模式相关下载策略的唯一出处：凭据来源、持久化范围、通知与可恢复性都由它决定，
新增差异应加在这里而不是散落成新的布尔判断。
下载恢复核对 validator、长度、最终 URL 和分段边界，不能确认同一实体时完整重下；
每个任务共享写入锁，取消和删除等待旧 writer 结束，文件发布成功后才显示完成。
Cookie 仅驻留内存，并且只向原 origin 发送；系统文件打开统一交给 Android 处理。
HTTP 客户端的重定向规则集中在 `core/RedirectPolicy`（跳数上限、禁止降级到 http），
更新器保留自己的遍历，因为它逐跳复核主机白名单并映射为类型化失败。
应用更新单独校验 GitHub 下载地址、文件摘要、包名、版本、SDK、ABI 和签名；
更新 APK 与相机输出使用不同的 FileProvider 组件类及目录，避免 URI 授权混用。
Gradle 和独立 Rust 构建共用 [rust/build.sh](rust/build.sh)，NDK 由
[resolve-android-ndk.sh](rust/resolve-android-ndk.sh) 解析。JNI 库从源码生成，依赖版本和校验值由 lockfile、
Gradle verification metadata 固定。检查入口见[测试指南](TESTING_GUIDE.md)，签名和发布见[发布指南](RELEASING.md)。
