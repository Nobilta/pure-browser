## 0.17.1 验证范围

被测产物：`PureBrowser-v0.17.1-release.apk`（versionCode 61），本地签名 Release。
SHA-256：`320c6cf7fee99acc308c0af52d52e3ce600d6e2e784a532b4e35bf7e2d3e5bf9`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

本版修复 0.17.0 评审确认的问题：

- **构建**：`QUERY_ALL_PACKAGES` 加 `tools:ignore` 与理由，`lintDebug` 通过；CI 和
  `build-and-test.sh --release` 恢复可执行。Play 分发仍需申报该受限权限。
- **Snackbar 滑动关闭**：页面、全屏页和弹层宿主共用 `SwipeDismissSnackbarHost`，
  横向拖动超过 80dp 关闭当前提示。
- **阅读模式**：退出换回原 `<body>` 节点，保留输入值、监听器和脚本引用；跟随明暗主题；
  关键排版加 `!important`，退出时移除阅读样式。
- **IPv6**：支持 IPv4 嵌入形式（`[::ffff:192.0.2.1]`、`[2001:db8::192.0.2.1]`）；
  非法点分段和错误压缩仍拒绝。
- **站点 UA**：单行输入 + 控制字符校验；优先级说明；`opt(...) as? String` 防 JSON null；
  归一化收敛到 `normalizeUserAgent`。
- **外跳**：`SECURITY.md` 写明任意 scheme、extras 保留、确认/手势/节流与包可见性；
  应用名查询移到 IO，并在返回前校验 prompt 身份。
- **书签撤销**：批量删除、清空全部可撤销；恢复保持文件夹/顺序；文件夹已删则回根目录；
  URL 冲突原地更新；恢复失败不再假报成功。
- **计数**：删除重复的 `AtomicInteger`，只用 `StateFlow.update`，消除 reset 竞态。

验证：

- 强制重跑 JVM 单测：678 项全部通过；`./build-and-test.sh --release` 通过
  （测试 + lintDebug + assembleRelease + 签名校验）。
- 模拟器 Release/调试版实测：
  - 阅读模式进入/退出：输入框内容 `hello` 保留，按钮监听器在退出后仍能点击并累加计数；
    浅色/深色阅读排版分别验证；
  - Snackbar 横向拖动关闭；
  - 书签单条删除 → 关闭菜单 → 提示转移 → 撤销恢复；
  - 设置页内搜索引擎切换提示；
  - `lintDebug` 与 release 脚本均为绿。
- 截图：`outputs/validation/0.17.0-menu-light.png`、`0.17.0-menu-dark.png`、
  `0.17.0-settings-snackbar.png`、`0.17.0-undo-snackbar.png`、
  `0.17.1-reader-light.png`、`0.17.1-reader-dark.png`。

未覆盖：

- 平板/折叠屏 P1 自适应布局未实现；
- TalkBack 手工走查、200% 字体、横屏、底部地址栏与 Snackbar 的组合未逐项截图；
- 批量删除/清空全部的撤销只做了单测与代码路径验证，未逐项截图；
- QUERY_ALL_PACKAGES 的 Play 申报未验证，只保证本地 lint/CI。
