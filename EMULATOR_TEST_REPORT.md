# 0.7.4 验证报告

日期：2026-09-13。使用本地专用 arm64 模拟器串行验证签名 Release。
套件和专项结果绑定实际安装 APK 的 SHA-256；原始记录保留在 `validation/results/`，不提交 Git。

## 安装包与自动检查

- `PureBrowser-v0.7.4-release.apk`，versionCode 15，包名 `com.mybrowser`，Android 10+、arm64-v8a。
- 4,610,358 bytes，约 4.40 MiB；SHA-256：`7e61f1296713e3dde28da9ff8dc1d471d8dfcd5b7b383ce093562ca002e5977f`。
- v2 签名、16 KiB zipalign 和三份 native 库的 ELF LOAD 16 KiB 对齐检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，与上一版相同。
- 330 项 Android/Robolectric 测试：0 failures、0 errors、0 skipped，包含真实 host JNI。
- 58 项 Rust、55 项 Node 测试通过；Rust fmt/clippy、586 项三语言资源校验、R8 和 Release 构建通过。
- lint：0 errors、10 warnings、1 hint。APK 中两个播放/脚本运行时资产与源码一致，未包含已删除的 `inline-player.js`。

最终 Android 构建在 13:50:46 开始，60.54 秒完成。Node/Rust 预检来自本轮 `build-first.log`；
该次早期构建后续有 Kotlin 编译错误，不能将整次早期构建描述为成功。最终构建及实际安装包以上述记录为准。

依据：[最终构建](validation/results/release-0.7.4/build-final.json)、
[构建日志](validation/results/release-0.7.4/build-final.log)、
[Node/Rust 预检](validation/results/release-0.7.4/build-first.log)、
[交付清单](validation/results/release-0.7.4/delivery.json)、
[签名](validation/results/release-0.7.4/signature.txt)。单元测试 XML 与 lint 报告保留在
`validation/results/release-0.7.4/automated/`。

## Android 17 播放器与浮层

PureBrowser_API37，Android 17 / WebView 145.0.7632.218，手势导航，9 个阶段全部通过：
`media-lifecycle`、`system-media`、`features-media`、`video-standard`、`video-custom`、
`video-custom-blob`、`video-custom-cross`、`video-custom-csp`、`video-square`。

- 倍速与投屏均在原播放器窗口内打开。面板锚定控制条右上方，宽度分别不超过 288dp / 336dp，
  高度受当前视口约束；窗口标识、视频 DOM 坐标、视口与控制条位置保持不变。
- 倍速选择、面板外点击、关闭按钮和系统返回正常；投屏面板停留超过自动隐藏时间仍可操作。
  原视频元素、媒体来源和播放控制保持，方形视频竖屏及手动切横屏均检查浮层边界。
- 标准、自定义容器、Blob 和可访问跨域视频正常接管；CSP 限制时恢复网站控件。
  非全屏保留网页播放器，网站关闭增强播放后原控件可用，偏好跨进程重启保留。
- 播放/暂停、中央及侧边双击、进度/亮度/音量手势、连续长按加速、锁定返回、旋转和后台返回通过。
  画中画保持原视频及正确视口；关闭小窗后暂停并释放系统媒体会话。
- 普通页面投屏正确选择当前播放来源；离开媒体页面时移除入口。
  后台播放显式开启/关闭、系统媒体 Play/Pause/Stop、视频结束/隐藏/卸载/移除/替换与关闭标签均通过。
  连续三次离开正在播放的页面后，媒体会话、通知和服务结束，浏览器 PID 保持不变。

另以简体中文、150% 字体重跑 `video-square`，横竖屏的两个浮层均通过边界检查并人工检查截图。
对暂停的标准视频保存六张截图，覆盖打开/关闭倍速、打开/滚动/关闭投屏。
矩形 `[500,270]–[1280,680]` 内具有纵向细节的视频像素完全相同，像素 SHA-256 为
`9ebf1b8504ff8e12319f314c6cf87260c6a2b02389a77b56bd1aac91cd1f958f`。
这项检查核对六个操作状态的实际渲染画面；原始截图及像素结果均已保留。
验证后已恢复字体和应用语言设置。

依据：[9 阶段套件](validation/results/api37-release-074-player-suite.json)、
[中文大字套件](validation/results/api37-release-074-large-font-suite.json)、
[画面像素与浮层检查](validation/results/release-0.7.4/visual/checks.json)、
[设置恢复](validation/results/release-0.7.4/large-font-settings.json)。

## Android 10 兼容与覆盖升级

PureBrowser_API29，Android 10 / WebView 91.0.4472.114，三键导航，6 个阶段全部通过：
`media-lifecycle`、`system-media`、`features-media`、`video-standard`、`video-custom`、`video-square`。
覆盖标准/自定义/方形视频、横竖屏浮层、手势、网站偏好、普通投屏来源选择、后台服务和 PiP 生命周期。
旧 WebView 无法观察跨域夹具，记录其网页回退，不将跨域会话超时清理计为 Android 10 通过项；
本轮未在 Android 10 重跑 Blob 或 CSP 专项。

以 `adb install -r` 完成 0.7.3（14）→ 0.7.4（15）同签名覆盖升级，未卸载或清空应用数据；
安装后设备 APK 哈希与交付包相同。Android 17 也逐次覆盖安装本轮构建，升级链单独保留。
两台设备最终包的日志均未发现浏览器 Java/native 崩溃或 ANR 标记。

依据：[6 阶段兼容套件](validation/results/api29-release-074-compat-suite.json)、
[Android 10 覆盖升级](validation/results/release-0.7.4/api29-upgrade.json)、
[Android 17 覆盖安装](validation/results/release-0.7.4/api37-upgrade.json)。设备信息、logcat 和诊断 JSON
保留在 `validation/results/release-0.7.4/`，分别使用 `api29-`、`api37-` 前缀。

## 发现的问题及复测

实现中的初版提示卡片会拦截紧接着的长按，现已改为透传触摸；其前期证据保留在 `pre-hud-fix/`。
媒体回归还触发了后台服务尚未应答前台启动就被停止的实际崩溃；修复为每次启动先进入前台，
再按请求顺序处理停止，并用 `stopSelfResult` 保留较新的启动。修复前证据在 `pre-service-fix/`，
最终 APK 已通过两版系统的连续离页、系统媒体和 PiP 回归；修复前的安装包不再保留。

辅助工具也做了修正：跳过后台网页树，刷新暂停后的原生节点缓存，并按宿主结构识别全屏层。
Android 10 的无障碍子节点顺序与 Android 17 不同，不能用最后一个子节点推断最前方窗口。
旧 WebView 漏报 HTML 按钮时，媒体夹具提供新鲜坐标，脚本核对可见页面、真实触摸及实际播放来源。
工具定位失败的阶段记录保留在相应套件 `priorAttempts` 中，不将这些失败或旧包通过项混作最终成功。

## 覆盖边界与清理

本轮聚焦媒体播放，没有重跑全部浏览器、下载、脚本等界面矩阵。
使用本地视频夹具和真实触摸，未使用实体 DLNA 接收器、真实账号或低端真机；
不代表所有生产网站、DRM/MSE、直播实现及接收设备都已验证。
临时 BMP、UI 辅助程序、生成的验证依赖/缓存、旧 APK 及旧版结果已清理；两台模拟器和 QA 服务已停止。
没有新增临时测试源文件残留，现有回归脚本与夹具按本次行为更新后保留。
本地仅保留最新交付 APK 和本轮必要记录，源码在 `master` 以普通提交维护。
清理明细见 [本轮清理记录](validation/results/release-0.7.4/cleanup.json)。
