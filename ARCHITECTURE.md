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
| `filter`、`userscript` | 过滤订阅与手写规则、元素选择器、脚本安装与注入 |
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
头 10% 仍差 21% 的距离。所以进屏的 `panelArrive` 直接用规范给「Enter the screen」的那一对
（`emphasised decelerate` + 400 ms，规范原文点名 bottom sheet 就是它），`windowArrive`/`pageArrive` 同理；
`chromeSpatial`（滚动收起工具栏、列表行让位）与 `resume`（被打断或手势松手后接着走）用弹簧——
后者是两者之间的桥：只有弹簧能从一个速度开始，曲线中途重放会让表面先停住。
退出沿用规范的 accelerate 曲线（本身偏后，中点仅 15%），并按其"退出短于进入"的规则配更短的时长，
压力测试见 `BrowserMotionTest`（形状、时长、单调性、退出短于进入都断言）。
`BrowserSheetWindow` 的面板与遮罩共用同一条进度：遮罩直接由面板的进度算出。
给遮罩单独一条曲线是量过两次的错法：跑得更快的那条让它比要遮的表面早到很多，
与表面同长的线性那条则让表面走完 81% 行程时遮罩才到 19%——眼睛看到的是两件事。
面板与遮罩同源之后，两者的相对节奏不再由曲线承担，进屏因此就用规范给它的那条曲线本身
（头 10% 走 62%、三分之一处 89%），而不是为了迁就遮罩另选一条更缓的；遮罩值仍做 `coerceIn(0, 1)`，
以免欠阻尼的收尾把它推过目标。
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
系统返回手势（预测性返回）落在可拖动面板上时，走的是和手指拖动同一条通路：
`SheetBackGesture` 把系统给的进度写进同一个 offset，松手或取消再交给同一条弹簧/退出。
还有些必须写下来的边界：启用的 `PredictiveBackHandler` 会**吞掉**它收到的一切返回事件，
而平台不提供"这次是不是预测性手势"的事前查询，所以
- 只有**停稳**的底部面板才注册这个 handler（`visibility` 与 `targetValue` 都为 1），
  正在进场/退场的表面不注册——否则正在退出的面板会被再关一次；
- 一个手势里**没有收到任何进度**（键盘返回、或设备没有预测性手势）时，按普通返回处理：
  仍然是这个面板关闭，而不是把按键吞掉后什么都不发生。这一步是被
  `gesture-dismiss` 阶段逼出来的：菜单用 keyevent 返回关闭，注册了 handler 之后
  那次返回被吞掉，菜单关不上；
- `BackEventCompat` 不含速度，速度由 `frameTimeMillis` 上的进度差算出，单位正好是
  窗口在用的"进度/秒"。
Android 16 起系统对 targetSdk ≥ 36 的应用默认打开预测动画；本应用固定在 35（见上文），
因此在 manifest 里显式写 `android:enableOnBackInvokedCallback="true"`。
`OnBackPressedCallback` 的行为与该开关无关（平台文档明确），应用自己的返回逻辑不依赖它。

刷新率不做任何限制：应用不设 `preferredRefreshRate`/`preferredDisplayModeId`，也不调用
`Surface.setFrameRate`，动画跑在系统 Vsync 上，有多少帧就出多少帧（120 Hz 设备上即是 120）。
Compose 1.9.4 有 `Modifier.preferredFrameRate`（底层是 API 35 的 `View.setRequestedFrameRate`），
但只对 Android 15+ 生效，且这里没有需要降帧的静止画面，因此不加。
模拟器用 SwiftShader 软件渲染（`dumpsys SurfaceFlinger` 可证），本身就有 70% 以上的卡顿帧，
所以帧率不能拿模拟器测；要测动画性能必须用真机，并用 `dumpsys gfxinfo <pkg> framestats` 看单帧分布。
在模拟器上按住面板下滑的实测：`Number Slow issue draw commands` 66/79 帧，而
`Number Slow UI thread` 只有 7 帧，且面板/宿主的重组计数为 0；把背后的网页换成空首页后数字不变。
也就是说该路径的耗时在光栅化与合成，不在应用的重组或测量，软件渲染器把它放大了约一个数量级。
由页面自己打开、靠卸载退出的面板没有可运行的动画，其退出仍由窗口动画承担，
`res/anim/browser_sheet_exit.xml` 必须与 Compose 驱动的退出同向同曲线。
滚到边缘的回弹保留系统默认值，只有确需不随之移动的表单在自己的滚动处关闭。

## 设置导入与导出

导入按分组独立提交，`ConfirmedPreferences.commitConfirmed` 检查磁盘写入结果，失败时恢复涉及键的内存值
并尝试恢复磁盘，恢复失败仍报告该组失败；分组之间不构成整体事务，结果明确报告成功与失败的分组。
失败的分组只上报标识，异常同时写入日志——用户无法处理堆栈，但排查需要它。
过滤订阅清单保存在 `filter_settings` 的 `subscriptions_manifest` 中，与全局过滤和自动更新开关一起提交，
成功后才发布状态；旧 `filter_subscriptions/subscriptions.json` 在首次成功写入时迁移。
手写规则存在同一组的 `user_rules` 里，作为一份 payload 与订阅一起进入同一个引擎快照；
导入时它和订阅遵守同一条规则：**文件里没有这个字段就保留本机的，显式给了列表（哪怕是空列表）才整体替换**，
否则旧版本导出的文件会静默清掉用户手写的东西。
`SettingsTransfer.collect` 等待订阅初始化并使用同一份列表快照；导入结果携带首次规则待下载数，
配置提交后由 App 的进程协程调用 `updateMissing`。备份的合并规则与上限见[功能介绍](FEATURES.md)。
导入元数据在解码时限制长度；`ImportPreviewText` 在格式化与排版前截断预览文本。

## 手动屏蔽

手动屏蔽有两条路，写出来的是同一种文本规则：长按资源后面板给出的网络规则与元素规则，
和元素选择器挑选出来的元素隐藏规则。长按落在「链接里的图片」上时，面板的三行都针对**图片的地址**
（`PageContextTarget.blockAddress` 让图片优先）：否则"屏蔽此网站的资源"会退回到链接本身，
在网站自己的图库里那就是正在浏览的这个站，等于把整页带走。生成规则的是纯函数 `BlockRules`（地址去掉查询串、`||host^`、
`host##selector`），它只返回引擎能接受的文本或 `null`——引擎读不懂规则时不会报错，只会丢掉，
于是"没写进去"和"写进去但不生效"从外面看完全一样，所以这里宁可拒绝也不写。
`PageHide` 负责"现在就把这个藏起来"：用行内 `!important` 而不是样式表规则，因为样式表规则会输给
页面自己的 `!important`，而行内声明不会，并且它随文档消失，正好是临时隐藏承诺的生命周期。
选择器的文本按 CSS 转义（`JSONObject.quote` 会把 `/` 写成 `\/`，CSS 认但写出来不是那条地址）。

**消息通道必须在 WebView 配置时挂上，不能等到挑选开始时才挂**：给一个已经加载完成、
已经稳定的文档加 `addWebMessageListener`，随后再用脚本改动那个文档（哪怕只是插一个空
`<style>` 和一个 `display:none` 的 div），会让整个应用进程在 `libwebviewchromium` 里以空指针
崩溃（SIGSEGV，fault addr 0x18）。这不是推测：同一份代码只把挂载时机从"挑选时"改成
"`configure(view)` 时"（与媒体探针、用户脚本运行时一致）就不再崩溃，且页面侧用同样的 DOM
改动复现不出来（页面自己改 DOM、开发者工具里执行同样的 DOM 改动都正常，只有"迟挂的通道 +
脚本改 DOM"这一组合会崩）。通道本来就和 WebView 同生命周期，挂在配置处也更合理。
查找过程中 `document` 上那个非 passive 的 `wheel` 监听一度是头号嫌疑（当时看到"加上它就崩"），
但那是被时间线骗了：真正的变量是挂载时机。修完时机之后 `wheel` 没有被放回去，因为它本来就不需要——
挑选期间滚轮让页面正常滚动，描边仍然正确，因为监听 `scroll` 会重新读取元素的盒子。

`element-picker.js` 与 `ElementPicker` 的分工是按"什么必须留在页面里"划的：
描边要跟着手指走，任何来回一趟 Kotlin 的往返都做不到这个速度，所以挑选、上下级与绘制都在页面里；
写入规则、报告结果在应用这边。`ElementPicker` 只处理一种消息（`pick`），并且只在**自己启动的选择器运行中、
来自主 frame、来源与启动时的页面同源**时接受它。这条通道对页面本身是敞开的，所以里面的内容一律当作页面输入：
它只用来填操作栏，真正的写入要用户按下面板上的按钮，而那个选择器还要再通过 `BlockRules` 的校验——
`body`/`html`/`head` 这类会清空页面的选择器在这里被拒，因为空白页不会被理解成"我屏蔽错了"，
而是"过滤器把这个网站弄坏了"，而那时用户已经看不见页面，只能在一堆文本里找出那条规则删掉。
挑选期间页面的输入在捕获阶段被拦下（`touchstart`/`touchmove`/`touchend`/`mouse*`/`contextmenu` 都显式声明
非 passive，document 级的 touch 监听默认是 passive 的，passive 监听不允许取消滚动；`wheel` 不在其中，
滚轮滚动时描边靠 `scroll` 监听重画，见上一段）。
预览用行内 `display:none !important` 隐藏选中的匹配项，和规则生效时同一种声明，
所以重排也是真的：一个会带走整片区域的选择器，在写下去之前就会显现出来。
每个元素原来的行内 `display` 值被记下来原样放回（原本没有就删掉该属性），
因此"取消"能还原到与挑选前完全一致；手指按下去时先还原，是为了让要选的东西看得见。
`stop()` 里第一件事就是还原——预览活得比挑选久，就等于在用户没有任何界面可撤销的情况下藏了东西。
操作栏是 Compose 浮层而不是布局里的一行：会改变 WebView 高度的一行会让页面重排，
被描边的元素就会从描边底下跑掉。

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
投屏把渲染器当成一台独立播放器：地址交给它、由它自己解码，所以进度、暂停、音量都是 AVTransport /
RenderingControl 上的动作，而不是操作手机上那份播放。为此有两处刻意的取舍：
`Seek` 只在重量级控制（`transport`）之外单独映射失败说明，因为设备拒绝它（SOAP fault 710 之类）
和"控制通道断了"是两件事；投屏时带的起始位置要等到 `PLAYING` 再发一次且只发一次——
传输未就绪时的 Seek 必被拒绝，而重发会让已经接受的设备白跳一次。**不做倍速**：
`Play(Speed)` 是唯一入口，规范只保证 `"1"`，且没有可查询的能力位（`GetDeviceCapabilities` 不含速度表，
`GetTransportInfo` 只回当前 `CurrentSpeed`），做出来只会是一排大概率无效的选项。
单位换算：设备用 `HH:MM:SS`，页面用秒，`AvTransport.parseTime`/`formatTime` 是唯一转换点。
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
用户脚本在网页环境运行，原生存储桥不等于隔离执行环境；无痕模式下因此只把脚本 **GM 值**放进会话内存
（`UserScriptStore` 的 private 映射），进入与退出都清空，其余部分与普通模式完全相同。
"无痕不执行脚本"曾是默认行为，改成执行之后，可能泄漏的就是脚本自己写下的数据，边界因此划在这里。
清理入口只有一处：`BrowserSessionState.beginPrivateSession/endPrivateSession` 同时负责脚本值，
所以手动切换、冷启动直接进无痕、Activity 销毁（关掉最后一个浏览器窗口但进程仍在）走的是同一条边界，
调用方不再各自记得清理。注入内容的变化由 `UserScriptStore.programs` 这个版本号通知运行时：
私有写入不改变 `InstalledUserScript`，列表订阅永远不会被唤醒，运行时因此同时比较每个脚本注入时用的
值文本（只重建那一个脚本，避免早先"每次写入重装全部程序、最多复制 12 MiB"的开销）。
运行时对外的`UserScriptStatus` 与注入共用同一个 `matchesUrl` 判定：本页状态只会列出真的会运行的脚本，
"已安装但没命中"与"这套 WebView 跑不了"分开计数，且每个脚本只落在其中一类。

## 下载、更新与构建

下载请求先经 `DownloadRequestCoordinator` 汇聚：确认、预算和拦截记录按标签文档保存，
前台只暴露所选标签状态，主文档导航或关页清理，后台窗口导航也重置自身状态。
`DownloadPresenter` 管理任务操作、文件打开及结果提示；WebView 提供的 contentLength 传至确认框。
`DownloadIdentity` 是模式相关下载策略的唯一出处：凭据来源、持久化范围、通知与可恢复性都由它决定，
新增差异应加在这里而不是散落成新的布尔判断。
`PageFileDownload` 处理网页在内存里生成的文件：`blob:` 地址只在该文档内可解析，任何 HTTP 客户端都取不到，
因此它把 `blob-download.js` 注入为 document-start 脚本，记住 `URL.createObjectURL` 创建的 Blob
（页面随后 `revokeObjectURL` 也不影响，撤销的是地址不是对象），并从捕获阶段的点击记录 `a[download]` 给出的文件名。
原生侧只通过 `WebMessageCompat` 的 ArrayBuffer 通道逐块索取：每块写入临时文件后才请求下一块，
既不整份驻留内存，也不让页面跑在磁盘前面；`DownloadHandler` 用 `page-file:` 前缀的占位地址记录这类任务，
该前缀会被 `validHttpUrl` 拒绝，所以引擎永远不会去抓它，重启后也据此识别为不可续传的记录。
两种来源在取到字节之后共用同一条链路：临时分片落盘、`publishParts` 交给 `DownloadDestinationWriter` 发布、
原子地把记录改成完成，成败都走同一套清理。区别只在取字节的那一半——引擎按 URL 分段下载，
页面文件按分片向页面索取——因此任务模型、确认框、下载列表、通知与打开方式都只有一份实现。
失败的那一端因此也要看住：真正停止页面侧传输的是写完最后一块、也唯一能确定失败原因的那条路径
（它把源从表里取出并 `cancel`），不是事后才发现表里已经空了的清理函数；否则页面会一直以为传输还在进行，
同一文档里的下一次 Blob 下载会被拒。页面文件只在内存里有意义，因此从不续传，重启清理也不会为它保留临时分片。
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
