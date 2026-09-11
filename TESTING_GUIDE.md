# 测试与验证指南

## 自动化检查

```bash
cd rust
cargo fmt --all -- --check
cargo test --locked --all
cargo clippy --locked --workspace --all-targets -- -D warnings

cd ..
npm --prefix validation ci --ignore-scripts
npm --prefix validation test
python3 validation/check-localization.py
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
./gradlew :app:assembleRelease --console=plain
```

Android 单元测试使用 Robolectric，覆盖 URL/导航、数据库、下载设置/Range/HTTP 引擎、文件名、媒体候选和 DLNA 解析；
媒体覆盖从当前加载源恢复遗漏候选、签名参数保留及 Blob/无关资源不生成直接候选。
新增脚本测试覆盖元数据、匹配、依赖、存储与桥接限制；过滤覆盖条件更新、损坏/离线回退、CSS 例外和引擎完成时序；
DLNA 覆盖 SOAP Fault、格式和服务版本、并发发送及迟到结果。
首页数据测试还覆盖稳定 ID、重复地址、自定义图标保护、写入失败回滚与旧图清理。
Rust 测试覆盖规则/索引匹配、元素隐藏和缓存、URL 工具及书签 HTML 解析。
0.4.1 增加短通配符的穷举参考对照、域名/token 索引与线性扫描等价性、大小写域名回归；
Android 增加重定向 Cookie/Range、异常部分响应、有界 JSON、编码媒体路径/默认端口、
重复播放线索、跨页查找重置及证书错误状态测试。
网页协议测试覆盖视频目标隔离、临时倍速恢复、全屏控制权、样式被阻止时恢复和过期消息回执；
增强播放器新增自定义容器、无 controls、签名直链、Blob/MSE、暂停目标固定及样式/路径失效恢复；
实际 CSS 布局、动态控件隐藏与网页控件切回由模拟器验证，见 [专项记录](design/enhanced-player-validation.md)。
用户脚本协议另覆盖执行时机、去重、API 授权、写入确认/回滚与值隔离；字符串检查覆盖三语言键和格式参数。
0.5.0 增加元素隐藏域名/例外/缓存、真实 JNI 书签 roundtrip、单事务导入回滚、origin 权限隔离、
迟到权限回执、最近关闭重启恢复、有界阅读模型、下载实体变化/重定向/暂停恢复和 DLNA 会话轮询测试。
Gradle 自动构建 host JNI，并将其作为测试输入；不使用假 native 解析替代真实跨语言契约。
当前数量及最近一次执行结果见 [README](./README.md) 和 [回归报告](./EMULATOR_TEST_REPORT.md)。

## APK 检查

```bash
APK=PureBrowser-v0.6.0-release.apk
apksigner verify --verbose "$APK"
unzip -l "$APK" | rg 'lib/|AndroidManifest.xml'
```

默认只应看到 `libmybrowser_adblock.so`、
`libmybrowser_url_utils.so`（以及 AndroidX 自带库）；不应看到 database、cache、downloader 或
filename_parser；这些无调用的实验模块已删除。

## 设备回归

```bash
./install_and_test.sh
./diagnose.sh
```

以下是验收清单；在 API 29、API 34、API 37 arm64 模拟器和真机上按需执行，实际完成项单独记录：

- 启动、WebView 页面加载、前进/后退、主页、刷新/停止
- 地址栏访问/搜索按钮、建议、外部协议交接
- 标签、书签新增编辑、历史、无痕清理
- 首页长按统一编辑标题/地址/图标、导入与取消、重启保留、旋转/大字体、删除确认及书签保留
- 链接/图片长按、后台延迟加载、标签搜索及返回记录隔离、批量关闭确认
- 书签/历史数据库分页搜索、旧查询失效、失败重试、重复书签编辑保护
- 系统 Download/SAF 自定义目录、线程设置持久化、Range/单线程回退、后台下载、重名文件
- 下载打开/重试，以及仅删除记录和同时删除对应本地文件
- 有强校验器的下载暂停/继续、主动暂停跨重启保留、运行中进程死亡后的自动接续及最终文件 SHA-256
- 书签 SAF HTML 导入预览/确认、重复导入、导出文件转义与非法协议拒绝
- 单站 JavaScript/过滤/桌面设置、端口隔离、网站同意与系统权限分离、导航取消授权
- 标签关闭撤销与最近关闭跨重启恢复；阅读保存、断网后重启打开、打印导出完整 PDF
- 150% 字体下阅读/网站设置的横竖屏滚动、底部操作与读屏名称
- 页面内查找、文件上传、全屏、JS 对话框和权限回调
- 视频媒体候选、当前播放标记、菜单/增强全屏投屏入口、弹层取消保留全屏、DLNA 设备发现/投送
- 当前视频 0.5×–3× 倍速、跨域 iframe 播放器和同页媒体源重载后的倍速保持
- 增强全屏亮度/音量/进度手势、临时倍速、锁定返回、横竖屏和退出状态恢复
- 有可识别媒体时网页右下角显示一个投屏按钮；YouTube/自定义容器由网页控制，标准视频交接和控件自动隐藏无重复显示
- 菜单 → 设置 → 返回菜单 → 关闭菜单，不能退出应用；选项/分类/管理页按原路径逐级返回
- 书签/历史/下载/网站设置/离线文章/开发工具返回原菜单，保留滚动位置；打开网页等动作关闭整条菜单路径
- 系统返回键、边缘手势、宽屏两侧工具栏、重建与后台恢复后的来路；快速点击和拖动关闭无残留窗口
- 主题持久化、大字体与宽屏双栏、应用语言切换
- 脚本安装预览、依赖、GM 数据重启保留、停用/删除、无痕隔离及旧 Provider 降级
- 内置和自定义过滤列表、请求阻断/元素隐藏、ETag 304、无效/离线更新保留旧规则、后台调度开关
- 按普通/无痕 Profile 清理 Cookie、localStorage、IndexedDB 和 CacheStorage，并核对非目标数据
- 书签文件夹与分组、版本化本地备份恢复、脚本停用恢复、输入拍照/录像与旧文档回执取消
- 普通/无痕系统 Autofill、网站来源权限及合成 passkey 请求；真实账号结果单独记录
- PiP、后台播放默认值、MediaSession、关闭其他窗口与无痕媒体排除
- 两窗口独立文档、真实分屏、私密 Activity 重建、TalkBack 焦点与双击、200% 字体和键盘
- 用户脚本资源字节/类型、进程重启、排除规则与私密禁用；Google Cast 选择器和可恢复返回

可控设备回归脚本、测试服务器启动命令和性能对照方法见
[回归报告](./EMULATOR_TEST_REPORT.md)。测试时只运行一台模拟器，避免与 Gradle 构建并行。

菜单专项：`python3 validation/menu-navigation-regression.py --serial emulator-5554`。
脚本检查真实菜单 semantics、前台 Activity 和窗口数量，覆盖 24 轮快速往返、4 组立即返回、
拖动手柄安全区域、重建与外部链接打断，以及最终网页真实点击。输入没有完成回执即失败，
不重发可能已送达的返回事件。

桌面模式专项：`python3 validation/desktop-mode-regression.py --serial emulator-5554 --online`。
脚本自建 8878/8879 端口的本机服务器，通过 `.localhost` 的 m./www. 主机模拟 JavaScript 和 HTTP 302 跳转；
同时检查请求 UA、页面 UA、1024 视口、文档标识、请求数量及真实菜单开关，覆盖关闭、刷新、重启、子域和端口隔离。
`--online` 额外验证 `https://m.jrs16.com/` 的桌面/移动切换；结果绑定安装 APK SHA-256。
默认不依赖公网，已纳入 runner 的 `desktop-mode` 阶段。运行前安装同一签名包及 UI 辅助程序。

设置返回的专项回归：`python3 validation/settings-back-regression.py --serial emulator-5556`。
需先启动 `validation/qa-server.py` 并安装 UI 辅助程序；脚本恢复旋转和字体配置，将设备 APK
SHA-256、逐项结果、跳过项及截图写入 `validation/results/`。边缘手势仅在设备已启用手势导航时执行。

证书专项回归：`python3 validation/security-regression.py --serial emulator-5554`。
脚本只接受模拟器，自行在本机 8877 端口启动自签名 TLS 夹具；验证用户继续访问后、刷新及同源
重访的警告与站点信息，最后删除临时证书和密钥。

开发工具专项：`python3 validation/developer-tools-regression.py --serial emulator-5554`。
验证面板关闭期间的日志、重新订阅、控制台草稿/结果跨标签保留、源码/信息读取和横屏键盘操作。

规则匹配基准：

```bash
cargo run --release --manifest-path rust/Cargo.toml -p adblock --example benchmark -- 10
```

固定样本、原始对照和测量边界见 [架构说明](ARCHITECTURE_REVIEW.md)。它不测量整页加载或真机耗电。

## 负面与边界测试

- 过长/损坏的偏好 JSON 不应导致崩溃或无限内存。
- `javascript:`, `file:` 和未知外部 scheme 不应从偏好恢复或被任意启动。
- 超大 DLNA XML/SOAP、下载 header、书签 HTML 和广告规则输入应按各自边界拒绝或截断。
- renderer 崩溃后页面应重建，logcat 不应出现 `FATAL EXCEPTION`、`UnsatisfiedLinkError`
  或 `SIGSEGV`。

没有实体 DLNA renderer 时，投屏只能验证候选选择和发现 UI；实际播放必须在同网真机设备
上确认。

脚本、规则更新、MD3 网页对话框和多视频投屏入口回归：
`python3 validation/features-regression.py --serial emulator-5554`。
可用 `--section scripts / imports / filters / dialogs / media` 单独执行，脚本只管理其命名的测试数据，结果记录实际 APK hash。

## 回归范围与当前 CSS 基准

```bash
python3 validation/qa-server.py
# 在另一个终端，同一台模拟器只运行一个 UI 测试：
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.6.0-release.apk
# 只有 APK 和 AVD 都相同时才能恢复通过的阶段：加 --resume
python3 validation/capabilities-regression.py --serial emulator-5554 --section downloads
python3 validation/cosmetic-benchmark.py
```

新增分段为 site、permissions、tabs、reader、printing、bookmarks、downloads、layout。
完整 runner 提供 API 29 的 39 阶段、API 34 的 41 阶段、API 37 的 42 阶段，
每阶段记录包哈希、退出码、耗时和日志。现代 Profile 专项要求对应 WebView 能力；
旧系统不把共享存储清理当作独立 Profile。实际执行范围见回归报告。
它只接受专用模拟器；分页测试会写入测试数据库，书签/下载/PDF 会创建测试文件。
系统文件选择器通过 Downloads 导航和列表滚动查找文件，避免依赖 Recent 的媒体索引或首屏位置。

系统集成和生命周期专项可单独复现：

```bash
python3 validation/desktop-mode-regression.py --serial emulator-5554 --online
python3 validation/lifecycle-boundaries-regression.py --serial emulator-5554 --section media \
  --output validation/results/media-boundaries
python3 validation/lifecycle-boundaries-regression.py --serial emulator-5554 --section private \
  --output validation/results/private-recreation
python3 validation/split-screen-regression.py --serial emulator-5554 --output validation/results/split-screen
python3 validation/talkback-regression.py --serial emulator-5554 --output validation/results/talkback
python3 validation/benchmark-startup.py --serial emulator-5554 --output validation/results/startup.json
```

`--resume` 只接受完全相同的 APK、AVD 和阶段选择；失败保留在 `priorAttempts`，完成时清除顶层错误状态。
浏览阶段冷启动新 renderer，减少 Provider 在前一阶段旋转后遗漏已绘制网页节点的干扰；
若仍失败，保留截图、完整无障碍树和 DOM 证据，区分真实页面操作与节点暴露问题，不能反复重试后隐去失败。
仍由真实点击和页面变化判断通过，不把可访问性节点存在当作实际交互成功。
TalkBack 专项使用辅助程序的 `a11y` 模式，保留真实无障碍服务；默认 UiAutomation 会暂时抑制它。
分屏专项使用 Android WMShell 的真实分屏组织器，要求 API 31+，两窗操作仍注入真实触摸。
启动测量期间停止其他 UI 测试与构建；首帧、两帧后网页回执和应用/renderer PSS 分开记录，PSS 不是峰值。

CSS 基准仅运行当前 Rust：80 个 host、480 个冷查询和 200 个缓存命中查询，记录输入/输出哈希。
临时 CSS 文件在运行后删除，只保留最新 `result.json`；不依赖旧提交、备份分支或 Kotlin 实验构建。
历史跨语言对照见 [能力复查](design/browser-capabilities-20260910.md)。


## 故障排查

```bash
./diagnose.sh
adb logcat -d | rg 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV|AndroidRuntime'
```

- 无法安装：核对 Android API、ABI 和签名，使用同一签名 `adb install -r` 升级；不要先删除用户数据。
- native 加载失败：检查两个产品 JNI 库是否齐全，重新运行 `./build-and-test.sh`；不手工复制旧 `.so`。
- 白屏或 renderer 异常：记录页面、WebView provider 和完整堆栈；区分浏览器、WebView renderer 与测试辅助进程 PID。
- 投屏发现/播放：真机与接收器同一局域网，核对网络隔离、媒体鉴权及格式；模拟器发现 UI 不能代表实体播放。
- 无痕数据：区分独立 Profile 与共享存储退出清理，等待清理完成提示；具体行为以 README 为准。

问题记录应包含 APK SHA-256、设备/API/ABI、WebView 版本、复现步骤和去掉敏感查询参数的页面 URL。
构建输出、截图和日志留在 `validation/results/`；交付清理后只保留当前版本必要的一套证据。
