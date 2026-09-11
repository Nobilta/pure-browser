# 0.6.0 系统能力与后续边界

用户已授权实施系统审查建议；翻译、账号和跨设备同步明确暂不实现。
本文件记录本轮实现方式与条件项，发布验收以 [模拟器报告](../EMULATOR_TEST_REPORT.md) 为准。

## Android 系统登录

普通 WebView 显式启用系统 Autofill，并在 Provider 支持时设置浏览器 WebAuthn 模式；
Android 14+ 同时检查清单声明的 `CREDENTIAL_MANAGER_SET_ORIGIN` 权限，缺少时禁用 WebAuthn。
此权限是安装时授予的普通权限，不代表凭据提供方已信任浏览器；不能用配置调用处的
`runCatching` 捕获随后由网页请求触发的 Chromium 异步异常。
无痕 WebView 关闭 Autofill 子树与 WebAuthn。设置中显示系统自动填充状态、WebView 版本、
能力开关和凭据提供方的信任要求。密码和 passkey 保存在用户选择的系统提供方，浏览器不保存密码库。

`WEB_AUTHENTICATION` 能力为真不等于任意密码管理器都允许第三方浏览器访问凭据。
Google Password Manager 还要求浏览器通过其审核。模拟器上的能力读回、请求拒绝和取消流程
不能证明真实账户已能登录；同样不使用 Cookie 作为密码管理实现。
官方接入说明：<https://developer.android.com/identity/sign-in/credential-manager-webview>。
浏览器来源与提供方授权：<https://developer.android.com/training/sign-in/privileged-apps>。

验证分为普通表单提示、系统配置、合成凭据请求和真实账户四层。前三层可用项目夹具重跑；
第四层需要提供方、已保存凭据与对应网站，不在无账户的模拟器环境中伪造成功结果。

## 媒体与两种投屏协议

Chromium 继续负责网页解码、Cookie 和音频焦点，应用不重复申请第二份焦点。
实测重复申请焦点会抢占同 UID 内 Chromium 的 AudioFocusDelegate，导致视频立即暂停。
应用只管理系统媒体按钮、耳机拔出、窗口所有权、后台政策和 PiP；一个进程同一时刻只有一个媒体所有者。
系统暂停和耳机断开会暂停该文档已知 frame 内的媒体。

后台播放默认关闭。开启后，当前普通页可随媒体前台服务继续播放；切换标签会暂停旧页。
无痕隐藏时暂停，不发布网页标题到系统 MediaSession，也不提供 PiP/Google Cast 入口。
Android 12+ 使用自动 PiP 参数及视频区域提示；旧系统使用离开 Activity 回调。
驻留标签与独立窗口不能使用进程级 `pauseTimers()`，否则另一可见窗口也会被冻结。

| 协议 | 本轮实现 | 可播放边界 |
|---|---|---|
| DLNA | 原有 SSDP、SOAP、状态、播放/暂停/停止/进度/音量控制 | 取决于接收器，命令成功与实际播放分别显示 |
| Google Cast | Cast Framework 22.3.1、系统设备选择、默认媒体接收器、显式发送与播放/暂停/停止/跳转 | 需要 Google Play services 与同网络的 Cast 设备；接收器直接读取 HTTP(S) MP4/WebM/HLS/DASH/音频地址 |

Google Cast 不转发网页 Cookie、密码或授权头，不接受 Blob 和 URL 内嵌凭据，不承诺 DRM。
签名 URL 仍完整传给用户主动选择的接收器；能被浏览器播放的地址不一定能被电视读取。
无设备时发送按钮禁用；缺少 Play services 时保留可返回的说明与独立 DLNA 路径。
实体 MP4/HLS、Seek、断网、鉴权失败和两类接收器画面仍需硬件验收。

## 用户脚本兼容集

本轮用项目维护的真实安装夹具覆盖 document-start、DOM、样式、GM 持久值、依赖、资源、
排除规则、弹窗和私密模式。它们会经安装预览、实际 HTTP 下载、WebView 执行和进程重启，
同时有 Node 桥接协议与 Kotlin 元数据/备份测试；没有据此声明兼容所有第三方脚本。

新增 `@resource` 与 `GM_getResourceText`、`GM_getResourceURL`、`GM.getResourceText`、
`GM.getResourceUrl`（及 URL 别名）。资源只在确认安装时下载，最多 8 个、每个 256 KiB；
二进制以 Base64/MIME 持久化，文本按 UTF-8 读取。最多 6 次连接尝试，拒绝 HTTPS 降级，
不读取 WebView Cookie/认证。总脚本/依赖/资源仍限制在 12 MiB；备份恢复后脚本默认停用。

`GM_xmlhttpRequest` 的跨域网络授权与隔离执行仍不开放。若以后扩展，需分别实现：

- 安装预览中的目标域授权、每次跳转再次校验、请求/响应大小与并发预算、取消/导航所有者。
- 认证头和 Cookie 的明确规则，禁止从页面伪造脚本身份取得网络权限。
- WebView 支持的隔离世界与 DOM 桥接设计；不能把同一网页环境里的随机令牌称为完整隔离。

当前缺少这些前提，因此不把跨域接口接到原生通用 HTTP 客户端。翻译和同步也不借脚本接口绕过用户的排除决定。

## 独立窗口与本地归档

两个普通 Android task 各有 ViewModel、TabManager、元数据及最近关闭记录，普通 Profile、
书签、设置与下载共享。私密状态只在窗口 ViewModel 中跨 Activity 重建保留，不写 Bundle/磁盘；
另一个窗口存在时先要求关闭它，私密期间也不能再开另一窗口。
清理共享 Profile 会先让两个窗口关闭匹配文档，防止存储被仍在运行的网页重新写回。

离线阅读当前保存提取后的文本、段落、代码和链接，并保留阅读位置；不保存完整网页、图片、
脚本内存、登录会话或流媒体。系统审查要求“完整网页归档另行设计”，本轮方案如下，尚不作为已实现功能：

1. 优先评估 WebView 的 MHTML `saveWebArchive`，通过 SAF 显式导出；它比自行爬取跨域资源更接近当前页面。
2. 捕获绑定当前文档代次，导航或取消后丢弃回执；临时文件与发布文件采用原子替换，失败不显示成功。
3. 对 HTML、CSS、图片、iframe、字体、懒加载和 Service Worker 建立离线断网保真集；Blob、DRM、直播和需鉴权资源明确标识为不可归档。
4. 导入时限制总大小/条目/嵌套/解压比，禁止路径穿越；以独立来源、关闭脚本和外部网络的阅读器展示，链接外跳另经用户操作。
5. 只有在资源重写、压缩或跨平台复用成为已测热点时才引入 Rust 归档库；密钥仍交给 Android Keystore。

## 性能和依赖选择

Android arm64 / WebView 145、同样的 118,828 条网络规则、12 类请求、每轮 1,200 次 JNI 调用，
分别按 z→2→3 与 3→2→z 执行。下表为两次运行各 5 轮中位数的均值，仅代表夹具内的 JNI 匹配：

| 过滤 crate opt-level | 缓存路径 P95 | 未压缩过滤库 | APK 内压缩过滤库 |
|---|---:|---:|---:|
| z | 468.92 μs | 1,578,864 B | 699,409 B |
| 2 | 175.17 μs | 1,516,608 B | 686,379 B |
| 3 | 143.98 μs | 1,518,912 B | 689,892 B |

所有匹配决定相同。过滤 crate 采用 `3`，其他 crate/依赖保持 `z`；约 69% 的尾延迟下降不等于
网页整体提速。加载耗时受模拟器调度影响明显，本轮不宣称加载改善比例。
文档上下文的有界缓存未体现稳定的 P95 增益，不把它另算为性能提升；并发淘汰等价性单独检查。

后台服务启动不再预建 WebView，首次浏览窗口才准备 Chromium 和清理遗留私密 Profile；
低内存设备不保留后台页，其他设备每窗口最多驻留一个最近普通页，后台压力通知会释放驻留缓存。
启动测量分开记录首页首帧、外链首帧、夹具 complete 回执及应用/renderer PSS。
没有低端真机的滚动帧率、功耗或网络条件结果，不据模拟器数字承诺这些表现。

Compose BOM 2025.10.01 将 UI/Foundation/Runtime 的编译与运行图统一为 1.9.4，Material 3 为 1.4.0；
图标 core 显式声明，Activity 1.13.0、WebKit 1.17.0、Cast Framework 22.3.1、AppCompat 1.7.1。
Gradle lockfile 和 SHA-256 校验元数据提交到 Git；Cargo 与验证用 npm 使用 lockfile。
校验和来自本次解析的官方仓库产物，是构建一致性记录，不冒充独立供应链审计。
Baseline Profile 暂不新增：先保留库自带 profile，用启动/滚动测量确定应用热点后再录制。

Native 增加规则索引与逐条扫描的 4,608 组生成对照、2,048 组恶意/畸形 Unicode HTML，
以及 host AddressSanitizer 下的全套 Rust 测试。它们是有界、可复现的生成测试，不能替代长期覆盖引导 fuzzing。
