# 架构与维护边界

2026-09-12，0.7.1。当前功能和安装包以 [README](README.md) 为准，实际检查结果见
[回归报告](EMULATOR_TEST_REPORT.md)。本文件集中记录代码职责、复用方式和语言选择。
当前实施与验证边界见 [实施记录](design/implementation-status.md)。

## 代码职责

| 范围 | 职责与状态所有者 |
|---|---|
| `MainActivity`、`core` | WebView、ActivityResult、外部 Intent、导航及平台生命周期；池归还时解除监听，恢复历史使用尚未导航的新 WebView |
| `ui` | Compose 界面、主题、输入及反馈；`BrowserSheetNavigation` 保存菜单来路，只挂载栈顶，父级只保存 UI 状态 |
| `data`、`home`、`tabs` | Android SQLite 书签/历史、快捷入口、标签元数据与最近关闭；后台标签延迟加载，缩略图只保留小尺寸 Bitmap |
| `download` | HTTP Range 引擎、实体校验、分段恢复、SAF/MediaStore、前台服务和记录管理；兼容读取已存在的系统下载记录 |
| `filter`、`userscript` | 过滤订阅更新与引擎切换、用户脚本元数据/存储及逐 frame 注入；共享有界读取和原子 UTF-8 写入 |
| `site` | 网站偏好、站点/系统权限状态机；仓库由 Application 单实例持有 |
| `privacy`、`security` | WebView Profile 或退出清理、证书错误状态、外部协议限制 |
| `media`、`dlna` | 媒体候选、播放元素追踪、内嵌和全屏控件、SSDP 与 AVTransport；投屏会话由进程级控制器管理 |
| `rust/adblock` | 网络规则解析/匹配、元素隐藏域名索引及有界 CSS 缓存 |
| `rust/url_utils` | URL/搜索分类和有界 Netscape HTML 书签解析 |

`MainActivity` 承担 Android 回调编排。存储、计算与独立状态机已下沉；`BrowserSessionState`
保留单浏览会话的标签与私密状态，没有跨窗口注册表。

## 菜单、输入和异步结果

- 菜单进入设置、书签、历史、下载、网站设置、开发工具或媒体选择时保存来路。
  返回只弹出一级；打开网页、分享、打印、应用倍速等操作关闭整个菜单路径。
  只允许“菜单 → 一个子页面”，恢复时拒绝反向或过深的来路。设置分类与选项由设置自身管理。
- 路由 key 用于恢复滚动位置/分类，每次显示的 `Presentation` 用于校验回调身份。
  旧动画的完成回调和重复点击不能关闭后来显示的页面；返回父级也会换新回调身份。
- `BrowserSheetHost` 保存父级 UI 状态并释放父级窗口。菜单滚动状态置于 Dialog 外；安全边距
  从主窗口读取，避免拖动手柄落在状态栏内。设置管理弹层打开时禁用父级返回处理。
  路由外层显式使用 `key(route.key)`，切换设置全屏页面和菜单 Dialog 时销毁旧组合，避免复用旧返回处理。
- 地址建议放在同一 Activity 窗口的页面覆盖层内，避免独立 Popup 将 IME 按键和清除按钮视为外部点击。
  编辑结束由提交、页面点击和返回等操作显式驱动，短暂的失焦通知不丢弃输入法组合文字或草稿。
- 同进程 Activity 重建保存来路；外部 VIEW/SEND 导航清空临时面板，MAIN 切回保留当前界面。
  退出确认仅接受浏览器本身连续两次返回，菜单操作清除之前的确认时间。
- WebView 回调校验实例和页面代次；导航取消旧权限、SSL、查找与媒体结果。弹窗使用新 WebView，
  完成交接后重新注册探针/脚本，再释放旧实例。
- 隐藏的开发工具、下载和投屏面板停止对应动态订阅；开发工具源码按需读取并设长度上限。
  投屏轮询仅前台可见时运行，命令串行且丢弃迟到结果。

完整菜单回归与根因见 [菜单导航设计](design/menu-navigation-20260910.md)。

系统媒体所有权与视频元素是否仍可继续播放分别追踪。停止或离开时发布 STOPPED、清除元数据并 release 系统 token，
不能只设为 inactive；独立定时任务清除消失 frame 的旧状态。暂停标签/PiP 时立即归一为暂停，丢弃迟到的播放状态。
全屏视频挂载在 Activity content 容器内以跟随 PiP 尺寸变化；PiP 过渡使用进入前的窗口内画面区域，
不把浮窗的屏幕位置在每次播放状态更新时反复写回。

## 数据、并发与复用

- 书签/历史共享 SQLite helper。书签 upsert 保留 ID/创建时间，历史合并重复访问；分页查询
  对 `%`、`_`、`!` 转义并限制输入和结果。写入完成后才报告成功；旧查询不能覆盖新搜索。
- 文件内容、JSON、HTML、XML、下载 header、规则和脚本均设边界。过滤/脚本复用
  `AtomicFile.writeUtf8`；条目只序列化一次，下载进度快照按顺序发布。
- 网站设置的写锁跨 Activity 重建共享。权限按完整 origin 管理，网站同意与 Android
  系统授权分开；请求身份使导航取消和迟到回执不会授权新页面。
- 0.5.2 将桌面显示偏好与权限范围分离：同协议/端口下的裸域、m.、mobile.、www. 展示入口共用桌面偏好，
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
| 网页探针、脚本运行时、视频控件 | JavaScript | 直接访问 DOM 与网站播放器，不将整页跨语言复制 |
| DLNA/SSDP/SOAP | Kotlin | 有界网络/XML 和低频命令；收益来自状态一致性及设备兼容 |

0.5.1 删除了没有产品或测试调用的旧 `NativeCache`、`NativeDownloader`、
`com.mybrowser.rust.FilenameParser`、`DatabaseManager` 及四个对应 Rust 实验目录。
实际下载使用的 `download/FilenameParser.kt` 及其测试保留。旧可选构建开关一并删除，
Cargo.lock 从 184 个 package 缩为 32 个，保留依赖的版本不变。

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
0.5.0 元素隐藏迁移的原 Kotlin/Rust 对照及 80 个 host 的输出一致性记录见
[系统能力说明](design/system-integration.md)。

当前基准不依赖已删除的备份分支，只测量当前源码；生成文件临时保存，最终保留结果 JSON：

```bash
cargo run --locked --release --manifest-path rust/Cargo.toml -p adblock --example benchmark -- 10
python3 validation/cosmetic-benchmark.py
```

以上均不包含 JNI、DOM 注入、网络、解码或手机功耗。旧原始构建与对照产物已按要求清理，
历史数值仅作为实现决策记录；最新性能需重新测量，不能将历史结果算作当前设备覆盖。

## 构建和源码管理

Gradle 与独立构建共用 `rust/build.sh`；NDK 统一由 `rust/resolve-android-ndk.sh` 解析。
Gradle 声明脚本、Cargo 配置、源码和 minSdk/ABI 为输入，避免脚本更新漏编。
Rust 使用 API 29 链接器及独立目标目录；每次 staging 清理当前 ABI，APK 仅含两个产品 JNI 库及 AndroidX 库。
R8 全模式与资源裁剪保留实际可达代码，DEX/native 使用可安装的 ZIP 压缩。

源码以 `master` 正常提交维护，不保留备份/回滚分支、重复图标、兼容等待脚本和多份交付摘要。
APK、生成验证证据、SDK 路径与签名材料留在 Git 外；本地仅保留最新 APK 及必要验证记录。
