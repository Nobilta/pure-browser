## 0.17.4 验证范围

被测产物：`PureBrowser-v0.17.4-release.apk`（versionCode 64），本地签名 Release。
SHA-256：`ee1aa8dec521787b2268cbfb7ea71c6632f39cef29b9b5d92a855480796bdee3`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

0.17.4 修复：

- **阅读模式刷新竞态**：新增 `ReaderDocumentGeneration`，主框架每次 `onPageStarted`
  （含同 URL 刷新）递增代次；请求在异步读取后、最终注入前都校验代次和 URL。
  旧文档的待执行请求不会向刷新后的新文档注入脚本；补代次失效单测。

0.17.3 及之前的修复仍包含：

- 外跳 `Prepared.Refused` fallback 也过 `ExternalNavigationGate`；
- 批量删除撤销移到 Activity，关闭面板不丢失、忙时不静默丢弃、跨页完整恢复，
  清空全部撤销在事务内校验文件夹上限；
- Rust/Kotlin URL 校验对齐（`[`/`]`、`[1.2.3.4::]`）；
- 页面文件失败锁、标记清理、发布后失败收尾；
- 阅读模式保留原 body 树，iframe 草稿/文档不重建；Trusted Types 失败不污染页面；
- 阅读器请求使用 `__pureReaderReady` 并校验 URL；
- 禁用行语义合并、Snackbar 拖拽与当前提示绑定；
- CHANGELOG Toast 例外、import 清理、Readability commit 固定、仅保留最新 APK。

验证：

- 683 个 JVM 单测全部通过；`lintDebug` 通过；`assembleRelease` 与签名校验通过。
- 既有 API 37 回归：输入 `hello`/`clicked 1` 进出后输入保留并变为 `clicked 2`；
  iframe 内 token 前后相同；Trusted Types 页面保持原白底；站点设置 20dp 对齐；
  Snackbar 可滑走。
- 本轮新增 `ReaderDocumentGenerationTest`：同一 URL 再次 `onPageStarted` 后，旧捕获代次
  立即失效。

未覆盖：

- 外跳 fallback gate 分支、页面文件失败/完成并发窗口没有专门并发 UI/压力测试；
- 批量删除/清空全部撤销的 UI 流程未逐项截图；
- 平板/折叠屏 P1 自适应未实现；Play 的 `QUERY_ALL_PACKAGES` 申报未验证；
- 本地 `main` 落后、远端 tag/Release 未创建，属于发布操作，未擅自执行。
