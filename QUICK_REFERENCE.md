# Pure 浏览器快速参考

## 构建

```bash
./build-and-test.sh
```

最终文件：`PureBrowser-v0.3.1-release.apk`（arm64-v8a，Android 10+）。脚本会输出实际
大小和 SHA-256；不要引用旧文档中的固定数值。

## 安装与启动

```bash
./install_and_test.sh
# 手工
adb install -r PureBrowser-v0.3.1-release.apk
adb shell am start -n com.mybrowser/com.mybrowser.MainActivity
```

调试包的 applicationId 是 `com.mybrowser.debug`；Release 是 `com.mybrowser`。

## 手工回归

1. 启动并打开一个 HTTPS 页面。
2. 聚焦顶部地址栏，确认外侧按钮按内容显示“访问”或“搜索”。
3. 打开菜单 → 添加书签，确认标题和 URL 可编辑并能保存。
4. 新建/切换/关闭标签，再重新打开确认元数据保留。
5. 进入/退出无痕，确认退出后清理完成再回到普通页面。
6. 播放视频，使用右下角倍速入口切换速度；跨域 iframe 播放器也应立即生效。
7. 检测到媒体后确认右下角出现投屏按钮，选择页中当前流显示“正在播放”。
8. 在同一局域网有 DLNA 设备时，选择设备并观察投送结果。
9. 在设置中分别选择系统 Download 与 SAF 自定义目录，修改下载线程数并重启确认保留。
10. 下载支持 Range 的文件，验证进度、后台通知、重试、重名编号；分别测试“仅删除记录”
   与“删除记录和文件”。
11. 测试文件上传、页面内查找、全屏视频和权限取消。

## 诊断

```bash
./diagnose.sh
adb logcat -d | rg 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV'
```

若只想查看 APK 内容：

```bash
unzip -l PureBrowser-v0.3.1-release.apk | rg 'lib/|AndroidManifest.xml'
apksigner verify --verbose PureBrowser-v0.3.1-release.apk
```

## Rust 构建选项

```bash
cd rust
cargo test --all
cargo clippy --workspace --all-targets -- -D warnings
cd ..
./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug --console=plain
```

默认 native 模块是 `adblock`、`url_utils`；cache 等 legacy 模块需显式
`-Pmybrowser.includeLegacyRust=true`。

更多背景见 [README.md](./README.md) 和 [ARCHITECTURE_REVIEW.md](./ARCHITECTURE_REVIEW.md)。
