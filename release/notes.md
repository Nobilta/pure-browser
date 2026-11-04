## 0.19 本地测试包验证范围

被测产物：`PureBrowser-v0.19-release.apk`（versionCode 71），本地签名 Release；本次保留 0.19 版本号，
包含投屏列表新标签播放的 Referer 修复，所有资源继续提供播放／下载入口，尚未发布。

自动检查通过：753 项 JVM、69 项 Rust、127 项 Node、14 项发布工具测试；三种语言的 1007 条字符串一致。
Rust fmt/clippy、Android lint、Release 构建和签名校验通过；lint 无错误，保留 79 条警告和 3 条提示。

设备：隐藏窗口运行的 Pixel 7 ARM64 模拟器，Android 14 / API 34，WebView 113.0.5672.136；签名包安装成功。
`validation/cast-referrer-regression.py` 检查普通／无痕回退模式下的受保护 MP4 播放、连续 Range、拖动和刷新，
服务端实际收到正确 Referer 与当前会话 Cookie；普通模式还检查跨端口重定向及离开媒体页面后不再发送旧来源。
普通／无痕返回原网页通过，无痕返回另有一次独立的签名包复查通过。
结果及被测 APK 校验值保存在 `validation/results/cast-referrer.json`。

`validation/cast-play-regression.py` 从投屏悬浮按钮打开列表，核对普通视频、HLS、DASH 均显示播放／下载，
下载确认取消后投屏选择不变；普通视频在前台新标签播放并保留完整查询参数，系统返回原网页后仍可选择投屏资源。
HLS、DASH 分别检查下载确认、新标签完整地址和返回原网页；清单夹具只验证入口和导航，不验证分片播放。
详细结果及被测 APK 校验值保存在 `validation/results/cast-play.json`。

调试包检查中曾在无痕返回时出现一次 WebView 原生 JNI 崩溃，根因未定位；上述签名包复查未复现，不能视为已修复。
保留诊断日志 `validation/results/cast-referrer-debug-crash.log`。

已知限制：超出 WebView 流接口约 2 GiB 上限的响应区间、未知长度或压缩编码响应回退原生加载，
这些情形仍可能丢失后续请求的 Referer。来源只保留在当前内存会话，进程重启后不恢复。
未验证实际 HLS／DASH 分片播放、实体 DLNA 接收器、其他鉴权方式、全部音视频编码、
多 Profile 无痕隔离和全屏播放器内的投屏入口；未覆盖其他 Android / WebView 版本、真机、平板和折叠屏。
