## 0.17.2 验证范围

被测产物：`PureBrowser-v0.17.2-release.apk`（versionCode 62），本地签名 Release。
SHA-256：`1f1e3ed5024da77b2cf1c1d5e0ed1499ab77df49b54afa765935db5fe9c8a591`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

第二轮评审修复：

- **导入设置**：未列出的站点重置 `userAgent`，不会再留下无法被默认值清理的覆盖。
- **书签导出**：孤儿 `folder_id` 在导出时回到根目录，导出不再整份失败；撤销仍校验文件夹。
- **页面文件失败竞态**：失败标记先记录，并在 `onPageFileFinished` 的写锁内读取；
  泵协程不再提前消费标记，也不会把失败任务发布为 COMPLETED。
- **URL 校验一致性**：Kotlin 去掉 trim 放宽，拒绝首尾空白与 `.`/`..`/`%` 主机；
  Rust 同步禁止字符和 IPv4 嵌入 IPv6，书签导入不会因 Rust 放行、Kotlin 拒绝而整份失败。
- **外跳**：确认框前先 `prepare()`，被拒目标不弹窗；`intent://` 内层 http(s) 留在当前
  WebView；应用名取 Application label；应用名查询仍在 IO。
- **阅读模式**：先构建正文再改样式，Trusted Types 拒绝时干净退出；临时禁用站点样式并在
  退出恢复；Readability 首次在 IO 读取并缓存，注入标记后只发送小型控制器。
- **Material 3 细节**：恢复禁用行 `disabled`/Role/ToggleableState 语义；站点设置开关
  0 内部边距，消除 36dp 双缩进；Snackbar 依据实际底栏高度定位并避让查找栏、地址栏收起、
  FAB、翻译条和元素选择条。
- **过滤列表**：初始化只校验一次并复用规则数，不再重复校验 MB 级 payload。

验证：

- 强制重跑 JVM 单测：680 项全部通过；`lintDebug` 通过；`assembleRelease` 与签名校验通过。
- 模拟器实测：
  - 阅读模式输入 `hello`、计数 `clicked 1`，进出后输入保留、按钮继续累加到 `clicked 2`；
  - Trusted Types 页面提示不支持，页面仍是原来的白底，没有残留阅读样式；
  - 站点设置开关与说明文本左边缘对齐；
  - Snackbar 横向滑动关闭；
  - 设置导入对未列出站点 UA 的重置、书签孤儿导出、外跳 intent 测试均有单测覆盖。
- 新增截图：`outputs/validation/0.17.2-site-settings.png`、
  `0.17.2-reader-trusted-types.png`；阅读浅色/深色见 0.17.1 截图。

未覆盖：

- 页面文件失败竞态只有代码路径和既有单测覆盖，没有专门的并发复现测试；
- 平板/折叠屏 P1 自适应布局未实现；
- TalkBack 手工走查、200% 字体、横屏、Snackbar 与所有悬浮条组合未逐项截图；
- `QUERY_ALL_PACKAGES` 仍为 lint 抑制，Play 分发需申报。
