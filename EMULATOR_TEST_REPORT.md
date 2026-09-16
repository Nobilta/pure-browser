# 回归测试记录

本文按版本和实际 APK 记录已经运行的检查。**同一版本的候选包可能包含不同代码，测试结果不能跨哈希合并。**
运行方法见[测试指南](TESTING_GUIDE.md)，安装包摘要见[发布指南](RELEASING.md)。

原始日志、suite JSON、截图和失败尝试保存在维护者本地的 `validation/results/`，不提交 Git。
下文的本地路径用于定位记录，不是公开可下载的附件。

## 0.9.1 · 2026-09-15

本版已发布。正式安装包为 `PureBrowser-v0.9.1-release.apk`，versionCode 19，
4,716,822 bytes，SHA-256：

```text
0213b1f2716d18026e30e6f121b555ff9ebd65cdd0e92dabe4a15411c257b9f9
```

设备为 `PureBrowser_API37` / `emulator-5554`，Android 17（API 37）、arm64-v8a，
WebView 145.0.7632.218。

### 正式安装包的检查结果

`./build-and-test.sh --release` 退出码为 0：

| 检查 | 结果 |
|---|---|
| Android / Robolectric | 336 项通过 |
| Rust | 57 项通过 |
| Node | 55 项通过 |
| 三语言资源 | 639 项通过 |
| Android lint | 0 errors、19 warnings、1 hint |
| Rust fmt / clippy、R8、签名构建 | 通过 |

以下三个阶段绑定上述正式 APK，来自 `validation/results/api37-release-091-review-fixes-suite.json`，
全部一次通过：

| 阶段 | 内容 | 耗时 |
|---|---|---:|
| `update-launch` | Activity 重建后保留更新提示 | 4.7 秒 |
| `resident` | 标签驻留、来源返回、同标签后退 | 37.6 秒 |
| `menu-navigation` | 菜单、返回和窗口切换 | 151.4 秒 |

`update-launch` 使用可 root 的 AVD 和缓存测试清单，不联网、不下载、不安装。
深色模式切换后确认进程 PID 未变，日志出现 `performDestroy` / `performCreate`，
且提示仍存在；修复前同一操作会丢失提示。结束时还原缓存、深色模式和应用进程。

新建 WebView 配置失败时的清理路径已修复，并通过上述构建与回归；**没有执行 OOM 故障注入**，
不能据此声称验证了所有内存耗尽情形。

### CI 与发布检查

发布提交 `1bc501a` 的 CI 通过，包含快速检查、lint、clippy、未签名 Release 构建和产物检查。
首次运行中修复了以下问题：

| 问题 | 处理 |
|---|---|
| Android SDK 安装请求已下线的 `tools` 包 | setup 步骤只安装 `platform-tools`，其余包显式安装 |
| 冷缓存缺少依赖校验值 | 本地 `--refresh-dependencies` 复现后，补齐 6 条 module/POM 校验值 |
| 缺少 Linux aapt2 的校验值 | 从 CI 获取构件信息，再从 Google Maven 下载同一构件核对 SHA-256 |
| Rust 脚本只识别 macOS NDK 工具链 | 按 darwin / linux 宿主选择工具链 |

最终 workflow 没有保留临时校验旁路。发布脚本新增 CI、版本递增及签名一致性检查；
这些检查不单独证明 APK 与源码提交的完整对应关系，仍需核对构建过程和安装包哈希。

发布后确认：

- `releases/latest` 指向 v0.9.1，非草稿、非预发布；APK、`update.json`、`SHA256SUMS` 与本地摘要一致。
- 应用使用的 `latest/download/update.json` 返回 versionCode 19、版本 0.9.1 和正确 APK 哈希。
- 标签 `v0.9.1` 指向 `1bc501a`。
- 在模拟器安装正式 0.9.0，从“设置 → 关于 → 检查更新”发现线上 0.9.1，显示版本、大小和发布说明。

最后一项验证到更新提示为止，**未记录正式 0.9.0 → 0.9.1 的完整安装过程**。
下面的较早测试覆盖了下载、授权和安装，但使用的是临时低版本包，更新目标为 0.9.0。

### 本版覆盖限制

正式 APK 运行了上表三个定向阶段，没有重跑完整 `tabs` 或 `full` 矩阵。
下面的无痕、媒体、设置等结果属于不同候选包，不能算作正式包的完整回归。
早期还运行过失败的地址栏和视频阶段，保留失败记录，不计入通过数量。

本版未验证实体设备、实体 DLNA 接收器画面、真实账号登录、全部在线视频站点或低端真机功耗。
最低安装版本仍为 Android 10，但本版没有 API 29 的设备结果。

## 0.9.1 开发期间的候选包记录

以下记录保留问题定位和设计决策所依据的测试。suite 文件名中的 `final`、`motion2` 等是当时的标签，
不代表它们都是最终发布产物。表内哈希仅显示前 16 位，完整值保存在各 suite JSON 中。

| APK 哈希前缀 | 主要记录 | 已通过的阶段 |
|---|---|---|
| `663c67f1dbee27df` | `api37-release-091-final-suite.json` | private-lifecycle 31.3s、profiles 95.5s、resident 65.3s、menu-navigation 293.9s、settings-back 246.5s |
| `b74f2dda0126fe99` | `api37-release-091-motion2-suite.json` | profiles 107.7s、resident 61.9s、menu-navigation 297.6s、settings-back 336.1s |
| `b74f2dda0126fe99` | `api37-release-091-perf-suite.json` | resident 63.6s、menu-navigation 207.9s、settings-back 220.8s |
| `c8e745b02efb314a` | `api37-release-091-nosec-suite-1789468881441810000.json` | private-lifecycle 65.1s、profiles 130.0s、settings-back 351.4s |
| `c8e745b02efb314a` | `api37-release-091-nosec-suite.json` | organization 121.6s |

路径均位于 `validation/results/`。其中 motion2 的菜单阶段重试一次后通过，perf 和上述 nosec 批次无重试。
31.3 秒的 `private-lifecycle` 属于 `663c67f1…`，不能归到 `b74f2dda…`。

### 测试脚本的三处修复

早期失败曾被归因于模拟器环境，后续排查确认以下问题来自测试代码：

1. `resident-regression.py` 点击分组对话框后，仅等待固定 0.5 秒就输入文本。
   慢设备上输入框尚未获得焦点，文本会丢失。修复后等待 `focused=true` 的可编辑控件再输入。
2. 工具栏返回选择依赖已被删除的翻译文本资源，导致英文 `Back` 无法匹配。
   改用 `resource_labels(name)` 按实际资源名取各语言值。
3. `_probe_action()` 没有捕获 `TimeoutExpired`，辅助探针超时会中断整个阶段。
   修复后标记探针不可用，并尝试已有的 UI 树路径。

无痕和驻留文案清理时，也同步修正了验证脚本的资源引用。
菜单退出确认的部分尝试仍因输入序列超出时间窗口中止，测得 3499 / 10945 / 3569ms；
较快运行中为 1037 / 1236ms。这些失败保留在 suite 的 `priorAttempts` 中。

### 标签驻留与同标签后退

`resident` 检查表单、JavaScript 状态、滚动、文档 token 和离开页媒体暂停，
并覆盖标签分组，以及 `window.open`、`target=_blank`、`window.close`、底栏返回和标签“×”的来源返回。

同标签跨文档后退在 WebView 145.0.7632.218 上仍重建文档：
能力探测与开关读回均为 true，但页面 token 变化，未提交表单丢失，JavaScript 状态归零。
普通探针页、追加特性标志、模拟器内存从 2 GB 增至 6 GB、关闭开关作对照，都未改变结果。
`notRestoredReasons` 仅返回 `masked`，无法确定实际阻止原因。

用例分别检查“保留文档”和“重建文档”两种结果，并记录实际分支；
通过不代表后退缓存已命中。详细说明见[后退缓存设计](design/back-navigation-without-reload.md)。
临时探针、命令行标志和调试包已在检查后清理。

### 菜单动效

使用设备端连续 `screencap` 检查了菜单进入、菜单到历史、菜单到设置、设置返回菜单四条路径。
采到的画面中可见遮罩、表面移动和旧表面的淡出副本，稳定帧内容正确。

采样间隔约 150–300ms，无法覆盖全部 120ms / 240ms 动画帧。
因此没有验证精确时长、曲线，也不能据这些截图证明整个过渡没有闪帧。

### 回归脚本耗时对照

在 `b74f2dda…` 候选包上测得，UI helper 的进程启动和 UiAutomation 连接约占 adb 调用时间的 91%，
单次约 280–540ms；dumpsys 约占 4%。

| 调整 | 同机对照结果 |
|---|---|
| 在同一 UiAutomation 连接中等待控件，再返回快照 | 同轮数 menu-navigation 约 232s → 207s |
| 菜单压力循环从 24 轮改为 8 轮 | 同代码 menu-navigation 约 303s → 207s |

8 轮仍覆盖四种返回延迟各两次；设置返回从 6 轮改为 4 轮，保留左右手势各两次。
断言保持相同，但重复次数减少；需要更高压力时可显式增加 `--cycles`。
这些耗时受主机负载影响，不是应用性能指标。

启用和关闭设备端等待的两次菜单运行，16 条检查结果一致。
另做过负向对照：临时移除菜单操作对退出确认的复位，脚本在对应检查处失败，
手动复现确认应用退出（输入序列 963ms）。还原后构建的 APK 哈希与 `b74f2dda…` 相同。

### 无痕截图行为

移除截屏保护后，在 `c8e745b0…` 候选包上确认：

- 普通和无痕窗口均不含 `SECURE` 标志，无痕截图返回 66,303 bytes 的实际画面。
- “隐私与过滤”及设置搜索中均不再有截屏保护开关。
- 无痕、Profile、设置返回阶段通过，结果见候选包表。
- 三语言资源由 641 项变为 639 项，快速检查通过。
- 启动时清理旧偏好 `protect_private_screens`，代码中已移除相关开关及窗口设置调用。

### 启动更新流程

较早测试使用临时构建：versionCode 17、versionName 0.8.1，但包含当时的 0.9.1 更新器代码。
它并非正式 0.8.1，线上目标是正式 0.9.0。

| 操作 | 结果 |
|---|---|
| 新进程启动 | 约 20 秒后显示 0.9.0、4.50 MiB 和发布说明 |
| 确认更新 | 约 10 秒下载完成，校验通过 |
| 尚未授权来源 | 打开 Android “Install unknown apps” |
| 授权返回 | 自动继续并打开“Update this app?” |
| 系统确认 | 覆盖安装成功，versionCode 17 → 18 |
| 关闭提示后旋转屏幕 | 未重复提示；旋转本身不能证明 Activity 已重建 |
| 再次冷启动低版本测试包 | 重新检查并提示 |
| 关闭自动检查 | 冷启动不检查也不提示 |
| 当时的 versionCode 19 候选包启动 | 高于线上 18，不显示更新提示 |

下载的 0.9.0 文件为 4,714,826 bytes，哈希前缀 `dd65ed4d…`，签名和清单均从线上文件核对。
测试后删除临时包并恢复当时的候选包。这项记录没有绑定当前正式 0.9.1 APK 的哈希。

### 未稳定通过的视频阶段

以下结果属于 `663c67f1…` 候选包：

| 阶段 | 结果 |
|---|---|
| `media-lifecycle` | 通过，132.0s |
| `video-popup` | 一次通过，80.5s；后续重跑在暂停操作处失败 |
| `video-popup-cross` | 未稳定通过，一次在暂停操作处失败 |

失败快照显示控件已隐藏，而视频仍播放，说明该次触摸没有完成预期暂停。
当时主机 swap 曾达到 11.6 / 12 GiB，负载较高，部分输入耗时明显增加；
这些现象与不稳定同时出现，但不足以排除应用问题。需要在空闲主机上复测，不能用 0.9.0 的通过结果替代。
当时的 `tabs` 全套没有一次完整通过。

## 0.9.0 · 2026-09-14

验证对象为最终签名 Release，覆盖新窗口来源页保留、标签返回、菜单过渡、媒体生命周期和应用更新。
模拟器阶段绑定实际安装 APK 的 SHA-256。

### 安装包与自动检查

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

本地记录位于 `validation/results/release-0.9.0/`：

- `build.log`：完整构建日志。
- `automated-checks.json`：自动检查汇总。
- `focused-unit-tests.json`：专项单元测试。
- `TEST-com.mybrowser.download.DownloadFilesTest.xml`：文件分享测试。
- `package-validation.json`：安装包校验。

### API 37 模拟器回归

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

本地记录：

- `validation/results/api37-release-090-final-suite.json`：七阶段汇总，含 APK 哈希、设备、测试源码哈希和日志文件名。
- `validation/results/api37-release-090-final-resident/result.json`：来源页检查。
- `validation/results/api37-release-090-final-capture/result.json`：相机文件回传。

### 覆盖升级与 GitHub 更新

已用原签名从原交付 **0.8.1（17）覆盖安装到 0.9.0（18）**，没有卸载或清空应用数据。
旧版创建的书签在升级后仍可见，已安装 APK 的 SHA-256 与交付文件一致。
记录：`validation/results/release-0.9.0/upgrade/result.json`。

初次线上安装验收发现相机与更新共用了 FileProvider 组件名，系统安装器因 URI 读取权限冲突退出。
修正为独立 `UpdateFileProvider` 后重新构建，并对最终 APK 完整重跑上述七阶段。
同签名临时旧包配合预置更新缓存，已通过真实系统来源授权、17 → 18 安装器覆盖升级、包哈希核对、书签保留和再次检查为最新版。
安装器检查记录为 `validation/results/release-0.9.0/local-update.json`，
初次失败记录为 `validation/results/release-0.9.0/initial-candidate/result.json`。

更新器使用公开仓库 `Nobilta/pure-browser` 的最新正式 Release 下载直链读取 `update.json`，避开匿名 REST API 共享限额。
首次发布后，线上验收使用带更新器、同签名且版本号设为 17 的临时测试包作为起点；原版 0.8.1 没有该更新入口。
流程包括实际获取 GitHub 清单、下载正式 APK、校验包、处理系统来源授权、由 Android 安装器覆盖升级、检查书签保留及再次检查为最新版。
临时包不对外发布。发布后验收结果单独上传为 [v0.9.0 的 github-update.json](https://github.com/Nobilta/pure-browser/releases/download/v0.9.0/github-update.json)，
该附件记录下载和安装后的 SHA-256；本文件中的发布前自动测试与模拟器数量不包含这项发布后验收。

正式分发附件为签名 APK、`update.json` 和 `SHA256SUMS`，位于 [v0.9.0 Release](https://github.com/Nobilta/pure-browser/releases/tag/v0.9.0)。

### 工具修正与覆盖边界

验证中修正了两个夹具时序问题：添加书签时等待键盘退出后“保存”按钮位置稳定；来源页夹具的新控件在滚动后才显示，
避免挡住视频播放按钮。修正后重新运行通过；失败尝试保留在本次记录中，未混入通过数量。
最终阶段曾因本地 QA 服务未运行而中断；恢复服务后，按相同 APK、设备及测试源码续跑全部通过，原始失败日志记录在汇总的 `priorAttempts`。

本轮只执行上述定向阶段，没有重跑历史完整 43 阶段矩阵，也没有连接 OPPO / ColorOS 等实体设备。
API 29 专项测试与工具兼容分支已移除，`minSdk=29` 及原签名保持不变；本版安装支持 Android 10，未将历史 Android 10 结果视为本次实测。
扫码硬件、实体 DLNA 接收器画面、真实账号登录和全部在线视频站点不在本轮已验证范围。

本地仅保留最新交付 APK 和必要验证记录；临时更新测试包、测试脚本及模拟器快照在发布后验收结束时清理。
