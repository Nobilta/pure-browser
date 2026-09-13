# 0.8.0 验证报告

日期：2026-09-13。验证对象为最终签名 Release；套件和专项结果绑定实际安装 APK 的 SHA-256。
原始日志、截图、测试 XML 和交付检查保留在本地 `validation/results/`，不提交 Git。

## 安装包与自动检查

- `PureBrowser-v0.8.0-release.apk`，versionCode 16，包名 `com.mybrowser`，Android 10+、arm64-v8a。
- **4,688,330 bytes**，约 4.47 MiB；SHA-256：`7d384d68084d754b904e63a2c703669b60e60cc82cc466e5c7d763cb903ee924`。
- v2 签名、16 KiB zipalign 和三份 native 库的 ELF LOAD 16 KiB 对齐检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，与 0.7.4 相同。
- **330 项 Android/Robolectric、58 项 Rust、55 项 Node 测试通过**。Android 测试使用真实 host JNI，0 failures、0 errors、0 skipped。
- Rust fmt/clippy、622 项三语言资源校验、R8、Release 构建和包内两个运行时脚本一致性检查通过。
- lint：0 errors、19 warnings、1 hint。警告包含版本提示、Compose 参数约定、复数候选和未使用资源等；未将有警告的结果写成零问题。

最终完整 `build-and-test.sh` 在 18:00:23 开始，608.15 秒完成，退出码 0；上述 Node、Rust 和 Android 结果均来自这次完整执行。
开发期间另有 29 项临时专项通过：20 项源码、分类、搜索与 QR 解码检查，9 项网络回调顺序检查。
临时 Kotlin 测试源文件已删除，XML 记录保留；这 29 项没有重复计入最终构建的 330 项固定测试。

依据：[完整构建](validation/results/release-0.8.0/build-final.json)、
[构建日志](validation/results/release-0.8.0/build-final.log)、
[交付检查](validation/results/release-0.8.0/delivery.json)、
[签名](validation/results/release-0.8.0/signature.txt)、
[ELF 对齐](validation/results/release-0.8.0/elf-alignment.json)。单元测试和 lint 原始记录位于
`validation/results/release-0.8.0/automated/`。

## Android 17 界面与功能回归

PureBrowser_API37，Android 17 / WebView 145.0.7632.218，arm64、手势导航、主机 GPU。
最终 APK 的 **18 个阶段全部通过**：
`omnibar`、`download-opening`、`menu-navigation`、`security`、`developer-tools`、`browser`、
`site`、`layout`、`features-scripts`、`features-filters`、`features-dialogs`、`features-media`、
`settings-back`、`settings`、`productivity`、`home-shortcut`、`locale`、`productivity-visual`。

- 地址栏真实输入、软键盘、上下布局、切标签与重新打开正常；下载打开、网站设置和证书状态检查通过。
- 菜单子页、设置分类和选择器保持正确返回路径；快速连续返回、后台回来、Activity 重建和边缘返回均可继续操作网页。
- 过滤订阅验证实际网络拦截、元素隐藏、ETag 更新、无效/离线更新保留可用规则；脚本安装、运行、禁用、删除及数据清理通过。
- 书签、历史、标签筛选、批量关闭、长按链接/图片、首页快捷方式编辑与取消通过；分享只打开系统选择器，未发送消息。
- 英文、简体中文、繁体中文切换保留当前设置分类。浅/深主题、130% 设置字体、150% 网站设置字体、横竖屏和键盘避让通过。
- 人工检查菜单、标签、设置、网站设置、源码、网络、扫码取景框和文本结果截图；标题间距、圆角、主题颜色和按钮位置一致，取景框保持在预览范围内。
- 将三项系统动画缩放均设为 0 后，连续三次菜单→设置→外观→逐级返回仍正常；完成后恢复原有动画设置。

依据：[18 阶段套件](validation/results/api37-release-080-suite.json)、
[界面检查拼图](validation/results/release-0.8.0/visual/overview.png)、
[主题与多语言拼图](validation/results/release-0.8.0/visual/themes-locales.png)。原始截图与层级文件保留在 `validation/results/`。

## 源码与网络专项

最终 APK 能显示并滚动百万字符源码。HTML 与内嵌 JavaScript/CSS 使用主题语法色；可见文本节点数量有界，
切换页签保留源码和滚动位置，手动刷新读取修改后的 DOM。网络专项 9 项通过，覆盖真实 Document、JavaScript、
Fetch/XHR 请求、HTTP 404、大小写不敏感的多词搜索、无结果提示、筛选状态恢复和清空。

专项发现过真实时序问题：Chromium 的请求回调先于 `onPageStarted`，导致早到的资源日志被清空。
现在由先到的导航回调建立记录，再合并合成的主文档条目；同网址刷新仍建立新时间线。
9 项临时回调顺序测试覆盖早到资源、重定向、POST、缓存页面、重复刷新及错误/拦截状态保留，最终包的真实请求分类再次通过。

`dumpsys gfxinfo` 在主机 GPU 模式下采集到的源码样本：

| 场景 | 帧数 | 中位耗时 | 最大耗时 | 超过 50 ms |
|---|---:|---:|---:|---:|
| 源码加载阶段 | 32 | 20.556 ms | 51.701 ms | 1 |
| 源码滚动阶段 | 56 | 18.371 ms | 22.474 ms | 0 |

这些是模拟器本轮采样，不能代表真机帧率或长期功耗。旧版对照使用 SwiftShader，且百万字符场景没有得到有效完整帧数据，
与新版 GPU 条件不同，因此没有计算性能提升百分比。优化针对主线程排版、重复解析和不可见日志更新，保留现有 Rust 过滤引擎。

依据：[源码结果与原始帧统计](validation/results/release-0.8.0/final/results.json)、
[网络专项](validation/results/release-0.8.0/api37-network/results.json)、
[旧版对照记录](validation/results/release-0.8.0/baseline/)。

## 二维码专项

最终 APK 在 Android 17 通过 9 项、Android 10 通过 8 项扫码检查：
首页地址栏移除主页占位和刷新入口、保留扫码；拒绝相机权限仍可选图；150% 字体横屏操作区可滚动；
中文、多行、emoji 和看似 HTML 的内容原样显示并复制；`javascript:` 保持纯文本；图片与相机中的网页地址直接访问。
相机解码后关闭扫描页并释放客户端。Android 17 另检查了切后台释放、返回前台恢复和 Back 释放。
空白图片提示没有二维码，损坏图片提示无法读取；两种错误后仍能选择有效图片并显示文本。

实时相机验证使用模拟器 `imagefile` 输入。早期输入被模拟器变换后裁切，缺失二维码定位角；独立 Camera2 采集器取出的
原始亮度帧也无法解码。校准夹具尺寸和位置后，原始帧及两版系统上的最终 APK 都成功解码。
修正在验证夹具，发布实现仍使用通用 Camera2 和离线 ZXing。临时采集器已卸载。

依据：[Android 17 扫码](validation/results/release-0.8.0/api37-qr/results.json)、
[Android 10 扫码](validation/results/release-0.8.0/api29-qr/results.json)、
[图片错误恢复与关闭动画](validation/results/release-0.8.0/api37-qr-edges/results.json)、
[相机校准后的原始帧解码](validation/results/release-0.8.0/camera-calibrated/decode.txt)。

## Android 10 兼容与覆盖升级

PureBrowser_API29，Android 10 / WebView 91.0.4472.114，arm64、三键导航、主机 GPU。
使用 `adb install -r` 从 0.7.4（15）覆盖升级到 0.8.0（16），没有卸载或清空浏览器数据。
安装后 APK 哈希与交付文件一致；升级前后已有数据库和 shared preferences 的 13 份文件校验值全部相同。
相机和图片扫码、新首页入口、150% 横屏布局及文本结果通过。

兼容套件的 `menu-navigation`、`developer-tools`、`layout` **三阶段全部通过**，覆盖菜单快速返回、
开发工具输入/源码/页签恢复，以及 150% 字体横竖屏网站设置。
两台设备收集的日志均未发现浏览器 Java/native 崩溃或 ANR 标记，结束时没有本应用的活动相机客户端。

依据：[覆盖升级](validation/results/release-0.8.0/api29-upgrade.json)、
[兼容套件](validation/results/api29-release-080-compat-suite.json)、
[Android 10 诊断](validation/results/release-0.8.0/api29-diagnostics.json)、
[Android 17 诊断](validation/results/release-0.8.0/api37-diagnostics.json)。

## 验证工具修正与覆盖边界

输入辅助现在会在 Compose `findFocus()` 返回空时查找实际聚焦的可编辑节点。
添加过滤订阅需要等待后台规则重建完成，脚本将该处的短界面等待改为有界的完成等待，随后整个过滤阶段通过。
Android 10 的旋转验证使用其 `wm set-user-rotation` 命令，并核对实际截图方向；系统设置异步写回造成的旧等待失败有独立记录。
旧 Android 的 shell UI 辅助进程偶发 ART 退出异常与浏览器进程分开记录，不能据此把辅助失败认作产品崩溃或产品通过。
失败尝试保留在套件或扫码结果的 `priorAttempts` 中；各项完成结果以同一最终 APK 的复测为准。

本轮重点覆盖内置页面、开发工具与扫码；媒体入口和来源选择已复测，没有把 0.7.4 的完整播放器/后台媒体矩阵计入本轮。
未使用真实相机镜头、低光环境、实体闪光灯、实体 DLNA 接收器、真实账号或低端真机；未逐站验证所有生产网站、DRM/MSE 和直播实现。
物理相机成像、真实设备性能/功耗及上述平台组合仍需对应设备验证。

## 清理

临时专项源文件、相机诊断程序及其 APK/密钥、二维码/大源码夹具和设备上的临时输入文件已移除。
旧交付 APK、旧版记录和重复的中间结果已清理；本地只保留 0.8.0 交付 APK 和本轮必要验证记录。
两台模拟器与 QA 服务已停止，字体和旋转设置已恢复；临时工作目录及设备上的 UI 辅助程序已删除。
现有回归工具的修正保留在源码，签名密钥、APK 和生成结果不进入提交。
明细见 [清理记录](validation/results/release-0.8.0/cleanup.json)。
