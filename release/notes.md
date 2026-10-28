## 0.17.3 验证范围

被测产物：`PureBrowser-v0.17.3-release.apk`（versionCode 63），本地签名 Release。
SHA-256：`e978067c64d0e6e6fa37a5f9ed1f2b5f2d2d79906ca031ccb4731c95fa71e8c4`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

第三轮评审修复：

- **外跳 fallback**：`Prepared.Refused` 的 fallback 也先过 `ExternalNavigationGate`；
  无手势子框架或站点拒绝时不会直接导航顶层页面。
- **批量删除撤销**：移到 Activity 持有；关闭书签面板不会取消撤销；用 `updateLibrary`
  而不是会因 busy 静默返回的 `library.mutate`；删除前按 id 批量读取（分块绕开 SQLite
  参数上限），跨页选择也能恢复；批量删除/撤销都刷新当前页书签状态。
- **清空全部撤销**：事务内校验文件夹上限，撤销期间新建文件夹导致超限时整体回滚。
- **URL**：Rust 禁止非 IPv6 主机含 `[`/`]`；两端拒绝 `[1.2.3.4::]`；补 parity 用例。
- **页面文件**：`failPageFile` 统一 `computeIfAbsent` 写锁；锁被占时的清理协程保证
  失败标记不残留、锁不泄漏；发布后到达的失败清空 `destinationUri` 并 FAILED 收尾。
- **阅读模式**：用 `window.__pureReaderReady` 检查我们注入的 Readability；异步读取和最终
  注入都校验 URL，导航后不会把控制器注入新文档。保留原 `<body>` 树并隐藏旧子节点，
  iframe 内部文档/草稿不因进出阅读模式重建。
- **无障碍/手势**：禁用行 `mergeDescendants = true`；Snackbar offset 随当前提示重置，
  不会误关替换后的提示。
- **卫生**：清理未使用 import；CHANGELOG 补 Toast 例外；THIRD_PARTY_NOTICES 固定
  Readability commit `ab4027a…` 与 SHA-256；根目录只保留最新 APK。

验证：

- 682 个 JVM 单测全部通过；`lintDebug` 通过；`assembleRelease` 与签名校验通过。
- 既有 API 37 回归仍有效：阅读模式输入/监听器保留、Trusted Types 页面不被污染、
  站点设置 20dp 对齐、Snackbar 滑动关闭。
- 本轮新增单测：外跳 web intent 留在当前 WebView、Rust 放行的方括号主机被跳过、
  孤儿书签导出、文件夹上限回滚、批量恢复查询。

未覆盖：

- 外跳 fallback 的 gate 分支没有专门 UI/跨框架测试，只有代码路径与既有 gate 单测；
- 页面文件失败与完成收尾的并发窗口没有专门并发复现测试；
- 批量删除/清空全部撤销的 UI 流程没有逐项截图；
- 平板/折叠屏 P1 自适应仍未实现；Play 的 `QUERY_ALL_PACKAGES` 申报仍未验证；
- 本地 `main` 落后、远端 tag/Release 未创建，属于发布操作，未擅自执行。
