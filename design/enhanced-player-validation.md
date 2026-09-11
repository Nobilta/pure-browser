# 增强播放器接管与验证

日期：2026-09-11；版本：0.6.0（versionCode 10）。

本次在续接任务已有的 0.6.0 代码上扩大增强全屏覆盖：视频进入全屏后，网站自带控件或自定义容器不再阻止接管。
控制的是网页已经加载的视频元素，保留来源、播放进度、倍速和音量；网页内播放仍由网站入口触发。
实现细节见 [播放器方案](video-playback-and-casting.md)。

## 安装包与自动检查

- 安装包：`PureBrowser-v0.6.0-release.apk`，6,321,255 bytes，arm64-v8a，Android 10+。
- SHA-256：`9452efe254164ccdf7ad40151e40513ad150ed1d0f1984fad1a6b14e15031743`。
- v2 签名通过，与 0.5.2 证书一致；三个模拟器均实际使用 `adb install -r` 安装此包。
- APK 中的 `assets/playback-probe.js` 与当前源文件逐字节一致。
- 完整 `build-and-test.sh` 通过：329 项 Android 单元测试、58 项 Rust 测试、54 项 Node 测试、661 项三语言资源检查，以及 Rust fmt/clippy、Android lint、R8 和签名检查。
- 本次没有增加播放器或解码依赖。

本地证据：[构建结果](../validation/results/enhanced-player/build.json)、[完整日志](../validation/results/enhanced-player/build.log)、
[交付清单](../validation/results/enhanced-player/delivery.json)。生成证据和 APK 留在 Git 外。

## 模拟器实测

所有阶段均绑定上方 APK SHA-256；通过真实触摸操作与网页上报的播放状态交叉验证。

| Android / API | WebView | 结果 |
|---|---|---|
| Android 10 / 29 | 91.0.4472.114 | 5 阶段通过：标准、自定义容器、容器 Blob、CSP 回退、旧跨域 Provider 回退 |
| Android 14 / 34 | 113.0.5672.136 | 5 阶段通过：标准、自定义容器、容器 Blob、跨域容器增强、CSP 回退 |
| Android 17 / 37 | 145.0.7632.218 | 9 阶段通过：系统媒体、标准、Blob、容器 Blob、跨域容器、CSP、跨域视频、方形视频、跨域弹窗；另有直接来源自定义容器专项通过 |

套件原始记录：[API 29](../validation/results/api29-enhanced-player-suite.json)、
[API 34](../validation/results/api34-enhanced-player-suite.json)、
[API 37](../validation/results/api37-enhanced-player-suite.json)、
[API 37 自定义容器](../validation/results/enhanced-player/custom-api37.json)。

具体检查包括：

- 没有 `controls` 的网站视频自动进入增强模式；存在嵌套、transform、裁剪和最大尺寸限制时，画面仍按比例铺满全屏。
- 原节点和播放地址保持不变，已播放进度不归零；暂停后仍控制相同视频，快进和倍速生效。
- 网站原有按钮、伪元素及之后动态添加的控制层隐藏；切回“网页控件”后恢复可点击，原始 `controls=false` 不被改成 true。
- 反复进入、退出和模式切换，临时属性完整清除，方向恢复；标准视频还验证亮度、音量、双击、长按加速、锁定和系统返回。
- 样式被 CSP 阻止时回到网页播放器；旧 WebView 无法访问的跨域视频保持网页播放与正常退出。
- 现代 WebView 的跨域容器和连续新开跨域弹窗能使用增强控件。
- Android 17 的 PiP、系统媒体暂停及显式后台播放策略继续生效。

协议测试还覆盖无扩展名签名来源、多个视频的目标固定、动态布局失效、样式删除、原有属性恢复和过期命令隔离。

## 重跑记录与边界

套件保留全部 `priorAttempts`，最终完成状态没有抹去早期失败。失败后修正的是以下夹具/输入假设，安装 APK 没有变化：

1. 沉浸全屏的第一次边缘滑动可能只显示系统栏。测试最多执行两次滑动，观察到解锁即停止，验证实际返回回调。
2. Blob 可控并不意味着存在可投送的 HTTP 地址。标准直链仍要求投屏入口；Blob 允许没有候选，同时继续验证增强控制。
3. WebView 91 的全屏可访问性节点可能陈旧。网站按钮改用夹具实时 DOM 几何进行真实触摸，并验证实际播放/暂停回执。

本次范围是播放器与相关媒体生命周期。未重跑此前系统集成的完整功能矩阵；历史阶段记录仍保留。
本轮没有使用真实账号、实体投屏设备或低端真机，也没有逐站验证 YouTube/JRS 的生产播放器、DRM、直播 HLS/DASH 和 CDN 鉴权策略。
Blob 夹具验证控制交接与原节点保留，不等同于覆盖所有 MSE 流媒体加载实现。
封闭 Shadow DOM、Canvas 播放器、无法访问的 iframe 及无法隔离的布局仍可能使用网站控件；清晰度、弹幕和 DOM 字幕可切回网页操作。

## 复现

启动 `validation/qa-server.py`，安装同一签名 APK 和 UI 辅助程序，将模拟器的 8875/8876 端口 reverse 到本机，然后运行：

```bash
python3 validation/run-regressions.py --serial emulator-5554 \
  --apk PureBrowser-v0.6.0-release.apk --label enhanced-player \
  --stages video-standard video-custom video-custom-blob video-custom-cross video-custom-csp
```

现代系统的扩展验证可增加 `system-media video-blob video-cross video-square video-popup-cross`。
每台模拟器串行执行；只有 APK、AVD 与阶段选择完全一致时才使用 `--resume`。
