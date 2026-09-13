# 增强播放器接管与验证

当前版本：0.7.3。安装包、自动检查及实际设备覆盖以 [README](../README.md) 和
[模拟器报告](../EMULATOR_TEST_REPORT.md) 为准。

## 接管原则

仅在全屏播放时尝试接管已加载的原始 `<video>`，不复制节点、不改写来源、不另开解码器。
网站设置按完整 origin 保存增强播放选择；未设置时继承浏览器默认值，私密修改不影响普通设置。
全屏不再提供模式切换和快进/后退按钮，保留双击、滑动、长按、旋转及锁定。
非全屏页面始终使用原始网页控件，旧内嵌播放器脚本及专用夹具已删除。

## 自动回归内容

`video-regression.py` 用真实触摸与网页遥测检查标准视频、自定义容器、Blob、跨域及 CSP 全屏路径：

- 非全屏时原 `controls` 属性不变，不存在全屏临时标记。
- 全屏接管控制原视频目标，保持来源、进度、静音状态和画面比例；退出恢复原始控件及 DOM 属性。
- CSP、布局失效或不可访问的跨域 frame 保留网页播放器。
- 标准与自定义播放器在网站设置关闭增强播放后使用网页全屏控件，进程重启后选择仍保留；重新开启仅在全屏接管。
- 手势跳转、亮度/音量/进度调节、长按临时加速、锁定返回、旋转、控件自动隐藏及倍速/投屏弹层关闭。

`system-media-regression.py` 检查 PiP、系统媒体暂停和显式后台播放；私密生命周期阶段检查无痕隐藏暂停。
`media-lifecycle-regression.py` 另核对视频结束/删除/卸载/隐藏/替换、页面导航、关闭标签和关闭 PiP 后，
系统 session、通知和服务退出，同时浏览器进程存活；检查小窗实际画面和跨域 frame 独立失效。
Node 协议测试覆盖签名来源、多个视频目标固定、布局失效、样式删除、原始属性恢复及过期命令隔离。
验证工具不为 Release 打开 WebView 调试接口，也不引入播放器或解码依赖。

## 复现

启动 `validation/qa-server.py`，安装签名 APK 和 UI 辅助程序，将 8875/8876 端口 reverse 到本机，然后运行：

```bash
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/run-regressions.py --serial emulator-5554 \
  --apk PureBrowser-v0.7.3-release.apk --label player-073 \
  --stages system-media video-standard video-custom video-custom-blob video-custom-cross video-custom-csp
```

只在专用模拟器串行执行，构建时停止模拟器。只有 APK、AVD 与阶段选择完全一致时可以 `--resume`。
结果 JSON 绑定安装包 SHA-256，记录每阶段日志并保留失败尝试；不能把旧版本结果计入新交付包。

## 平台与网站边界

逐 frame 消息和 document-start 注入能力由 WebView Provider 决定，不能仅按 Android 版本判断。
现代 Provider 可控制可见跨域视频；旧 Provider 只处理主文档和可访问的同源 frame，不可访问的跨域视频使用网页控件。
视频未加载元数据时先等待网站正常加载；接管不会绕过网站的登录、付费或 DRM 条件。

本机 Blob 夹具验证控制交接和节点保留，不等同于覆盖所有 MSE、HLS/DASH、直播或 CDN 鉴权实现。
封闭 Shadow DOM、Canvas 播放器、无法访问的 iframe 和无法隔离的布局仍可能无法接管。
未使用真实账号、实体 DLNA 接收器或低端真机，也未逐站验收所有生产视频服务。
