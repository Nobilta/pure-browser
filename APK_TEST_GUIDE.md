# Pure 浏览器 APK 测试指南

## 安装

Release APK 由 `./build-and-test.sh` 生成到项目根目录：

```bash
adb install -r PureBrowser-v0.1.0-release.apk
adb shell am start -n com.mybrowser/com.mybrowser.MainActivity
```

安装前确认设备为 Android 14+ 且 ABI 为 arm64-v8a。调试 x86_64 模拟器应另行构建：

```bash
./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug --console=plain
```

## 功能清单

### 地址栏与导航

- 未聚焦时确认安全指示和刷新/停止按钮可用。
- 聚焦时确认“访问/搜索”按钮位于输入框外侧，输入 URL/关键词时标签正确。
- 键盘 Go、按钮点击、前进/后退、主页和 `target=_blank` 均可工作。

### 书签与历史

- 菜单 → 添加书签，确认默认标题/URL来自当前页。
- 修改标题和 URL 后保存；在书签列表打开、删除，并确认当前页状态更新。
- 访问页面后检查历史记录；清除数据后确认历史为空、书签仍保留。

### 标签和无痕

- 新建、切换、关闭标签；旋转/重启后检查 URL/标题元数据。
- 进入无痕并访问带 Cookie 的页面，退出后确认普通标签按界面提示完成清理。
- 在 provider 不支持多 profile 的设备上，确认 UI 仍说明这是退出清理降级，而非并行隔离。

### 下载、上传和页面功能

- 在设置 → 下载设置中选择系统 Download 与 SAF 自定义目录，并验证 1–16 线程设置跨重启保留。
- 下载支持 Range 的 HTTP(S) 文件，检查分段数、通知、后台进度、重试、打开和重名编号；
  再用不支持 Range 的服务确认自动回退单线程。
- 对已完成项目分别选择“仅删除记录”和“删除记录和文件”，确认本地文件是否按选择保留。
- 使用 `<input type=file>` 选择单个/多个文件；取消选择后页面不应卡住。
- 测试页面内查找、JavaScript alert/confirm/prompt、全屏视频、摄像头/麦克风和地理位置
  权限的允许/拒绝路径。

### 媒体与投屏

1. 打开一个包含视频的页面并手动播放目标视频。
2. 等待右下角悬浮投屏按钮出现；没有可识别媒体时按钮应隐藏。
3. 点击按钮，确认候选列表只把当前播放流标注为“正在播放”，其它清晰度仍可手选。
4. 与 DLNA renderer 同网时搜索设备并投送；没有实体设备时只记录候选/发现 UI 结果，
   不宣称实际播放成功。

## 日志与验收

```bash
./diagnose.sh
adb logcat -d | rg 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV'
```

验收要求：自动测试、lint、Rust clippy 全通过；APK 签名通过；启动和上述核心交互无崩溃。
结果写入 [EMULATOR_TEST_REPORT.md](./EMULATOR_TEST_REPORT.md)。
