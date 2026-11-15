## 0.22.1 本地测试包（2026-11-15，未发布）

被测产物：`PureBrowser-v0.22.1-release.apk`（versionCode 83），本地签名 ARM64 Release。安装包摘要与下述四阶段 suite 记录一致。

- 自动化：`./build-and-test.sh --release` 通过，包含快速检查、Rust 测试与 clippy、Android JVM 单测（832 项，1 项跳过，0 失败）、Android Lint（0 错误、20 警告）、R8、APK 签名与 16 KiB zipalign 校验。
- 设备：Android 16 / API 36 无窗口模拟器（mybrowser_test，emulator-5554），WebView 133.0.6943.137，安装并运行同一签名包；下载管理、登录入口、过滤规则、媒体入口四阶段通过。
- 下载：验证修改文件名后的文件内容、任务详情和来源复制、搜索与状态筛选、本次 SAF 保存目录、只删记录保留文件、同时删除文件，以及批量暂停和继续。
- 登录入口：验证打开系统提供方设置并返回状态面板；普通页合成通行密钥请求返回提供方错误且保留页面，无痕页请求被拒绝。此结果不代表真实凭据注册或登录成功。
- 过滤与媒体：验证七份离线规则、CJX / Mobile Ads 默认开启、App 引导 / Cookie 默认关闭、开关叠加及重启保留、自定义订阅更新与失败时保持可用，以及浮动投屏入口跟随当前播放媒体。
- 未验证：实体真机、其他 Android / WebView 版本、Android 10–13 的自动填充选择器、真实账号密码保存/填充/更新、真实通行密钥注册/登录、Google 密码管理器的浏览器接入批准，以及真实网站对新增默认规则的全面兼容性。

详细结果保存在 Git 外的 `validation/results/api36-v0221-suite.json`、对应阶段日志与结果目录；此包仅供本机测试，未发布。

## 0.22.0 正式发布验证（2026-11-11）

被测产物：`PureBrowser-v0.22.0-release.apk`（versionCode 81），本地签名 Release。

- 自动化：`./build-and-test.sh --release` 通过：快速检查、Android JVM 单测、Rust clippy、Android Lint、R8、APK 签名与 16 KiB zipalign 校验。
- 构建产物：5,419,345 bytes，SHA-256 `0d9fc5a8d8b00e4d9310bf78adb4fab428a9be27dacfce52b4f029d8d95e2ab8`。
- 设备回归：Android 14 / API 34 模拟器（emulator-5554）安装上述签名包，运行 `validation/update-sources-regression.py` 三项通过：Gitee 主源检查、注入 Gitee 限流后 GitHub 回退成功、两边均限流时显示失败；更新偏好已恢复。
- 未验证：实体真机、真实升级安装、其他 Android/WebView 版本；传输故障和包元数据校验由 JVM 测试覆盖。

## 0.21.0 双源更新本地测试包（2026-11-11，未发布）

被测产物：`app/build/outputs/apk/release/app-release.apk`，本地签名 Release；版本仍为 0.21.0 / versionCode 80，包含工作区既有未提交改动。

- 自动化：快速检查、814 项 Android JVM 单测（含 16 项更新仓库测试）、Rust clippy、Android Lint（0 错误、15 警告）、R8、APK 签名与 16 KiB zipalign 校验通过。
- 更新测试：覆盖 Gitee 优先及 CDN 跳转、GitHub 回退、来源缓存和独立限流、同版本中断重下，以及非法清单、摘要／签名错误、取消和本地文件失败不会换源。
- 设备回归：Android 14 / API 34 模拟器安装上述签名包，实际从 Gitee 检查更新；注入 Gitee 限流后实际从 GitHub 检查成功；两边均限流时显示失败。三项检查通过，更新偏好已恢复。
- 未验证：实体真机及真实升级安装；传输故障和包元数据校验使用注入连接与 Robolectric，设备回归仅检查来源切换与更新界面。

产物大小、校验值和详细结果保存在 `validation/results/update-sources/`。

## 0.21.0 摄像头预览与控制台复制本地测试包（2026-11-11，未发布）

被测产物：此前的 `app/build/outputs/apk/release/app-release.apk` 本地签名 Release（已由上方双源更新测试包替换）；版本为 0.21.0 / versionCode 80，包含当时工作区其他既有未提交改动，不是下方的历史正式包。

- 自动化：快速检查、803 项 Android JVM 单测、Rust clippy、Android Lint（0 错误、15 警告）、R8、APK 签名与 16 KiB zipalign 校验通过。
- 设备回归：Android 14 / API 34 / WebView 113.0.5672.136 模拟器安装上述签名包，摄像头 7 项、开发者工具 15 项检查通过。覆盖授权后自动预览、前置→后置→前置及持续出帧、默认摄像头记录、拒绝授权、普通媒体自动播放，以及网页日志和执行结果的长按复制与整条粘贴一致性、横竖屏与面板导航。
- 未验证：实体真机、e签宝真实活体流程、其他 Android/WebView 版本；不保证网页未指定采集方向时默认选择前置。

产物大小、校验值和详细结果保存在 `validation/results/realtime-camera-release/` 与 `validation/results/api34-devtools.json`。

## 0.21.0 正式发布验证范围

被测产物：`PureBrowser-v0.21.0-release.apk`（versionCode 80），公开正式版。

- 产物：`PureBrowser-v0.21.0-release.apk`，5417077 bytes，SHA-256 `f6f406480ccb4644ebcd85729d91359b1cc71799b4b03d5980f5dd5bf1aac12f`。
- 自动化：`./build-and-test.sh --release` 通过；本地化 989 条三语资源、发布工具测试、Node 测试、Rust 测试与 clippy、Android JVM 单测、Lint（0 错误、62 警告、0 个未使用资源）、R8、APK 签名验证均通过。
- 设备回归：API 34 无窗口模拟器安装并启动 0.21.0，完成设置页导航、普通 Snackbar 2 秒消失检查；应用无崩溃日志。模拟器高负载时出现过一次 System UI 无响应，等待后恢复，与应用无直接关系。
- 未验证：实体真机、深色模式全页面、TalkBack、完整下载/投屏回归；倍速平滑滑轨仅完成编译与代码检查，未在设备上实际拖动。

## 0.20.4 本地测试包验证范围

被测产物：`PureBrowser-v0.20.4-release.apk`（versionCode 79），对应公开更新日志 0.21.0 的本地测试构建，尚未发布。

- 产物：`PureBrowser-v0.20.4-release.apk`，5417073 bytes，SHA-256 `b2af0c27de632bde584e12897a342b5f40bbe4165d684675044469a2b7c95bed`。
- 自动化：本地化（989 条三语资源）、发布工具测试、Node/Rust 测试、Android JVM 单测、Lint（0 错误、63 警告、0 个未使用资源）、R8、APK 签名验证通过。
- 设备回归：API 34 无窗口模拟器完成安装启动、设置导航、2 秒普通 Snackbar，以及导航、设置、翻译、文本、脚本和开发者工具截图检查；真机测试待进行。
- 未验证：实体设备、深色模式全页面、TalkBack、完整下载/投屏回归。

## 0.20.3 本地测试包验证范围

被测产物：`PureBrowser-v0.20.3-release.apk`（versionCode 78），本地签名 Release，尚未发布。

- 产物：`PureBrowser-v0.20.3-release.apk`，SHA-256 `d22e62f2015f735ca6e9d8b30d7284e9ae82eb80f8bafbb70384f725f6899ac5`。
- 自动化：本地化检查、发布工具测试、Node/Rust 测试、JVM 单测、Lint、R8 与签名验证通过。
- 设备回归：API 34 无窗口模拟器验证网站设置内容滚动不关闭、手柄拖动关闭、菜单无关闭按钮、设置页无副标题、360×540 缩略图及 Release 启动；未在真机重跑，等待真机测试。
- 未验证：实体设备、深色模式全页面、TalkBack、完整下载/投屏回归。

## 0.20.2 本地测试包验证范围

被测产物：`PureBrowser-v0.20.2-release.apk`（versionCode 77），本地签名 Release，尚未发布。

- 自动化：`./build-and-test.sh --release` 完成本地化检查、发布工具测试、Node 测试、Rust 测试与 clippy、Android JVM 单测、Lint、R8、APK 签名验证；APK 版本 0.20.2 / 77。
- 设备回归：仅在 API 34 无窗口模拟器上完成启动、首页、菜单和 1.0/200% 字体截图冒烟；未在真机上重跑完整回归，等待真机测试结果。
- 未验证：实体设备、其他 Android/WebView 版本、深色模式全页面、TalkBack、极低亮度与户外可读性、完整播放器与下载回归。

详细日志与截图保存在忽略目录 `validation/results/`。

## 0.20.0 本地测试包验证范围

被测产物：`PureBrowser-v0.20.0-release.apk`（versionCode 75），本地签名 Release，尚未发布。

- 本版是从 0.19 起全部媒体、下载、书签、过滤改动的汇总重建包；源码与上一轮验证的 0.19.3 一致，差异只有版本号与更新日志。
- 自动化：`./build-and-test.sh --release` 通过，包含 791 项 JVM、132 项 JavaScript、69 项 Rust、14 项发布工具测试，以及 1014 条三语资源一致性检查；Rust fmt/clippy、Android Lint（0 错误，85 条警告）、R8、APK 签名与 zipalign 校验通过。
- 设备回归：未在新的安装包上重跑。上一轮源码对应的 Android 14 / API 34 / WebView 113 与 Android 17 / API 37 / WebView 145 结果保留在 `validation/results/`，可作为该源码的参考记录，但不构成本安装包的实测结论。
- 未验证：实体设备、其他 Android 与 WebView 版本、Digest 与代理认证组合、真实 DLNA 接收设备；媒体格式兼容性仍受系统 WebView 与设备解码器限制。

详细日志、服务器请求与模拟器结果保存在忽略目录 `validation/results/`。
