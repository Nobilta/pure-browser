# 系统能力与后续边界（0.7.0）

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

## 媒体与 DLNA

Chromium 继续负责网页解码、Cookie 和音频焦点，应用不重复申请第二份焦点。
实测重复申请焦点会抢占同 UID 内 Chromium 的 AudioFocusDelegate，导致视频立即暂停。
应用只管理系统媒体按钮、耳机拔出、页面所有权、后台政策和 PiP；一个进程同一时刻只有一个媒体所有者。
系统暂停和耳机断开会暂停该文档已知 frame 内的媒体。

后台播放默认关闭。开启后，当前普通页可随媒体前台服务继续播放；切换标签会暂停旧页。
无痕隐藏时暂停，不发布网页标题到系统 MediaSession，也不提供 PiP 入口。
Android 12+ 使用自动 PiP 参数及视频区域提示；旧系统使用离开 Activity 回调。
驻留标签不使用进程级 `pauseTimers()`，避免影响当前可见文档。

DLNA 使用 SSDP、SOAP 发现和远端状态/播放控制；接收器直接访问选中地址。
命令被接受和设备报告播放分别呈现，实体画面另需设备验证。具体边界见 [播放器说明](video-playback-and-casting.md)。

## 用户脚本兼容集

本轮用项目维护的真实安装夹具覆盖 document-start、DOM、样式、GM 持久值、依赖、资源、
排除规则、弹窗和私密模式。它们会经安装预览、实际 HTTP 下载、WebView 执行和进程重启，
同时有 Node 桥接协议与 Kotlin 元数据/资源测试；没有据此声明兼容所有第三方脚本。

支持 `@resource` 与 `GM_getResourceText`、`GM_getResourceURL`、`GM.getResourceText`、
`GM.getResourceUrl`（及 URL 别名）。资源只在确认安装时下载，最多 8 个、每个 256 KiB；
二进制以 Base64/MIME 持久化，文本按 UTF-8 读取。最多 6 次连接尝试，拒绝 HTTPS 降级，
不读取 WebView Cookie/认证。总脚本/依赖/资源仍限制在 12 MiB。

`GM_xmlhttpRequest` 的跨域网络授权与隔离执行仍不开放。若以后扩展，需分别实现：

- 安装预览中的目标域授权、每次跳转再次校验、请求/响应大小与并发预算、取消/导航所有者。
- 认证头和 Cookie 的明确规则，禁止从页面伪造脚本身份取得网络权限。
- WebView 支持的隔离世界与 DOM 桥接设计；不能把同一网页环境里的随机令牌称为完整隔离。

当前缺少这些前提，因此不把跨域接口接到原生通用 HTTP 客户端。翻译和同步也不借脚本接口绕过用户的排除决定。

## 单浏览会话

`BrowserSessionState` 在 Activity 重建期间保留普通/私密标签和临时网站设置；私密状态不写入 Bundle 或磁盘。
普通会话的元数据、最近关闭记录与书签 HTML 导入导出继续保留。阅读/离线文章、整体备份恢复与第二窗口已移除。

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
低内存设备不保留后台页，其他设备最多驻留一个最近普通页，后台压力通知会释放驻留缓存。
启动测量分开记录首页首帧、外链首帧、夹具 complete 回执及应用/renderer PSS。
没有低端真机的滚动帧率、功耗或网络条件结果，不据模拟器数字承诺这些表现。

Compose BOM 2025.10.01 将 UI/Foundation/Runtime 的编译与运行图统一为 1.9.4，Material 3 为 1.4.0；
图标 core 显式声明，Activity 1.13.0、WebKit 1.17.0。
Gradle lockfile 和 SHA-256 校验元数据提交到 Git；Cargo 与验证用 npm 使用 lockfile。
校验和来自本次解析的官方仓库产物，是构建一致性记录，不冒充独立供应链审计。
Baseline Profile 暂不新增：先保留库自带 profile，用启动/滚动测量确定应用热点后再录制。

Native 保留规则索引与逐条扫描的 4,608 组生成对照、2,048 组恶意/畸形 Unicode HTML，
以及此前阶段的 host AddressSanitizer 检查记录。最新发布的实际检查范围以回归报告为准。它们是有界、可复现的生成测试，不能替代长期覆盖引导 fuzzing。
