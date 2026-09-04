# 当前实施报告

早期版本的实施报告描述了尚未接入的 Rust database、text_search、json_parser，并给出了
没有 benchmark 支撑的性能倍数；这些内容已废弃。当前实现和本轮改动如下：

## 已实现

- 地址栏访问/搜索动作、书签新增编辑、媒体当前播放识别和悬浮投屏入口。
- WebView 池化、renderer 崩溃恢复、文件上传、全屏、JS 对话框和权限回调。
- Android SQLite 书签/历史、Kotlin 分段下载与 Android 存储编排、Rust 广告过滤/缓存/URL 工具。
- DLNA SSDP/设备描述/AVTransport，候选和网络响应均有大小上限。

## 本轮工程优化

- 下载与标签偏好 JSON、输入字段、文件名和 header 有界；损坏数据安全丢弃。
- 下载目录可选 MediaStore 系统 Download 或 SAF 目录，线程数可设为 1–16；任务由前台服务
  承接，列表可仅删记录或同步删除本地文件。
- Cookie 清理等待异步完成；数据库仓库关闭与查询同步；WebView 池和 DLNA 任务竞态修正。
- Rust cache JNI 生命周期锁与输入限制；全 workspace `clippy -D warnings` 通过。
- 构建脚本移除个人绝对路径、临时任务文件和全局配置写入。

## Rust 取舍

默认只打包 `adblock`、`cache`、`url_utils`。下载引擎与存储编排、SQLite、
WebView/Compose/UI 和页面内查找保留 Kotlin/Android；legacy crate 不代表默认产品路径。完整决策见
[RUST_OPTIMIZATION_ANALYSIS.md](./RUST_OPTIMIZATION_ANALYSIS.md)。

## 验证

```bash
./build-and-test.sh
```

脚本输出当前 APK 的真实大小、SHA-256 和签名结果；请勿引用旧报告中的固定数字。
