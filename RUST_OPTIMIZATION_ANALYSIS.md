# Rust 重构评估

更新时间：2026-09-05

## 结论先行

没有必要把整个 Android 浏览器改写成 Rust。WebView、Compose、Activity 生命周期、权限、
SAF/MediaStore、前台服务和下载通知都是 Android 平台语义；改写只会增加 JNI 胶水、另一套
网络/TLS 依赖、线程切换和版本兼容成本。当前采用“Rust 计算内核 + Kotlin 平台编排”的
边界更稳定。

## 已落地并默认打包

| crate | 适合原因 | JNI 约束 |
|---|---|---|
| `adblock` | 规则解析和高频匹配，索引可复用 | Kotlin 读写锁保护 native handle |
| `cache` | 字节级 LRU、容量和淘汰逻辑纯粹 | key/entry 有上限；Kotlin close 与访问共用锁 |
| `url_utils` | URL/搜索分类是纯函数，易做 Kotlin fallback | native 失败时自动回退，不影响导航 |

## 明确不迁移

- `BrowserWebViewClient`、`BrowserChromeClient`、`MainActivity`、Compose UI：必须调用
  Android/WebView 生命周期 API。
- `BrowserDatabase`、书签和历史：Android SQLite 已提供 schema、事务和生命周期；Rust
  版本会制造第二套数据源。
- 公共下载：Kotlin 分段引擎与 Android 的 SAF、MediaStore、`dataSync` 前台服务、通知、
  WebView Cookie/UA/Referer 直接协作；Rust downloader 会额外引入约 3 MB 网络/TLS 依赖，
  仍不能替代这些平台语义。旧系统 DownloadManager 记录只保留兼容查询/打开/删除能力。
- 页面内查找：WebView renderer 内部执行 `findAllAsync`，不复制整页文本到 native。
- 普通 JSON、偏好和 DLNA UI：数据量小，Kotlin 更易维护；真正重要的是有界输入。

## Legacy 与隔离模块

`rust/downloader`、`rust/filename_parser` 为早期 JNI 调用者保留，只有显式传入
`-Pmybrowser.includeLegacyRust=true` 或 `INCLUDE_LEGACY_RUST=1` 才构建。`rust/database`
含早期实验代码，未加入 workspace，也不进入 APK；重新接入前必须先有迁移方案、schema
测试和基准数据。

## 下一步是否值得 Rust

只有在 profiler 证明某个纯计算路径占用明显 CPU，且 JNI 一次传递的数据可以保持很小，才
考虑新 crate。可能候选是规则编译/媒体 URL 规范化的进一步优化；阅读模式、HTML 解析、
同步加密等应先有产品需求和真实 benchmark，再决定语言。未经 benchmark 不写“3x/17x/33x”
等固定收益。

## 验证

```bash
cd rust
cargo fmt --all -- --check
cargo test --all
cargo clippy --workspace --all-targets -- -D warnings
```

Android 侧通过 Gradle 编译和 lint 后，再检查 APK 只包含默认三个 native 库。
