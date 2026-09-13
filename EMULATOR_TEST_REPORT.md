# 0.7.3 验证报告

日期：2026-09-13。所有设备操作使用本地专用 arm64 模拟器，串行验证同签名 Release。
套件与专项记录绑定实际安装 APK 的 SHA-256；构建和验证原始记录位于 `validation/results/`，不提交 Git。

## 安装包与自动检查

- `PureBrowser-v0.7.3-release.apk`，versionCode 14，包名 `com.mybrowser`，Android 10+、arm64-v8a。
- 4,599,770 bytes，约 4.39 MiB；SHA-256：`d10dc92ec6a16fce41ee7a18cc399eaca8bbf046bc97484629eddbd88d9f4600`。
- v2 签名、16 KiB zipalign 和三份 native 库的 ELF LOAD 16 KiB 对齐检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，与上一版相同。
- 330 项 Android/Robolectric 测试：0 failures、0 errors、0 skipped，包含真实 host JNI。
- 58 项 Rust、55 项 Node 测试与 587 项三语言资源校验通过；Rust fmt/clippy、R8 与 Release 构建通过。
- lint：0 errors、10 warnings、1 hint。
- APK 中 `playback-probe.js`、`userscript-runtime.js` 与源码一致；不再包含 `inline-player.js`。

依据：[最终构建](validation/results/release-0.7.3/build-final.json)、
[构建日志](validation/results/release-0.7.3/build-final.log)、
[交付清单](validation/results/release-0.7.3/delivery.json)、
[签名](validation/results/release-0.7.3/signature.txt)。单元测试 XML 与 lint 报告保留在
`validation/results/release-0.7.3/automated/`。

## 页面切换与边界滑动

Android 17 / WebView 145.0.7632.218 的 8 个界面阶段全部通过：
`menu-navigation`、`developer-tools`、`browser`、`site`、`layout`、`features-scripts`、`features-filters`、`settings-back`。
包含 24 轮菜单/设置快速往返、点击后 0/15/50/100 ms 立即返回、所有菜单子页、
后台切回、Activity 重建、设置分类/选项、左右边缘手势、键盘避让、横屏/大字体及真实网页触摸。

另对设置、网站设置、书签、历史、下载和开发工具共 6 条菜单路径录屏，窗口标识在进入与返回期间保持相同。
背景使用临时纯色测试页面，在各页面都应覆盖的底部区域逐帧检测网页颜色；129 帧、0 帧露出网页。
录屏使用正常系统动画倍率，保留原录像、检查结果与截图；测试页面已删除。

油猴脚本、过滤订阅和管理网站列表分别在每个上下边界执行六次快/慢滑动。
每次滑动后标题像素不变，停止后三帧内容哈希一致；网站设置主表单的同类检查在 `site` 阶段通过。
初次临时脚本把“油猴脚本”误写成“用户脚本”，定位失败；修正验证标签后边界专项通过，保留首次记录。

依据：[界面套件](validation/results/api37-release-073-ui-suite.json)、
[窗口与边界专项](validation/results/release-0.7.3/visual/checks.json)、
[逐帧分析](validation/results/release-0.7.3/visual/frame-analysis.json)、
[切换录像](validation/results/release-0.7.3/visual/transitions.mp4)。

## 播放器与兼容设备

Android 17 / WebView 145.0.7632.218 另通过 6 个播放器阶段：
`system-media`、`video-standard`、`video-custom`、`video-custom-blob`、`video-custom-cross`、`video-custom-csp`。
结合上述 8 个界面阶段，共 14 个阶段通过，使用手势导航。

- 标准、自定义容器、Blob 和可访问跨域视频在非全屏时保留原网页控件，仅全屏接管同一个视频元素；
  来源和播放进度保持，退出后恢复原始控件、DOM 属性、方向和亮度。CSP 限制交接时恢复可用网页控件。
- 全屏不再显示模式切换及快进/后退按钮；播放、倍速、侧边双击、中央双击、滑动、长按加速、
  锁定返回、自动隐藏、旋转和投屏选择器开关通过真实触摸验证。
- 网站设置关闭增强播放后保留网页全屏控件，进程重启后仍生效；重新开启后只在全屏接管。
  继承全局默认值、按 origin 持久化及私密会话隔离由现有站点单元测试覆盖。
- PiP 沿用原视频继续播放，系统媒体暂停可控制当前视频，默认后台暂停和显式允许后台播放均通过。

播放器首次标准视频阶段错误地断言“保存并刷新”后返回菜单；实际既有行为是关闭菜单路径并刷新浏览器。
修正脚本断言并在每个视频用例开始时明确设置网站偏好后，同一 APK 通过；首次失败日志保留在 `priorAttempts` 中。

Android 10 / WebView 91.0.4472.114 使用三键导航，通过 7 个兼容阶段：
`menu-navigation`、`developer-tools`、`browser`、`layout`、`video-standard`、`video-custom`、`settings-back`。
覆盖 24 轮菜单快速往返、旧系统安全边距、横屏与大字体、键盘、分类及选项返回、普通/自定义全屏手势和网站偏好重启保存。
本轮未在旧 WebView 重跑完整 `site`、Blob、跨域或 CSP 阶段，不将这些计入 Android 10 通过范围。

Android 10 还完成同签名 0.7.2（13）→ 0.7.3（14）覆盖升级，使用 `adb install -r`，未卸载或清空应用数据。
升级后设备 APK 哈希与交付包一致；兼容套件没有失败阶段，日志未发现崩溃标记。

依据：[Android 17 播放器套件](validation/results/api37-release-073-video-suite.json)、
[Android 10 兼容套件](validation/results/api29-release-073-compat-suite.json)、
[覆盖升级](validation/results/release-0.7.3/api29-upgrade.json)。
设备诊断和 logcat 分别保留为 `validation/results/release-0.7.3/api29-device.txt`、`api29-logcat.txt`，
Android 17 同类记录使用 `api37-` 前缀。

## 覆盖边界与清理

验证使用本地视频夹具和实际触摸，不代表所有在线站点、DRM/MSE 实现或媒体接收设备。
本轮未使用低端真机、实体 DLNA 接收器或真实账号。临时验证脚本与页面、两台模拟器中的辅助程序、
旧交付 APK 及旧版验证输出均已清理；模拟器和本轮 QA 服务已停止。
仅保留最新交付 APK 和必要验证记录，原有回归脚本按本次行为调整后保留。
清理明细见 [本轮记录](validation/results/release-0.7.3/cleanup.json)；源码在 `master` 使用一个普通 commit 维护。
