# Android 系统集成

本文说明系统凭据、媒体、用户脚本和会话如何与 Android、WebView 配合，供修改这些模块时参考。
使用方式见[功能介绍](../FEATURES.md)，已验证的版本和设备见[回归报告](../EMULATOR_TEST_REPORT.md)。

## Android 系统登录

普通 WebView 启用系统 Autofill，并在 Provider 支持时设置浏览器 WebAuthn 模式。
Android 14+ 同时检查清单声明的 `CREDENTIAL_MANAGER_SET_ORIGIN` 权限，缺少时禁用 WebAuthn。
此权限是安装时授予的普通权限，不代表凭据提供方已信任浏览器；不能用配置调用处的
`runCatching` 捕获随后由网页请求触发的 Chromium 异步异常。
无痕 WebView 关闭 Autofill 子树与 WebAuthn。设置中显示系统自动填充状态、WebView 版本、
能力开关和凭据提供方的信任要求。密码和 passkey 保存在用户选择的系统提供方，浏览器不保存密码库。

`WEB_AUTHENTICATION` 能力为真不等于任意密码管理器都允许第三方浏览器访问凭据。
Google Password Manager 还要求浏览器通过其审核。模拟器上的能力读回、请求拒绝和取消流程
不能证明真实账户已能登录。
官方接入说明：<https://developer.android.com/identity/sign-in/credential-manager-webview>。
浏览器来源与提供方授权：<https://developer.android.com/training/sign-in/privileged-apps>。

验证分为普通表单提示、系统配置、合成凭据请求和真实账户四层。前三层可用项目夹具重跑；
第四层需要提供方、已保存凭据与对应网站，当前模拟器记录未覆盖。

## 媒体与 DLNA

Chromium 继续负责网页解码、Cookie 和音频焦点，应用不重复申请第二份焦点。
实测重复申请焦点会抢占同 UID 内 Chromium 的 AudioFocusDelegate，导致视频立即暂停。
应用只管理系统媒体按钮、耳机拔出、页面所有权、后台政策和 PiP；一个进程同一时刻只有一个媒体所有者。
系统暂停和耳机断开会暂停该文档已知 frame 内的媒体。

普通暂停保留当前视频的恢复入口；停止、结束、隐藏并暂停、卸载或删除视频、离开页面时，
清空系统播放状态与标题并释放 MediaSession token，同时取消通知/前台服务。
探针区分“DOM 仍有媒体元素”和“媒体可继续播放”；frame 在 4 秒没有状态更新后由独立定时任务淘汰。
新的待播视频不继承旧视频的媒体所有权，私密播放也不保留普通会话的公开 token。

后台播放默认关闭。开启后，当前普通页可随媒体前台服务继续播放；切换标签会暂停旧页。
无痕隐藏时暂停，不发布网页标题到系统 MediaSession，也不提供 PiP 入口。
Android 12+ 使用自动 PiP 参数及视频区域提示；旧系统使用离开 Activity 回调。
全屏视图放在 Activity content 容器中跟随窗口重排；画面提示使用进入前的窗口内坐标，进入 PiP 后保持稳定。
关闭/隐藏 PiP 时暂停原 WebView、释放会话并退出全屏，即使用户允许普通后台播放也不复活已关闭的小窗。
驻留标签不使用进程级 `pauseTimers()`，避免影响当前可见文档。

DLNA 使用 SSDP、SOAP 发现和远端状态/播放控制；接收器直接访问选中地址。
命令被接受和设备报告播放分别呈现，实体画面另需设备验证。具体边界见 [播放器说明](video-playback-and-casting.md)。

## 用户脚本兼容集

项目的用户脚本夹具覆盖 document-start、DOM、样式、GM 持久值、依赖、资源、排除规则、弹窗和无痕模式。
检查会经过安装预览、HTTP 下载、WebView 执行和进程重启，另有 Node 桥接协议与 Kotlin 元数据/资源测试。
这些用例验证支持的接口，不代表兼容所有第三方脚本。

支持 `@resource` 与 `GM_getResourceText`、`GM_getResourceURL`、`GM.getResourceText`、
`GM.getResourceUrl`（及 URL 别名）。资源只在确认安装时下载，最多 8 个、每个 256 KiB；
二进制以 Base64/MIME 持久化，文本按 UTF-8 读取。最多 6 次连接尝试，拒绝 HTTPS 降级，
不读取 WebView Cookie/认证。总脚本/依赖/资源仍限制在 12 MiB。

`GM_xmlhttpRequest` 的跨域网络授权与隔离执行仍不开放。若以后扩展，需分别实现：

- 安装预览中的目标域授权、每次跳转再次校验、请求/响应大小与并发预算、取消/导航所有者。
- 认证头和 Cookie 的明确规则，禁止从页面伪造脚本身份取得网络权限。
- WebView 支持的隔离世界与 DOM 桥接设计；不能把同一网页环境里的随机令牌称为完整隔离。

当前没有开放这些权限和隔离机制，因此暂不提供原生跨域网络接口。

## 单浏览会话

`BrowserSessionState` 在 Activity 重建期间保留普通/私密标签和临时网站设置；私密状态不写入 Bundle 或磁盘。
普通标签可以持久化网址、标题和选中项，书签通过 HTML 导入导出。当前没有最近关闭、撤销关闭、
阅读/离线文章、整体备份恢复或第二窗口；升级时会清理旧版的最近关闭存档。

## 性能和依赖选择

以下为 0.6.0 阶段的历史对照：Android arm64 / WebView 145、118,828 条网络规则、12 类请求、每轮 1,200 次 JNI 调用，
分别按 z→2→3 与 3→2→z 执行。下表为两次运行各 5 轮中位数的均值，仅代表夹具内的 JNI 匹配：

| 过滤 crate opt-level | 缓存路径 P95 | 未压缩过滤库 | APK 内压缩过滤库 |
|---|---:|---:|---:|
| z | 468.92 μs | 1,578,864 B | 699,409 B |
| 2 | 175.17 μs | 1,516,608 B | 686,379 B |
| 3 | 143.98 μs | 1,518,912 B | 689,892 B |

所有匹配决定相同。过滤 crate 采用 `3`，其他 crate/依赖保持 `z`；约 69% 的尾延迟下降不等于
网页整体提速。该次模拟器测量的加载耗时不稳定，未据此判断加载速度是否改善。
文档上下文的有界缓存未体现稳定的 P95 增益，不把它另算为性能提升；并发淘汰等价性单独检查。

后台服务启动不再预建 WebView，首次浏览窗口才准备 Chromium 和清理遗留私密 Profile；
普通后台页的驻留不设固定数量上限。创建新页面遇到内存不足，或收到系统内存压力通知时，
按最早停放的顺序回收页面；最严重的压力通知会清空剩余驻留页。仅切到后台的通知不会清空这些页面。
启动测量分开记录首页首帧、外链首帧、夹具 complete 回执及应用/renderer PSS。
没有低端真机的滚动帧率、功耗或网络条件结果，不据模拟器数字承诺这些表现。

Compose BOM 2025.10.01 将 UI/Foundation/Runtime 的编译与运行图统一为 1.9.4，Material 3 为 1.4.0；
图标 core 显式声明，Activity 1.13.0、WebKit 1.17.0。
Gradle lockfile、SHA-256 校验元数据和 Cargo.lock 提交到 Git。
Node 验证使用内置测试运行器，没有额外 npm 依赖或 lockfile。
依赖校验和用于发现产物变化，并不等同于独立的供应链审计。
Baseline Profile 暂不新增：先保留库自带 profile，用启动/滚动测量确定应用热点后再录制。

Native 测试包括规则索引与逐条扫描的 4,608 组生成对照、2,048 组恶意或畸形 Unicode HTML。
历史检查还运行过 host AddressSanitizer；最新版本的执行范围以回归报告为准。
这些固定生成用例不能替代持续的覆盖引导模糊测试。
