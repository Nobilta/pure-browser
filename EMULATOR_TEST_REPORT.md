# 模拟器与交付验证报告

本文件按版本记录实际的自动检查结果与模拟器回归结果；模拟器阶段均绑定实际安装 APK 的 SHA-256。
[RELEASING.md](RELEASING.md) 的交付包一节是当前版本的摘要，本文件是完整记录。
原始日志、汇总 JSON 与失败尝试保留在本地 `validation/results/`，不提交 Git。

- 0.9.0（已发布）：日期 2026-09-14，验证对象为最终签名 Release，覆盖新窗口来源页保留、标签返回、菜单过渡、媒体生命周期和应用更新。
- 0.9.1（本地构建）：日期 2026-09-15，见下文；含结构重构与三处用户可见变化，`menu-navigation` 因主机负载未完成。

## 0.9.0 验证报告

日期：2026-09-14。发布前验证对象为最终签名 Release；模拟器阶段均绑定实际安装 APK 的 SHA-256。
本轮验证新窗口来源页保留、标签返回、菜单过渡、媒体生命周期和应用更新。原始结果保留在本地 `validation/results/`，不提交 Git。

## 安装包与自动检查

- `PureBrowser-v0.9.0-release.apk`，versionCode 18，包名 `com.mybrowser`，Android 10+、arm64-v8a。
- **4,714,826 bytes**，约 4.50 MiB；SHA-256：`dd65ed4d44d91a6c48d830da7ffcc3348551b0fed317d867a1afb89ad896e5f4`。
- v2 签名、16 KiB zipalign、三份 native 库的 ELF LOAD 16 KiB 对齐及两个运行时脚本一致性检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，与原交付 0.8.1 相同。
- **333 项 Android/Robolectric、58 项 Rust、55 项 Node 测试通过**；Android 使用真实 host JNI，0 failures、0 errors、0 skipped。
- Rust fmt/clippy、646 项三语言资源校验、R8 和签名 Release 构建通过。lint：0 errors、24 warnings、1 hint。
- 最终完整命令为 `./build-and-test.sh --release`，退出码 0。最终构建后只修改文档与验证工具，没有改变 APK 对应的应用源码。

另有 **15 项临时更新器测试通过**，覆盖 HTTPS 重定向边界、清单类型与兼容性校验、缓存到期、服务端限流退避、
截断/损坏/未签名文件、取消下载与拒绝降级。临时 Kotlin 测试源码在完整构建前删除，XML 结果保留，不计入固定 333 项。
标签管理固定测试共 11 项，包含来源关系、最近使用回退及关闭后台标签保持当前选择。
既有文件打开用例另外核对相机与更新 Provider 的组件独立、正确内容读取和目录边界。

依据：[完整构建日志](validation/results/release-0.9.0/build.log)、
[自动检查汇总](validation/results/release-0.9.0/automated-checks.json)、
[专项单元测试](validation/results/release-0.9.0/focused-unit-tests.json)、
[文件分享测试](validation/results/release-0.9.0/TEST-com.mybrowser.download.DownloadFilesTest.xml)、
[安装包校验](validation/results/release-0.9.0/package-validation.json)。

## API 37 模拟器回归

设备为 `PureBrowser_API37` / `emulator-5554`：Android 17（API 37）、arm64-v8a、WebView 145.0.7632.218。
UI 回归串行运行；使用上面的签名安装包。以下七个定向阶段均通过，另完成原版本覆盖升级检查。

| 阶段 | 验证范围 | 耗时 | 结果 |
|---|---|---:|---|
| resident | 来源页 DOM、表单、SPA、滚动与五类子标签返回路径；标签分组 | 47.1 秒 | 通过 |
| media-lifecycle | 普通页面媒体生命周期、后台与返回 | 140.0 秒 | 通过 |
| private-lifecycle | 无痕页面媒体与会话生命周期 | 31.4 秒 | 通过 |
| capture | 相机授权、拍照/录像完整字节回传、取消与旧回执处理 | 45.1 秒 | 通过 |
| menu-navigation | 菜单、设置与子页面导航、返回及界面状态 | 354.0 秒 | 通过 |
| video-popup | 全屏播放器倍速和投屏弹层、临时倍速与后台 | 101.1 秒 | 通过 |
| video-popup-cross | 跨域播放器的对应弹层及生命周期 | 97.4 秒 | 通过 |

来源页专项共七项检查：切回后页面 token 与首次加载时间不变，未提交文本、SPA 内存和滚动位置不丢失，离开时媒体暂停；
标签分组正常；`window.open` 后系统返回、`target=_blank` 先返回自身历史再关闭、`window.close`、底栏返回、标签列表“×”
均正确回到来源页，且没有退出提示。来源页的滚动位置在往返前后均为约 1049.9 CSS px。

相机专项共六项检查通过：拒绝权限和取消操作保持原表单；JPEG 的 75,270 bytes、MP4 的 609,104 bytes 全部返回网页；
相机期间旋转或网页导航不会提交旧回执。

依据：[最终七阶段汇总](validation/results/api37-release-090-final-suite.json)、
[来源页详细检查](validation/results/api37-release-090-final-resident/result.json)、
[相机文件回传](validation/results/api37-release-090-final-capture/result.json)。汇总记录准确 APK 哈希、设备、测试源码哈希和原始日志文件名。

## 覆盖升级与 GitHub 更新

已用原签名从原交付 **0.8.1（17）覆盖安装到 0.9.0（18）**，没有卸载或清空应用数据。
旧版创建的书签在升级后仍可见，已安装 APK 的 SHA-256 与交付文件一致。
依据：[原版覆盖升级](validation/results/release-0.9.0/upgrade/result.json)。

初次线上安装验收发现相机与更新共用了 FileProvider 组件名，系统安装器因 URI 读取权限冲突退出。
修正为独立 `UpdateFileProvider` 后重新构建，并对最终 APK 完整重跑上述七阶段。
同签名临时旧包配合预置更新缓存，已通过真实系统来源授权、17 → 18 安装器覆盖升级、包哈希核对、书签保留和再次检查为最新版。
依据：[安装器验收](validation/results/release-0.9.0/local-update.json)、
[初次安装失败记录](validation/results/release-0.9.0/initial-candidate/result.json)。

更新器使用公开仓库 `Nobilta/pure-browser` 的最新正式 Release 下载直链读取 `update.json`，避开匿名 REST API 共享限额。
首次发布后，线上验收使用带更新器、同签名且版本号设为 17 的临时测试包作为起点；原版 0.8.1 没有该更新入口。
流程包括实际获取 GitHub 清单、下载正式 APK、校验包、处理系统来源授权、由 Android 安装器覆盖升级、检查书签保留及再次检查为最新版。
临时包不对外发布。发布后验收结果单独上传为 [v0.9.0 的 github-update.json](https://github.com/Nobilta/pure-browser/releases/download/v0.9.0/github-update.json)，
该附件记录下载和安装后的 SHA-256；本文件中的发布前自动测试与模拟器数量不包含这项发布后验收。

正式分发附件为签名 APK、`update.json` 和 `SHA256SUMS`，位于 [v0.9.0 Release](https://github.com/Nobilta/pure-browser/releases/tag/v0.9.0)。

## 工具修正与覆盖边界

验证中修正了两个夹具时序问题：添加书签时等待键盘退出后“保存”按钮位置稳定；来源页夹具的新控件在滚动后才显示，
避免挡住视频播放按钮。修正后重新运行通过；失败尝试保留在本次记录中，未混入通过数量。
最终阶段曾因本地 QA 服务未运行而中断；恢复服务后，按相同 APK、设备及测试源码续跑全部通过，原始失败日志记录在汇总的 `priorAttempts`。

本轮只执行上述定向阶段，没有重跑历史完整 43 阶段矩阵，也没有连接 OPPO / ColorOS 等实体设备。
API 29 专项测试与工具兼容分支已移除，`minSdk=29` 及原签名保持不变；本版安装支持 Android 10，未将历史 Android 10 结果视为本次实测。
扫码硬件、实体 DLNA 接收器画面、真实账号登录和全部在线视频站点不在本轮已验证范围。

本地仅保留最新交付 APK 和必要验证记录；临时更新测试包、测试脚本及模拟器快照在发布后验收结束时清理。
源码按 `master` 的一次普通提交维护并推送到远程 `main`，签名材料、生成输出和 APK 不入 Git。

## 0.9.1 的模拟器回归

日期：2026-09-15。对象为 `PureBrowser-v0.9.1-release.apk`（versionCode 19，当前交付包 SHA-256
`0213b1f2716d18026e30e6f121b555ff9ebd65cdd0e92dabe4a15411c257b9f9`，4,716,822 bytes），
设备为 `PureBrowser_API37` / `emulator-5554`（Android 17，API 37）。
本轮多次改动应用源码，因此下面的阶段结果按实际安装包的 SHA-256 归属，不跨包复用。
0.9.1 包含结构重构与六处用户可见变化：启动时自动检查更新并在确认后自动下载、校验和打开系统安装器、
弹层动效统一为一套节奏（进入/窗口内换页/换容器）、普通页面开启 WebView 的 back/forward cache、
后台页面不再受固定槽位限制并在内存压力下按最先停放顺序回收、标签管理页移除驻留状态小字、
无痕顶部提示精简为图标 + “无痕”徽标。

自动化检查全部通过：**336 项 Android/Robolectric、57 项 Rust、55 项 Node 测试与 639 项三语言资源校验**，
clippy、lint（0 errors、19 warnings、1 hint）、R8 与签名构建均无错误。

### 回归中定位并修复的三个测试代码缺陷

前三轮把失败归因为"本机 AVD 环境问题"，经逐项排查后确认**不成立**——根因是测试代码自身：

1. **固定 sleep 竞态**（`resident-regression.py`）。`ux.tap_node()` 内部只等 0.5 秒，而该脚本在"点开
   分组名对话框后立刻 `adb shell input text`"。`input text` 把文本送给**当前持有焦点的控件**，慢设备上
   0.5 秒不够输入框获得焦点，文本被丢弃，`ux.expect('QAgroup')` 超时失败。
   修复：新增 `focused_editor(timeout=10)`，轮询到 `focused=true` 的可编辑控件再注入文本。
2. **控件选择依赖翻译文本**（由本轮的资源清理引入）。`emulator-ux.py` 的 `_label_variants` 从**应用全部
   字符串资源**构建：`labels('返回')` 之所以能匹配返回按钮真实的 `content-desc="Back"`，是因为被清理删除的
   `ui_back` 恰好带着 zh=`返回` 值。删除后 `labels('返回')` 只剩 `['返回']`，`toolbar_back()` 必然失败。
   修复：新增 `resource_labels(name)` 按**资源名**取全部语言值，6 处改为解析 `cd_back`。
   教训：lint 判定的"未使用资源"只针对应用自身，验证框架同样消费资源表。
3. **探针超时未降级**（`emulator-ux.py`）。`_probe_action()` 的 `subprocess.run(..., timeout=15)` 未捕获
   `TimeoutExpired`，而 `nodes()` 是捕获的。设备一慢就抛异常终止整个阶段。
   修复：捕获后按 `nodes()` 的既有模式标记探针不可用并返回 False，由调用方走基于 UI 树的路径。

本轮删除标签驻留小字与无痕提示文案前，追加了一次引用检查：三个被删资源的语言值在
`validation/*.py` 中确有引用，两个脚本一并改为按资源名解析（`profile-cleanup` 的模式判定改为
"徽标出现且降级警告不出现"，比原先依赖文案更严格）。

### 本轮实测结果

最终交付包（`b74f2dda…`）上四个定向阶段在同一个 suite 中全部通过（`api37-release-091-motion2-suite.json`），
`private-lifecycle` 在同一交付包的前一批运行中通过：

| 阶段 | 结果 | 耗时 | 说明 |
|---|---|---:|---|
| `profiles` | **PASS** | 107.7s | `MULTI_PROFILE` 隔离、存储隔离与清理 |
| `resident` | **PASS**（8 项检查） | 61.9s | 驻留改动 + 同标签后退用例（本轮升级为两种契约分别断言） |
| `menu-navigation` | **PASS**（重试一次） | 297.6s | 菜单子页、24 轮快速往返、退出确认复位、底部面板↔全屏切换路径 |
| `settings-back` | **PASS** | 336.1s | 六分类、选项、管理弹层、横屏两栏与左右边缘手势 |
| `private-lifecycle` | **PASS** | 31.3s | 无痕会话与媒体边界（同一交付包的上一批） |

`resident` 的 8 项检查包含：两标签往返后 DOM/表单/SPA/滚动与离开页媒体暂停、标签分组、
`window.open`／`target=_blank`／`window.close`／工具栏返回／标签列表 × 五条返回路径，
以及同标签后退用例。`menu-navigation` 的首次尝试仍中止在退出确认时间守卫上（设备端输入序列超窗），
重试通过；早期三次同点失败的实测值（3499 / 10945 / 3569 ms，健康时段 1037 / 1236 ms）保留在
各 suite 的 `priorAttempts` 与 `validation/results/` 中。

### 弹层动效的目视核对

在设备端把"点击 + 连续 `screencap`"放在同一条 shell 命令里抓过渡帧，四条路径都检查了起始帧与稳定帧：
ENTER（工具栏 → 菜单）、WITHIN（菜单 → 历史）、CONTAINER 正向（菜单 → 设置）、CONTAINER 反向（设置 → 返回 → 菜单）。
结果：过渡期间始终有遮罩或不透明表面盖住网页，没有出现网页闪出；换容器时被替换的表面以"同形状的无内容副本"淡出，
随后新表面就位。帧级时长（240ms / 120ms）未核对——本机没有可抽帧的工具，`screencap` 间隔约 150–300ms。

### 同标签后退（back/forward cache）的实测

开启 WebView 的 back/forward cache 后，本机文档**仍被重建**，行为与开启前一致：

| 检查 | 结果 |
|---|---|
| `WebViewFeature.isFeatureSupported(BACK_FORWARD_CACHE)` | `true`（WebView 145.0.7632.218） |
| 设置后 `getBackForwardCacheEnabled` | `true` |
| 普通页面（临时探针页：无媒体、无 fetch 循环、无 pushState）后退 | `t=back_forward`，标题里的文档 token 变化 → 文档重建 |
| 页面的 `notRestoredReasons` | `{"reasons":[{"reason":"masked"}]}`（WebView 未暴露真实原因） |
| 追加 `--enable-features=WebViewBackForwardCache`（日志确认已读取） | 文档仍重建 |
| 模拟器内存 2 GB → 6 GB | 文档仍重建 |
| 关掉开关做 A/B | 行为完全一致（同样重建，滚动表现也相同） |

`resident` 的同标签用例因此走"重建"分支并通过：未提交表单丢失、SPA 内存归零、页面可交互。
用例对"保留"与"重建"两种结果分别断言，未来某个 WebView 版本真正生效时会自动校验到新契约。
临时探针页、WebView 命令行标志与调试包在验证后已删除，设备改回交付包。

### 回归框架提速（同一交付包、同一批检查）

耗时构成先用一个记录每次 `adb` 调用的包装器量过：`FastUiDump` 每次查询都要在设备上新建进程并连接
UiAutomation，**占 adb 时间的 91%**（单次 280–540 ms），dumpsys 只占 4%。据此做了两项改动：

| 改动 | 做法 | 实测 |
|---|---|---|
| 设备端等待 | 新增 `await` 命令：在一个连接内轮询控件出现/消失，再返回整树；`expect`、`expect_menu`、`tap_resource`、`menu_item`、`category`、`toolbar_back`、`browser` 改用等待 | 背靠背 A/B（同轮数同机器）：`menu-navigation` **232s → 207s**，且实验组负载更高 |
| 压力循环降档 | `menu-navigation --cycles` 24→8（4 种 Back 延迟各覆盖两次）、`settings-back --cycles` 6→4（左右手势各两次），保留显式放大参数 | 同代码对照：`menu-navigation` 24 轮 **303s** → 8 轮 **207s** |

最终 suite（`api37-release-091-perf-suite.json`，无重试）：`resident` 63.6s、`menu-navigation` **207.9s**、
`settings-back` **220.8s**。对照上一批记录（同负载区间）的 297.6s 与 336.1s，两个最重的阶段分别**快约 30% 与 34%**。

**检查项没有减少**：`menu-navigation` 在启用与关闭设备端等待两种模式下产出的 16 条检查字符串逐条一致
（`perf-await` 与 `perf-noawait` 两次运行比对）；`settings-back` 的轮数检查文本随参数更新为
"4 repeated edge and toolbar return cycles …"，断言内容不变。

**负向对照**：临时移除“菜单打开/关闭解除退出确认”后，`menu-navigation` 如期在退出确认检查处失败；
手工复现同一序列确认缺陷版本上应用真的退出（序列 963 ms、随后前台为 False），排除负载噪声。
还原后重建的 APK 与交付包 SHA-256 完全一致（`b74f2dda…`），对照没有污染交付物。

### 移除无痕截屏保护后的验证

改动（不再设置 `FLAG_SECURE`、不再隐藏最近任务预览、设置开关与偏好字段及三语言字符串下线）之后：

| 检查 | 结果 |
|---|---|
| 无痕模式窗口标志 | `dumpsys window windows` 中本应用窗口**不含 `SECURE`**（普通模式同样不含；改动前无痕必含） |
| 无痕截屏 | `screencap` 返回 66,303 bytes 的真实画面（改动前同一路径截出全黑图） |
| 设置项 | “隐私与过滤”分类下已无截屏保护开关，搜索索引同步移除 |
| 回归 | `private-lifecycle` 65.1s、`profiles` 130.0s、`settings-back` 351.4s 全部通过（同一 suite，无重试） |
| 静态检查 | 资源校验 641 → 639（三语言各删 2 条），`./build-and-test.sh --quick` 退出码 0 |
| 无残留引用 | 全仓库已无 `protectPrivateScreens`、`private_screenshot_*`、`FLAG_SECURE`、`setRecentsScreenshotEnabled` |

升级清理：`App.onCreate` 在启动时移除旧版本留下的 `protect_private_screens` 存储项（与既有
`recently_closed_tabs` 清理同一处）。

### 外部复核后的修复与验证

第三方复核提出两条实现问题与若干 CI／发布缺口（复核报告当时落在 `validation/results/` 下，该目录不入库），本轮逐条处理：

| 复核结论 | 处理 |
|---|---|
| P2：启动更新提示在 Activity 重建后丢失（有复现证据：同进程、提示由存在变消失） | 检查与提示移到进程作用域——`App` 持有 `StateFlow` 与 `startupUpdateClaimed`，请求跑在应用级协程里，Activity 只订阅与显示；新增 `update-launch` 阶段复现同一场景：重建后 `promptAfter=true`、同 PID，并要求日志中确实出现 `performDestroy`／`performCreate` 才算通过（修复前同一路径为 `false`） |
| P2：OOM 重试未回收"已由池持有但配置失败"的实例 | `acquireFreshPage` 拆开创建与配置：失败时清理该实例的媒体追踪器与脚本运行时并交还 `pool.discard`，再回收最老驻留页。池内部 `create()` 本就是"先 `WebViewConfig.apply` 再登记"的顺序，无需额外改动。未做 OOM 故障注入，属代码路径修复 |
| CI 漏 Android lint，且"lint 需要正式签名"的说法不成立 | 实测签名配置是条件创建的（无 `keystore.properties` 时 release 无签名配置），`lintDebug` 任务图也不含交叉编译 → CI 已加入 `:app:lintDebug` |
| CI 绿灯不能证明 APK 可构建 | 移走 `keystore.properties` 实测 `assembleRelease` 仍成功并产出 `app-release-unsigned.apk` → CI 加入无签名 Release 构建与产物校验（按名断言两个 Rust JNI 库 + R8 输出） |
| 需先补 Linux 工具链 | `rust/build.sh` 改为按宿主系统挑选 NDK prebuilt 工具链（darwin/linux，保留原有回退顺序） |
| 发布脚本未绑定"已验证的 APK"与提交 | 新增 `release/gate.py`，在 `publish.sh` 的"远端 main == HEAD"检查之后调用：要求该提交 CI 成功、APK 版本等于源码版本、versionCode 高于上一正式版、签名证书沿用上一正式版（尚无正式版时跳过并打印说明） |
| 公开说明过期 | 回归报告与 PR 模板统一为 639 项资源、lint 记为 19 warnings、`release/notes.md` 的后退缓存改为"在 Provider 允许缓存的页面上尝试保留文档" |

本轮验证：`./build-and-test.sh --release` 退出码 0（336 项 Android/Robolectric、57 项 Rust、55 项 Node、639 项资源、
lint 0 errors / 19 warnings / 1 hint、clippy、R8 与签名），交付包更新为 **4,716,822 bytes / `0213b1f2…`**；
针对该包运行的阶段（`api37-release-091-review-fixes-suite.json`，无重试）：`update-launch` **4.7s**、
`resident` **37.6s**、`menu-navigation` **151.4s**，全部通过。

`update-launch` 需要可 root 的专用 AVD：它向应用写入一份**缓存的**测试清单（不联网、不下载、不安装），
只在提示处停下，结束时还原清单缓存、深色模式与应用进程。

### 本机负载（当时的阻塞原因，非应用缺陷）

- 主机 swap 曾用满 **11.6 GiB / 12 GiB**，load average 4.7–10.5；模拟器因内存压力退回软件 GL 渲染；
- `adb shell input keyevent` 单次实测 0.18 / 0.42 / 1.49 s；设备端纯等待序列计时始终准确（700 ms 实测 701–703 ms）；
- 负载来自机器上的其他应用，本会话结束后没有残留的构建或模拟器进程。

### 启动更新流程的端到端实测

同一模拟器上用一个临时的低版本构建（versionCode 17 / 0.8.1，其余代码与交付包一致）验证了启动更新全流程；
验证后该临时构建已删除，设备改回交付包（已核对安装包 SHA-256 与交付文件一致）。步骤与结果：

| 步骤 | 结果 |
|---|---|
| 冷启动（新进程） | 自动检查并在约 20 秒内弹出更新提示，显示已装 0.8.1、可更新 0.9.0 · 4.50 MiB 与发布说明 |
| 点“更新” | 显示下载进度，约 10 秒完成 4.5 MiB，随后校验通过 |
| 未授权“安装未知应用” | 从应用内打开系统“Install unknown apps”页（已定位到 Pure Browser），未自行安装 |
| 授权后返回 | **自动**继续校验并打开系统安装器“Update this app?”，无需再次点击 |
| 在安装器确认 | 覆盖安装成功，`versionCode` 17 → 18、`versionName` 0.8.1 → 0.9.0 |
| 关闭提示后旋转屏幕 | Activity 重建**不再**弹出（每个进程只检查一次） |
| 再次冷启动 | 重新检查并弹出（每次启动都会检查） |
| 关闭“启动时自动检查更新” | 冷启动直接进入主页，无网络检查与提示 |
| 终包（19 > 线上 18）冷启动 | 静默通过，不弹出提示 |
| 设置项 | 位于设置 → About，开关状态随偏好持久化 |

分发校验、签名比对和 0.9.0 清单（`versionCode` 18 / 4,714,826 bytes / `dd65ed4d…`）均由线上真实文件验证。

### 尝试过但未取得稳定结果的阶段

主机负载回升后继续补跑 `--profile tabs` 的其余阶段，结果如下（同一交付包、同一套测试源码）：

| 阶段 | 结果 | 说明 |
|---|---|---|
| `media-lifecycle` | **PASS**（132.0s） | 普通页面媒体生命周期、后台与返回 |
| `video-popup` | 通过后又在重跑中失败 | 80.5s 通过；负载升到 17 后重跑在“点暂停后视频未暂停”处失败 |
| `video-popup-cross` | 未取得稳定结果 | 一次在同类步骤失败；同阶段在 0.9.0 验证时通过 |

失败快照显示播放器控制层处于隐藏状态（`controls: false`）而视频仍在播放，属慢设备下控件点击未生效；
两个阶段涉及的播放器、投屏与媒体代码本轮均未改动。0.9.0 验证时这两个阶段曾通过（101.1s / 97.4s），
因此判定为主机负载导致的测试不稳定，需要在空闲主机或 CI 上重跑确认。
`--profile tabs` 全套仍未一次性跑完。

### 未覆盖范围

- `omnibar` 及完整矩阵的其余阶段本轮未运行（`media-lifecycle`、`video-popup` 已在终包上通过或部分通过，见上表）；
- `video-popup-cross` 需要空闲主机或 CI 复跑；
- 未在实体设备上验证；启动更新流程只在 API 37 模拟器上验证过。

本轮改动的自动化检查与五个定向阶段（含直接覆盖改动的 `resident`、`profiles`、`settings-back`）通过，
启动更新流程完成端到端实测。原始日志、汇总 JSON 与被重试的失败尝试保留在 `validation/results/`，不提交 Git。
