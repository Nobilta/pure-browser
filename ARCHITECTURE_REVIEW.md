# Pure 浏览器：架构审查与重构决策

更新时间：2026-09-05

这份文档记录当前源码的真实边界、已经落地的整理，以及没有采用“为了 Rust 而 Rust”方案的原因。它和构建产物一起作为后续维护的基线。

## 1. 当前分层

```text
app/src/main/java/com/mybrowser/
├── MainActivity.kt       生命周期编排：把 WebView、状态、系统回调接到一起
├── core/                 WebView 客户端、导航策略、URL 安全、日志、池化
├── data/                 Android SQLite 书签/历史，以及可选的 Rust 缓存门面
├── download/             分段 HTTP 引擎、目标写入、前台服务、记录管理和 legacy 兼容
├── filter/               广告规则加载、开关、统计、自定义列表
├── privacy/              无痕模式、WebView profile 与清理策略
├── search/               搜索引擎、模板校验、URL/搜索统一决策
├── tabs/                 轻量标签元数据、缩略图和 WebView 状态保存
├── media/                网络媒体嗅探、实际播放元素追踪、候选排序
├── dlna/                 SSDP 发现和 UPnP AVTransport 投屏
├── security/             页面安全信息
└── ui/                   Compose 浏览器界面和各类 sheet/dialog

rust/
├── adblock/              规则解析和匹配（默认打包）
├── cache/                有界 LRU 字节缓存（默认打包）
├── url_utils/            URL/搜索纯逻辑 JNI 快速路径（默认打包）
├── downloader/           私有目标下载原语（legacy，显式 opt-in）
├── filename_parser/      文件名解析 JNI 原语（legacy，显式 opt-in）
└── database/              早期实验实现，未加入 workspace、未打包（quarantined）
```

`MainActivity` 仍然较大，但它现在是一个明确的生命周期 orchestrator，而不是把业务算法散落在 UI 回调中。导航已经抽成 `core/NavigationPolicy.kt`；数据库、媒体、下载和过滤器各自拥有独立边界。继续把 Activity 拆成多个 ViewModel 只有在引入持久的多窗口/后台任务需求时才有明显收益，当前强行拆分会增加 WebView 回调与 ActivityResult 生命周期的竞态。

## 2. 已实施的工程优化

- WebView 使用 `MutableContextWrapper` 池化，限制同时存活实例，renderer 崩溃时丢弃尸体并重建；脱离 Activity 的实例切换到 `DetachedWebViewClient`。
- 地址栏、键盘 Go、可见“访问/搜索”按钮共用 `NavigationPolicy`；HTTP(S) 校验、外部 scheme allow-list 和未知 opaque scheme 的搜索回退在 Kotlin/Rust 两侧一致。
- 媒体候选使用 `StateFlow`，文档开始脚本在主文档和 iframe 中追踪真正播放的 `<video>`，投屏列表用“正在播放”标记首选流，候选数量有上限。
- SQLite helper 采用进程内引用计数共享；书签是显式 upsert（保留 id/createdAt），历史访问合并计数，LIKE 搜索转义 `%`、`_`、`!` 并限制输入/结果长度。
- WebView 控件回调都检查当前实例；弹窗先验证 `WebViewTransport`，favicon 重复回调不会回收仍在使用的 Bitmap；文件、权限、JS 对话框、SSL 和安全浏览回调均有释放路径。
- 自定义搜索引擎现在限制名称、模板、数量，只接受带一个占位符的 HTTP(S) 模板；设置页提供添加/删除入口，坏的偏好 JSON 会被忽略。
- Rust 构建由共享的 `rust/resolve-android-ndk.sh` 解析 NDK，不再依赖个人绝对路径。Gradle 默认只生成并打包 `adblock`、`cache`、`url_utils` 三个实际使用的库；每次 staging 会先清掉旧 ABI 目录。
- 旧的 checked-in `app/src/main/jniLibs` 二进制和重复 Cargo 配置不再作为源码输入，避免 stale JNI 库混入 APK。
- 下载设置由独立 repository 持久化：目标可为 MediaStore 的系统 Download 目录或用户经
  SAF 授权的目录，线程数统一限制为 1–16。传输引擎会验证 Range/Content-Range/长度并在
  服务端不支持分段时安全回退单线程；发布文件、重名处理和删除由目标写入层负责。
- `DownloadTransferService` 使用 `dataSync` 前台服务承接进程级任务，Activity 重建不会
  取消下载；任务记录区分新引擎与旧 Android DownloadManager 条目，旧记录仍可查询、打开
  和删除。失败或系统超时暂停的任务可重试，删除本地文件失败时保留记录供用户再次处理。

## 3. Rust 取舍

### 保留在 Rust

| 模块 | 决策 | 理由 |
|---|---|---|
| `adblock` | 保留并默认打包 | 规则解析/匹配是高频、纯计算路径；共享不可变索引和有界匹配适合 Rust。 |
| `cache` | 保留并默认打包 | 字节级 LRU 需要严格的总容量和线程安全；JNI 边界只传二进制。 |
| `url_utils` | 保留快速路径并有 Kotlin 回退 | 逻辑纯、容易测试；性能收益有限，但可作为统一实现，失败时不影响浏览。 |

### 维持 Kotlin/Android 原生

| 模块 | 决策 | 理由 |
|---|---|---|
| 书签/历史数据库 | 不迁移到 Rust | Android SQLite 已负责连接、迁移、生命周期和事务；Rust 版会维护第二套 schema，并复制 Cursor/字符串，收益不足。 |
| 下载引擎与存储 | 保留 Kotlin/Android | Range 网络传输需要和 SAF、MediaStore、前台服务、通知、WebView Cookie/UA/Referer 及生命周期协作；Rust HTTP/TLS 会增加约 3 MB 和 JNI 复制，不能改善这些平台边界。 |
| WebView/Compose/UI | 不迁移 | 生命周期、ActivityResult、权限和渲染器必须使用 Android API；Rust 只能增加 JNI 胶水。 |
| 页面内搜索 | 不迁移 | WebView `findAllAsync` 已在渲染器内完成，不应复制整页 HTML 到 native。 |

`downloader` 和 `filename_parser` 仍保留源码以兼容早期调用者，但默认构建不会编译/打包；需要旧 JNI 集成时使用 `-Pmybrowser.includeLegacyRust=true` 或 `INCLUDE_LEGACY_RUST=1`，并自行承担体积与维护成本。`rust/database` 明确隔离，不应重新接回 APK。

## 4. 构建与验证基线

```bash
cd rust
cargo fmt --all
cargo test --all

cd ..
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
./gradlew clean :app:assembleRelease --console=plain
unzip -l app/build/outputs/apk/release/app-release.apk | rg 'lib/|META-INF'
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

Release 当前目标为 `arm64-v8a`、minSdk 34；安装前应确认 APK 中只有三个默认 native 库且不存在 `database`、`downloader`、`filename_parser`。模拟器回归至少覆盖启动、地址栏搜索/访问、标签、书签编辑、无痕切换、系统/SAF 下载目录、线程设置与持久化、分段下载、两种删除方式、投屏按钮和直播页当前流标记；日志中不应出现 `FATAL EXCEPTION`、`UnsatisfiedLinkError` 或 `SIGSEGV`。

## 5. 本轮系统审查落地

- 下载 SharedPreferences 恢复和写入都有 JSON、条目、URL、文件名、MIME、UA、Referer
  与 Content-Disposition 上限；只接受带主机的 HTTP(S) URL，并清理文件名和 header 控制字符。
- 下载设置、分段传输、MediaStore/SAF 发布和后台服务已按职责拆分；Cookie 只保存在进程
  内存中。文件删除按 URI 类型分别调用 MediaStore 或 DocumentsProvider API，不会用
  “删记录成功”掩盖本地文件删除失败。
- `PrivacyMode` 的 Cookie 清理改为 completion callback；清理完成后才提示用户。无 profile
  的无痕退出会先清理共享存储，再加载普通标签，避免旧 Cookie 在窗口期被读取。
- `NativeCache` 的所有 JNI 句柄访问与 `close()` 共用同一生命周期锁；Kotlin 与 Rust 两侧
  都限制 key/entry 大小，Rust LRU 增加了单元测试。
- DLNA SOAP、SSDP 设备描述和错误详情均采用有界读取；搜索任务在锁内保存 Job，取消不会
  错过刚创建的协程。设备描述文本、URL 和搜索超时也有边界。
- 标签元数据恢复只接受受限 ID/文本和 HTTP(S)/about:blank，避免偏好数据直接变成
  `javascript:` 或本地文件导航；SQLite repository 的查询/关闭通过同步方法避免生命周期竞态。
- 构建脚本全部基于脚本目录，旧的等待脚本只是兼容包装器；不再读取其他任务的临时文件、
  不再覆盖 `.zshrc` 或全局 Cargo 配置。项目 `.cargo/config.toml` 不含开发者绝对路径。
- 修正了 WebView 池归还时的容量计算，避免无痕切换的 `acquireFresh()` 留下额外实例。
- 清除浏览数据现在先确认且只清除历史、Cookie、WebStorage、认证、定位授权与 WebView
  缓存，不再误删书签或系统下载文件；异步 Cookie 删除完成后才刷新页面和提示。
- 自定义过滤列表、书签草稿和 Bitmap 缓存入口增加了持久化/数量/长度/像素边界；损坏
  偏好不会再触发无界解析或重复提交。
- 删除了未调用的旧 `TabSheet`、SSL 包装器、自定义规则 Rust 残留代码和无用 JNI 导出；
  同时清理 69 个未使用资源并迁移 adaptive icon 目录。Android lint 从 101 条降至 2 条
  有意保留的工具链/发版 ABI 提示，仍为 0 errors。
- 安装脚本明确启动 `MainActivity`，并用花括号包住 shell 变量，已在模拟器上完整执行。

## 6. 后续边界

真正值得下一步投入的工作是 UI 测试（Compose semantics）、下载跨进程死亡后的自动续传、
多窗口状态持久化，以及真实 DLNA 设备矩阵测试。它们属于产品/平台验证，不是把剩余 Kotlin
代码机械翻译成 Rust；在没有基准数据和明确 JNI 契约前，不建议继续扩大 native 面积。

最终审查仍不建议为了缩短 `MainActivity.kt` 而强拆 ViewModel：它的大部分代码是 WebView、
ActivityResult 和权限回调的同生命周期编排，计算与持久化已经下沉。若以后加入多窗口或
跨进程可恢复工作流，再按这些真实生命周期边界拆分，而不是按文件行数拆分。
