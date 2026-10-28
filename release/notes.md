## 0.17.5 验证范围

被测产物：`PureBrowser-v0.17.5-release.apk`（versionCode 65），本地签名 Release。
SHA-256：`1a27f32d83a78e9f308602a1b488f42ff4f20be2f4df25da12ef5da09c956b69`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

0.17.5 是自 0.15.0 以来的稳定版汇总，正文按纯文本写，避免应用内更新提示直接显示
Markdown 标记。主要包含：

- 阅读模式、打印 / 另存 PDF、站点级 UA、秘塔 AI 搜索；
- Material 3 Snackbar、拖拽把手、统一 ListItem、语义状态色；
- 任意 scheme 外部应用跳转与目标应用名、外跳 fallback gate、URL 校验；
- 批量删除/清空全部撤销、页面文件失败收尾、过滤列表只校验一次；
- 阅读模式 iframe 树保留、Trusted Types 清理、刷新代次、无障碍与 Snackbar 细节。

验证：

- 683 个 JVM 单测全部通过；`lintDebug` 通过；`assembleRelease` 与签名校验通过。
- 既有 API 37 回归：阅读模式输入 `hello` / `clicked 1` 进出后保留并变为 `clicked 2`；
  iframe token 前后相同；Trusted Types 页面保持原白底；站点设置 20dp 对齐；
  Snackbar 可滑走；同 URL 刷新的阅读模式代次单测通过。
- 更新说明纯文本已人工检查，不含粗体、列表符号、反引号或链接等 Markdown 标记；
  `release/prepare.py` 生成的 `releaseNotes` 与应用更新面板一致。

未覆盖：

- 外跳 fallback gate、页面文件失败/完成并发窗口没有专门并发 UI/压力测试；
- 批量删除/清空全部撤销的 UI 流程未逐项截图；
- 平板/折叠屏 P1 自适应未实现；Play 的 QUERY_ALL_PACKAGES 申报未验证；
- GitHub Release 与 tag 的创建待推送后执行。
