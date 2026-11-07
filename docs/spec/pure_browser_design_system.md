# Pure Browser 设计系统与新页面开发规范 (Pure-White Edition)

本规范定义了 `pure-browser` 的完整视觉语言、自包含 Token 词典、人体工学布局与全局高复用基础组件。后续重构或新增任何页面、抽屉面板（Sheet）、沉浸式播放器或弹窗（Dialog）时，以此作为唯一设计基准。

---

## 一、核心设计原则（6 条黄金准则）

1. **「底座重度封装，业务一行调用」的可维护架构（Encapsulated Primitives, One-Line Reuse）**
   * **拒绝粗陋的 M3 默认堆砌**：M3 原生部分控件（如锁死 `56.dp` 高的 `OutlinedTextField`、线框噪点过重的 `OutlinedButton`、裸露无边界的次级 `TextButton`、默认灰色 `12.dp` `Card`）无法满足清爽度与无障碍要求。
   * **复杂度全部下沉至 5 个全局基础组件**（[`BrowserGroupCard`](../../app/src/main/java/com/mybrowser/ui/components/BrowserGroupCard.kt) / [`SettingsGroupCard`](../../app/src/main/java/com/mybrowser/ui/settings/SettingsComponents.kt)、[`BrowserTileCard`](../../app/src/main/java/com/mybrowser/ui/components/BrowserTileCard.kt)、[`BrowserActionPill`](../../app/src/main/java/com/mybrowser/ui/components/BrowserActionPill.kt)、[`LibrarySearchField`](../../app/src/main/java/com/mybrowser/ui/shell/BrowserActions.kt)、[`ThumbZoneLibrarySheet`](../../app/src/main/java/com/mybrowser/ui/components/ThumbZoneLibrarySheet.kt) 及 [`BrowserListItem`](../../app/src/main/java/com/mybrowser/ui/components/BrowserListItem.kt) / [`BrowserSheetHeader`](../../app/src/main/java/com/mybrowser/ui/shell/BrowserActions.kt)）：在基础组件内部**手写封死全部视觉、状态生命周期与无障碍细节**（三层前景色映射、`Role.Switch` 与 `Role.Button` 分流、`0.38f` 禁用降级、紧凑胶囊内边距、`Error > Loading > Empty > Content` 状态分支）。
   * **业务页面零手写样式**：业务页面严禁自行拼装 `OutlinedButton` / 裸 `Card`、严禁手写档位外 `RoundedCornerShape(xx.dp)` 或随意 `.copy(alpha = ...)`，一律传业务参数一行调用基础组件。
2. **微边纯白分层与雪雾交互槽法则（Pure-White Canvas, Porcelain Hairline Cards & Snow-Mist Wells）**
   * [`Theme.kt`](../../app/src/main/java/com/mybrowser/ui/theme/Theme.kt) 全局设置 `surfaceTint = Color.Transparent`，彻底禁用 M3 自动色调叠加。
   * **4 层物理表面角色**：
     * **通透纯白画布与面板底板（Canvas & Sheet Base，`surface` / `background` / `surfaceContainer`）**：浅色模式**全页面、全抽屉、顶底导航栏统一为 `100%` 纯白 `#FFFFFF`**（深色 `#0E131B` ~ `#131924`）。顶置地址栏与底置导航栏与网页交界处必须绘制 `0.5.dp` `outlineVariant.copy(alpha = 0.45f)` 细分割线，杜绝白栏与白网页粘连。
     * **微边纯白结构卡（Porcelain Hairline Card，`surfaceContainerLow` + `1.dp` `BrowserColors.cardBorder`）**：设置分组卡、站点身份卡、未选中标签卡、订阅/脚本项与弹窗表面内部同样保持纯白 `#FFFFFF`（深色 `#1B2332`），通过 **`1.dp` `#DCE3EC`（深色 `#2C3A50`）瓷白微边**清晰界定分组边界，消除灰底压迫感且绝不产生“白叠白隐形”。全屏错误诊断页（[`PageRecovery`](../../app/src/main/java/com/mybrowser/ui/shell/NavigationRecoveryUi.kt)）与主页快捷图标区（[`HomeDashboard`](../../app/src/main/java/com/mybrowser/ui/home/HomeDashboard.kt)）直接去框融合于纯白画布，拒绝“盒套盒”。
     * **雪雾交互胶囊与紧凑磁贴（Snow-Mist Pill & Tile，`surfaceContainerHigh`）**：浅色 `#EDF2F8` / 深色 `#253044`，无描边轻盈浅槽，专用于地址栏胶囊、抽屉顶部搜索框、主页快捷图标底座、菜单资料库/功能磁贴/底栏块与次级胶囊按钮（`BrowserActionPill`）。输入框聚焦时底色提亮为 `surfaceContainerLow`（`#FFFFFF`）并叠加 `1.5.dp` `primary` 描边。
     * **内嵌代码与诊断凹槽（Recessed Well，`BrowserColors.recessedWell`）**：浅色 `#EDF2F8` / 深色 `#090D13`，专用于报错页 URL/错误详情槽、控制台/源码/存储日志槽、二维码非相机占位卡与证书详情凹槽。
   * **全局浮层与阴影纪律**：
     * **模态抽屉 / 弹窗（Sheet / Dialog）**：`0.dp` 阴影，靠背后 `32%` 黑色遮罩（`scrim`）与 `1.dp` `BrowserColors.cardBorder` 分离。
     * **底部提示条（Snackbar）**：`0.dp` 阴影，浅色采用 `#1B2332` 深藏青、深色采用 `#F8FAFC` 高反差浮层。
     * **沉浸式暗场视频播放器例外（Dark Cinema Overlay）**：全屏视频增强控制层（[`PlayerControls`](../../app/src/main/java/com/mybrowser/media/PlayerControls.kt)）固定运行于 `ThemeMode.DARK`，其缓冲指示器与手势 HUD **严禁使用刺眼亮白 `inverseSurface`**，必须使用暗场 `surfaceContainerLow` + `1.dp` `BrowserColors.cardBorder` 胶囊，保护暗光观影视觉舒适度。
     * **非模态浮层例外**：① 无遮罩的弹出菜单（统一使用 [`BrowserDropdownMenu`](../../app/src/main/java/com/mybrowser/ui/shell/BrowserActions.kt) / `Tooltip` / 浮动拾取条 / 浮动翻译胶囊）采用 `surfaceContainerLow` + `1.dp` `BrowserColors.cardBorder` + `2.dp ~ 4.dp` 浮层阴影，杜绝纯白底板上无遮罩浮层融边；② 手指长按拖拽项允许 `8.dp` 阴影。
3. **石墨单色主导 + 海蓝克制点缀（Monochrome-First, Ocean-Blue Accent Discipline）**
   * **常规态去蓝化**：分组小标题、菜单常规态图标、快捷方式「添加」图标、次级胶囊图标一律采用冷静石墨灰（`onSurfaceVariant`，浅色 `#526075` / 深色 `#94A3B8`），彻底消除全屏碎蓝噪点。
   * **主题海蓝（`#1D5BD6` / `#8AB4F8`）与冰透浅蓝激活底（`#DCE9FF` / `#283A56`）仅出现在 4 类高价值锚点**：① 主页标题徽点与搜索放大镜锚点；② 输入框聚焦环与网页加载进度条；③ 正在开启的开关/选中标签/活跃功能磁贴（`secondaryContainer` 填充 + `1.dp` `primary` 描边）；④ 页面唯一主操作实心胶囊按钮（`Button`）。
4. **拇指区高频动作优先 + 紧凑浏览器壳层 + 键盘防挤压（Ergonomics & Slim Chrome）**
   * **纤薄壳层尺度**：地址栏行高 **`48.dp`**（内部胶囊高 **`40.dp`**），底部 5 键主工具栏高 **`48.dp`**，将最大可视面积归还网页内容。
   * **高频正向操作沉底与对称双按钮**：并排次级动作（如报错页 `[回到首页] · [网站设置]`、扫码页 `[选择图片] · [手电筒]`）必须使用 `1:1` 等宽对称 [`BrowserActionPill`](../../app/src/main/java/com/mybrowser/ui/components/BrowserActionPill.kt)，严禁混搭 `OutlinedButton`、`FilledTonalButton` 与裸 `TextButton`。
   * **中断性操作防误触错位**：主菜单底栏固定为 `16.dp` 圆角三等分块 **`[退出] · [更多工具] · [设置]`**，让最右下角承接安全的「设置」；「退出」采用雪雾灰底座 + `error` 红色图标/文字。
   * **搜索框置顶防键盘挤压**：抽屉面板内的搜索输入框固定在**面板顶部 Header 下方**，严禁放在半屏抽屉最底部。
5. **开关与动作语义分流 + 多重视觉冗余（Switch vs Action Semantics）**
   * [`BrowserTileCard`](../../app/src/main/java/com/mybrowser/ui/components/BrowserTileCard.kt) 与 [`BrowserActionPill`](../../app/src/main/java/com/mybrowser/ui/components/BrowserActionPill.kt) 均通过 `isToggle: Boolean` 严格区分无障碍与视觉语义：
     * **布尔开关（`isToggle = true`）**：绑定 `Modifier.toggleable(..., role = Role.Switch)`（如无痕、桌面版、阅读模式、广告拦截、手电筒开关）；开启态采用 `secondaryContainer` 填充 + `1.dp` `primary` 描边 + `primary` 图标（若同时处于禁用态则容器、描边与前景同步降级至 `0.38f` 透明度并保留 `1.dp` 结构环），绝不依赖单一颜色传递状态。
     * **一次性动作或面板启动器（`isToggle = false`）**：绑定 `Modifier.clickable(..., role = Role.Button)`；硬件暂不可用（如相机未授权时的手电筒槽位）优先保持 `0.38f` 禁用占位而非隐藏整个按钮，防止授权瞬间布局跳变。
6. **层级导航头契约（Root Dismiss vs Child Back）**
   * **顶层独立半屏抽屉**（栈深 $= 1$，如 `TabsSheet`）：`BrowserSheetHeader` 传入 **`onDismiss`**（渲染右侧 `×` 关闭按钮）。
   * **从父面板压栈进入的子页面或全屏工具页**（如 `SettingsSheet`、`BookmarksSheet`、`QrScannerSheet`）：传入 **`onBack`**（渲染左侧 `←` 返回箭头，优先回退页内子状态，根节点时弹栈）。

---

## 二、完整色彩与透明度 Token 词典（Color & Alpha Tokens）

业务组件严禁硬编码 Hex 色值或随意拼凑透明度，必须严格映射以下 Token：

### 1. 表面与背景层 Token（Surface Roles）

| 语义角色 | Compose Token | 浅色值 (Light) | 深色值 (Dark) | 适用场景与边界规则 |
| :--- | :--- | :--- | :--- | :--- |
| **底层页面画布** | `surface` / `background` | **`#FFFFFF`** | `#0E131B` | 主页、报错页、全屏抽屉底层纯白背景（`values/themes.xml` 启动底色同步 `#FFFFFF`） |
| **半屏抽屉底板 / 顶底工具栏** | `surfaceContainer` | **`#FFFFFF`** | `#131924` | `BrowserBottomSheet` 底板、顶/底地址栏与工具栏（网页交界处配 `0.5.dp` 分割线） |
| **微边纯白结构卡片** | `surfaceContainerLow` | **`#FFFFFF`** | **`#1B2332`** | 设置分组卡、站点身份卡、标签卡、弹窗（必配 `1.dp` `BrowserColors.cardBorder`） |
| **卡片瓷白微边** | `BrowserColors.cardBorder` | **`#DCE3EC`** | **`#2C3A50`** | 所有纯白结构卡片、浮动面板与暗场视频浮层的 `1.dp` 物理边界线 |
| **内嵌代码与诊断凹槽** | `BrowserColors.recessedWell` | **`#EDF2F8`** | **`#090D13`** | 代码块、报错页 URL 槽、缩略图底槽、扫码占位槽、证书详情槽 |
| **雪雾交互胶囊与磁贴底座** | `surfaceContainerHigh` | **`#EDF2F8`** | **`#253044`** | 地址栏胶囊、搜索框、快捷图标槽、菜单磁贴、`BrowserActionPill` 次级胶囊 |
| **深层徽章 / 按压态底座** | `surfaceContainerHighest` | `#E8EDF5` | `#2F3C54` | 深层按压反馈或次级徽章底座 |
| **开关/磁贴冰透激活填充** | `secondaryContainer` | **`#DCE9FF`** | `#283A56` | `BrowserTileCard` / `BrowserActionPill` 激活态、无痕徽章、阅读模式胶囊 |
| **主操作填充 / 选中高亮** | `primary` | **`#1D5BD6`** | `#8AB4F8` | 主按钮、聚焦描边、激活态 `1.dp` 描边、选中标签卡 `2.dp` 描边 |
| **主要容器强调色** | `primaryContainer` | `#D8E6FF` | `#004397` | 强提示横幅或搜索建议分类底色 |
| **危险 / 删除 / 警告容器** | `errorContainer` | `#FEE2E2` | `#7F1D1D` | 滑动删除背景、异常警告图标底座 |
| **危险 / 退出强调色** | `error` | `#DC2626` | `#F87171` | 菜单「退出」图标与文字、破坏性文本行、SSL 证书错误图标 |
| **反色浮层 (仅限 Snackbar)** | `inverseSurface` | `#1B2332` | `#F8FAFC` | 仅用于页面级 `Snackbar`（严禁用于暗场视频缓冲或手势 HUD） |
| **表面染色屏蔽** | `surfaceTint` | `Transparent` | `Transparent` | 强制透明，杜绝任何 M3 原生组件产生色调叠加 |

### 2. 前景内容、边框与浏览器扩展语义色（Content, Outline & Semantic Roles）

| 语义角色 | Compose Token | 浅色值 (Light) | 深色值 (Dark) | 对比度与适用场景 |
| :--- | :--- | :--- | :--- | :--- |
| **主要正文与标题** | `onSurface` / `onBackground` | **`#0F172A`** | `#F1F5F9` | 卡片主标题、正文、输入文本（纯白对比度 `17.8:1`，雪雾槽 `16.3:1`） |
| **次要文字与默认图标** | `onSurfaceVariant` | **`#526075`** | `#94A3B8` | 分组小标题、副标题、域名、所有未激活常规图标（纯白 `6.35:1`，雪雾槽 `5.81:1`） |
| **主按钮前景** | `onPrimary` | `#FFFFFF` | `#002E6B` | `primary` 实心按钮上的文字与图标（`5.86:1`） |
| **激活胶囊/开关前景** | `onSecondaryContainer` | `#001B3E` | `#D6E3FF` | `secondaryContainer` 激活态上的主文字（`14.9:1`），激活态图标配 `primary` |
| **危险容器前景** | `onErrorContainer` | `#7F1D1D` | `#FECACA` | `errorContainer` 上的文字与图标 |
| **表单控件外框线** | `outline` | `#6E7C91` | `#64748B` | 未选中的复选框/单选框描边（对比度 `>= 3.5:1`） |
| **微弱分割线基色** | `outlineVariant` | `#CBD5E1` | `#2B374B` | 配合 `0.45f` 透明度用于卡片内行间分割线与工具栏边界线 |
| **模态背景遮罩基色** | `scrim` | `#000000` | `#000000` | 配合 `BrowserMotion.SCRIM_ALPHA` (`0.32f`) 用于抽屉/弹窗背景遮罩 |
| **连接已加密色 (HTTPS)** | `BrowserColors.secure` | `#146C2E` | `#7DD88F` | 加密锁图标、有效证书状态标识 |
| **连接未加密 / 警告色** | `BrowserColors.warning` | `#7A5900` | `#F2C94C` | HTTP 明文警告、性能/权限降级提醒 |

### 3. 全局唯一允许的 5 个受控透明度常量（Controlled Alpha Tokens）

| 辅助用途 | 标准写法 | 透明度值 | 说明 |
| :--- | :--- | :--- | :--- |
| **禁用态文字 / 图标** | `contentColor.copy(alpha = 0.38f)` | `38%` | 所有 `enabled == false` 时的文本与图标（封装在基础组件内） |
| **禁用态实心容器** | `onSurface.copy(alpha = 0.12f)` | `12%` | 禁用态实心按钮底座 |
| **卡片内分割线 / 壳层边界线** | `outlineVariant.copy(alpha = 0.45f)` | `45%` | `0.5.dp` 卡片内行间分割线、顶置地址栏下缘与底栏上缘分割线 |
| **激活卡片内的次要副标题** | `onSecondaryContainer.copy(alpha = 0.78f)` | `78%` | `BrowserTileCard` 选中态下的第二行状态小字 |
| **模态抽屉背景遮罩** | `scrim.copy(alpha = BrowserMotion.SCRIM_ALPHA)` | `32%` | 抽屉背后的网页压暗层 |

---

## 三、字体排版、几何尺度与桌面图标体系（Typography, Shapes, Metrics & Launcher Icon）

### 1. 字体层级表（`PureBrowserTypography`，严禁交互文本低于 `12.sp`）

| 角色 Token | 字号 (Size) | 行高 (Line Height) | 字重 (Weight) | 典型应用场景 |
| :--- | :--- | :--- | :--- | :--- |
| `headlineSmall` | `24.sp` | `32.sp` | `SemiBold (600)` | 主页品牌标题、模态弹窗大标题（`BrowserAlertDialog`） |
| `titleLarge` | `20.sp` | `28.sp` | `SemiBold (600)` | 抽屉顶部标题（`BrowserSheetHeader`） |
| `titleMedium` | `16.sp` | `24.sp` | `SemiBold (600)` | 设置一级分类名、报错页主标题、播放器 HUD 文本 |
| `titleSmall` | `14.sp` | `20.sp` | `SemiBold (600)` | 站点身份卡标题、紧凑型子模块标题 |
| `bodyLarge` | `15.sp` | `22.sp` | `Normal (400)` | 列表项主标题（`BrowserListItem`）、地址栏/搜索框输入文本 |
| `bodyMedium` | `14.sp` | `20.sp` | `Normal (400)` | 弹窗正文、列表项副标题 |
| `bodySmall` | `12.sp` | `16.sp` | `Normal (400)` | 网格卡片副标题（`BrowserTileCard`）、辅助说明文本、域名与时间戳 |
| `labelLarge` | `14.sp` | `20.sp` | `Medium (500)` | 按钮文字、`BrowserActionPill` 文字、网格卡片主标题（`BrowserTileCard`） |
| `labelMedium` | `12.sp` | `16.sp` | `Medium (500)` | 设置分组卡片上方小标题（`onSurfaceVariant` 色）、快捷方式名、胶囊徽章文字 |
| `labelSmall` | `11.sp` | `14.sp` | `Medium (500)` | **仅限非文本交互的角标数字**（如底栏标签计数方框内数字） |

### 2. 五档圆角尺度与同心嵌套公式（严禁手写档位外圆角）

嵌套容器遵循同心圆角递减公式 $R_{\text{inner}} = \max(R_{\text{outer}} - \text{Padding}, 4\text{dp})$，全项目严格收敛至以下 **5 个 `MaterialTheme.shapes` 槽位 + `CircleShape`**：

* **`extraLarge` (`28.dp`)**：半屏抽屉顶部两角、模态弹窗外框、全屏视频底部悬浮控制坞（`player_control_bar`）。
* **`large` (`20.dp`)**：设置分组卡片（`BrowserGroupCard`）、菜单顶部站点身份卡（`SiteSummaryCard`）、搜索建议浮层、视频锚定弹出面板与二维码取景框。
* **`medium` (`16.dp`)**：双列标签卡（`TabGridCard`）、菜单功能磁贴（`BrowserTileCard`）、4 列资料库块与菜单底部三等分操作块（`FooterRow`）。
* **`small` (`12.dp`)**：嵌套在 `16.dp` 标签卡内部的缩略图容器（`16.dp - 4.dp = 12.dp`）、主页快捷方式 `48.dp` 图标底座、报错页代码块凹槽。
* **`extraSmall` (`8.dp`)**：标签计数徽章方框、微型角标。
* **`CircleShape` (全圆角胶囊)**：地址栏胶囊、搜索框、主操作实心按钮、次级/开关胶囊按钮（`BrowserActionPill`）、状态徽章。

### 3. 间距、触控热区、图标与桌面启动图标（澄白星环）

* **标准壳层与间距步进**：
  * 页面/抽屉水平安全外边距：**`16.dp`**；分组标题左内边距：**`16.dp`**（与 `BrowserListItem` 文本左边缘严格对齐）。
  * 浏览器壳层高度：地址栏行高 **`48.dp`**（胶囊 **`40.dp`**），底部工具栏高 **`48.dp`**。
  * 双列网格间距：标签页网格与对称双按钮行 **`10.dp`**，菜单紧凑矩阵 **`8.dp`**。
* **触控热区与大字体弹性**：
  * 所有独立胶囊按钮与主操作按钮一律使用 **`heightIn(min = 48.dp)` / `defaultMinSize(minHeight = 48.dp)`**（严禁硬编码固定 `.height(48.dp)`），支持系统 `200%` 字体缩放自动纵向增高换 2 行。
  * 密集卡片内部附属图标按钮（如标签关闭按钮、站点卡快捷图标）：外层点击热区显式声明 `Modifier.size(44.dp)`。
* **界面图标规范**：全应用（含扫码页、投屏面板与全屏增强播放器）统一使用 `R.drawable.ic_ui_*`（`24dp` 画布，Material Symbols Rounded 圆润几何语言），严禁混入尖角旧版 Material 2 图标；系统通知/PiP 图标可继续使用平台资源。
* **Adaptive Launcher Icon（澄白星环）**：
  * `ic_launcher_background.xml`：`108dp` 对角微瓷白线性渐变（`#FFFFFF -> #E5E9F0`），与全白界面一脉相承且防止纯白壁纸融边。
  * `ic_launcher_foreground.xml`：安全区（半径 $\le 33\text{dp}$）内居中绘制品牌海蓝渐变星球（`#1D5BD6 -> #004397`）与 `-26°` 倾角圆头破界星环（`#0284C7`），星球下半部与星环保留布尔负空间切口。
  * `ic_launcher_monochrome.xml`：Android 13+ 主题图标层，复用完全一致的布尔分离负空间路径，确保系统单色染色时星体与星环绝不糊连。

---

## 四、动效与响应式布局速查表（Motion & Adaptive Rules）

直接调用 [`BrowserMotion`](../../app/src/main/java/com/mybrowser/ui/shell/BrowserMotion.kt) 预置函数（自动遵循系统无障碍 `TRANSITION_ANIMATION_SCALE` 动画缩放设置）：

| 维度 / 场景 | 规范标准 / 必须调用的 API | 参数细节 |
| :--- | :--- | :--- |
| **半屏抽屉进 / 出场** | `BrowserMotion.panelArrive()` / `BrowserMotion.panelDepart()` | `400ms` EmphasizedDecelerate / `200ms` EmphasizedAccelerate |
| **全屏页面进 / 出场** | `BrowserMotion.windowArrive()` / `BrowserMotion.windowDepart()` | `500ms` Standard / `250ms` EmphasizedAccelerate |
| **同窗口内子页面推入** | `BrowserMotion.pageArrive()` | `250ms` StandardDecelerate + `24.dp` 位移 |
| **同位置内容交叉淡入** | `BrowserMotion.contentCross()` | `200ms` Standard（用于标题切换、弹窗淡入） |
| **跟手滚动 / 布局重排** | `BrowserMotion.chromeSpatial()` / `userItemMotion()` | 空间弹簧（`damping = 0.9f, stiffness = 1400f`） |
| **Compact 手机竖屏 (`<600.dp`)** | 单栏全宽，标签网格固定 **`GridCells.Fixed(2)`** | 严禁在竖屏手机使用 `Adaptive` 导致 `320~340dp` 窄屏坍缩为 1 列 |
| **Medium 横屏/小折叠 (`600~839.dp`)** | 半屏抽屉居中限宽 `widthIn(max = 640.dp)` | 标签网格切换为 `GridCells.Fixed(3)` |
| **Expanded 平板/大折 (`>=840.dp`)** | 左右双栏 Master-Detail | 左侧分类列表固定 `260.dp` + `VerticalDivider` + 右侧详情 |

---

## 五、全局高复用基础组件库（底座封死细节，业务一行调用）

### 底座 1：微边纯白分组卡片（[`BrowserGroupCard`](../../app/src/main/java/com/mybrowser/ui/components/BrowserGroupCard.kt)）

```kotlin
@Composable
fun BrowserGroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, // 冷静石墨灰分组标题
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large, // 20.dp
            color = MaterialTheme.colorScheme.surfaceContainerLow, // 浅 #FFFFFF / 深 #1B2332
            border = BorderStroke(1.dp, BrowserColors.cardBorder), // 浅 #DCE3EC / 深 #2C3A50
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}
```

### 底座 2：双模式网格功能磁贴（[`BrowserTileCard`](../../app/src/main/java/com/mybrowser/ui/components/BrowserTileCard.kt)）

常规态使用雪雾灰 `surfaceContainerHigh`（`#EDF2F8`）+ 石墨灰图标（`onSurfaceVariant`）；激活态点亮冰透蓝 `secondaryContainer`（`#DCE9FF`）+ `1.dp` `primary`（`#1D5BD6`）描边 + `primary` 图标，内部自动分流 `Role.Switch` 与 `Role.Button`。

### 底座 3：统一次级动作与开关胶囊（[`BrowserActionPill`](../../app/src/main/java/com/mybrowser/ui/components/BrowserActionPill.kt)）

全面替代散乱的 `OutlinedButton` 与页面级次级 `TextButton`，用于报错页、扫码页、投屏远控、元素拾取与页面翻译面板中的次级按钮及独立开关胶囊：

> 例外：模态弹窗/确认框的操作按钮、列表内联的“加载更多/重试”文字动作、投屏候选的文本动作、翻译取消等内联文本链接，仍使用 `TextButton`；它们不是页面级次级按钮，不应套用胶囊。

```kotlin
@Composable
fun BrowserActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    active: Boolean = false,
    isToggle: Boolean = false,
    enabled: Boolean = true,
)
```

### 底座 4：紧凑胶囊搜索框（[`LibrarySearchField`](../../app/src/main/java/com/mybrowser/ui/shell/BrowserActions.kt)）与拇指抽屉（[`ThumbZoneLibrarySheet`](../../app/src/main/java/com/mybrowser/ui/components/ThumbZoneLibrarySheet.kt)）

* `LibrarySearchField` 常态为 `surfaceContainerHigh`（`#EDF2F8`），聚焦时切换为 `surfaceContainerLow`（`#FFFFFF`）+ `1.5.dp` `primary` 描边。
* `ThumbZoneLibrarySheet` 按 **`Error > Loading > Empty > Content`** 封装内容生命周期，底部主按钮使用 `CircleShape` + `heightIn(min = 48.dp)`。

### 底座 5：模态弹窗与弹出菜单契约（[`BrowserAlertDialog`](../../app/src/main/java/com/mybrowser/ui/shell/BrowserAlertDialog.kt) / [`BrowserDropdownMenu`](../../app/src/main/java/com/mybrowser/ui/shell/BrowserActions.kt)）

* 模态弹窗一律调用 `BrowserAlertDialog` 或 `BrowserConfirmDialog`（纯白 `surfaceContainerLow` + `1.dp` `BrowserColors.cardBorder`），弹窗内嵌详情块一律使用 `BrowserColors.recessedWell`，禁止直接调用原生 `AlertDialog`。
* 弹窗动作层级：普通确认/提示弹窗默认横向 `TextButton`，次要在左、主操作在右；只有证书/SSL 等安全阻断弹窗允许纵向全宽「主 `Button` + 次 `TextButton`」布局。同一弹窗不得混用两套动作布局。
* 弹出菜单一律调用 `BrowserDropdownMenu`（`MaterialTheme.shapes.medium` + `surfaceContainerLow` + `1.dp` `BrowserColors.cardBorder` + `3.dp` 阴影），禁止直接调用无边框原生 `DropdownMenu`。

---

## 六、性能、国际化与安全文案红线（Guardrails）

1. **多语言同步与字体缩放防御**：严禁硬编码中/英文字符串，新增或删除任何字符串资源必须同步更新 `values/`、`values-zh/`、`values-b+zh+Hant/` 并通过 `validation/check-localization.py` 校验；所有按钮与卡片高度一律使用 `heightIn(min = ...)` / `defaultMinSize(minHeight = ...)`，防止 `200%` 字体缩放截断。
2. **标签卡选中态与微边防隐形**：[`TabGridCard`](../../app/src/main/java/com/mybrowser/ui/menu/TabsSheet.kt) 必须附带 `.semantics { selected = isSelected }`；选中态使用 `2.dp` `primary` 描边，未选中态使用 `1.dp` `BrowserColors.cardBorder` 描边，缩略图槽使用 `BrowserColors.recessedWell` + `0.5.dp` `BrowserColors.cardBorder`。
3. **重组缓存红线**：抽屉/列表热路径内的集合转换（`filter`、`groupBy`、`associateBy`）与日期格式化器必须包裹在 `remember(完整依赖 keys)` 中。
4. **沉浸式视频增强控制守则**：全屏播放器（[`PlayerControls`](../../app/src/main/java/com/mybrowser/media/PlayerControls.kt)）在暗场下所有常驻工具按钮（倍速 `1.0x`、未打开投屏、未锁屏）保持 `surfaceContainerHigh` 克制中性态，仅在面板展开、倍速非 `1.0x` 或屏幕已锁定时点亮 `secondaryContainer` + `1.dp` `primary` 描边；保留全部自动化回归 `testTag` 与无障碍 `contentDescription`。
5. **严谨的浏览器安全与隐私表达契约**：
   * **连接状态三元组（图标 + 准确文案 + 语义色）**：区分 **「连接已加密 (HTTPS)」**（锁图标 + `BrowserColors.secure`）、**「连接未加密 (HTTP)」**（警告图标 + `BrowserColors.warning`）、**「无法验证此连接 (证书异常)」**（危险图标 + `error`），严禁仅靠颜色表达安全状态。
   * **无痕模式边界与防遗忘**：在地址栏左侧、主页顶部、底栏标签计数按钮处**同时呈现 `secondaryContainer` 无痕标识**；无痕说明文案明确表述本地隔离边界。
