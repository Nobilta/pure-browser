# Pure 浏览器项目摘要

Pure 浏览器采用 Kotlin + Jetpack Compose + Android WebView，并在两个计算路径使用
Rust JNI：广告规则匹配和 URL/搜索分类。平台能力（WebView 生命周期、
SQLite、下载网络/存储编排、权限和 UI）保持 Kotlin/Android 原生实现。

## 当前目录

详见 [README.md](./README.md) 的目录树；分层审查见 [ARCHITECTURE_REVIEW.md](./ARCHITECTURE_REVIEW.md)。

## 当前功能

浏览/导航、多标签、地址栏访问/搜索按钮、书签编辑、历史、可配置目录和线程的下载、无痕、
广告过滤、页面内查找、开发者工具、媒体检测和 DLNA 投屏选择均已接通。投屏候选页会标记
当前实际播放流。

## 构建与验证

```bash
./build-and-test.sh
```

该命令运行 Rust/Android 测试、lint、Release 构建和签名检查，并生成
`PureBrowser-v0.3.1-release.apk`。当前 APK 大小和 hash 以 README 及脚本输出为准。

## 未纳入当前产品的规划

同步、密码管理、阅读模式、数据导入导出和跨设备标签同步仍属于产品路线图；早期文档中
关于 FTS5、Rust database/text_search/json_parser 或固定性能倍数的描述不再有效。
