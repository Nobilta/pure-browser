# 架构说明

Pure 浏览器使用 Kotlin 和 Compose 管理界面与 Android 生命周期，WebView 负责网页渲染，
Rust 处理过滤规则、网址和书签解析，JavaScript 负责网页中的媒体观察与脚本执行。

本文供修改代码时查阅，重点说明状态归属、异步回调和模块之间的约定。
产品行为见[功能介绍](FEATURES.md)，构建入口见[贡献指南](CONTRIBUTING.md)，实测结果见[回归报告](EMULATOR_TEST_REPORT.md)。

## 代码职责

| 范围 | 职责与状态所有者 |
|---|---|
| `App` | 进程级仓库、媒体/投屏控制器，以及启动更新检查和待处理提示 |
| `MainActivity`、`core` | WebView、ActivityResult、外部 Intent、导航及平台生命周期；池归还时解除监听，恢复历史使用尚未导航的新 WebView。`core` 同时持有跨层共享的词汇（`ResourceType`、`PlaybackSpeed`）与请求分类 |
| `ui` | Compose 界面、主题、输入及反馈，按功能分 `shell`/`menu`/`settings`/`library`/`devtools`/`player`/`home`/`download`/`qr` 子包；`BrowserSheetNavigation` 保存菜单来路，只挂载栈顶，父级只保存 UI 状态 |
| `data`、`home`、`tabs` | Android SQLite 书签/历史、快捷入口、标签元数据；后台标签延迟加载，缩略图只保留小尺寸 Bitmap |
| `search` | 搜索引擎清单与持久化、地址栏文本的网址/搜索判定 |
| `download` | HTTP Range 引擎、实体校验、分段恢复、SAF/MediaStore、前台服务和记录管理；兼容读取已存在的系统下载记录 |
| `update`、`release` | GitHub 正式资产直链、缓存和退避、独立内部下载、APK/签名校验；专用 `UpdateFileProvider` 向系统安装器授予读取权；从实际签名包生成清单并发布草稿 |
| `filter`、`userscript` | 过滤订阅更新与引擎切换、用户脚本元数据/存储及逐 frame 注入；共享有界读取和原子 UTF-8 写入 |
| `site` | 网站偏好、站点/系统权限状态机；仓库由 Application 单实例持有 |
| `privacy`、`security` | WebView Profile 或退出清理、证书错误状态、外部协议限制 |
| `media`、`dlna` | 媒体候选、播放元素追踪、全屏 Material 3 控件及浮层、SSDP 与 AVTransport；投屏会话由进程级控制器管理 |
| `qr` | Android Camera2 生命周期、工作线程帧分析、ZXing QR 解码与网页/纯文本分流；关闭或后台释放相机 |
| `rust/adblock` | 网络规则解析/匹配、元素隐藏域名索引及有界 CSS 缓存 |
| `rust/url_utils` | URL/搜索分类和有界 Netscape HTML 书签解析 |
| `rust/site_identity` | PSL/IDNA 站点身份，供过滤和桌面站点别名判断复用 |

### 相机预览

Camera2 的 `TextureView` 已处理传感器方向和默认前摄镜像。`qrPreviewTransform` 按旋转后的缓冲区宽高
撤销拉伸，仅补偿屏幕旋转，再等比例居中裁切；不得重复套用传感器角度或镜像。
`QrCameraController` 监听当前显示器变化，覆盖宽高不变的 180° 旋转，关闭时注销监听；分析帧与解码路径不受预览矩阵影响。
依据：[Android Camera2 预览](https://developer.android.com/media/camera/camera2/camera-preview)、
[可调整尺寸的预览教程](https://developer.android.com/codelabs/android-camera2-preview)。

### 浏览会话

`MainActivity` 承担 Android 回调编排。存储、计算与独立状态机已下沉；`BrowserSessionState`
保留单浏览会话的标签与私密状态，没有跨窗口注册表。

### 包依赖方向

跨模块的共享类型放在 `core`，减少业务包之间的循环依赖。维护时尤其注意这两处约定：

- 网络资源类型：开发工具的网络日志需要资源类型枚举与请求分类，而 `filter` 本就依赖 `core`。
  `ResourceType` 与 `classifyResourceType` 因此移到 `core`；JNI 符号名编码的是 `NativeFilter` 类名，
  枚举位置变化不影响 JNI 解析，但枚举声明顺序仍是 JNI 契约的一部分。
- 播放速度：持久化偏好需要 `PlaybackSpeed`，而 `media` 本就依赖 `data` 的
  `VideoPreferences`。`PlaybackSpeed` 因此移到 `core`。

`media → ui` 是既有且有意的例外：`FullscreenVideoView` 在同一视图内用 Compose 渲染 Material 3
控件，因此 `media` 不是纯领域层。改动播放器前需注意这层耦合。

## 菜单、输入和异步结果

- 菜单进入设置、书签、历史、下载、网站设置、开发工具或媒体选择时保存来路。
  返回只弹出一级；打开网页等操作关闭整个菜单路径。
  只允许“菜单 → 一个子页面”，恢复时拒绝反向或过深的来路。设置分类与选项由设置自身管理。
- 路由 key 用于恢复滚动位置/分类，每次显示的 `Presentation` 用于校验回调身份。
  旧动画的完成回调和重复点击不能关闭后来显示的页面；返回父级也会换新回调身份。
- `BrowserSheetHost` 在整个菜单路径中保留一个 `BrowserSheetWindow`，路由 key 只替换窗口内内容。
  父级保存滚动/分类状态，`SheetWindowContent` 将系统返回交给当前页面；释放旧页面不会清除新页面回调。
  设置、书签和扫码使用全屏布局，其余页面使用固定底部 Surface；二级选项及确认仍有独立输入窗口。
  安全边距从宿主读取，保留键盘避让。所有 Compose 表单与列表关闭边界拉伸，底部弹层不使用拖动锚点；图层变换避免每帧重新测量。
  动效收敛为三种场景（`SheetScene`）：首次进入时表面 240ms 上滑、遮罩随之淡入；同一容器内换页时表面不动、
  内容 120ms + 12dp 并按路由深度给出方向；底部面板与全屏页互切时，本页绘制被替换表面的淡出副本并让新表面滑入，
  同为 120ms。两个容器共用实现；遮罩持续存在，但表面淡出期间不保证全区域不透明，视觉效果需单独检查。
- 地址建议放在同一 Activity 窗口的页面覆盖层内，避免独立 Popup 将 IME 按键和清除按钮视为外部点击。
  编辑结束由提交、页面点击和返回等操作显式驱动，短暂的失焦通知不丢弃输入法组合文字或草稿。
- 同进程 Activity 重建保存来路；外部 VIEW/SEND 导航清空临时面板，MAIN 切回保留当前界面。
  退出确认仅接受浏览器本身连续两次返回，菜单操作清除之前的确认时间。
- WebView 回调校验实例和页面代次；导航取消旧权限、SSL、查找与媒体结果。弹窗使用新 WebView，
  完成交接后重新注册探针/脚本；来源页在交接前保持原生内容有效，交接后按标签数驻留（不设固定槽位），
  不能无条件销毁。`RecentTabStore` 只负责顺序，容量由使用方通过回收决定：创建新页面分配失败时先丢最老页面，
  收到适用的 `onTrimMemory` 压力通知时回收一个，`TRIM_MEMORY_COMPLETE` 才清空；`UI_HIDDEN` 不回收驻留页。
  若新实例已创建但配置失败，先清理它的媒体追踪器、脚本运行时并从池中丢弃，再回收旧页重试。
  普通页的暂停/恢复与手动切换复用同一流程；私密切换、关闭来源与 renderer 退出会撤销对应保留。
- `TabManager` 保留当前会话的来源 ID 与访问顺序。关闭选中页优先来源，其次最近存活页；关闭后台页保持选择。
  返回先消费当前网页历史，再跨标签返回，最后一个标签才进入退出确认。来源关系不写入持久会话，也不能跨普通/无痕管理器。
- 隐藏的开发工具、下载和投屏面板停止对应动态订阅；开发工具仅订阅选中的日志页。
  源码 JSON 解码和 HTML/JS/CSS 词法扫描移到 Default，最多 100 万字符、4096 块，每块最多 768 字符/12 行，
  Compose 只测量可见块；控制台也使用惰性列表。来源代次使同 URL 刷新及旧命令回执失效。
  网络日志按最先到达的主请求建立导航记录，页面开始回调只确认已有记录；仅合并待补齐的主文档占位条目，
  避免早期子资源被清空，也不会将连续刷新误合并为同一次请求。
  投屏轮询仅前台可见时运行，命令串行且丢弃迟到结果。

菜单的详细规则见[菜单导航设计](design/menu-navigation-20260910.md)。

## 媒体生命周期

系统媒体所有权与视频元素是否仍可继续播放分别追踪。停止或离开时发布 STOPPED、清除元数据并 release 系统 token，
不能只设为 inactive；独立定时任务清除消失 frame 的旧状态。
后台媒体服务在每次 `onStartCommand` 先应答前台启动，停止请求作为同一服务的有序命令处理；
`stopSelfResult(startId)` 不终止较新的启动，避免直接 `stopService` 抢先撤销待处理的前台请求导致进程崩溃。暂停标签/PiP 时立即归一为暂停，丢弃迟到的播放状态。
全屏视频挂载在 Activity content 容器内以跟随 PiP 尺寸变化；PiP 过渡使用进入前的窗口内画面区域，
不把浮窗的屏幕位置在每次播放状态更新时反复写回。
`FullscreenVideoView` 保留 Chromium 视频视图和原生手势，`PlayerControls` 用 Compose Material 3 渲染控件。
倍速和投屏面板在同一个控件层中锚定右下角，不创建独立窗口；安全边距只应用于控件，视频视图保持全尺寸。
投屏面板复用 `CastSheet` 内容与进程级控制器，可见时发现设备/轮询，关闭后停止；返回先关闭面板，再解锁或退出。

## 数据、并发与复用

启动更新检查及待处理提示由 `App` 持有，Activity 仅订阅和显示，避免配置重建取消检查或丢失提示。

更新包与相机输出使用不同的 FileProvider 组件类和目录。Android 按组件类名登记 Provider，
仅更改 authority 会使两个声明混用组件，导致系统安装器无法取得 URI 读取权限；两者均不导出，仅授予单个 URI 的临时权限。

- 书签/历史共享 SQLite helper。书签 upsert 保留 ID/创建时间，历史合并重复访问；分页查询
  对 `%`、`_`、`!` 转义并限制输入和结果。写入完成后才报告成功；旧查询不能覆盖新搜索。
- 文件内容、JSON、HTML、XML、下载 header、规则和脚本均设边界。过滤/脚本复用
  `AtomicFile.writeUtf8`；条目只序列化一次，下载进度快照按顺序发布。
- 网站设置的写锁跨 Activity 重建共享。权限按完整 origin 管理，网站同意与 Android
  系统授权分开；请求身份使导航取消和迟到回执不会授权新页面。
- 桌面显示偏好与权限范围分离：同协议/端口下的裸域、m.、mobile.、www. 展示入口共用桌面偏好，
  其他设置仍按 origin 保存。相同 UA 不重复写入，桌面视口在新文档完成后应用。
- 下载记录保存 validator、总长度、最终实体 URL 与分段边界。暂停/继续共用每任务 Mutex；
  取消/删除等待旧 writer 结束。实体变化或无法验证时完整重下，拒绝异常 206 和错误范围。
- Cookie 仅驻留内存，重定向仅向原 origin 发送；普通重试读取当前 Cookie，无痕任务不跨进程恢复。
  MediaStore/SAF 发布成功后才显示完成，删除本地文件失败时保留记录。
- 用户脚本 ID 只计算一次，GM 值变化只重注册对应脚本；媒体 hints 未变化就复用快照，单次
  更新每个 URL 只解析一次，并保留编码路径和签名查询的资源身份。

已加载的 WebView Profile 不能在当前进程直接删除。无痕按会话生成专用前缀的唯一名称，退出先清理
站点数据/Cookie 并退休该名称；下次进程启动枚举并删除遗留 Profile。清理失败也不复用旧会话，
清理期间阻止再次进入无痕。Activity 重建继续使用当前会话，不能误当成全新无痕。

## Rust 选择

| 模块 | 当前选择 | 理由 |
|---|---|---|
| 网络过滤、元素隐藏 | Rust | 批量纯计算，共享索引与缓存；已有参考算法对照和主机测量 |
| URL 与书签 HTML | 现有 `url_utils` JNI 库 | 输入/输出边界清晰，有界线性解析；书签保留 Android SAF、预览与 SQLite 单事务 |
| UI、WebView、权限和生命周期 | Kotlin/Android | 平台负责渲染和生命周期；增加 JNI 不能替代平台语义 |
| 数据库、下载、文件名和存储 | Kotlin/Android | 主要依赖 SQLite、HTTP、SAF、MediaStore、通知和 Cookie；没有另引 Rust 数据库/HTTP/TLS 的端到端收益证据 |
| 网页探针、脚本运行时、DOM 控制权交接 | JavaScript | 直接访问 DOM 与网站播放器，不将整页跨语言复制 |
| 源码着色与扫码 | Kotlin 工作线程 / ZXing | 主要瓶颈为 UI 大文本排版；惰性块、缓存和按需发布已消除同步长任务，扫码复用成熟离线算法，未发现需要增加 JNI 的收益依据 |
| DLNA/SSDP/SOAP | Kotlin | 有界网络/XML 和低频命令；收益来自状态一致性及设备兼容 |

当前 APK 只包含 `adblock` 和 `url_utils` 两个产品 JNI 库，共用 `site_identity` crate。
下载、文件名和数据库仍由 Kotlin 实现；早期未使用的 Rust 实验模块及可选构建开关已删除。

## 计算性能与测量边界

0.4.1 网络匹配优化共用阻止/例外索引，借用域名后缀，无星号直接匹配；非锚定通配符只执行
一次 DP，复杂度为 O(模式长度 × URL 长度)。参考匹配穷举和索引/线性扫描等价性测试覆盖正确性。

历史主机对照使用 118,828 条网络规则、12 类请求各 10 轮，包含约 2 KiB 的签名 URL：

| 指标 | 优化前 | 优化后 |
|---|---:|---:|
| 匹配中位数 | 34.398 ms | 0.0189 ms |
| 匹配 P95 | 31,278.816 ms | 0.619 ms |
| 引擎加载 | 75.128 ms | 76.437 ms |

旧 P95 由长 URL 重复构造 DP 主导；这组固定样本不代表整页或真机提速。
0.5.0 的元素隐藏迁移记录见[更新日志](CHANGELOG.md)；JNI 编译优化的另一组历史对照见
[系统集成说明](design/system-integration.md#性能和依赖选择)。

以下命令测量当前源码，可用于观察后续修改的计算成本：

```bash
cargo run --locked --release --manifest-path rust/Cargo.toml -p adblock --example benchmark -- 10
python3 validation/cosmetic-benchmark.py
```

以上命令不包含 JNI、DOM 注入、网络、解码或手机功耗。历史数据只用于解释当时的实现选择，
当前设备的表现需要重新测量。

## 构建和源码管理

Gradle 与独立构建共用 `rust/build.sh`；NDK 统一由 `rust/resolve-android-ndk.sh` 解析。
Gradle 声明脚本、Cargo 配置、源码和 minSdk/ABI 为输入，避免脚本更新漏编。
Rust 使用 API 29 链接器及独立目标目录；每次 staging 清理当前 ABI，APK 仅含两个产品 JNI 库及 AndroidX 库。
R8 全模式与资源裁剪保留实际可达代码，DEX/native 使用可安装的 ZIP 压缩。

开发环境和提交约定见[贡献指南](CONTRIBUTING.md)，CI、签名和发布步骤见[发布指南](RELEASING.md)。
