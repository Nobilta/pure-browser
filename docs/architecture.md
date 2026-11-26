# 架构与实现约束

本文用于定位代码和状态所有者。用户行为见[功能说明](features.md)，UI 规则见[设计规范](design-system.md)，
无痕数据归属以[无痕设计契约](incognito-policy.md)为准。工具版本与构建参数见[构建指南](building.md)。

## 代码地图

Kotlin 根目录为 [app/src/main/java/com/mybrowser](../app/src/main/java/com/mybrowser)。

| 入口 / 目录 | 职责 |
|---|---|
| `App` | 进程级仓库、隐私清理、系统媒体与投屏控制器 |
| `MainActivity`、`core/BrowserPageHost` | Android 回调与站点策略；页面宿主、WebView 和运行时归属 |
| `ui/shell` | 浏览器壳、导航、面板窗口、共用动作与动效 |
| `ui/components`、`ui/settings` | 公共视觉组件与设置页面 |
| `tabs`、`data`、`home`、`search` | 标签会话、书签历史、首页和搜索 |
| `site`、`privacy`、`security` | 站点偏好、环境隔离、权限、证书与外部协议 |
| `download`、`update` | 普通下载、附件响应、页面文件；应用更新与安装包校验 |
| `media`、`dlna`、`qr` | 增强播放器、系统媒体、画中画、投屏和扫码 |
| `translate`、`userscript`、`filter` | 翻译调度、用户脚本、订阅与手动屏蔽 |
| `devtools`、`ui/devtools` | 采集通道、DOM／存储读写和开发工具界面 |
| `backup` | 设置、书签、历史的编解码、预览与应用 |
| [rust](../rust) | `adblock` / `url_utils` JNI 库，共享 `site_identity` |
| [assets](../app/src/main/assets) | 媒体/阅读/翻译/脚本运行时和过滤离线数据 |
| [validation](../validation)、[release](../release) | 回归夹具和测试；签名验证、更新附件与发布脚本 |

## 状态、并发与持久化

- 进程级操作由 App 持有，页面级操作归宿主/文档，面板级操作归当前 Presentation。异步结果只能更新原所属对象；优先使用已有协程生命周期、owner 和文档标识，不再并排增加状态副本。
- Cookie、网站权限、媒体命令和下载响应必须保持来源身份。不能以同一个 URL 判定为同一文档，也不能在获取 Profile 失败时借用默认环境。
- 书签、历史和需要确认的设置写入成功后才发布界面状态。`ConfirmedPreferences.commitConfirmed` 负责确认写入；设置导入按分组提交，不是跨组事务，失败分组可见。
- 书签 HTML 导出在系统返回目标 URI 后读取仓库，完成编码与大小校验后才打开输出流写入，不依赖文件选择器打开前的 Activity 内存快照。系统选择器可能先创建空目标；编码失败不会由应用打开或截断目标流。
- 网站显示偏好与无痕权限覆盖层共用写锁。单项 transform 和表单差异在锁内合并最新共享设置；显示偏好共享，权限不互相覆盖。
- 外部文件/网络/Provider 输入按协议边界处理。取消继续传播；不要用宽泛 `runCatching` 把编程错误或取消变成空数据、成功或自动重试。
- Rust 枚举中的 `ResourceType`、`SourceToken` 顺序属于 JNI 契约，Kotlin 与 Rust 必须同步。原生库加载错误在入口处理，不能把任意 JNI 异常吞成正常统计。

## 页面、标签与导航

`ui/shell/PageNavigator` 负责地址/搜索、加载/刷新、历史与外部链接的标签选择；
`core/NavigationReadiness` 协调隐私清理和脚本初始化。访问器取得当前页面与 Profile，避免捕获切换前对象。
`BrowserPageHost` 管理页面创建、通道安装、后台停放和销毁，MainActivity 保留 Android clients 与策略回调。

后台标签常驻**没有固定数量上限**。`RecentTabStore` 保留后台 WebView，内存压力按现有规则回收，
`UI_HIDDEN` 本身不清空页面。不得把“增加标签上限”作为性能优化。WebView 不跨 Activity 复用，销毁宿主时统一释放。
`PopupSessionStore` 记录真实 JavaScript 窗口关系；手动新标签的来源/返回关系由 TabManager 管理，二者不混用。
后台页面的请求属于自身文档，不更新前台媒体或弹权限窗口。

BFCache 按 WebView 能力启用，恢复时读取页面实际状态并同步阅读/翻译样式；不能只凭 URL 或滚动位置确认文档复用。
`ReaderMode` 的 generation 保护异步注入；阅读控制器保留原 DOM，底部留白接收 Compose 工具条实测高度并换算 CSS 像素。
翻译由 `TranslationController` 维护任务、队列和标签快照；页面切换或停止取消原任务，旧结果不能回写新文档。

## UI 宿主与媒体

`BrowserSheetNavigation` 管理“菜单 → 子页面”；`BrowserSheetHost` 保留窗口并替换内容。
返回处理归当前页，旧页面释放不能移除新回调；嵌套选项与确认框接管返回。
面板动画、拖动和预测性返回使用同一 offset；动画进行中与手势接管按既有窗口逻辑交接。
独立面板由 `SheetWindowContent` 保留到退出完成；内容通过 `browserSheetDismiss()` 请求关闭，路由面板继续交由原路由宿主处理。
`BrowserMotion` 是动效参数入口，页面不重复实现曲线或状态机。固定标题/操作栏的表单仅滚动内容区域。

`FullscreenVideoView` 承载全屏画面及增强控件，`BrowserMediaSession` 管理系统控制与后台服务，
`PictureInPictureController` 管理画中画。用户开启后台播放后，全屏锁屏应继续播放，解锁保持同一全屏；
Home 按现有策略进入画中画。关闭后台播放则暂停。延迟退出由唯一 Job 管理，生命周期变化取消并在恢复焦点时重新判断；
旧媒体 owner token 不能操作新页面。暂停、结束会话、退出全屏与 Activity 暂时失去焦点不可混同。
系统 Stop 立即释放会话与通知，同一媒体的异步暂停确认前忽略迟到的播放状态；暂停确认后，页面重新播放仍可建立会话。

## 无痕实现入口

完整政策在[无痕设计契约](incognito-policy.md)，这里仅列实现归属：

- `PrivacyMode` / `PrivacyCleanupBarrier`：独立 Profile、异步清理、失败提示与加载等待。
- `BrowserSessionState.beginPrivateSession/endPrivateSession`：统一会话身份和脚本 GM 内存值的开始/结束；Activity 重建保持同一会话。
- `UserScriptStore`：私密请求捕获所属存储对象，旧请求不能写入新会话；程序版本通知与安装列表分开。
- `DownloadIdentity`：私密任务的凭据、来源持久化、通知与恢复能力，避免散落新的模式布尔分支。

## 下载与更新

`DownloadRequestCoordinator` 按标签文档管理待确认请求；`DownloadPresenter` 负责操作、文件打开与反馈。
一次性 `DownloadResponse` 在确认前归请求，认领后归传输；取消/导航关闭未认领流。确认不能丢掉仍需用户处理的请求。
`DownloadHandler` 调度任务，`DownloadMetadataStore` 负责记录格式、迁移、隐私裁剪和重启恢复。
快照在现有任务/元数据锁顺序内提交，SharedPreferences 仅延后磁盘写入，不能把每次保存改成无序 IO 协程。

每个任务的 writer 负责临时文件和发布边界；取消/删除等待原 writer 结束再清理。
HTTP 连接随传输协程取消而关闭，使阻塞读取退出；关闭引起的 I/O 错误继续按取消传播。
更新下载链接复用任务 writer 锁和临时目录，先验证新响应及已保存区间，再提交新地址、断点并请求剩余区间。
详情入口在 IO 线程按需读取有效断点，提交时再确认；进度快照不读取断点文件，也不缓存该资格。
不同资源的 ETag 不作为内容相同的证明；校验失败保留旧地址和分段，更新后的首轮续传不静默降级为完整重下。
页面生成文件通过 `PageFileDownload` 的 ArrayBuffer 通道逐块写入，使用不可请求的 `page-file:` 标记，重启不能续传。
文件发布由 `DownloadDestinationWriter` 统一处理 SAF/MediaStore，成功后才显示完成。
目录名称查询在任务锁外进行，授权与选择提交仍核对原确认所有者，取消后不提交旧结果。

| 传输 | 入口 | 恢复与输出 |
|---|---|---|
| HTTP(S) | `HttpDownloadEngine` | Range、validator、长度、最终 URL 与分段边界一致才能续传 |
| FTP | `FtpDownloadEngine` | PASV、TYPE I、SIZE/MDTM；核对版本后 REST，无法确认同一实体则重下 |
| HLS | `HlsDownloadEngine` | 解析子列表、顺序合并、AES-128；播放列表摘要与已提交段落确定断点；容器识别决定最终 `.ts`/`.mp4` 与 MIME |

HLS 确定性格式错误属于不支持，不做网络退避重试；状态损坏与 I/O 失败区分处理。
断点原子写保持原子操作，不加无证据的非原子降级。无痕退出保留已接受传输，清理时序见独立契约。

更新器与普通下载分开：更新逐跳复核来源白名单、摘要、包名、版本、SDK、ABI 和签名。
启动请求与待确认结果归 `BrowserSessionState` 的 `viewModelScope` 所有；每个浏览器会话一次，手动入口每次重新请求。
版本清单不缓存，仅保存服务端限流时间。来源优先 Gitee，网络／限流／发行版缺失后 GitHub；换源从头下载同版同摘要文件，本地写入失败、取消和校验失败不换源。
正式版本/附件的流程由[发布指南](releasing.md)维护。

## 过滤、外部调用与备份

`FilterController` 持有引擎快照与读写/释放所有权；订阅和手写规则一起进入快照。
内置来源由 `FilterSubscriptions.BUILT_INS` 定义，新增时同步默认开关、数量上限、离线数据和 APK 许可证。
AdGuard 使用官方 Chromium 预编译订阅；引擎不解释源仓库的平台条件。
手动屏蔽匹配、规则有效性、分组与 Rust 接收语法必须一致。

外部 App 由站点确认、手势边界、节流与 Intent 清洗控制；不枚举所有可用 scheme 白名单。
清除显式 component/selector/package 和 URI 授权，限定 BROWSABLE；网页 fallback URL 由浏览器消费。
`QUERY_ALL_PACKAGES` 用于显示目标 App 名称，不用于上传应用清单；分发渠道的政策需在实际发布时单独核对。

设置备份的缺失字段表示保留，显式空列表表示清空，不能混同；权限不从备份授予。
`SettingsTransfer.collect` 等待订阅初始化，导入提交后由进程协程更新缺少的规则。文件边界预算与取消归属保持原流程。

## 本地文件查看

`PlainTextLoader` 只读取获授权的单个 URI；`TextFormat` 根据 MIME 和通用文本文件名选择展示方式。
`TextPreview` 在后台生成 Markdown / JSON / XML、CSV／TSV 表格与配置文件高亮的展示标记，原始文本单独保留，切换和复制不会从 DOM 反向提取。
查看器查找复用 `FindBar` 与 WebView 原生查找 API；文档加载完成后查找当前展示文本，切换预览或原文时重新计算匹配，不开启 JavaScript。
查看器的 WebView 不执行 JavaScript，也不读取网络或本地资源；HTML 原文复用该查看器，关闭后返回原标签，不替换其 WebView。

`LocalHtmlDocument` 保留单个临时授权 URI，挂在 `TabState` 上；`BrowserPageHost` 给对应 WebView 绑定
`WebViewAssetLoader`，在每文件独立的虚拟 HTTPS origin 下返回原始字节。默认只映射文档自身；用户选择资源目录后，通过 DocumentsProvider 的子节点查询解析关联资源，未授权或不存在的路径返回 404；
WebView 的全局 file/content 访问保持关闭。标签沿用普通／无痕 Profile 与销毁流程，URI 和虚拟地址不进入持久化标签元数据。
同一标签组中的本地子标签和目的地尚未知的弹出页继承文件引用，明确打开远程地址的子标签不继承；弹出页在 Chromium 接管前绑定加载器，后台标签在首次显示时绑定。
仍在运行的后台弹出页／打开者沿用同一文件请求入口；原文查看和文件分享仅匹配该授权文档本身。
加载器绑定保留给后退恢复；子资源请求须同时满足顶层文档同源、请求的 `Origin`（或 `Referer`）同源，否则返回 404，避免远程顶层页或 iframe 沿用目录授权。WebView 拦截接口不提供发起请求的 frame，不能单靠顶层地址判断。本地响应使用 `Referrer-Policy: same-origin`，默认仅向同源资源提供来源，不在跨源导航和请求中外发虚拟地址。

本地 HTML 的资源目录由 `LocalHtmlResources` 保存用户选择，`LocalHtmlDocument` 在既有虚拟来源中按目录子节点读取资源，保持 WebView 文件与 content 直接访问关闭。初次打开文件、系统目录选择的待处理文档、授权保存任务及未呈现的结果由 `BrowserSessionState` 持有，重建后仍归原会话／标签；恢复前台后复用空主页或创建标签，并应用刷新与反馈。授权完成时标记原文档标签，后台驻留页面恢复前台后再加载新资源。连续打开请求分别保留结果，无痕退出后不应用旧会话结果。目录查询在共享锁外执行，获取并保存读取授权与下载目录清理共享锁，清理不得释放显式保留的网页资源目录。

## 开发工具编辑

开发工具的编辑使用原有 `DevToolsSession` 和文档标识，提交前在主线程检查文档归属，页面内再次核对文档实例或存储 origin。
元素拾取共用原有通道；检查模式只高亮，返回节点身份，不按 CSS 选择器猜测节点。面板窗口隐藏期间保留路由与 Compose 状态。

开发工具根组件保留当前会话的 DOM／源码读取状态和当前文档的存储快照，页签 UI 的筛选、滚动位置通过现有 SaveableStateHolder 保留；完整导航更换文档时重新读取。详情沿用同一状态所有者，编辑后重读目标，删除后返回列表，不另建全局状态仓库。
