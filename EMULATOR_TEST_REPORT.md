# 0.5.1 验证报告

日期：2026-09-10。每个设备阶段均核对安装包 SHA-256；同一模拟器上的 UI 操作串行执行。
当前验证记录集中保留在 `validation/results/release-0.5.1/`，APK 仅在项目根目录保留一份。

## 安装包与自动检查

- `PureBrowser-v0.5.1-release.apk`，versionCode 8，包名 `com.mybrowser`。
- Android 10+（minSdk 29）、targetSdk 37、arm64-v8a，3,633,556 bytes。
- SHA-256：`e13dbe9d9602514318f51c65c31b16d7f5c3ea966f40d7bc746462e211a73bde`。
- Rust fmt、51 项测试、clippy 全部通过；40 项 Node 协议测试、520 项三语言资源检查通过。
- Android/Robolectric：279 项测试，0 failures、0 errors、0 skipped，包含真实 host JNI。
- lint：0 errors / 3 warnings（低版本 localeConfig、AGP 更新、ChromeOS x86_64）。
- R8 Release、APK Signature Scheme v2、zipalign 检查通过；只有两个产品 JNI 库及 AndroidX 库。
- 清理四组无调用 JNI 模块和重复构建逻辑后重新构建，APK 字节与菜单修复包完全一致。
  删除的 11 项 Rust 测试属于已移除实验；保留产品模块的测试未减少。

构建、签名及测试原始结果分别为 `menu-0.5.1-clean-build.log`、`menu-0.5.1-build.json`、
`menu-0.5.1-apk-checks.log`、`lint-results-debug.xml` 和 `android-unit-tests/`。

## 当前 APK 的设备范围

| 模拟器 | 环境 | 本轮结果 |
|---|---|---|
| MyBrowser_Pixel7 | Android 14 / WebView 113.0.5672.136，1080×2400，手势导航 | 12/12 个受影响阶段通过 |
| PureBrowser_API29 | Android 10 / WebView 91.0.4472.114，1080×1920，三键导航 | 12/12 个受影响阶段通过，0 次阶段重试 |

本轮选择菜单/窗口生命周期相关阶段，不是重新执行全部能力矩阵。
完整 runner 目前提供 API 29 的 29 阶段和 API 34 的 31 阶段。

| 阶段 | 主要验证 |
|---|---|
| menu-navigation | 菜单父级、实际窗口数、退出确认、重建、拖动、快速返回及最终网页触摸 |
| developer-tools | 窗口关闭/重开、草稿、按需源码、横屏键盘、MAIN/VIEW/SEND |
| browser | SPA 标题/地址、工具栏滚动、弹窗标签、进程恢复、移除带系统选择器的任务 |
| site | 网站设置保存/刷新、来源隔离和管理入口 |
| reader | 原生阅读、字号、保存及离线重启读取 |
| layout | 150% 字体、横竖屏、阅读和网站设置的安全边距及操作可达性 |
| features-filters | 订阅更新、条件请求、拦截/元素隐藏、失败回退及管理弹层 |
| features-dialogs | 网页提示/输入/确认及窗口关闭 |
| video-standard | 标准视频、投屏/倍速弹层、增强全屏、手势、锁定及后台恢复 |
| settings-back | 选项/分类/设置/菜单/浏览器逐级返回、管理页、重建与宽屏 |
| settings | 偏好、主题、下载设置及布局 |
| productivity | 标签身份/历史隔离、长按操作、书签编辑/分页、历史分组及大字体 |

阶段结果、退出码、耗时、包哈希与重试记录分别保存在 `api34-menu-051-suite.json` 和
`api29-menu-051-suite.json`。旧 0.5.0 的完整矩阵不计入上述覆盖。

## 菜单复现与回归

旧 0.5.0 用单一可空状态覆盖父级；旧包在强化后的“设置返回到真实菜单”断言处失败。
此前只匹配“菜单”文字的断言会误把工具栏按钮当作菜单。新版同时检查真正的菜单标识、
前台 Activity 和应用窗口数；关闭菜单后要求浏览器可实际操作。

菜单专项覆盖 17 组检查，包括 24 轮快速往返、点击设置后 0/15/50/100 ms 立即返回、
字体引起 Activity 重建、后台恢复、设置关于页进入开发工具、拖动关闭和外部网址打断选项。
API 34 / API 29 的退出确认输入序列分别为 1,321 / 1,584 ms，均位于 2,000 ms 确认窗口内，
菜单操作正确解除旧确认。两版均完成 17 组检查和 24 轮快速往返。

`BrowserSheetNavigation` 保存来路并校验每次显示的回调身份，父级仅保存 UI 状态，窗口随切换释放。
长菜单的手柄由主窗口安全边距约束，避免触摸系统通知栏。设计与测试命令见
[菜单导航说明](design/menu-navigation-20260910.md)。

用户报告的那次卡死没有原始设备日志，不能认定唯一原因或直接归类为 ANR。
本轮以窗口回收、输入完成回执和最终网页点击验证交互是否恢复。

## 实际失败与辅助程序情况

API 34 有两次阶段重跑：菜单专项末尾和浏览阶段遇到 WebView 可访问性树缺少已绘制的页头按钮。
截图确认按钮可见；菜单专项在外部导航后实际刷新，浏览阶段从新 renderer 冷启动，避免继承
上一阶段旋转后的树状态。两处都保留真实触摸及页面变化断言，没有跳过失败检查；原失败日志
记录在 `priorAttempts`，不能描述为无重试通过。

API 34 的 crash buffer 有一次测试辅助进程 `FastUiDump`（PID 19387）在窗口切换期间找不到
活动根节点的异常，随后读取恢复。完整堆栈属于 `/data/local/tmp` 的 shell 测试程序；未观察到
浏览器进程崩溃。ActivityManager 报告本次启动以来没有 ANR。
API 29 有 5 条 JIT 线程 SIGSEGV 记录，PID 为 12604、17023、18898、23313、3738；与保留的
39 条浏览器及 WebView 启动事件中的 PID 均不匹配。本轮未取得这些 PID 的完整 tombstone，
因此不逐一推断它们的具体程序或原因；未观察到浏览器进程崩溃，ActivityManager 报告无 ANR。
其 UI 阶段全部通过，辅助读取的恢复不计为 runner 阶段重试。

对应 crash、进程事件、last-anr 和 `crash-review.json` 随当前 suite 保留。
诊断脚本实测保持浏览器 PID 不变；它因这些现有异常关键词返回提示状态，没有清空日志或重启应用。

## 当前 Rust 基准

清理后运行当前源码，30,692 条元素隐藏规则、80 个 host，480 个冷查询和 200 个缓存命中查询。
Apple arm64 主机 / Rust 1.98.0：冷查询中位数 413,750 ns，热查询中位数 208 ns。
输入/输出哈希和环境记录于 `cosmetic-benchmark.json`。基准不再依赖旧分支，临时 CSS 自动清理。

这只衡量主机 CSS 查询，不包含 JNI、DOM、整页加载、启动、内存或手机功耗。
历史跨语言对照仅在 [能力复查](design/browser-capabilities-20260910.md) 中保留决策记录，
旧源码副本和旧构建产物已清理。

## 复现及限制

```bash
./build-and-test.sh
ANDROID_SERIAL=emulator-5554 ./install_and_test.sh
python3 validation/qa-server.py
# 另一个终端，同一模拟器串行运行：
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.5.1-release.apk \
  --label menu-051 --stages menu-navigation developer-tools browser site reader layout \
  features-filters features-dialogs video-standard settings-back settings productivity
# 仅相同 APK、AVD 和阶段选择可加 --resume
```

本轮未重新跑完整下载续传、打印/书签文件迁移、网站权限、其他视频变体、语言和首页编辑矩阵；
相关源码和夹具保留，主机自动检查覆盖相应模块。API 29 不执行边缘返回手势；跨域媒体和 GM
存储继续按旧 WebView 能力降级。

实体 DLNA、厂商 WebView、低端真机、其他 Android 版本、第三方 DRM、相机/麦克风实际采集、
完整 SAF Provider 矩阵及设备启动/内存/耗电仍未验证。现有功能的边界以 README 和
[剩余工作](design/follow-up-priorities.md) 为准。
