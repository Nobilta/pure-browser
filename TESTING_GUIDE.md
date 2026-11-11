# 测试指南

先按[贡献指南](CONTRIBUTING.md#开发环境)准备环境。本文介绍检查命令、模拟器运行方式和结果定位。

## 自动检查

网页实时摄像头回归：启动 `python3 validation/qa-server.py`，在带前后摄像头的模拟器运行
`python3 validation/realtime-camera-regression.py --serial emulator-5554 --package com.mybrowser.debug`。
覆盖授权后不点击网页即可开始预览、视频时间和帧数持续增加、前置→后置→前置切换、默认摄像头选择记录，
以及普通媒体遵循页面自动播放逻辑、拒绝摄像头授权仍不能采集。它验证 `getUserMedia`，
不代替 e签宝真实活体流程或真机验证。

相机上传回归：启动 `python3 validation/qa-server.py`，再运行
`python3 validation/capture-regression.py --serial emulator-5554 --package com.mybrowser.debug --output validation/results/capture`，
覆盖拍照／录像文件回传、权限拒绝、取消和页面切换。

证书警告回归：安装待测 APK 后运行 `python3 validation/security-regression.py --serial emulator-5554`，
调试包加 `--package com.mybrowser.debug`。脚本使用本地自签名证书，检查允许访问后、刷新和再次访问时的危险连接提示。

广告闪现回归：安装待测 APK 后运行 `python3 validation/cosmetic-flash-regression.py --serial emulator-5554`。
脚本仅用于可 root 的本地模拟器，临时设置测试订阅与站点偏好，结束后恢复；自带 8878 端口服务器，
使用模拟器宿主地址 `10.0.2.2`，避免 EasyList 对 localhost 的通用隐藏例外干扰结果。
每个动画帧检查首屏、动态插入及 head 替换后的广告可见性，延迟图片 2.5 秒验证不依赖加载完成，
并覆盖重定向、弹窗标签、无痕、临时及持久站点开关、全局开关和规则例外。旧包可加 `--expect-flash` 验证基线。
结果和被测 APK 校验值写入 `validation/results/cosmetic-flash-<serial>.json`；只证明夹具匹配规则的可见性，
不代表所有真实广告、WebView 版本或帧内脚本观察时点都已覆盖。Node 测试另检查样式重挂载及关闭恢复。

书签拖拽设备回归：在隐藏窗口的本地可 root 模拟器启动 `validation/qa-server.py`，安装待测 APK，
运行 `python3 validation/setup-ui-probe.py emulator-5554`，再运行
`python3 validation/bookmark-drag-regression.py --serial emulator-5554`（调试包加 `--package com.mybrowser.debug`）。
脚本只在模拟器建立专用 `Drag QA` 目录和 75 条测试书签，保留其他记录；检查精简菜单、文件夹排序、
拖动取消及原滚动位置恢复、上下拖动、多选保持、边缘滚动跨过 50 条分页、顺序持久化、搜索禁用拖拽和本地网站图标。
记录在 `validation/results/bookmark-drag-<serial>.json`，截图同目录 `bookmark-*.png`；再次运行会重置专用夹具。
该脚本会启动本地网页获取图标，不访问外部图标服务。

`BookmarkLibraryTest` 用 275 条记录覆盖超过 SQL 搜索上限后的排序与继续分页；搜索查询仍保持单次结果上限。

```bash
./build-and-test.sh --quick
```

包含三语言资源校验、Python 发布工具测试、Node 协议测试、Rust fmt/test 和 Android/Robolectric 单元测试。
发布工具测试覆盖 CI 运行选择、GitHub CLI 路径、版本递增、签名失败和验证记录的版本边界；不调用真实发布接口。
Android 测试会构建 host JNI，Node 使用内置测试运行器。
更新仓库测试使用注入的 HTTP 连接覆盖 Gitee 优先、GitHub 回退、来源缓存、独立限流、下载中断后同版本重下，
以及非法清单、错误摘要／签名、取消和本地文件失败不会换源。PackageManager 元数据使用 Robolectric，不能代替真实安装验证。
签名包的更新来源回归运行 `python3 validation/update-sources-regression.py --serial emulator-5554`。
脚本只用于已安装正式包、允许 root 的专用模拟器；实际访问两个平台，临时设置来源限流以触发自动切换，
检查更新面板和成功来源缓存，结束后恢复更新偏好。不下载或安装新版，换源传输及校验由 JVM 测试覆盖。
脚本先用 `npm ci --prefix validation` 安装锁定的 DOM 测试依赖。单独运行 Node 回归时也先执行该命令。
翻译批处理单测检查合并后的中文、emoji 和分隔标记总编码长度；使用内存中的回显回调，不消耗线上额度。
`TranslationControllerTest` 驱动真实 JS 回执解码与协程调度，覆盖停止后迟到的结果、驻留标签恢复、
同 URL 重载、异步模式查询跨文档返回及连续查询取消时的快照保留；
`BrowserPageHostTest` 覆盖配置 OOM 的释放顺序，以及弹窗交接期间关闭宿主。
真实 WebView 翻译回归运行
`python3 validation/translation-regression.py --serial emulator-5554 --package com.mybrowser.debug --output validation/results/translation`。
脚本在专用模拟器使用本机 LibreTranslate 协议桩，临时写入调试包翻译偏好并在结束后恢复，
覆盖替换／双语模式、动态段落、滚动、恢复原文、慢请求期间切换标签及导航隔离，不消耗线上翻译额度。
此回归验证客户端协议与页面生命周期，不验证外部提供者的服务质量。
用户脚本存储测试通过暂停写锁复现旧无痕请求跨会话排队；历史导入测试用 SQLite 拒绝触发器验证整组回滚。
缩略图改动需运行 `resident-regression.py` 并目视检查标签图片；位图分配量可按目标像素计算，
模拟器上的耗时不能代替真机帧率测量。
UI 回归里的无痕入口按 `menu_incognito_short` / `menu_incognito_exit_short` 资源解析当前文案，
标签卡片操作通过长按打开，避免继续依赖已移除的菜单文案或独立操作按钮。
资源辅助同时读取数量资源的默认形式和 `name/one`、`name/other`，便于检查英文单复数。
翻译 DOM 回归用 jsdom 覆盖段落拆分/合并、动态排除节点、源版本校验和失败重试；布局尺寸由夹具提供，
不代表真实 WebView 的布局与滚动验证。
其中还覆盖根编辑区过滤、移除后重新插入段落的懒加载观察器，以及动态文本更新的字符预算。
导航协调测试覆盖等待请求替换、生命周期取消、当前 WebView/Profile、外部链接的标签选择及过期历史操作；
下载存储测试覆盖记录数量/字符预算、损坏快照和 Cookie 不落盘，调度器测试继续验证迁移、隐私与并发续传。
修改这些边界后，设备回归应包含 `navigation-recovery`、`private-lifecycle`、`resident`、`download`、
`blob-download`；设置公共控件改动应运行 `settings-back` 和 `settings`。
单独运行 `settings-back-regression.py`、`settings-regression.py` 和 `locale-regression.py` 时，
可加 `--package com.mybrowser.debug` 检查调试包。
设置持久化通过重新打开选项页并检查选中状态验证，不依赖分类列表的副标题。
调用同一 JNI 库的测试应使用相同的 Robolectric runner 和 `@Config`，避免 JVM 类加载器之间重复加载原生库。
字符表固定使用 OpenJDK 26.0.2 的 BMP 分类（含该版本补丁），不会随 Android/JDK 自动变化：
用对应 JDK 运行 `python3 rust/tools/generate-java-char-tables.py --check` 对比所有 65,536 个值，
`--write` 可重建表，修改基线版本时必须审查分类差异。该命令独立于使用其他 JDK 的常规构建。

具体覆盖以测试源码为准：`app/src/test` 中每个测试的注释说明它为什么存在，
反馈回归覆盖重要消息被撤销打断后的补发、暂停期间暂存，以及书签和设置导出结果在 Activity 重建后的保留；
文本回归覆盖恰好 5 MiB 完整显示、超过一个字节拒绝、未知长度流有界读取、中文与 emoji 编码及 HTML 转义；过滤校验覆盖带资源类型、域名选项的关键词和通配符规则；
`validation/*.test.cjs` 覆盖网页侧的媒体探针和用户脚本协议。
`element-picker.test.cjs` 还覆盖触摸与鼠标松开坐标跨越元素边界、空白释放、取消选择及预览恢复；
修改元素选择器后，运行设备阶段 `block-element` 验证真实 WebView 中的选择、预览与规则生效。
这些都在 JVM 或 Node 上运行，不执行真实的 Chromium，因此不能替代设备验证——
Autofill、窗口通信、系统媒体、内存压力和真实触摸的行为只能由下面的模拟器回归确认。

过滤引擎的 JNI 决策表和系统登录能力由 `app/src/androidTest` 在设备上核对，不在上面的快速检查里。
`testInstrumentationRunner` 是一个自定义 `Instrumentation` 而不是 JUnit 用例，
`./gradlew :app:connectedDebugAndroidTest` 会以零个用例「通过」，必须显式运行：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 决策表：核对发布 ABI 与宿主构建是否给出同一组答案
adb shell am instrument -w com.mybrowser.debug.test/com.mybrowser.validation.NativeFilterInstrumentation
# 系统登录：Autofill 与 WebAuthn 的开关随普通/无痕模式切换
adb shell am instrument -w -e suite login com.mybrowser.debug.test/com.mybrowser.validation.NativeFilterInstrumentation
```

两者的结果都在输出的 `report` 里，`"passed":true` 表示通过；失败时同一行会带上 `failure`。

设备验证可在 QA 服务打开 `login-lifecycle-fixture.html`：用虚拟账号测试系统 Autofill；
分别打开回调/跳转弹窗并完成，确认原页面显示 PASS，回调模式的文档标识与表单不变。
在三秒延迟内切到第三个标签，确认关闭的是弹窗，返回原页面后回调请求成功；再验证嵌套弹窗、
无痕模式（仅窗口通信）、关闭其他标签和退出会话。真实 Bitwarden 与 Google/X 登录需另外记录版本和结果。

签名配置完成后，发布检查包含全部快速检查，并运行 clippy、lint、R8、签名及 zipalign 校验；无需再先运行 `--quick`：

```bash
./build-and-test.sh --release
```

只运行 Android 单元测试和 lint：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
```

[CI](https://github.com/Nobilta/pure-browser/actions/workflows/ci.yml)执行快速检查、lint、clippy 和未签名 Release 构建；
设备回归在本地运行。签名与发布流程见[发布指南](RELEASING.md)。

## 开发者工具回归

`developer-tools-regression.py` 覆盖控制台、网页日志与执行结果长按复制后的整条文本粘贴一致性、网络轻量日志、元素引导和存储，
并沿用原来的横竖屏、隐藏后重开和外部 VIEW 用例。调试包可加 `--package com.mybrowser.debug`。
此脚本使用静态页面服务，可通过 `--port 8877`
连接另开的 `python3 -m http.server 8877 --bind 127.0.0.1 --directory validation`，
与增强采集服务并行运行，避免带查询参数的静态页面被错误地返回 404。增强采集另有本地夹具：

```bash
python3 validation/devtools_server.py            # 8875 / 8876，含 Fetch、XHR、HTML、大正文、SSE、opaque 和 Cookie
adb reverse tcp:8875 tcp:8875
adb reverse tcp:8876 tcp:8876
```

打开 `http://127.0.0.1:8875/devtools-fixture.html`，在开发者工具网络页签执行
「开启并刷新」，然后核对：初始化 Fetch／XHR 出现在列表中并标注「页面脚本」；
请求与响应正文、Query 重复键、请求头／响应头可查看和复制；大于 256 KiB 的正文显示截断原因；
SSE 和 opaque 响应显示不支持的原因；元素页 DOM 树、搜索、子节点分页、DOM 源码分页与导出；
存储页 Cookies／Local Storage／Session Storage 无需增强即可读取。再核对「停止」后
新请求不再产生页面脚本记录、已有记录仍可查看，关闭面板后延迟请求仍会被采集。

交互回归同时检查标题栏仅保留标题、系统返回退出工具页、列表顶部反复滑动仍留在全屏工具页、搜索框聚焦前后不换行、详情返回列表、
重复三次横屏键盘开合与系统栏避让，以及请求／存储文字长按后的系统复制菜单。DOM 空白节点显示开关
应保留展开位置，空白筛选后加载更多和搜索定位仍使用原始节点索引；原文分段可跨行选择，
整段复制仍需保留真实截断状态。Node 与 JVM 用例覆盖空白上下文、长空白前缀、原始分页
和 Unicode 文本分段的完整性。

## 模拟器回归

媒体响应交接回归可单独运行，不需要 QA 服务：

```bash
python3 validation/media-fallback-regression.py --serial emulator-5554
```

脚本在本机 8880 端口启动夹具，检查跳转响应的 no-referrer／origin 策略、HTML 目标与刷新，
以及 HTTP 登录提示、直接／重定向附件、一次性链接、gzip 和未知长度响应。
下载通过实际文件 SHA-256 和服务器请求计数验证，不能用“出现确认框”代替保存完成。
结果写入 `validation/results/media-fallback-<serial>.json`，
记录被测 APK 和测试源码校验值；`--package com.mybrowser.debug` 可检查调试包。
媒体网络边界回归运行 `python3 validation/media-network-regression.py --serial emulator-5554`，
使用 8881/8882 端口、下面生成的测试证书和 `create-large-media-fixtures.py` 的稀疏媒体。
验证 Basic 直接地址与跨站跳转登录、缓存凭据后的来源保持、接受证书后的播放/拖动、分段 403 保留页面，
以及分段重新认证保留文档、原生导航与媒体拦截的 A→B→A Cookie 对照。脚本会重启被测应用，以清除进程内的认证与证书决定。
本地测试账号固定为 test/pure，不连接外部网站。结果写入 `validation/results/media-network-<serial>.json`。

书签排序回归运行 `python3 validation/bookmark-drag-regression.py --serial emulator-5554`，需先启动下面的 QA 服务。
它使用专用 root 模拟器中的夹具记录，覆盖分页边缘滚动、取消、持久排序、多选和一次位移跨多行的向上拖动。
跨分页拖动包含固定时长断言，应单独运行；并发模拟器的负载会影响滚动在限时内到达的行数。
修改 FastUiDump 后先运行 `python3 validation/setup-ui-probe.py emulator-5554`，避免旧辅助程序漏测新手势。

投屏列表的播放操作可在 QA 服务运行后单独检查：

```bash
python3 validation/cast-play-regression.py --serial emulator-5554
```

该脚本使用 `cast-play-fixture.html`，检查普通媒体、HLS 和 DASH 均显示播放／下载按钮，下载取消后投屏选择不变，
普通视频携带完整 URL 参数在新标签播放、返回原页面和投屏资源行选择，并核对清单的下载确认和新标签打开。
清单夹具只用于检查入口和导航，不用于验证分片播放或合并下载。
结果写入 `validation/results/cast-play.json`，包含被测 APK 的 SHA-256；实体接收器需另行验证。

来源校验回归运行 `python3 validation/cast-referrer-regression.py --serial emulator-5554`。
QA 服务对缺少或错误 Referer 的媒体返回 403；脚本检查普通／无痕标签播放、Range／拖动、刷新、
跨源重定向，以及同标签打开其他页面后不再发送旧来源。结果保存在 `validation/results/cast-referrer-<serial>.json`。
夹具同时检查当前会话 Cookie、跨主机重定向各自的 Cookie／Set-Cookie，以及 origin／no-referrer 策略。
跨主机夹具用浏览器预置目标域会话 Cookie，并用每次运行独有的 Lax Cookie 验证 GET 主导航的逐跳写入；
不要在 HTTP 夹具中用手动写入 Secure Cookie 验证更新，CookieManager 要求这类写入使用 HTTPS。
扩展回归运行 `python3 validation/cast-referrer-regression.py --serial emulator-5554 --cases direct,redirect,origin,none,cdn,large,boundary,over-limit,private`。
先运行 `python3 validation/create-large-media-fixtures.py` 生成稀疏 MP4；其中视频样本覆盖 1 GiB、2 GiB 边界及超过边界的偏移，
可验证流定位和解码，不代表持续大码率传输。应在旧、新 WebView 上分别运行，不能只凭 Android API 版本判断覆盖。

错误页回归运行 `python3 validation/cast-media-failure-regression.py --serial emulator-5554`，检查 HTTP 拒绝、连接失败、
拒绝不受信任证书和响应读取中断。启动 QA 服务前，用下列命令生成仅用于本地夹具的短期自签名证书：

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -keyout validation/results/referrer-tls-key.pem -out validation/results/referrer-tls-cert.pem -subj /CN=localhost -addext subjectAltName=DNS:localhost,IP:127.0.0.1
```

QA 服务发现这两个文件时会额外监听 8877；测试不会信任该证书或跳过 TLS 校验。设备结果保存在
`validation/results/cast-media-failure-<serial>.json`。两份回归脚本均可加 `--debug` 检查调试包；最终交付仍须检查签名 Release。
这些回归不代表 HLS／DASH 分片播放已兼容，实际设备与场景覆盖以 `release/notes.md` 为准。

JVM 回归覆盖并发与会话逻辑（清理回调延迟与失败重试、Activity 等待者替换、ViewModel 结束下载会话、
导出前订阅初始化、导入后的首次规则更新等），联想测试用本地 HTTP 服务核对 OpenSearch / JSONP
与深嵌套失败处理，备份测试用真实书签与历史存储核对导出合并、上限截断和重复导入不累加。
这些都不能证明远程服务可达，也不能代替下面的设备验证。

三个平台都可以本地运行这套回归：需要 API 30 以上的 `google_apis` 镜像（`adb root` 可用，
Play Store 镜像不行），ABI 与本机一致——Apple Silicon 用 arm64-v8a，x86_64 主机用 x86_64。
脚本会写入书签、下载和站点数据，请使用专用模拟器。
被测包须为 `com.mybrowser` 的签名 Release；调试版使用不同包名，不能直接用于这套回归。
runner 不接受 API 29。`full` 包含启动更新提示检查。

准备模拟器（用 Android Studio 的设备管理器创建也可以）：

```bash
python3 validation/manage-avd.py image                   # 查看本机该用哪个系统镜像
python3 validation/manage-avd.py create PureBrowser_API37
python3 validation/manage-avd.py start PureBrowser_API37
```

x86_64 主机（Windows、Linux、Intel Mac）装不上只含 arm64-v8a 的官方 APK，回归前用测试签名
构建 x86_64 包；`release/prepare.py` 的发布校验只接受 arm64，正式包不受影响：

```bash
./gradlew -Pmybrowser.abi=x86_64 :app:assembleRelease
```

启动脚本使用 `local.properties` 指定的 SDK，向模拟器传递相同路径，避免多套 SDK 的系统镜像混用。
Windows 上把 `python3` 换成 `python` 即可；单独运行某个回归脚本时设置 `PYTHONUTF8=1`，
经 `run-regressions.py` 运行时它会代为设置。脚本在 Git Bash 中执行。

下面以 `emulator-5554` 和 0.14.1 为例，替换为实际序列号与 APK 路径。

1. 安装并启动应用，部署 UI 辅助程序：

   ```bash
   ANDROID_SERIAL=emulator-5554 bash install_and_test.sh PureBrowser-v0.14.1-release.apk
   python3 validation/setup-ui-probe.py emulator-5554
   ```

   覆盖安装要求签名相同。使用自己的测试密钥时，应使用独立测试环境。

2. 启动 QA 服务，并保持该终端运行：

   ```bash
   python3 validation/qa-server.py --apk PureBrowser-v0.14.1-release.apk
   ```

3. 在另一个终端执行回归：

   ```bash
   python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.14.1-release.apk \
     --label release-0141-smoke --profile smoke
   ```

同一台模拟器上一次只运行一个 UI 脚本，避免输入焦点和构建互相干扰；分片时每台 AVD 各跑一片。
QA 使用本机 8875/8876 端口，
部分夹具使用 8877–8879，通过 ADB reverse 访问。使用本机代理时，在回归命令前加
`NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost`。

需要缩短完整矩阵时，可用独立 AVD 和 `--shard N/M` 分片；每片必须安装同一个 APK 并各自启动 QA
reverse。`features-filters` 会修改 QA 服务的全局过滤模式，不能与其它片共用同一个 QA 服务并行；
给分片使用独立 QA 端口/实例，或把该阶段固定在一个片里。

### 选择阶段

| 参数 | 覆盖范围 |
|---|---|
| `--profile fast` | 地址栏、标签驻留、设置，约几分钟的日常门禁 |
| `--profile smoke`（默认） | 地址栏、标签驻留、基础浏览、网页对话框 |
| `--profile tabs` | 驻留、普通/无痕媒体生命周期、菜单、两种弹窗视频 |
| `--profile full` | 当前设备适用的完整矩阵 |
| `--stages 名称…` | 只运行指定阶段，覆盖 profile 选择 |
| `--keep-going` | 跑完所有选中阶段并汇总失败，不在第一个失败处停止 |
| `--cycles N` | 覆盖菜单/设置返回循环次数；默认菜单 4、设置 2 |
| `--shard N/M` | 只跑第 N 片（共 M 片），配合独立模拟器并行 |

完整矩阵默认已把菜单循环降到 4 次（覆盖 30/80/160/300 ms 四种返回延迟各一次）、设置返回降到
2 次（覆盖两个边缘手势方向各一次）；需要压力测试时显式传 `--cycles 8` 或 `--cycles 4`。
`settings-back` 现在按分类轮换系统返回和工具栏返回，两类路径和所有分类仍会覆盖，但不再每个分类都走两遍。

例如，只检查启动更新提示和标签行为：

```bash
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.14.1-release.apk \
  --label update-tabs --stages update-launch resident
```

`update-launch` 使用 root AVD 写入缓存测试清单，验证配置重建后提示仍存在；
不会下载或安装更新，结束时还原缓存、深色模式和进程。
`site-storage` 同样使用 root AVD，验证网站设置损坏提示、取消重置、确认修复和重启后的持久化，结束时还原原设置文件。
`motion-gesture` 与 `motion-interruption` 用真实 MotionEvent 检查面板的动效：前者按住标题栏读回表面位置，
确认它跟手、短拖回弹、拉过自身高度约三分之一后关闭；后者在入场 80ms 处反向退出、在退出途中重新打开，
断言最后没有留下卡住或关不掉的面板。两者都不看"最终状态是什么"，只看"动效进行中表面在哪、被打断后剩下什么"，
所以其它阶段替代不了它们。
面板只有停稳之后才接受手指——动效进行中的表面会拒绝手势，这是刻意的，避免窗口动画与手指同时移动同一个值。
因此这类阶段在手势之前要等表面不再移动，并且要先自己把应用启动到前台。
面板下拉的"甩出速度"判定没有自动化：速度来自最后约 100ms 内采样点的拟合，而 adb 注入单个事件的耗时与这个窗口同量级，
无法提供与手指同密度的采样，该路径手工核对。
页面加载与错误恢复可单独运行 `--stages navigation-recovery`：本地服务延迟响应头，检查地址输入、网页链接、POST 表单的进度提示，以及连续失败、重试成功、停止加载和停止后再次提交表单。下载删除的单元测试覆盖传输取消、临时文件清理和暂停/删除并发。
每次下载都会先弹出确认对话框（刻意设计，没有跳过设置），下载相关回归在点击链接后显式点击确认再等待传输。
`download` 与 `bookmarks` 还检查多选：长按进入、点击取消/重新选中、返回退出，
并断言每行只暴露一个带 `checkable` / `checked` 状态的复选框。这验证无障碍树，不等同于人工试听 TalkBack 播报。
长按测试遵循设备的长按超时并留出事件处理时间。SAF 文件选择优先按文件名搜索，
只匹配真实文件行，避免复用模拟器中的大量旧下载或同名搜索建议干扰回归。
`blob-download` 覆盖网页在内存里生成的文件：夹具自己读取正文、显示自己的进度，再把 Blob 交给 `<a download>`，
回归断言确认框内容、多分片写入的字节一致性和第二次传输；纯页面半部的协议（分片顺序、越界、取消、映射上限）
由 `validation/blob-download.test.cjs` 在 Node 上覆盖。
`video-standard` 使用真实触摸验证倍速滑块拖动，以及 0.5×、5× 和 1.5× 档位；以网页实际播放状态判断结果，轨道点击会校正 Compose 无障碍边界的额外留白。
画中画尺寸以系统 pinned task 的实际边界与网页 viewport 比对，避免横竖屏切换后无障碍树的过期坐标造成误报。
控制台命令通过无障碍 `setText` 写入并读回校验：`adb shell input text` 会在首个 shell 元字符处截断 JavaScript 命令，开发工具回归不能改回逐字符注入。
慢模拟器上，菜单辅助接受设备端延迟完成、回执丢失的点击，页面坐标在等待 WebView 表面出现后再读取，两者都避免把已生效的操作判为失败。

完整阶段名称与检查项以 [run-regressions.py](validation/run-regressions.py) 及其调用的脚本为准。

## 结果与续跑

suite JSON 和阶段日志生成在 `validation/results/`，记录 APK SHA-256、设备、WebView、测试源码哈希及阶段结果。
已安装 APK 必须与传入文件一致。只有 APK、AVD、测试源码和阶段选择全部相同时，才能加 `--resume` 续跑。
失败尝试保留在 `priorAttempts`；换包或改测试后使用新的 suite。`--keep-going` 会保留每个失败阶段的结果，
修完后用 `--resume` 只重跑失败和未跑阶段，不必重跑整片。

CI 日志和报告从对应 Actions 运行下载；本地结果不提交 Git。
发布时只把设备、实际通过阶段和未覆盖范围写入[验证记录](release/notes.md)，不合并不同 APK 的通过结果。

## 排查与补充验证

- 运行旧版本时，启动更新提示可能挡住 UI。测试其他功能前可关闭自动检查，更新流程单独验证。
- 安装或 JNI 加载异常可运行 `./diagnose.sh`；崩溃信息使用 `adb logcat`，同时记录包哈希、操作和 WebView 版本。
- UI 定位优先使用资源名，并等待控件实际出现或获得焦点。全屏控件会自动隐藏，定位与触摸应尽量在同一辅助会话完成。
- 修改回归框架后，做一次已知缺陷的负向对照，确认对应检查仍会失败。
- 模拟器通过不能代替真机结果。扫码预览方向、实体投屏画面、真实账号登录和更新安装，需分别检查实际结果。

需要更高压力时可增加菜单/设置返回循环次数：

```bash
python3 validation/menu-navigation-regression.py --serial emulator-5554 --cycles 8
python3 validation/settings-back-regression.py --serial emulator-5554 --cycles 4
```

性能问题可使用现有启动和规则计算基准：

```bash
python3 validation/benchmark-startup.py --serial emulator-5554 --output validation/results/startup.json
cargo run --locked --release --manifest-path rust/Cargo.toml -p adblock --example benchmark -- 10
python3 validation/cosmetic-benchmark.py
```

对照测量需使用相同设备、样本和负载条件；规则计算耗时不能代表网页加载速度或功耗。
