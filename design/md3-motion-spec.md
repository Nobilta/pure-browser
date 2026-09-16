# 全应用 MD3 动效改造方案

本文用于实施 Pure Browser 的交互动效改造。目标是让打开、切换、返回、关闭和即时反馈形成一致的节奏，并在连续操作时保持画面、焦点和业务状态连贯。

代码基线为 `b3c4226`。这是待实施方案；文中的目标效果不代表当前已经实现。实施前应对照最新代码补齐新增入口。

## 1. 采用哪套规范

采用 Material 3 的动效原则：动画表达层级、来源和去向；进入与退出配对；直接操作及时跟手；颜色和透明度变化与空间运动分别处理。常规浏览操作以清晰、克制为主。

本文分清三类依据：

| 类型 | 如何使用 |
|---|---|
| Material 官方定义 | 使用官方的时长和缓动 token；遵循组件的交互、焦点和可访问性语义 |
| Material3 组件现成行为 | 保留组件提供的菜单、指示器、波纹和状态动效，核对实际依赖版本的表现 |
| 本项目的设计选择 | 下文指定每个场景采用哪些 token、移动多少距离、如何衔接；这些参数是本项目的实现基准 |

**MD3 没有要求所有页面统一使用某个毫秒数，也不要求所有状态变化都添加位移动画。** 本方案中的 24 dp、32 dp、缩放比例和具体场景时长属于项目选择。验收既看参数，也看连贯性、交互响应和真实帧表现。

当前锁定 Compose UI / Animation `1.9.4`、Material3 `1.4.0`，见 [版本目录](../gradle/libs.versions.toml) 和 [依赖锁](../app/gradle.lockfile)。已核对 Material3 1.4.0 的官方源码包：`MotionScheme`、带 `motionScheme` 参数的 `MaterialTheme` 重载为 `internal`。应用层通过公开的 Compose Animation API 实现下述自定义动效；组件继续使用库内默认动效。无需为了调用新版示例中的 API 升级整套依赖。

参考资料：

- [Material 3 Motion](https://m3.material.io/styles/motion/overview)：动效原则。
- [Material 3 easing and duration](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs)：缓动和时长体系。
- [Material3 1.4.0 官方源码包](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.4.0/material3-1.4.0-sources.jar)：`MotionTokens.kt`、`MotionScheme.kt`、`MaterialTheme.kt`、`Menu.kt`、`AlertDialog.kt`；用于核对本项目版本的 token 和 API。
- [Compose 动画指南](https://developer.android.com/develop/ui/compose/animation/quick-guide)：可见性、内容切换和图层动画。
- [Compose 预测性返回](https://developer.android.com/develop/ui/compose/system/predictive-back)：系统返回手势的进度、提交与取消。
- [Compose 动画测试](https://developer.android.com/develop/ui/compose/animation/testing)：控制动画时钟与检查中间状态。

## 2. 先修复的体验问题

| 当前实现 | 体验影响 | 改造落点 |
|---|---|---|
| `BrowserMotion` 只有 240 ms 入场、120 ms 内容切换，所有场景使用同一曲线 | 大容器和小反馈缺少节奏区分 | 建立按用途命名的动效规格 |
| `CONTAINER` 用 120 ms 移动整个新容器高度，全屏页也如此 | 菜单进入设置等操作会显得冲、突兀 | 跨容器采用短距离覆盖与揭示，见 M04 |
| 路由切换立即卸载旧内容，`ReplacedSurface` 只画旧容器底色 | 文字和控件先消失，过渡中只有空表面 | 保留真实的离场展示内容 |
| Compose 负责进入，窗口 XML 单独淡出 120 ms | 关闭方向与进入不同，中途返回难以连续 | 同一状态机管理进入和退出 |
| `SettingsPage.browserContentMotion(title)` 与外层动画叠加 | 一次操作有两次淡入、两段位移，标题也被用作动画身份 | 在真正的导航边界控制一次转场 |
| 顶部和底部地址栏分别使用不同的 `AnimatedVisibility` 配置 | 相同操作的节奏、展开方向不一致，可能反复改变网页视口 | 统一滚动显隐与布局策略 |
| 播放器控件、局部面板主要直接挂载与移除 | 出现和消失生硬 | 加入局部轻量动效，保持视频连续 |

以上是从代码识别出的行为；是否掉帧及掉帧原因需要在设备上测量。

## 3. 统一动效规格

### 3.1 基础 token

在 [BrowserMotion.kt](../app/src/main/java/com/mybrowser/ui/shell/BrowserMotion.kt) 集中定义。下列缓动数值和时长来自 Material token，名称可按 Kotlin 风格调整。

| 名称 | 数值 | 本项目用途 |
|---|---|---|
| `Standard` | `CubicBezierEasing(0.2f, 0f, 0f, 1f)` | 已在屏幕内的内容切换、尺寸与位置调整 |
| `StandardDecelerate` | `CubicBezierEasing(0f, 0f, 0f, 1f)` | 小范围元素进入 |
| `StandardAccelerate` | `CubicBezierEasing(0.3f, 0f, 1f, 1f)` | 小范围元素离开 |
| `EmphasizedDecelerate` | `CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)` | 主要浮层进入并停稳 |
| `EmphasizedAccelerate` | `CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)` | 主要浮层退出 |
| `Short2 / Short3 / Short4` | `100 / 150 / 200 ms` | 局部反馈、透明度与快速退出 |
| `Medium1 / Medium2` | `250 / 300 ms` | 层级切换和主要浮层 |

### 3.2 应用层规格

调用方使用 `sheetEnter`、`pageChange` 等语义规格，避免在页面散写时长。以下为系统动画倍率 1× 时的基准。

| 规格 | 时间、曲线 | 属性 |
|---|---|---|
| `sheetEnter` | 300 ms，EmphasizedDecelerate | 底部面板从下边界外移入，表面保持不透明 |
| `sheetExit` | 200 ms，EmphasizedAccelerate | 底部面板从当前状态沿原方向退回下边界外 |
| `fullscreenEnter / Exit` | 300 / 200 ms，对应 Emphasized 曲线 | 全屏浮层向下偏移 32 dp 的位置与原位之间过渡，并淡入 / 淡出 |
| `pageChange` | 位移 250 ms，Standard；内容交叉淡化 150 ms，Standard | 层级内容水平移动 24 dp，方向由前进 / 返回决定 |
| `contentReplace` | 150 ms，Standard | 同级内容交叉淡化，无位移 |
| `localEnter / Exit` | 150 / 100 ms，StandardDecelerate / Accelerate | 小面板透明度变化；有明确锚点时可叠加 `0.96 ↔ 1` 缩放 |
| `chromeShow / Hide` | 200 / 150 ms，StandardDecelerate / Accelerate | 地址栏向所在屏幕边缘移动一个自身高度 |
| `itemPlacement` | 200 ms，Standard | 用户增删、移动项目后的相邻项目位置调整 |
| `scrim` | 跟随所属浮层的进出时间，Standard | 首层遮罩 `0 ↔ 0.32`；同一窗口内切换保持 `0.32` |

所有距离使用 dp 转 px，比例使用浮点数。透明度始终限制在 `[0, 1]`。组件库自带的弹簧动画继续采用其默认规格，不强行改成表中固定时长。

动画被反向操作打断时，从当前值向新目标运动。不得重新跳到入场起点，也不得排队播放过时操作；剩余时长可按剩余距离缩短。手势驱动阶段直接使用手势进度，松手后再完成或恢复。

## 4. 全部交互的目标效果

编号用于实施与验收逐项核对。标为“保留组件 / 系统行为”的场景也需要走查。

### 4.1 页面、浮层和导航

| 编号 | 场景 | 目标效果与参数 |
|---|---|---|
| M01 | 菜单、标签、历史、下载、站点设置、受管理站点、过滤设置、用户脚本、开发者工具、投屏设备、网页长按菜单 | 共用 `sheetEnter / Exit`。位移包含退出窗口下边界所需距离，底部 inset 计算一次；遮罩同步。继续保持现有固定面板交互 |
| M02 | 首次打开设置、书签、扫码全屏页 | 共用 `fullscreenEnter / Exit`，32 dp 短距离和整体淡入淡出。工具栏与内容作为同一次入场，落稳后表面完全不透明 |
| M03 | 菜单进入同为底部面板的子页，再返回菜单 | 共用 `pageChange`；保持面板下边缘，容器高度若有变化，以 250 ms Standard 调整裁剪边界，文字按正常字号绘制。返回反向运动，恢复菜单滚动状态 |
| M04 | 菜单进入设置 / 书签等全屏子页，再返回菜单 | 前进时旧菜单保持原位，新全屏页用 `fullscreenEnter` 覆盖它；返回时菜单先在原位置准备好，旧全屏页用 `fullscreenExit` 揭示菜单。整个过程沿用一个窗口和遮罩 |
| M05 | 设置首页进入分类、分类返回首页；书签进入文件夹、返回上级 | 窄屏用 `pageChange`。LTR 前进：新内容 `+24 → 0 dp`、旧内容 `0 → -24 dp`；返回反向，RTL 镜像。工具栏边界固定，标题 / 返回按钮按实际状态过渡，列表恢复各自位置 |
| M06 | 宽屏设置分类切换 | 现有双栏模式下，仅右侧内容使用 `contentReplace`，左侧导航保持稳定。进入 / 离开双栏模式按最终布局重排，避免把断点变化模拟成第二次页面入场 |
| M07 | 搜索引擎、主页、主题、长按倍速、默认倍速等设置选择器 | 独立子浮层采用 M01；其中的 `SettingsPage` 不再自行入场。关闭后父页原位保留，焦点回到触发项 |
| M08 | 开发者工具的控制台、网络、源码、信息切换；同级分组切换 | 指示器使用 Material 组件行为，内容 `contentReplace`。外层面板和工具栏不重新入场；每个分区保留自己的滚动位置 |
| M09 | 浮层内点链接、打开标签或完成扫码后回到网页 | 业务动作在提交时执行一次，浮层随即退出。网页和加载反馈可以立即响应，结束回调仅负责回收展示层 |

M04 采用“覆盖 / 揭示”转场。旧菜单的文字和控件应真实保留在下层；删除当前只画底色的 `ReplacedSurface` 替代方案。若返回时父页因数据变化需要刷新，先恢复可用的当前内容，再开始揭示。

同一窗口内切换时，遮罩保持稳定。独立子对话框可以遮暗父页面，但需检查最终合成亮度：每个层级只拥有一次遮罩，避免同时叠加平台 dim 与同层 Compose scrim。

### 4.2 地址栏、浏览界面和即时反馈

| 编号 | 场景 | 目标效果与参数 |
|---|---|---|
| M10 | 滚动时地址栏显示 / 隐藏，顶部 / 底部位置设置 | 共用 `chromeShow / Hide`，顶部向上、底部向下。一次显隐只有一个进度源；主页、编辑、IME 与现有强制显示规则继续有效 |
| M11 | 地址栏聚焦、建议列表出现 / 消失 | 建议面板用 `localEnter / Exit` 的淡入淡出，不缩放文字。编辑焦点、光标和键盘即时响应；继续在现有窗口内显示建议 |
| M12 | 输入联想刷新、设置搜索、列表搜索 | 查询结果及时替换，稳定 key 保留项目身份。连续输入时不反复播放整表入场，空结果提示可 `contentReplace` |
| M13 | 页面内查找栏打开 / 关闭 | 局部淡入淡出 150 / 100 ms；若占据布局空间，显式协调栏高与网页视口。焦点即时获得，匹配数量和前后跳转即时更新 |
| M14 | 网页加载进度、下载 / 导入 / 更新等任务进度 | 指示器使用 Material 行为，值来自真实任务。位置预留或作为覆盖层，避免显隐令页面上下抖动；完成可淡出 100 ms，下一次任务立即重置 |
| M15 | 媒体浮动按钮、计数徽标、页面错误提示 | 浮动按钮 `localEnter / Exit`，缩放以按钮中心为原点；错误面板 `contentReplace`。计数、错误操作状态及时更新 |
| M16 | 主页与网页、网页间导航、标签切换、普通 / 无痕切换 | 保持实际 WebView 切换和网页首次绘制流程，应用壳只对必要状态提示使用局部淡化。切换完成即可操作，旧标签或旧隐私模式内容立即失去展示资格 |
| M17 | 主页快捷方式增删、排序、编辑；主题 / 地址栏位置设置变化 | 项目增删和移动采用 M23；编辑对话框采用 M18。主题和布局设置即时生效，配置重建恢复到稳定终态，保留键盘和安全区正确位置 |

M10、M13 的实现必须核对 `AndroidView` 的实际测量次数。优先将地址栏作为覆盖层，通过图层平移完成动效；网页在过渡期间保持稳定尺寸，在终态协调可用视口。若终态切换仍有可见跳动，应使用统一容器处理视口与栏的位置关系，并测量连续布局方案的开销。验收以地址栏不遮挡可操作内容、网页不跳动、点击坐标正确为准，不能仅凭改成 `graphicsLayer` 判定完成。

IME 使用系统 inset 动画进度，应用不再给同一个键盘位移追加定时动画。地址栏置顶和置底都检查键盘打开、关闭及交互中断。

### 4.3 对话框、菜单和组件

| 编号 | 场景 | 目标效果与参数 |
|---|---|---|
| M18 | 确认、输入和信息对话框 | 优先保持 Material `AlertDialog` 的布局、语义和实际平台表现；若当前版本 / 设备缺少一致的进出动画，通过统一封装补齐 `localEnter / Exit`，中心 `0.96 ↔ 1` 缩放与淡化，遮罩同进度 |
| M19 | 更多菜单、排序、列表行菜单、站点权限下拉框、网络日志筛选菜单 | 保留 Material `DropdownMenu` 的锚点、缩放、透明度与退出生命周期。1.4.0 源码已包含相应空间 / 透明度动画，外层不再叠加一次 |
| M20 | Button、IconButton、Card、Switch、RadioButton、Checkbox、Slider、Tab | 保留 Material 的按压、选中、拖动、焦点和禁用状态反馈。自定义可点击行使用语义正确的 `clickable / toggleable / selectable` 及合适的 `InteractionSource` |
| M21 | 长按、复制、输入选择、链接上下文操作 | 长按识别和业务反馈及时发生；应用自有长按面板采用 M01。Android / WebView 的文本选择工具和系统 Toast 使用原有行为 |

M18 覆盖：JavaScript 对话框、书签编辑 / 文件夹 / 移动 / 删除 / 导入确认、标签分组和批量关闭、下载删除、过滤规则 / 订阅和脚本操作、网站权限与重置、安全信息与 SSL 提示、清理浏览数据、系统登录说明、外部应用确认、长按返回历史、快捷方式编辑 / 删除、更新提示、过滤命中说明和自定义搜索引擎输入。

对话框封装需要区分“用户已经回答”和“展示层退出完成”。JavaScript result、权限回答、删除和清理操作在提交时只执行一次，退出动画只负责画面回收。忙碌时原有的关闭限制继续有效。若现有窗口动画已承担完整进出，统一窗口规格即可，不再包第二套 Compose 缩放。

遮罩等纯关闭区域可继续使用无波纹点击反馈；文字按钮、图标按钮和可选列表行应能清楚呈现按下与选中状态。

### 4.4 列表、扫码和持续更新内容

| 编号 | 场景 | 目标效果与参数 |
|---|---|---|
| M22 | 列表首次显示、分页、后台刷新、从详情返回 | 页面转场负责一次整体呈现；列表项目稳定显示，分页保持阅读位置，返回恢复原滚动位置 |
| M23 | 用户关闭标签、删除下载 / 书签 / 规则 / 脚本，移动书签或快捷方式 | 使用稳定业务 ID。被删除项淡出 100 ms，相邻项 `itemPlacement`；新增项淡入 150 ms。批量操作作为一次更新，数据提交只执行一次 |
| M24 | 书签选择模式、分组展开和显式展开的详情 | 顶部操作区 `contentReplace`；局部详情高度用 200 ms Standard，保持触发位置可追踪。大量项目重排时限制为可见区域的必要动画 |
| M25 | 开发者日志、网络日志、下载速度、播放器时间、投屏设备发现 | 按真实状态更新文字和项目，持续数据流不触发整表过渡。设备选择的选中态采用组件反馈；空 / 有内容状态可淡化 150 ms |
| M26 | 扫码预览、权限说明、相册识别、处理中与错误反馈 | 页面进出采用 M02；说明、进度和错误覆盖层淡化 150 ms。预览尺寸保持稳定，镜头帧使用原有渲染；离开时按生命周期及时释放摄像头 |

`animateItem` 等 API 的具体签名以当前 Compose 版本为准。先区分用户操作、筛选和流式更新，再决定是否启用项目动画；所有项目都加统一动画会干扰阅读并扩大重排成本。

### 4.5 全屏视频和投屏

| 编号 | 场景 | 目标效果与参数 |
|---|---|---|
| M27 | 视频控制层手动显示 / 隐藏、自动隐藏 | 标题和底部控制层共用一次透明度变化，150 ms 进入、100 ms 退出；按钮和时间轴的位置稳定，视频纹理持续播放 |
| M28 | 播放器倍速 / 投屏小面板打开、关闭及互换 | 在现有视频窗口内，以触发按钮方向为缩放原点，使用 `localEnter / Exit`。互换内容淡化 150 ms，大小变化最多 200 ms；锚点按布局方向和实际按钮位置计算 |
| M29 | 锁定 / 解锁、手势亮度 / 音量 / 进度 / 临时倍速 HUD | 锁定控件淡化 150 / 100 ms；HUD 首次出现与最终隐藏用同一局部淡化。拖动过程中数值即时跟手，连续数值更新不重播入场 |
| M30 | 远端投屏播放 / 暂停、进度和音量拖动 | 按钮保留组件反馈，用户拖动值即时呈现，网络确认按真实状态显示。轮询刷新不能拉回正在拖动的滑块 |
| M31 | 网页视频进入 / 退出全屏、旋转、进入 / 返回画中画 | 保持原视频 View / 解码链的切换流程；应用动画仅作用于自己的控制覆盖层。画中画、旋转和系统窗口转场由 Android 承担，核对 `sourceRectHint` 与最终位置 |

保持当前播放器规则：菜单外的第一次点击只关闭菜单；Back 先关闭菜单，再解除锁定，最后退出全屏；拖动、菜单打开和 TalkBack 触摸探索期间遵循现有自动隐藏限制。控件自动隐藏仍按现有 3500 ms 逻辑计时，动画完成回调不能重新触发过期定时器。

进入画中画或退出视频时立即清理不适用的输入捕获与 HUD 状态，防止淡出中的透明覆盖层继续接收点击。

### 4.6 系统界面交接

| 编号 | 场景 | 目标效果与参数 |
|---|---|---|
| M32 | 文件 / 图片选择、分享、运行时权限、默认浏览器设置、外部登录、打开其他应用、更新安装 | 由系统或目标应用负责窗口转场。应用自己的来源面板按已提交状态退出或保留；返回后恢复输入、滚动和当前路由，取消系统操作时原页面仍可继续使用 |

M16 的标签切换也包括工具栏横滑入口：遵循现有手势判定与切换时机，正确更新当前标签、地址栏和媒体状态。系统或网页提供的交互由其所属渲染层处理，应用统一负责自己的页面、控件和交接状态。

## 5. 转场实现的关键约束

### 5.1 区分业务目标、展示层与操作归属

当前 `BrowserSheetHost` 只组合当前路由；清空路由就会立即卸载 Dialog。需要把逻辑导航与短暂保留的展示状态分开：

```text
Hidden → Entering → Visible → Exiting → Hidden
                    ↕
                 Switching

Entering / Switching / Exiting 接收新目标时：从当前可见状态重定向。
预测性返回：Visible → 手势预览 → 提交退出，或取消并恢复 Visible。
```

建议转场记录至少包含：`transitionId`、来源与目标路由 key、操作类型（打开 / 前进 / 返回 / 关闭）、当前展示进度、有效交互 owner。方向由导航操作决定，不再通过当前栈深猜测。

- Dialog 持续存在到最终退出结束。所有直接用 `if (show)` 挂载独立浮层的调用处，也要让展示宿主保留退出阶段；只改导航 Host 无法覆盖这些入口。
- 通常最多保留当前目标与必要的离场展示层。快速 A → B → A 时复用对应身份、重定向当前过渡，不累积隐藏页面。
- 每个路由同一时刻只使用一个 `SaveableStateProvider`。被弹出的保存状态在没有展示层引用后再回收；返回中的父页继续使用原 key。
- 稳定业务状态仍属于原来的数据层。展示层负责动画，不复制导航、数据库写入、权限响应或媒体控制逻辑。
- 配置重建和进程恢复以已提交的目标状态恢复，动画进度是瞬态信息，不恢复半退出的窗口。

### 5.2 保留旧画面时，明确副作用的生命周期

优先把路由的交互与副作用从展示内容中分离：逻辑 owner 驱动数据和事件，离场层读取最后一份展示数据。可以使用 `AnimatedContent` 或显式 `Transition` 承载画面，但不能直接让两个完整路由各运行一套副作用。

| 对象 | 转场期间的要求 |
|---|---|
| 点击、键盘焦点、无障碍语义、Back | 已提交目标为唯一有效 owner；退出层立即失去交互和语义。根浮层完全退出前，由模态宿主阻止点击穿透到网页 |
| `LaunchedEffect / DisposableEffect`、采集日志、设备发现、相机 | 跟随明确的业务可见性与 owner。离场展示保留不能延长已结束任务，也不能重复启动任务 |
| 设置输入、滚动位置、列表选择 | 保留原路由状态，视觉切换不清空输入和选择 |
| WebView、视频 View、相机预览 | 维持唯一真实实例和现有生命周期；动效围绕容器 / 控制层组织。普通展示数据足以过渡时使用数据，不为动效截取这些表面 |
| 退出完成回调 | 核对 `transitionId` 与 owner，过时回调不得关闭新页面、删除新状态或恢复错误系统栏颜色 |

若某个含实时 View 的页面无法安全保留离场视图，用其正常背景和已有静态 UI 承接，同时及时释放真实资源；不把空背景冒充旧内容保留。要在实现说明中写出该页面实际采用的过渡。

### 5.3 一次操作只编排一次

删除以标题字符串触发的通用 `browserContentMotion(title)`，将动画放到路由或分类切换的位置。外层容器进入时，内层页面按终态布局；外层稳定后的分类变化，才由内容转场接管。

用同一个 `Transition` / 状态源协调容器、内容与遮罩。连续两层各自执行 alpha 会相乘，导致前半段内容过淡；两层都移动则会改变实际行程。组件内部的波纹、选中态仍独立响应。

当 Compose 已完整承担自定义浮层退出时，移除 `motion.xml` 中对应的窗口退出动画及失效的 `browser_sheet_exit.xml` 引用。检查其他窗口的使用范围，确保每个窗口只有一个进出动画责任方。

### 5.4 返回、可访问性和系统动画倍率

返回操作包括页面箭头、系统 Back、遮罩关闭和业务完成关闭。它们应调用同一导航语义，并由上层判断是退回父页还是关闭整组浮层。

预测性返回遵循设备与当前宿主支持情况：在支持进度回调的环境中连接 AndroidX / 系统 API，手势取消恢复原路由、滚动和焦点，提交后才执行 pop / close。手势预览层没有业务执行资格；取消不会重复启动相机、刷新页面或提交权限答案。宿主已有系统预测动画时沿用其行为；只能收到提交事件的宿主使用一致的普通退出，验收中注明实际覆盖。键盘、子对话框和播放器菜单继续优先处理 Back。

Compose 动画通常会读取系统动画时长倍率，应保留标准动画上下文；避免自行再把时长乘一遍倍率。检查自定义 View / 窗口动画也遵循系统设置。倍率为 0 时立即进入目标终态并完成回收：内容可见、遮罩正确、Dialog 能关闭、回调只执行一次。清理过程应等待动画完成或显式终态，而不是 `delay(300)`。

TalkBack 只读到当前可交互页面；关闭后焦点返回触发项。移动中的隐藏控件不留下可点击区域，键盘焦点不必等动画结束。检查 RTL、200% 字体、浅色 / 深色、横竖屏、屏幕缺口和导航栏区域。

### 5.5 渲染与性能

位移、透明度和缩放优先在 `graphicsLayer` 中读取进度；遮罩在绘制阶段读取。确实需要尺寸变化的场景集中在小容器，避免每帧触发整页重组和 WebView 重测量。

同屏过渡保留层应有数量上限；视口外列表项目、源代码高亮、日志处理和网络请求不随动画帧执行。按钮按下、搜索输入和拖动操作及时改变状态，视觉动画不能成为提交操作前的等待步骤。

## 6. 代码入口与实施顺序

以下路径均相对 `app/src/main/java/com/mybrowser/`，资源和验证路径另行注明。

| 顺序 | 修改入口 | 完成范围 |
|---|---|---|
| 1 | `ui/shell/BrowserMotion.kt`、`ui/shell/BrowserSheetNavigation.kt`、`ui/shell/BrowserSheetWindow.kt`、`ui/shell/SheetAppearance.kt`；`MainActivity.kt`；`app/src/main/res/values/motion.xml`、`app/src/main/res/anim/browser_sheet_exit.xml` | 统一规格、操作方向、展示状态、退出生命周期和独立浮层宿主；先打通 M01–M04、M09 及中断操作 |
| 2 | `ui/settings/SettingsSheet.kt`；`ui/library/BookmarksSheet.kt`、`BookmarkLibrary.kt`；`ui/devtools/DeveloperTools.kt` | 清理重复动画，落实分类 / 文件夹 / Tab 转场及宽屏布局，覆盖 M05–M08 |
| 3 | `ui/shell/BrowserScreen.kt`、`Omnibar.kt`、`SmartSuggestions.kt`、`FindBar.kt`、`BrowserToolbar.kt`、`BrowserActions.kt`；`ui/home/` | 统一地址栏、建议、查找、进度和即时反馈，覆盖 M10–M17、M20–M21 |
| 4 | `ui/shell/Dialogs.kt`、`SecurityIndicator.kt`、`ClearBrowsingDataDialog.kt`、`SystemLoginDialog.kt`、`NavigationRecoveryUi.kt`；各页面内的 `AlertDialog / DropdownMenu` | 走查全部对话框和菜单，必要时统一封装，覆盖 M18–M19 |
| 5 | `ui/menu/`、`ui/library/`、`ui/download/`、`ui/settings/`、`ui/devtools/`、`ui/qr/QrScannerSheet.kt` | 稳定列表身份，区分用户操作与数据刷新，覆盖 M22–M26 |
| 6 | `media/PlayerControls.kt`、`FullscreenVideoView.kt`、`PictureInPictureController.kt`；`ui/player/CastSheet.kt`、`RemotePlaybackControls.kt` | 控制层、小面板、HUD 与投屏交互，覆盖 M27–M31 |

M32 随各入口一起检查，重点核对 `MainActivity.kt`、`data/BookmarkDocuments.kt`、`site/WebsitePermissions.kt`、`core/SystemLoginSupport.kt`、`core/DefaultBrowser.kt` 与 `ui/settings/UpdatePanel.kt` 的 Activity result 和返回恢复逻辑。

每一步完成后先验证其交互，再继续下一步。开始和结束各做一次代码扫描，核对条件挂载的控件、自定义点击区域及新增加的入口：

```bash
rg -n 'AnimatedVisibility|AnimatedContent|Crossfade|animate[A-Z]|Transition|graphicsLayer|window.*Animation' app/src/main
rg -n 'BrowserBottomSheet|BrowserFullscreenSheet|AlertDialog|DropdownMenu|Dialog\(|Popup\(' app/src/main/java
rg -n 'if \(|\.let \{|clickable|toggleable|selectable|pointerInput' app/src/main/java/com/mybrowser/ui app/src/main/java/com/mybrowser/media
```

扫描是入口清单的补充，不能单独证明场景覆盖完整。自定义新增动效都应能映射到规格表中的用途；对保留默认行为的组件，说明实际走查结果即可。

## 7. 验收方法

### 7.1 功能与动画中断

扩展现有 [BrowserSheetNavigationTest](../app/src/test/java/com/mybrowser/ui/shell/BrowserSheetNavigationTest.kt) 及相关状态机测试，覆盖真正可能回归的行为：过期 owner / 完成事件、快速反向操作、退出中重开、状态回收和 0× 动画倍率。对话框的业务回调增加“一次回答”检查。

| 操作 | 必须观察到的结果 |
|---|---|
| 菜单 → 设置 → 分类 → 返回 → 菜单 → 关闭 | 每一步方向正确；无空白旧面板、短暂露出的错误页面或窗口重建闪烁 |
| 浮层进入到约 25%、50%、75% 时关闭；关闭中立即重开 | 从当前画面连续转向，最新目标最终可操作，没有残留遮罩 |
| 快速连续 Back、点击遮罩、重复点同一个菜单项 | 导航只执行有效次数，父页不会被旧回调意外关闭 |
| 设置分类 → 搜索引擎选择器 → 输入对话框 → 返回 | 焦点、IME、父页状态和遮罩层级正确；业务答案仅执行一次 |
| 预测性返回拖出一半后取消，再提交 | 取消后原状态可继续操作；提交后只退一层，资源生命周期正确 |
| 顶 / 底地址栏滚动显隐、打开键盘、显示查找栏 | 网页阅读位置稳定，触摸位置吻合，编辑不被重建或退出动画打断 |
| 视频播放时开关菜单、锁屏、拖动、旋转、画中画 | 画面和音频连续，第一下外部点击只关菜单，退出层不拦截新输入 |
| 标签 / 书签 / 下载等列表增删、排序、搜索、分页 | 项目身份和阅读位置稳定；流式更新不会反复播放整表动画 |
| 动画倍率 0×、0.5×、1×、2×；TalkBack 开启 | 业务结果相同，0× 没有不可见但仍占位 / 拦截的窗口，焦点唯一 |
| 转场中旋转、切换主题、切后台再恢复 | 恢复已提交终态，无旧窗口、双重播放器或被重新清空的输入 |

### 7.2 使用现有回归工具

按[测试指南](../TESTING_GUIDE.md)准备签名 Release、专用模拟器、UI probe 和 QA 服务。先执行快速检查与 lint：

```bash
./build-and-test.sh --quick
./gradlew :app:lintDebug --console=plain
```

迭代期间可以选择相关阶段；全部改造完成后，对同一个最终 APK 跑完整矩阵：

```bash
python3 validation/run-regressions.py --serial emulator-5554 --apk /absolute/path/to/release.apk \
  --label md3-motion-final --profile full
```

重点阶段包括 `menu-navigation`、`settings-back`、`settings`、`omnibar`、`developer-tools`、`features-dialogs`、`layout`、`bookmarks`、`home-shortcut`、`media-lifecycle`、`private-lifecycle` 和各 `video-*` 阶段。现有脚本主要验证功能，应为上表中尚未覆盖的中断、焦点和过期回调行为补充检查；不得把脚本通过直接等同于动画流畅。

runner 要求 API 30 以上、支持 `adb root` 的 `google_apis` 镜像和 `com.mybrowser` 签名 Release，ABI 与宿主一致。项目仍支持 API 29，其基础进出动画与返回降级需用兼容设备或单独测试验证；预测性返回用支持进度回调的 Android 版本检查。Windows 命令差异和构建方式以测试指南为准。

### 7.3 视觉与帧表现

保留改造前后同设备、同刷新率、相同内容与操作的对照：菜单往返、设置切换、地址栏显隐、长列表操作、视频菜单各至少重复 10 次，先预热后采样。使用接近发布配置的构建，记录设备、系统、WebView、构建标识和当前刷新率。

正常速度检查节奏与响应，逐帧回看检查空白、双重淡化、位置跳变和不连续反向。录屏仅按实际录制帧率判断，60 fps 录屏不能证明 120 Hz 没有掉帧。

使用 Perfetto / Android Studio System Trace 观察主线程、RenderThread、FrameTimeline、布局与合成；支持时结合 FrameTiming 指标记录 P50 / P95 与错过帧期限的情况。60 Hz、120 Hz 的一帧预算约为 16.7 ms、8.3 ms，应按设备实际帧期限分析。`dumpsys gfxinfo` 可辅助定位应用绘制问题，但不能单独代表 WebView 子进程和视频合成的全部表现。

验收要求：上述操作没有新增可重复的长帧簇；能复现的动画相关重测量、重复重组或额外窗口问题得到解释并修复；同条件帧耗时与错过帧期限比例没有超出重复测量波动的退化。若仍有明显卡顿，保留 trace 定位原因。模拟器用于功能验证，流畅度结论至少需要一台真机；高刷新率、实体投屏等没有验证时如实注明。

## 8. 完成条件与交付

- M01–M32 全部核对；每项有明确的改动或保留依据，覆盖独立弹层、嵌套选择器与系统界面交接。
- 主要浮层由统一状态机承担进出；旧内容、焦点、输入、资源和保存状态都有正确的交接时机。
- 返回、连续操作、系统动画倍率和可访问性测试通过；行为不依赖等待固定毫秒数。
- 自动检查、最终 APK 设备回归及真机动效走查有实际结果，未验证的设备能力单独说明。
- 提交实现与必要测试，按[发布指南](../RELEASING.md)交付签名 APK；对外说明只写真实可见的体验变化。

验证记录沿用仓库约定：简洁结论写入 `release/notes.md`，录屏、trace 和脚本原始结果放在 Git 之外。实现完成后将本文更新为最终采用的动效规则，删去已经完成的实施步骤，避免长期保留一份与代码不一致的计划。
