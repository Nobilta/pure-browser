# 测试指南

先按[贡献指南](CONTRIBUTING.md#开发环境)准备环境。
本文介绍如何运行检查和选择回归范围；已经执行过的结果、失败记录和设备覆盖见[回归报告](EMULATOR_TEST_REPORT.md)。

## 选择检查范围

| 需要验证的内容 | 入口 |
|---|---|
| 日常代码改动 | `./build-and-test.sh --quick` |
| 签名发布包 | `./build-and-test.sh --release`，然后运行设备回归 |
| 基础浏览 | 模拟器 `--profile smoke` |
| 标签和媒体生命周期 | 模拟器 `--profile tabs` |
| 特定功能 | 模拟器 `--stages 阶段名` |
| 完整阶段矩阵 | 模拟器 `--profile full` |

代码改动需要本地自动检查和模拟器回归，范围按受影响功能选择。仅修改文档时，核对链接、命令和对应实现即可。

[CI](.github/workflows/ci.yml) 已执行快速检查、Android lint、Rust clippy、未签名 Release 构建，
并检查 APK 中的 DEX 和 Rust JNI 库。签名和模拟器阶段在本地完成，具体分工见[发布指南](RELEASING.md)。

## 自动检查与构建

```bash
./build-and-test.sh --quick
```

快速检查包含三语言资源、Node 协议测试、Rust fmt/test 和 Android/Robolectric 单元测试。
Android 测试会构建并加载 host JNI，Node 使用内置测试运行器，无需安装 npm 包。

发布前先配置[签名](RELEASING.md#配置签名)，再运行：

```bash
./build-and-test.sh --release
```

发布检查额外执行 clippy、lint、R8、签名及 zipalign 校验，并生成安装包和更新附件。
单独运行 Android 检查可用：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
```

构建会自动生成 JNI 库，不需要手动复制。依赖校验失败时先排查版本或产物差异，
不要为通过检查而直接覆盖校验值。

## 模拟器回归

使用专用模拟器，脚本会写入测试书签、下载和站点数据。正式回归使用 API 37 ARM64 模拟器和
包名为 `com.mybrowser` 的签名 Release；当前 runner 不接受 API 29。
`full` 包含启动更新提示检查，需要可执行 `adb root` 的 AVD。
调试版包名不同，不能直接用于这套 Release 回归。
x86_64 调试版的构建方法见[贡献指南](CONTRIBUTING.md#构建和运行)。

以下以 `emulator-5554` 和 0.9.1 为例，运行时替换为实际设备序列号与安装包路径。

1. 安装并启动签名包，部署 UI 辅助程序：

   ```bash
   ANDROID_SERIAL=emulator-5554 ./install_and_test.sh PureBrowser-v0.9.1-release.apk
   python3 validation/setup-ui-probe.py emulator-5554
   ```

   覆盖升级需要相同签名。请使用专用测试设备，不要通过卸载或清空日常使用的应用来绕过签名问题。

2. 在一个终端启动 QA 服务，测试期间保持运行：

   ```bash
   python3 validation/qa-server.py --apk PureBrowser-v0.9.1-release.apk
   ```

3. 在另一个终端运行所需阶段：

   ```bash
   python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.9.1-release.apk \
     --label release-091-tabs --profile tabs
   ```

一次只运行一台模拟器和一个 UI 脚本，避免同时执行 Gradle 构建。
QA 服务监听本机 8875/8876，经 ADB reverse 供模拟器访问；证书和桌面模式夹具还使用 8877–8879。
设置了本机 HTTP 代理时，可在回归命令前加
`NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost`，让遥测直连本机。

`smoke` 包含地址栏、标签驻留、基础浏览和网页对话框；`tabs` 包含驻留、普通/无痕媒体生命周期、
菜单及两种弹窗视频。阶段定义见 [run-regressions.py](validation/run-regressions.py)。
发布前按风险选择定向阶段或 `full`，并记录没有执行的范围。

每个 suite 记录 APK SHA-256、设备、测试源码哈希、阶段和日志。
只有安装包、AVD、测试源码和阶段选择全部相同时才能加 `--resume` 续跑；
失败尝试保留在 `priorAttempts`，换包或改测试后应创建新的 suite。

测试旧版本时，线上更新提示可能遮挡 UI。可先关闭“设置 → 关于 → 启动时自动检查更新”，
更新功能本身则应单独验证。

### 启动更新提示

`update-launch` 检查切换深色模式导致 Activity 重建后，待处理的更新提示是否仍存在。
它需要可 `adb root` 的 AVD，会写入缓存测试清单，仅检查提示，不下载或安装。
结束时还原缓存、深色模式和应用进程。

```bash
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.9.1-release.apk \
  --label update-prompt --stages update-launch
```

## 标签与应用更新

- 在驻留夹具中填写表单、修改 SPA 状态并滚动，分别手动新建标签、`window.open`、`target=_blank` 后返回。
  核对原文档 token、表单、JavaScript 状态和滚动位置，不能只核对 URL；离开页的视频保持暂停。
- 子页有自身历史时先返回该历史，再关闭子标签返回来源；系统返回、底栏返回、“×”和 `window.close` 分别检查。
  来源标签已关闭时返回最近使用的存活标签；关闭后台页不改变当前选择，最后一个“×”返回新标签。
- 更新检查使用匿名的 Releases `latest/download/update.json`，缓存 5 分钟；核对正式版本、无版本、离线、限流、大小和版本号。
  拒绝外站/HTTP 重定向、异常清单、错误大小/哈希/包名/版本/签名/ABI；取消下载删除未完成文件。
- 使用相同签名的较旧验证包，从真实 GitHub Release 下载，经系统授权后覆盖更新。
  记录安装前后版本、包哈希和数据保留结果；没有通过实际安装的环节单独标注。
- 更新安装与相机文件分享分别验证：单元检查两个 Provider 的组件独立和目录边界，模拟器确认系统安装器可以读取 APK，
  并用 `capture` 阶段确认系统拍照/录像仍能将完整 JPEG/MP4 返回网页。只出现安装器启动提示不算安装成功。

## 扫码预览

- 使用带 TOP、LEFT、RIGHT、不同角标、方格和正圆的非对称测试图；核对文字方向、左右顺序、圆形比例及居中裁切。
  模拟器 `imagefile` 先通过独立 Camera2 采集记录原始帧与传感器方向，区分夹具投影和应用预览变换；扫码成功不能代替画面验证。
- 覆盖屏幕 0°、90°、180°、270°、窄竖屏和宽横屏；连续 90°→270° 时核对预览宽高不变仍更新方向。
  前摄回退保持系统默认镜像，应用不再叠加镜像。模拟器结果与未验证的实体设备覆盖分开记录。
- 确认后台释放、前台恢复、Back/快速关闭释放，以及相机网页码、图片文本码、继续扫描和拒绝权限后选图。
- 几何测试覆盖各传感器角度与不同缓冲区/视口比例，检查等长垂直像素轴、中心位置、裁切边界和方向。
  若使用会改变应用构建的临时测试代码，移除后需重新构建，并对最终签名 APK 运行回归。

## 开发工具与通用界面

- 首页地址栏可点击输入，左侧不显示主页占位图标，右侧仅扫码；普通网页保留安全信息、刷新/停止。顶部/底部地址栏均验证。
- 相机首次授权、拒绝、设置恢复、切后台、旋转、快速开关和手电筒；退出后 `dumpsys media.camera` 不保留本应用活动客户端。
- 扫描 HTTP(S) 与无协议域名应在当前标签访问一次；多行中文、emoji、Wi-Fi、`javascript:`、`file:` 和应用协议只展示纯文本。
- 从系统文件选择器选择二维码图片，验证中文原文复制、继续扫描、无二维码和无效文件错误；不需 Google Play 服务或外网。
- 开发工具源码查看普通 HTML、脚本/样式、单行压缩源码、百万字符、只有大量换行、emoji 跨块边界；检查颜色、滚动、截断和刷新。
- 网络组合测试类型、状态和大小写搜索，清空、无结果、切页签恢复及后台日志；Fetch/XHR 使用目的头或明确 Accept 头的本地夹具。
- 菜单、标签、历史、下载、书签、网站设置、过滤、脚本、设置、投屏及扫码检查统一标题间距、48dp 操作、轻量过渡、逐级返回。
- 浅/深主题、中文/英文、150% 字体、横竖屏和键盘避让；系统动画缩放设为 0 时也可正常操作。
- 使用 `dumpsys gfxinfo com.mybrowser reset` / `framestats` 采集实际布局/滚动帧；主机词法扫描耗时不能代替手机渲染性能。

可复用的回归用例保留在源码中，一次性探针和生成夹具在检查后清理；结果和截图放在本地验证目录。

## 播放器及其他功能

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
  菜单与全部子页复用一个弹层窗口；用录像检查切换是否出现闪帧。返回菜单后没有旧设置内容，最终只剩主窗口且可触摸。
- 网站设置、过滤订阅、用户脚本、管理网站、下载、历史、标签等页面上下边界连续执行快/慢滑动；标题及固定操作栏不移动，边界不拉伸，停止后像素稳定。
- 管理网站列表使用固定弹层，通过左上角返回关闭；横屏和放大字体下同样可到达全部操作。
- 开发者工具仅在菜单出现，关于和设置搜索不再包含该入口。

### 关闭媒体与画中画

```bash
python3 validation/media-lifecycle-regression.py --serial emulator-5554 --output validation/results/media-lifecycle
```

- 普通暂停仍可系统继续；Stop、播放结束、隐藏并暂停、卸载、移除、替换、关闭标签和导航后释放系统会话。
- 用 dumpsys 核对 session token、服务和活动通知消失，浏览器 PID 不变；迟到消息不能重新创建旧会话。
- 通过 `cmd notification list` 核对活动通知；归档记录不代表通知仍在显示。
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

### 其他功能

- 主浏览链路、标签与历史恢复、主页、链接/图片上下文菜单、默认浏览器、外部 VIEW/SEND。
- 菜单/设置逐级返回、快速连续点击、按钮及外部区域关闭、重建与宽屏；私密会话配置重建及 Profile 清理。
- 书签文件夹/移动/批量、HTML 导入导出与非法协议过滤。
- 网站权限与系统授权分离；导航取消旧请求；input capture 拍照/录像/取消/重建后的字节回传。
- Autofill 配置及合成 passkey 请求结果，不能冒充真实账户成功登录。
- 脚本预览、依赖/资源字节、排除匹配、进程恢复、私密禁用和旧 WebView 降级。
- 广告来源更新、304/无效/离线回退、命中解释；开发工具可见订阅、草稿及横屏键盘。
- 语言、主题、150%/200% 字号、TalkBack 与键盘按需要单独执行，实际覆盖以报告为准。

对应脚本可由 `validation/run-regressions.py` 查看。阅读/离线文章、Google Cast、整体备份恢复和双窗口
目前不提供；最近关闭、撤销和打印也没有对应阶段。一次性诊断脚本在检查完成后清理。

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

## 维护 UI 回归脚本

Release 不开放远程 WebView 调试。`setup-ui-probe.py` 将辅助程序放到
`/data/local/tmp/pure-ui-dump.jar`，用真实触摸、键盘和无障碍树操作界面。
动态网页还要检查 DOM 遥测、播放状态或返回的文件字节，按钮存在本身不足以证明操作成功。

UI 查询会启动设备端 `app_process` 并连接 UiAutomation，历史单次耗时约 280–540ms。
现有 helper 支持在同一个连接里等待控件出现或消失，减少重复抓取整棵树。
不要用固定 sleep 代替“输入框已获得焦点”等实际条件；控件标签优先通过资源名读取各语言值。

权限窗口和全屏控件应在同一次辅助会话中定位并触摸，避免动画或自动隐藏让坐标过期。
播放器可用 `playerDump`、`playerReveal`、`playerTap`，减少遍历后台 WebView。
网页节点漏报时，先核对当前网址和新鲜的夹具几何；旋转、PiP 或窗口切换后，等待尺寸及模式稳定再操作。

压力循环默认是菜单 8 轮、设置返回 4 轮。需要增加重复次数时：

```bash
python3 validation/menu-navigation-regression.py --serial emulator-5554 --cycles 24
python3 validation/settings-back-regression.py --serial emulator-5554 --cycles 6
```

比较等待机制时，可设置 `PURE_UX_NO_AWAIT=1` 退回普通快照，在相同包、设备和负载下对照。
历史性能数据见[回归报告](EMULATOR_TEST_REPORT.md)，不作为每次运行的耗时标准。

修改验证框架后，应做一次负向对照：临时引入一个已知缺陷，确认对应阶段仍会失败，
随后还原代码并重新核对安装包。这样可以发现“脚本变快，但漏掉问题”的情况。

## 故障记录

```bash
./diagnose.sh
adb logcat -d | rg 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV|AndroidRuntime'
```

记录 APK SHA-256、API/ABI、WebView 版本、操作步骤和脱敏网址，保留失败截图、完整树及遥测。
区分浏览器、WebView renderer、系统服务和 shell 辅助程序 PID，不把平台辅助进程故障算作浏览器崩溃，
也不能因重跑成功删除失败记录。生成证据不提交 Git，清理时只保留本版必要记录与复现脚本。
