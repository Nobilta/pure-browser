# 0.22.2 本地验证（2026-11-17，未发布）

被测产物：`PureBrowser-v0.22.2-release.apk`（versionCode 86），当前工作区构建的本地签名 ARM64 Release，包含工作区既有修改；大小、签名和摘要见生成的发布附件。

- 自动化：`./build-and-test.sh --release` 通过。1034 条三语资源、发布工具 14 项、Node 132 项、Rust 70 项；JVM 892 项无失败（1 项跳过）。Rust fmt/clippy、Android Lint（0 错误、20 项既有警告）、R8、签名与 16 KiB zipalign 校验通过。
- 设备：专用 `PureBrowser_DesignQA` ARM64 模拟器，Android 14 / API 34、WebView 113.0.5672.136；以下设备结果均绑定同一最终 APK 摘要。
- 下载图标：安装包、压缩包、图片、音视频、通用文件五类下载逐个核对文件摘要，并确认进入「已完成」筛选；单色图标和完成文字已目视检查。测试使用每轮独立文件名，避免清理夹具后残留的 MediaStore 同名记录干扰；不会仅凭文件存在判定成功。
- 识图：最终包中默认 Bing 实际识别公开猫图片；网页与图片引擎独立选择、自定义两字段表单、重启后配置保留、图片 URL 编码传递和删除后回到 Bing 均通过。JVM 覆盖图片引擎备份往返、旧备份保留配置及失败导入不部分写入。本机直连测试中 Google 超时、Yandex 返回暂不可用页，未将 HTTP 200 作为识图成功。
- 播放器与窄屏：320／360／400dp 的 100%／200% 字体及 720dp 横屏下标题和四个 48dp 顶栏操作完整显示；400dp／200% 下「播放设置」中的后台播放开关可双向切换。下载筛选单选语义和 320dp／200% 多选操作布局通过，相关截图已检查。
- 分享与阅读：普通／无痕图片分享均携带各自当前 Cookie 并打开系统分享；本版仍仅普通模式显示以图搜图。英／简中／繁中阅读工具条在 320dp／200% 下完整显示，测试结束恢复字体、分辨率和语言。
- 未验证：实体真机、其他 Android/WebView 版本、真实 TalkBack 播报、完整媒体功能矩阵；联网识图结果仅代表本次测试网络，不保证所有国内网络可用，Google Lens／Yandex 未取得成功识别结果。

详细结果、截图与失败尝试保存在 Git 外的 `validation/results/design-20261117/`；最终通过记录为 `final-image-search-verified`、`final-compact-ui`、`final-reader-sharing`。没有推送或发布。

## 0.22.1 审查问题修复验证（2026-11-17，本地未发布）

被测产物：`PureBrowser-v0.22.1-release.apk`（versionCode 83），本地签名 ARM64 Release；大小和摘要见生成的发布附件。

- 自动化：`./build-and-test.sh --release` 通过。1034 条三语资源，发布工具 14 项、Node 132 项、Rust 70 项；JVM 889 项无失败（1 项因测试网络立即返回而跳过）。Rust fmt/clippy、Android Lint（0 错误、20 项既有警告）、R8、签名与 16 KiB zipalign 校验通过。
- 协议与隐私：JVM 覆盖 HTTP(S) HLS 识别、普通 M3U8 不重试、MAP 的密钥切换／明密文转换、FTP 无 SIZE 时重下、来源链接凭据隐藏、旧无痕记录凭据清理；图片分享覆盖最终响应 MIME、无扩展名 PNG、HTML 拒绝、64 MiB 上限及缓存清理。
- 设备：独立 Pixel 7 ARM64 模拟器，Android 14 / API 34、WebView 113.0.5672.136。最终签名包通过下载管理回归（改名、单次 SAF 目录、来源复制、搜索、批量暂停／继续、两种删除行为），FTP/HLS 落盘文件摘要正确；空列表只显示一处空状态。
- UI：筛选暴露单选语义；320dp／200% 字体下多选计数一行、四个操作完整分行。播放器在 320／360／400dp 的 100%／200% 字体及 720dp 横屏下保留标题和五个顶栏操作，400dp／200% 后台播放开关可双向切换；截图已目视检查。
- 分享与阅读：普通模式显示以图搜图，无痕模式隐藏；两种模式的图片分享均携带当前 Cookie 并打开系统分享。阅读工具条在英／简中／繁中、320dp／200% 字体下完整显示并避让提示条。
- 验证过程：一次 Lint 内部分析异常，完整重跑后通过；设备脚本已修正节点定位、语言切换后重启和横屏夹具进入方式，未放宽产品断言。先前失败日志保留在 Git 外。
- 未验证：实体真机、其他 Android/WebView 版本、真实 TalkBack 播报及完整媒体功能矩阵；设备 HLS 为未加密点播，AES/MAP 组合由本地 HTTP JVM 测试覆盖，独立音轨与字节范围仍不支持保存。

详细结果与截图位于 Git 外的 `validation/results/verified-review-fixes/`，各设备结果绑定最终 APK 摘要；没有推送或发布。

## 0.22.1 FTP 与 HLS 下载（2026-11-16 本地构建，未发布）

被测产物：`PureBrowser-v0.22.1-release.apk`（versionCode 83，6 064 976 字节，
SHA-256 `6c9b57c034bfdaf1f6d164980c9682afa62cbd0ea7daa71d85800095b0c3b1e2`），本地签名 ARM64 Release。
`./build-and-test.sh --release` 通过：JVM 单测、Lint、R8、签名与 apksigner 校验（v2）。

- 改动：下载的取字节部分抽成 `DownloadTransport`，新增 FTP（被动模式、TYPE I、SIZE/REST 续传）与
  HLS 合并（最高码率子列表、按序拼接分段、AES-128 解密、`EXT-X-MAP` 前置、按段续传）；
  `ftp://` 链接与地址栏输入进入同一条下载确认，HLS 结果按 `video/mp2t` 存为 `.ts`。
- 设备：Android 14 / API 34，`MyBrowser_Pixel7`（`emulator-5554`，`-no-window` 隐藏窗口）。
  `mybrowser_test` 的 Android 37 系统镜像在本机已缺失，因此改用该 AVD。
- 覆盖：点击 FTP 链接保存的文件与 FTP 服务端实体 SHA-256 一致（131 072 字节）；点击 HLS 播放列表得到
  全部分段拼接的 `.ts`（163 840 字节）并核对摘要；两个任务都出现在下载管理；地址栏输入 `ftp://` 同样
  进入下载确认。设备阶段由 `validation/ftp-hls-regression.py` 记录结果与截图。
- 自动化：FTP 客户端由脚本化 FTP 服务验证 RETR/SIZE/REST 与断点续传；HLS 单测覆盖主列表选码率、
  AES-128 解密、`EXT-X-MAP`、失败后按段续传以及命名与 MIME 改写。
- 未验证：FTPS、主动模式、EPSV、真实公网 FTP 与 NAT/被动端口受限的场景；设备侧未跑 AES-128 与
  直播列表拒绝（仅单测覆盖）；Android 10–13 与其他 WebView 版本。

## 0.22.1 本地测试包（2026-11-15，未发布）

被测产物：`PureBrowser-v0.22.1-release.apk`（versionCode 83），本地签名 ARM64 Release。最终包与 `api36-code-review-delivery-suite.json`、`api36-code-review-recheck-suite.json` 和 `api36-code-review-resident-suite.json` 的 APK 摘要一致；大小和摘要见生成的发布附件。

- 代码复核：扫描全库文件，人工重点检查下载、过滤/JNI、导航和标签生命周期、隐私及凭据边界、权限、脚本、存储与备份、更新、媒体和工具入口；不代表逐行穷尽审查。
- 修复：完成通知的下载定位在滚动后清除，后续从菜单进入显示最新任务；仅手柄拖动模式不再接收内容区嵌套滚动，网站设置顶部继续下拉不再误关。
- 自动化：最终源码运行 `./build-and-test.sh --release` 通过；832 项 JVM 测试（1 项跳过，0 失败）、132 项 JavaScript、70 项 Rust、14 项发布工具测试；Rust fmt/clippy、Lint（0 错误、20 警告）、R8、签名和 16 KiB zipalign 通过。全库 59 份 Python、9 份 Shell、24 份 JavaScript 语法及 80 份 XML、5 份 TOML、2 份 JSON 解析通过。
- 设备：Android 16 / API 36，mybrowser_test / emulator-5554，WebView 133.0.6943.137，安装并核对最终包摘要。26 个阶段分别取得通过结果（包括失败后的针对性复测），覆盖地址栏、无痕及驻留生命周期、下载打开及通知定位、下载管理、Blob 与分段下载/进程恢复、面板拖动及动画中断、TLS、安全导航恢复、开发者工具、会话恢复、站点和权限、书签导入导出、脚本与订阅、对话框、媒体入口、标准视频、设置及三语切换。
- 修复验证：旧包可复现通知定位导致新任务不可见；最终包新增跨入口检查通过。旧包网站设置顶部滑动会关闭；最终包顶部和底部反复滑动保持面板、标题及操作栏稳定。
- 测试维护：更新旧菜单/书签/首页名称，按真实开关状态启用会话恢复；过滤夹具避开 EasyList 回环地址例外；分组确认等待对话框关闭，旧 WebView 缺失节点时复用真实按钮几何点击/可见 WebView 等待。断言和原始失败记录保留。
- 环境与复测：无窗口和软件渲染模式下模拟器进程多次以 Windows `0xc0000005` 退出；改用隐藏窗口和主机 GPU 后完成验证。导航/视频曾因辅助功能节点读取失败停下，单独复测通过。较早包的通过结果不计入最终包覆盖。
- 未验证：实体真机、其他 Android / WebView 版本、真实 DLNA 接收设备和新增规则对真实网站的全面兼容性。

详细阶段结果、失败尝试和截图保存在 Git 外的 `validation/results/`；此包仅供本机测试，未发布。

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
