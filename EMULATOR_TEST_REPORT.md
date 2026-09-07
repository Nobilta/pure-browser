# 模拟器回归报告

更新时间：2026-09-07。本报告仅记录 0.2.0 最终签名 APK 的实际检查，未复测的旧版项目不计入通过项。

## 产物与环境

- APK：`PureBrowser-v0.2.0-release.apk`，versionCode 2，包名 `com.mybrowser`。
- 大小：2,131,247 bytes（约 2.03 MiB）；APK Signature Scheme v2 验证通过。
- SHA-256：`591cc04ace4138ed8daa182e22189453891dbb28bf2747ae8abc2b3badff2908`。
- 最低 API 29，compile/target API 37，Release 仅提供 `arm64-v8a`。
- 同一个最终 APK 在两套系统上安装成功；API 34 上从 0.1.0 覆盖升级及默认安装脚本也通过。

| 设备 | 系统 | WebView | 分辨率 | 用途 |
|---|---|---|---|---|
| `PureBrowser_API29` / `emulator-5554` | Android 10 / API 29 | 91.0.4472.114 | 1080 × 1920 | 低系统版本、旧 WebView 回退 |
| `MyBrowser_Pixel7` / `emulator-5556` | Android 14 / API 34 | 113.0.5672.136 | 1080 × 2400 | 增强跨域控制、应用语言、宽屏布局、性能对照 |

模拟器均为 arm64、2 核、1536 MiB、SwiftShader，无快照启动。最终测试每次只运行一台模拟器。
本地测试服务器监听 `127.0.0.1:8875/8876`，通过 ADB reverse 提供页面和媒体。

## 自动检查

| 检查 | 实际结果 |
|---|---|
| Rust fmt / test / clippy | 通过，49 tests |
| Android / Robolectric | 150 tests，0 failures / errors / skipped |
| Node 网页视频协议 | 15 tests，全部通过 |
| 三语言资源 | 392 个字符串键及格式参数一致 |
| Android lint | 0 errors / 3 warnings |
| R8 Release 构建、签名 | 通过 |
| 安装脚本、Python 回归工具 | 默认安装执行通过，修改脚本语法检查通过 |

Lint 的三个提示分别为 AGP 更新、ChromeOS x86_64 支持、`localeConfig` 在 API 33 以下不生效。
低系统版本仍可根据系统语言选择资源。构建日志为 `validation/build-final-validation.log`。
最终构建后只修改了验证工具和文档，交付 APK 未重新打包或替换。

## 设置与基础浏览

| 项目 | API 29 | API 34 |
|---|---|---|
| 六个设置分类及搜索选择弹层返回 | 通过 | 通过 |
| 深浅主题切换、进程重启后保留主题 | 通过 | 通过 |
| 过滤管理返回隐私分类，外部导航关闭过滤管理 | 通过 | 通过 |
| 下载线程滑块 1–16、重启后保留 16 | 通过 | 通过 |
| 1.3 倍字体后仍在视频设置，文字换行无重叠 | 通过 | 通过 |
| 横屏布局 | 可用宽度不足 720dp，保持单栏 | 分类与详情双栏，截图确认 |
| 应用语言切换后保留当前设置分类 | 不支持系统应用语言入口 | 简体中文、繁体中文、英文均通过 |
| SPA 标题及完整地址同步、滚动隐藏/展开地址栏 | 通过 | 通过 |
| 弹窗页面、后台后进程重启恢复普通标签 | 通过 | 通过 |
| 关闭恢复后回主页、退出移除最近任务 | 通过 | 通过 |
| 默认浏览器入口打开系统选择页 | 通过 | 通过 |

语言验证检查了切换后的实际显示文本，并检查简繁中文、英文设置截图；未把资源文件存在当成运行结果。

## 视频播放

| 夹具 | API 29 / WebView 91 | API 34 / WebView 113 |
|---|---|---|
| 主页面 MP4 | 增强控件完整回归通过 | 增强控件完整回归通过 |
| Blob 视频 | 增强控件完整回归通过 | 增强控件完整回归通过 |
| 方形视频 | 保持竖屏、手动旋转与退出恢复通过 | 同左 |
| 跨域 iframe | 网站全屏与原控件回退通过；不提供增强控制 | 增强控件完整回归通过 |

完整回归通过真实触摸和网页遥测共同验证了以下行为：

- 全屏显示、单击显隐控件、横向视频自动横屏。
- 播放/暂停后控制目标仍有效，双击中央暂停/恢复。
- 1.5× 长按变为 2×，松手或切后台恢复 1.5×，默认倍速不被临时加速覆盖。
- 暂停时横向拖动仍能跳转进度且保持暂停。
- 左侧纵向滑动改变窗口亮度，右侧改变媒体流音量。
- 锁定时双击和拖动不生效，返回键先解锁并保留全屏。
- 网页/增强控件切换，退出恢复原方向、原窗口亮度及 HTML 控件状态。

主页面、Blob、跨域媒体的播放画面均非空；横屏与方形竖屏控件截图已检查。
进度跳转边界、不可跳转直播和任意原速度恢复另有协议/手势单元覆盖。
双击两侧快进/快退未在本轮设备上单独执行，不列为逐项触摸通过结果。

视频继续由网站和 WebView 解码，未加入 ExoPlayer/FFmpeg。WebView 91 的跨域能力限制是
特性检测后的回退路径，不能据 Android 系统版本推断所有厂商 Provider 的能力。

## 下载与日志

两套系统都下载了可控的 6 MiB 文件，服务器记录到多个非零起点的 Range 请求。
系统 Download 目录中的完整文件 SHA-256 均为
`e338caefa380bafe02a98dac6b2865a8c4783d80f5d813906abd01c250463d70`，与预期字节一致。
下载列表显示完成，完成后前台服务停止；API 34 还直接捕获到了传输期间的前台服务。
API 29 的瞬时采样未捕获服务启动，只确认了完成后的退出状态。API 29 重复下载自动生成带编号的文件。

API 34 最终回归崩溃缓冲区为空，未见应用的 Java 崩溃、JNI 链接错误、SIGSEGV 或 ANR。
API 29 记录到两次 shell UI 读取辅助进程退出时的 ART JIT SIGSEGV；日志明确为 uid 2000 的
`com.mybrowser.validation.FastUiDump`，不是浏览器进程，该辅助工具不进入 APK。
浏览器和其 WebView 进程未出现对应崩溃，后续用例正常通过。

## 性能对照

API 34 同一模拟器、同一本地 `browser-ux.html`、相同默认配置，分别安装原版和最终版。
安装后的 QA 应用数据均从干净状态开始；每版预热 2 次，随后测量 7 次。
每次先 `am force-stop`，再用 `am start -W` 的 TotalTime 记录进程冷启动，等待 2 秒后取主进程 PSS。
操作系统和页面缓存保持热状态，不代表设备刚重启后的首次启动。期间无 Gradle 构建或第二台模拟器。

| 指标 | 原版 0.1.0 | 最终版 0.2.0 |
|---|---|---|
| 启动样本 / ms | 615, 3376, 547, 538, 664, 586, 469 | 565, 556, 532, 539, 471, 613, 800 |
| 启动中位数 / ms | 586 | 556 |
| 主进程 PSS 中位数 / KiB | 78,236 | 73,627 |
| 主进程 PSS 中位数 / MiB | 76.4 | 71.9 |
| APK / bytes | 1,910,313 | 2,131,247 |

本次未观察到明显性能退化；样本存在波动，不能把 30 ms 的差异视为已证明提速。
PSS 不包含 Chromium 子进程，未测量整机耗电、视频帧率或长时间热状态。
APK 增量 220,934 bytes 包含设置、视频与三语言的全部改动，不是降低 minSdk 的独立成本。
较早的双模拟器/构建负载下样本不参与本次对照。

## 复现与证据

```bash
python3 validation/qa-server.py
# 在另一个终端运行，每次只连接并测试一台已安装最终 APK 的 QA 模拟器。
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/settings-regression.py --serial emulator-5554
python3 validation/download-regression.py --serial emulator-5554
ANDROID_SERIAL=emulator-5554 python3 validation/emulator-ux.py regress
python3 validation/video-regression.py --serial emulator-5554 --variant standard
# 分别再执行 --variant blob / square / cross；API 34 将 serial 替换为 emulator-5556。
python3 validation/locale-regression.py --serial emulator-5556
python3 validation/benchmark-startup.py --serial emulator-5556 --rounds 7 --warmups 2 --output validation/results/startup-local.json
```

本地证据不纳入 Git：

- `validation/results/api{29,34}-{standard,square,blob,cross}.json`：8 个最终视频结果，均为 `error: null`。
- `validation/results/api{29,34}-settings.json`、`api34-locales.json`、`api{29,34}-download.json`。
- `validation/results/api*-settings-*.png/xml`、`api34-locale-*.png/xml`、`api*-*-controls.png`：布局与视频画面。
- `validation/{browser,download,settings,video,locales}-api*.log`：完整用例过程。
- `validation/results/api{29,34}-{logcat,crash}.txt`：崩溃归属检查。
- `validation/results/startup-final-v0.1.0.json`、`startup-final-v0.2.0.json`：全部启动及 PSS 样本。

## 未验证范围

- Android 11/12/13 及 API 35–37 的设备、厂商 WebView；32 位 Android 不在本次 Release 支持范围内。
- 低端真机帧率、耗电、长时间视频播放和多视频网站的广泛兼容性。
- 第三方 DRM、实际直播网站、字幕/清晰度定制 UI 和 MSE 自适应流；Blob 文件夹具不等同于完整 MSE 验证。
- 实体 DLNA 接收器投送、摄像头/麦克风、定位、文件上传。
- 本轮未重新执行 SAF 授权目录、下载失败重试和删除文件的所有设备路径；它们属于既有功能，不能沿用旧 APK 的设备结果冒充本轮通过。

原始代码回滚点为 `backup/pre-android10-settings-video-20260906`（`b838e47`）。
