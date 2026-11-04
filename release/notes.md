## 0.19 本地测试包验证范围

被测产物：`PureBrowser-v0.19-release.apk`（versionCode 71），本地签名 Release，尚未发布。
本次修复媒体播放的来源策略、重定向 Cookie 和错误提示，版本号保持 0.19。

自动检查通过：761 项 JVM、69 项 Rust、127 项 Node、14 项发布工具测试；三种语言的 1011 条字符串一致。
Rust fmt/clippy、Android lint、Release 构建和签名校验通过；lint 无错误，保留 79 条警告和 3 条提示。
构建日志保存在 `validation/results/media-request-release-build.log`。

两台 ARM64 模拟器均以隐藏窗口运行并安装最终签名包：

- Android 14 / API 34，WebView 113.0.5672.136：普通受保护视频、跨端口重定向、离开媒体页后清除来源、跨主机 Cookie、1 GiB／接近 2 GiB 大偏移，以及无痕播放和返回。
- Android 17 / API 37，WebView 145.0.7632.218：origin／no-referrer、跨主机 Cookie、1 GiB／接近 2 GiB 大偏移，以及无痕播放和返回。

上述播放场景均检查连续 Range、拖动和刷新；跨主机检查目标域自己的会话 Cookie、GET 主导航逐跳写入的 Cookie，
以及原主机 Cookie 未被带到另一主机。大文件使用有效稀疏 MP4，在多个播放位置验证高偏移定位与解码，
不代表长时间大码率传输。详细请求、播放状态及被测 APK 校验值保存在
`validation/results/cast-referrer-emulator-5554.json` 和 `cast-referrer-emulator-5556.json`。

两台最终签名包均通过 HTTP 403、连接失败、不受信任证书和响应读取中断的错误页检查，显示对应原因，
没有信任测试证书或跳过 TLS 校验。结果及 APK 校验值保存在
`validation/results/cast-media-failure-emulator-5554.json` 和 `cast-media-failure-emulator-5556.json`。
取消及过期请求不报告错误的逻辑有单元测试覆盖；超时错误尚未做设备回归。

API 34 最终签名包还通过 `validation/cast-play-regression.py`：普通视频在前台新标签播放且保留完整查询参数，
下载确认取消不改变投屏选择，返回原网页后仍可选择资源；HLS／DASH 均保留播放和下载入口，
新标签地址完整且可返回。清单夹具只验证入口与导航，不验证分片播放。
详细结果及 APK 校验值保存在 `validation/results/cast-play.json`。

已知限制：旧 WebView 缺少 Cookie 属性接口时保守处理跨站 Cookie，手动媒体请求不转发 Partitioned Cookie；
无法获得原页面对后续跨源请求的策略时采用默认严格来源策略。
超出 WebView 流接口约 2 GiB 上限的响应区间、未知长度或压缩编码响应回退原生加载，仍可能丢失后续请求的 Referer。
来源只保留在当前内存会话；离开媒体页后清除，按历史返回且需要重新请求时及进程重启后均不恢复。

此前调试包曾在无痕返回时出现一次 WebView 原生 JNI 崩溃，根因未定位；本轮两版签名包未复现，不能视为已修复。
诊断日志保留在 `validation/results/cast-referrer-debug-crash.log`。
未验证实际 HLS／DASH 分片播放、实体 DLNA 接收器、其他鉴权方式、全部音视频编码、多 Profile 无痕隔离，
以及全屏播放器内的投屏入口；未覆盖 API 29、其他 WebView 版本、真机、平板和折叠屏。
