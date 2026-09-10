# 0.5.2 验证报告

日期：2026-09-10。验证使用直接交付的签名 Release；同一模拟器的 UI 操作串行执行。
证据保存在 `validation/results/release-0.5.2/`，旧包记录只作为根因对照。

## 安装包与自动检查

- `PureBrowser-v0.5.2-release.apk`，versionCode 9，包名 `com.mybrowser`。
- Android 10+（minSdk 29）、targetSdk 37、arm64-v8a，3,634,176 bytes。
- SHA-256：`214b0908c9e92a8d52486c5104c259991be7fe846fac59ca87ce126e924158cf`。
- APK Signature Scheme v2、16 KB zipalign 检查通过；签名证书与 0.5.1 相同，覆盖安装成功。
- Rust fmt、51 项 Rust 测试、clippy 通过；40 项 Node 协议测试、520 项三语言资源检查通过。
- Android/Robolectric：289 项，0 failures、0 errors、0 skipped，含真实 host JNI 和新增的 10 项回归。
- lint：0 errors / 3 warnings（低版本 localeConfig、AGP 更新、ChromeOS x86_64）。R8 Release 构建通过。
- APK 只包含两个产品 JNI 库和 AndroidX graphics path 库；运行时依赖没有增加。

原始结果见 `build-and-test.log`、`android-build.log`、`apk-checks.json`、
`lint-results-debug.xml` 和 `android-unit-tests/`。首次编译发现一个 Compose `remember` 引用遗漏，
修正后完整 Android 检查和 Release 构建通过；未把首次编译失败隐藏或计为通过。

## 根因与修复

0.5.1 把桌面模式和权限一起按完整 origin 读取。在 `m.jrs16.com` 启用桌面模式后，
网站脚本根据桌面 UA 跳转到 `www.jrs16.com`；后者没有保存桌面偏好，导航回调又恢复移动 UA。
WebView 在加载期间改变 UA 会重新加载，旧移动页回调又恢复桌面 UA，形成反馈循环。

旧签名包 SHA-256 为 `e13dbe9d9602514318f51c65c31b16d7f5c3ea966f40d7bc746462e211a73bde`。
在 Android 14 上从真实菜单取了 14 个样本，13.39 秒内桌面开关自行改变 9 次；
地址栏持续显示移动页和停止加载按钮。原始样本见 `api34-old-jrs-loop.json` 和对应截图。

修复只让桌面显示偏好在同站裸域及 `m.`、`mobile.`、`www.` 入口之间共用。
协议、端口、其他子域保持区分；权限、JavaScript、图片、Cookie、过滤及缩放仍严格按 origin 读取。
已有配置直接兼容；关闭或重置桌面模式会在一次持久写入中更新已有入口，不新增隐式来源记录。
网站设置读取实际生效值，菜单与设置一致；无痕更改不影响普通模式。
相同 UA 不再重复写入，视口脚本仅在新文档完成后执行。

## 设备结果

| 模拟器 | 环境 | 桌面专项 | 相关阶段 |
|---|---|---|---|
| MyBrowser_Pixel7 | Android 14 / WebView 113.0.5672.136，arm64 | 本机 12/12，实际 JRS 4/4 | browser、site、permissions、layout：4/4 |
| PureBrowser_API29 | Android 10 / WebView 91.0.4472.114，arm64 | 本机 12/12，实际 JRS 4/4 | browser、site、permissions、layout：4/4 |

本机专项使用 `m.pure.localhost` 与 `www.pure.localhost`，通过 adb reverse 连接本机 8878/8879：

- JavaScript `location.replace` 和 HTTP 302 的移动站/桌面站互跳。
- 同时核对服务器收到的 UA、页面 `navigator.userAgent`、实际生效的 1024 视口和两个设置入口。
- 稳定窗口内文档 ID 不变、没有额外主文档请求；桌面开启和关闭各连续采样 8 次菜单开关。
- 从桌面站关闭后回到移动站，两次主动刷新、进程重启、返回原站及其他子域/端口隔离。

实际网站通过 Release 内置控制台读取诊断。两套模拟器的两次采样均为
`https://www.jrs16.com/`、`ready=complete`、桌面 UA、`width=1024`，
Android 14 的 `performance.timeOrigin` 保持 `1789022522942.6`，
Android 10 保持 `1789024319669.7`，没有重新创建文档。
关闭后回到 `m.jrs16.com` 和移动 UA，开关连续 8 次保持关闭。
另检查了实际桌面页截图，比赛列表和完整桌面布局可见；这不等同于验证站内直播播放。

相关阶段检查普通/SPA/弹窗导航、恢复、站点 JavaScript 和过滤、桌面重置、系统权限与网站同意分离、
迟到权限回执、150% 字体和横竖屏布局。它们的 APK 哈希、阶段耗时和输出见 `api*-desktop-052-suite.json`；
两套模拟器均一次通过四个阶段，`priorAttempts` 为空。汇总索引见 `validation-summary.json`。
本轮没有重跑全部功能矩阵；完整 runner 现有 API 29 的 30 个阶段和 API 34 的 32 个阶段。

## 测试脚本调整与诊断

两套模拟器均在本机 12 项通过后，被实际网站的延迟公告弹窗挡住菜单，首次在线步骤失败。
脚本等待页面完成、关闭公告，并在公告遮挡后重新打开菜单；随后各自只重跑在线部分，均 4 项通过。
记录分别是 `api29-desktop-mode.json`、`api34-desktop-mode.json`（保留本机通过及首次在线失败）
与对应的 `api29-desktop-mode-online.json`、`api34-desktop-mode-online.json`（在线重跑通过）。
旧的“页面”截图实际上是开发工具返回后的父菜单，已改名为 `returned-menu`；
重新关闭菜单后取得真实网页截图 `api34-desktop-mode-jrs-desktop-page.png`。

Android 10 首次本机步骤把用户“保存并刷新”的异步完成误判为自动重载。
请求记录只有首次访问和用户保存这两个移动 UA 请求，没有桌面/移动切换。
脚本现只接受操作之后的主文档请求，并核对对应文档的请求 ID，然后开始观察无额外请求窗口。
原失败保存在 `api29-desktop-mode-initial-wait.*`；其后一次模拟器连接中断记录另行保留，
没有把测试环境或辅助脚本问题作为应用问题修改 APK。

两套模拟器的最终 crash buffer 均为空，ActivityManager 均报告本次启动后无 ANR。
原始诊断保存在 `api29-crash.log`、`api34-crash.log`、`api29-last-anr.txt` 和 `api34-last-anr.txt`。

## 复现与范围

```bash
./build-and-test.sh
ANDROID_SERIAL=emulator-5554 ./install_and_test.sh PureBrowser-v0.5.2-release.apk
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/desktop-mode-regression.py --serial emulator-5554 --online
# 公网步骤单独重跑：加 --online-only；不会重复本机步骤。
python3 validation/qa-server.py
# 在另一终端，同一模拟器串行执行：
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.5.2-release.apk \
  --label desktop-052 --stages browser site permissions layout
```

模拟器的 SDK 路径应与其系统镜像一致；本机 Android 10 镜像位于
`/opt/homebrew/share/android-commandlinetools`，启动时需要同时指定对应的 `ANDROID_HOME` 和 `ANDROID_SDK_ROOT`。
两台 AVD 分别启动，不与 Gradle 构建并行。测试只操作专用模拟器，生产 Release 不开启 WebView 远程调试。

其他 Android/WebView 版本、厂商真机、实体投屏设备、第三方直播/DRM、完整下载/打印/书签迁移矩阵、
低端真机的启动/内存/耗电均未在本轮验证。旧版本完整矩阵和主机算法基准不计入本轮设备结果。
