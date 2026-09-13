# 构建与验证指南

当前功能、依赖与交付包以 [README](README.md) 为准，实际执行结果见 [模拟器报告](EMULATOR_TEST_REPORT.md)。
本文件是复现方法和验收范围，不表示下列所有设备/网站都已验证。

## 自动检查和签名构建

```bash
./build-and-test.sh
# 单独执行 Android 测试与静态检查：
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
# 需要 x86_64 模拟器时另行构建 debug；交付 Release 为 arm64：
./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug --console=plain
```

完整脚本执行三语言占位符检查、Node 播放/脚本协议测试、Rust fmt/test/clippy、Android/Robolectric、
lint、R8 和签名验证。Android 测试自动构建 host JNI，验证真实跨语言契约。Node 使用内置测试运行器。
SDK/NDK、签名配置和确切版本见 README；不手工复制旧 JNI 库，不为普通构建更新依赖校验值。

```bash
apksigner verify --verbose --print-certs PureBrowser-v0.8.1-release.apk
shasum -a 256 PureBrowser-v0.8.1-release.apk
./install_and_test.sh PureBrowser-v0.8.1-release.apk
```

安装应使用原签名覆盖升级；不要为了绕过错误先卸载用户应用或清空用户数据。

## 模拟器串行回归

使用专用模拟器，回归会写入测试书签、下载和站点数据。只运行一台模拟器和一个 UI 脚本，
不要与 Gradle 构建同时运行。QA 仅监听本机 8875/8876，通过 ADB reverse 使用；证书和桌面模式夹具另外使用 8877–8879。
本机设置 HTTP 代理时，给回归命令增加 `NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost`，让夹具遥测直连本机。

```bash
python3 validation/qa-server.py --apk PureBrowser-v0.8.1-release.apk
# 另一个终端：
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.8.1-release.apk \
  --label release-081 --stages menu-navigation settings-back site layout browser productivity
```

不指定 `--stages` 时选择该设备可运行的全部阶段。只有 APK、AVD、阶段选择完全相同时可以 `--resume`；
退出码、耗时、日志和包哈希写入 suite JSON，失败保留到 `priorAttempts`。改变源码/包后使用新的 suite，不能复用旧包通过项。

Release 不开放远程 WebView 调试。辅助程序仅推入 `/data/local/tmp/pure-ui-dump.jar`，提供真实触摸、
键盘和无障碍树读取；动态网页使用 DOM 遥测与播放/文件内容核对操作结果，不能只判断按钮存在。
WebView 漏报可见网页节点时，先核对当前网址与新鲜夹具几何，再执行真实触摸；网站夹具不在导航后立即追加刷新。
权限窗口和全屏控件须在同一次辅助会话中定位并触摸，防止窗口动画/自动隐藏造成坐标过期。
播放器检查通过 `playerDump` / `playerReveal` / `playerTap` 只查询前景原生控件，跳过后台 WebView 子树，并主动刷新暂停视频的 Compose 节点缓存。
全屏宿主按原生结构识别，不能假定无障碍树的子节点顺序等于窗口前后顺序；媒体选择夹具会回传按钮坐标与实际播放来源。
全部窗口的只读采集遇到 Android 10 辅助进程 SIGSEGV 时仅重试一次；不重放触摸或返回输入，连续失败仍终止回归。
Compose 页签切换后的输入先核对当前窗口实际聚焦的可编辑节点。添加过滤订阅使用有界完成等待，
覆盖后台规则重建；普通界面切换仍使用较短等待。Android 10 手动旋转使用 `wm set-user-rotation`，
新版使用 `wm user-rotation`，并以实际截图尺寸核对方向。
PiP 返回先等待 Activity 离开 pinned 模式和屏幕尺寸稳定；沉浸模式边缘返回先唤出系统栏，
两次滑动之间只快速查询原生锁定控件，避免整棵网页树读取耗尽系统栏的显示时间。

## 0.8.1 扫码预览验收

- 使用带 TOP、LEFT、RIGHT、不同角标、方格和正圆的非对称测试图；核对文字方向、左右顺序、圆形比例及居中裁切。
  模拟器 `imagefile` 先通过独立 Camera2 采集记录原始帧与传感器方向，区分夹具投影和应用预览变换；扫码成功不能代替画面验证。
- 覆盖屏幕 0°、90°、180°、270°、窄竖屏和宽横屏；连续 90°→270° 时核对预览宽高不变仍更新方向。
  前摄回退保持系统默认镜像，应用不再叠加镜像。模拟器结果与未验证的实体设备覆盖分开记录。
- 确认后台释放、前台恢复、Back/快速关闭释放，以及相机网页码、图片文本码、继续扫描和拒绝权限后选图。
- 临时几何测试覆盖各传感器角度与不同缓冲区/视口比例，检查等长垂直像素轴、中心位置、裁切边界和方向。
  删除临时测试源码后再执行完整构建，最终模拟器回归必须绑定同一签名 APK 的 SHA-256。

## 既有开发工具、界面和扫码验收

- 首页地址栏可点击输入，左侧不显示主页占位图标，右侧仅扫码；普通网页保留安全信息、刷新/停止。顶部/底部地址栏均验证。
- 相机首次授权、拒绝、设置恢复、切后台、旋转、快速开关和手电筒；退出后 `dumpsys media.camera` 不保留本应用活动客户端。
- 扫描 HTTP(S) 与无协议域名应在当前标签访问一次；多行中文、emoji、Wi-Fi、`javascript:`、`file:` 和应用协议只展示纯文本。
- 从系统文件选择器选择二维码图片，验证中文原文复制、继续扫描、无二维码和无效文件错误；不需 Google Play 服务或外网。
- 开发工具源码查看普通 HTML、脚本/样式、单行压缩源码、百万字符、只有大量换行、emoji 跨块边界；检查颜色、滚动、截断和刷新。
- 网络组合测试类型、状态和大小写搜索，清空、无结果、切页签恢复及后台日志；Fetch/XHR 使用目的头或明确 Accept 头的本地夹具。
- 菜单、标签、历史、下载、书签、网站设置、过滤、脚本、设置、投屏及扫码检查统一标题间距、48dp 操作、轻量过渡、逐级返回。
- 浅/深主题、中文/英文、150% 字体、横竖屏和键盘避让；系统动画缩放设为 0 时也可正常操作。
- 使用 `dumpsys gfxinfo com.mybrowser reset` / `framestats` 采集实际布局/滚动帧；主机词法扫描耗时不能代替手机渲染性能。

临时测试代码和二维码/大源码夹具在完成后删除，实际专项结果和截图保留在本次交付的验证目录。

## 播放器与既有功能验收


### 全屏浮层与 Material 3 控件

- 倍速和投屏只在播放器右下角弹出；窗口标识、原视频 DOM 坐标、视口、进度及控制条位置在打开和关闭时保持。
- 七档倍速可选择，投屏媒体/设备列表可滚动；点击面板外、关闭按钮和系统返回都只关闭浮层。
- 面板停留超过 3.5 秒仍可操作，关闭后恢复控件自动隐藏；切后台、进入 PiP 和退出全屏不残留面板。
- 横屏、竖屏、放大字体下检查控件配色、圆角、滑块、文字和 48dp 触摸区域；暂停视频，在浮层打开、滚动与关闭前后比对画面像素，核对无整体平移。
- 播放、双击、拖动滑块、音量/亮度/进度手势、连续临时加速、锁定、旋转、系统媒体及 PiP 使用原视频继续工作。
- 后台播放开启时连续三次离开正在播放的页面，系统媒体会话、通知与服务均结束，浏览器进程保持不变。

### 菜单、标签、设置返回与滚动

```bash
python3 validation/menu-navigation-regression.py --serial emulator-5554
python3 validation/settings-back-regression.py --serial emulator-5554
python3 validation/capabilities-regression.py --serial emulator-5554 --section site
```

- 菜单没有页面播放速度、分享网页、复制链接和打印/PDF；书签与添加/取消书签、网站设置与设置有不同图标。
- 单个/批量关闭、最后一个标签关闭、普通/无痕切换仍正常，重启不恢复已关闭的标签。
- 验证滚动收起/展开地址栏时，滑动起终点均位于当前 WebView 边界内，避免底部地址栏拦截按整屏比例定位的触摸。
- 菜单 → 设置 → 菜单 → 浏览器；左上角、系统 Back、左右边缘手势、连续快速返回、重建和宽屏行为一致。
  菜单与全部子页复用一个弹层窗口；逐帧检查切换期间不露出网页。返回菜单后没有旧设置内容，最终只剩主窗口且可触摸。
- 网站设置、过滤订阅、用户脚本、管理网站、下载、历史、标签等页面上下边界连续执行快/慢滑动；标题及固定操作栏不移动，边界不拉伸，停止后像素稳定。
- 管理网站列表使用固定弹层，通过左上角返回关闭；横屏和放大字体下同样可到达全部操作。
- 开发者工具仅在菜单出现，关于和设置搜索不再包含该入口。

### 关闭媒体与画中画

```bash
python3 validation/media-lifecycle-regression.py --serial emulator-5554 --output validation/results/media-lifecycle
```

- 普通暂停仍可系统继续；Stop、播放结束、隐藏并暂停、卸载、移除、替换、关闭标签和导航后释放系统会话。
- 用 dumpsys 核对 session token、服务和活动通知消失，浏览器 PID 不变；迟到消息不能重新创建旧会话。
- Android 10 从通知转储的 `Notification List` 段读取活动通知；归档记录不代表通知仍在显示。
- 删除最后一个跨域 frame 后仍独立失效；不支持跨域观察的旧 WebView 记录实际限制，不冒充已验证该路径。
- 开启后台播放后持续播放超过两个 frame 失效周期；正常播放不会被错误退休。
- PiP 必须实际显示视频，等待 DOM 尺寸匹配小窗实际边界，不能只断言 pinned 或 PLAYING；系统关闭后恢复浏览器，视频仍暂停。
- Android 17 的部分 PiP 菜单不暴露无障碍节点时，依据已复核的菜单截图和当前边界点击系统关闭目标。
  关闭触摸前只读取必要的窗口信息；耗时截图放在关闭后，避免按钮在截图编码期间自动隐藏。


### 下载

```bash
python3 validation/download-opening-regression.py --serial emulator-5554 \
  --output validation/results/download-opening
python3 validation/download-regression.py --serial emulator-5554
python3 validation/capabilities-regression.py --serial emulator-5554 --section downloads
```

- 观察真实字节和进度变化；未知总长不显示虚假百分比，拒绝通知权限仍能下载。
- 文件落盘并校验 SHA-256；点击完成行直接进入 Android 查看器/选择器/安装器，没有浏览器的第二个打开或安装按钮。
- APK 来源授权和安装确认属于系统界面；检查取消后可返回浏览器。不得用浏览器内的文件类型分支替代系统解析。
- 手工移除夹具文件后点旧记录，显示缺失/授权失败提示；没有处理程序时提示无法打开。
- 完成通知返回相应记录，包含浏览进程已被结束的冷启动情况；删除/打开记录后对应通知消失。
- 暂停、继续、主动暂停跨重启、运行中进程死亡后恢复、If-Range 及最终文件哈希；保存阶段与服务停止。
- 系统 Downloads/SAF 自定义目录、同名文件、仅删记录/同时删文件、失败重试和队列策略。

QA 的 APK 下载路由直接读取 `--apk` 指定的文件，应与回归套件使用同一安装包；不保存 APK 夹具副本，
清理构建缓存后仍可使用根目录的交付 APK。省略该参数时兼容读取 `app/build/outputs/apk/release/app-release.apk`。

### 非全屏原控件与全屏增强播放

```bash
python3 validation/video-regression.py --serial emulator-5554 --variant standard
python3 validation/video-regression.py --serial emulator-5554 --variant custom
python3 validation/system-media-regression.py --serial emulator-5554 --package com.mybrowser \
  --output validation/results/system-media
```

- 非全屏标准、自定义、Blob 和 frame 视频保留原控件与布局；没有新增控制层或临时属性。
- 网站设置关闭增强全屏播放后使用网页控件，重启仍保留；重新开启只在全屏接管。重置后继承全局默认值。
- 全屏只有一套控件，不含快进/后退按钮与模式切换；实际触摸播放、倍速和双击跳转，核对原视频及进度。
- 网站追加控件、样式丢失、目标变化与失败回退；退出后原始属性与网站控制层恢复。
- 原生全屏双击、亮度/音量/进度手势、长按临时倍速、锁定返回、旋转、控件自动隐藏、弹层取消。
- 旧 Provider 跨域不可控制时保留网页；样式受 CSP 限制时仍能使用原播放器。
- Home/PiP、MediaSession 暂停、可选后台媒体、标签切换及无痕隐藏暂停。

静态本地 Blob 夹具不代表已验证真实 MSE/DRM、所有直播网站或实体接收器。

### 保留能力

- 主浏览链路、标签与历史恢复、主页、链接/图片上下文菜单、默认浏览器、外部 VIEW/SEND。
- 菜单/设置逐级返回、快速连续点击、拖动关闭、重建与宽屏；私密会话配置重建及 Profile 清理。
- 书签文件夹/移动/批量、HTML 导入导出与非法协议过滤。
- 网站权限与系统授权分离；导航取消旧请求；input capture 拍照/录像/取消/重建后的字节回传。
- Autofill 配置及合成 passkey 请求结果，不能冒充真实账户成功登录。
- 脚本预览、依赖/资源字节、排除匹配、进程恢复、私密禁用和旧 WebView 降级。
- 广告来源更新、304/无效/离线回退、命中解释；开发工具可见订阅、草稿及横屏键盘。
- 语言、主题、150%/200% 字号、TalkBack 与键盘按需要单独执行，实际覆盖以报告为准。

对应脚本可由 `validation/run-regressions.py` 查看。阅读/离线文章、Google Cast、整体备份恢复和双窗口
已经删除；本版另外删除最近关闭/撤销与打印的过时测试阶段。可临时创建专项测试文件，完成验证后删除，不把一次性测试留在源码中。

## 可选性能和线上检查

```bash
python3 validation/desktop-mode-regression.py --serial emulator-5554 --online
python3 validation/talkback-regression.py --serial emulator-5554 --output validation/results/talkback
python3 validation/benchmark-startup.py --serial emulator-5554 --output validation/results/startup.json
cargo run --locked --release --manifest-path rust/Cargo.toml -p adblock --example benchmark -- 10
python3 validation/cosmetic-benchmark.py
```

线上检查依赖网站当时的可用性；规则计算基准不代表整页速度、真机内存峰值或功耗。
TalkBack 的 `a11y` 模式保留真实读屏服务；默认 UiAutomation 会暂时抑制它。

## 故障记录

```bash
./diagnose.sh
adb logcat -d | rg 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV|AndroidRuntime'
```

记录 APK SHA-256、API/ABI、WebView 版本、操作步骤和脱敏网址，保留失败截图、完整树及遥测。
区分浏览器、WebView renderer、系统服务和 shell 辅助程序 PID，不把平台辅助进程故障算作浏览器崩溃，
也不能因重跑成功删除失败记录。生成证据不提交 Git，清理时只保留本版必要记录与复现脚本。
