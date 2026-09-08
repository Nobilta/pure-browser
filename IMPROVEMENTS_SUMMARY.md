# 工程优化摘要

更新时间：2026-09-05

本文为该日期的历史快照；悬浮入口、默认 Rust 模块和测试数量已有调整，最新信息见 [README](./README.md)。

## 已完成

- WebView 池化与 renderer 崩溃恢复，旧回调隔离，ActivityResult 和媒体 tracker 生命周期
  收口。
- 地址栏统一由 `NavigationPolicy` 决定 URL/搜索；书签、历史、下载和自定义搜索引擎均有
  输入上限与失败回退。
- 媒体检测按实际播放元素排序，右下角悬浮投屏按钮与 DLNA 选择界面接通。
- Rust 默认模块收敛为 `adblock`、`cache`、`url_utils`；下载引擎/Android 存储编排和
  SQLite 保持 Kotlin/平台原生。
- 下载新增系统 Download/SAF 自定义目录、1–16 线程 Range 分段传输、后台前台服务、
  失败重试，以及“仅删记录/同时删除文件”两种安全删除路径；旧 DownloadManager 记录兼容保留。
- 本轮补上下载/标签偏好、Cookie 清理、NativeCache JNI、DLNA 响应、数据库关闭和构建脚本
  的边界保护。
- 清除浏览数据增加确认并只清浏览状态，书签和下载文件会保留；过滤器、书签草稿与位图
  缓存补齐输入边界和重复提交保护。
- 删除重复 UI、未调用包装器和 69 个无用资源，29 处调用统一为 KTX；lint 从 101 条降至
  2 条经过评估后保留的环境提示（0 errors）。

## 验证

```bash
cd rust && cargo fmt --all -- --check && cargo test --all \
  && cargo clippy --workspace --all-targets -- -D warnings
cd .. && ./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:assembleRelease --console=plain
```

## 不采用的方案

不把整个浏览器或 SQLite/下载存储编排/UI 迁移到 Rust；没有可复现 benchmark 时不承诺
固定倍数性能提升。未来新增 native 模块必须先有 profiler 证据和稳定 JNI 契约。
