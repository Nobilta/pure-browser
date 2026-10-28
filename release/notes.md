## 0.17.0 验证范围

被测产物：`PureBrowser-v0.17.0-release.apk`（versionCode 60），本地签名 Release 候选。
SHA-256：`5293d631536bf25043e21630a287936fd53053a12dea17117a598969e3d87447`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

Material 3 P0 改造（无信息架构变化）：

- 反馈：86 处应用内 Toast 汇总到一个 Snackbar 入口；底栏上方显示，带动作的停留 10 秒。
  设置/菜单等 Dialog 弹层内各有宿主，弹层关闭后同一条提示接着显示在页面；
  删除书签提供「撤销」，恢复原文件夹、创建时间与顺序（`BookmarkManager.findBookmark/
  restoreBookmark` 有单测）。
- 面板：非全屏底部面板顶部加 32×4dp 拖拽把手；28dp 圆角、scrim、下拉关闭逻辑不变。
- 列表：菜单、设置项、设置开关、单选项统一到 `BrowserListItem`（M3 `ListItem`）：
  单行 56dp / 双行 72dp、24dp 图标、`bodyLarge` 标题、`bodyMedium` 副标题。
- 语义色：`BrowserColors` 改为随明暗主题提供的 `BrowserExtendedColors`；
  浅色安全/警告对比度 6.23/6.15，深色 10.70/11.71。

验证：

- Android/JVM 单元测试 675 项全部通过；`./build-and-test.sh --quick` 通过。
- 模拟器 Release：菜单（浅色/深色）显示把手与统一行；设置全屏页内「搜索引擎切换为
  Metaso AI」的 Snackbar 正常；菜单内删除书签出现带「撤销」的 Snackbar，关闭菜单后
  提示转移到页面并仍可撤销；撤销后菜单回到「Remove bookmark」，提示「Bookmark restored」。
- 截图：`outputs/validation/0.17.0-menu-light.png`、`0.17.0-menu-dark.png`、
  `0.17.0-settings-light.png`、`0.17.0-settings-snackbar.png`、`0.17.0-undo-snackbar.png`。

未覆盖：

- 平板/折叠屏的 P1 自适应布局尚未实现，本次只在手机宽度验证；
- TalkBack 手工走查、200% 字体、横屏、底部地址栏 + Snackbar 的组合未逐项截图；
- 真实设备的下拉手势、系统返回与弹层内 Snackbar 的交互未覆盖；
- `DownloadPresenter` 的完成提示仍走系统 Toast（后台事件没有 Activity 窗口可承载 Snackbar）。
