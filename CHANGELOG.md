# 更新日志

## 0.1.0 — 2026-09-05

### 功能

- 顶部地址栏聚焦时显示外侧“访问/搜索”按钮，键盘 Go 与按钮共用 URL 分类逻辑。
- 菜单支持添加、编辑和删除书签；默认带入当前页面标题与 URL。
- 菜单按页面操作、浏览数据、隐私与安全、设置与工具四类重新归并。
- 新增 Material 3 原生导航首页；可在设置中切换导航首页与固定网址主页。
- 添加书签时可同时创建带网页 favicon 的首页快捷入口；长按可仅从首页移除。
- HTTPS 安全弹窗显示当前证书使用者、组织、签发机构、有效期和当前有效状态。
- 直播/视频页检测当前实际播放元素，右下角显示悬浮投屏入口；选择页标注“正在播放”。
- 当前视频支持 0.5×–3× 倍速播放，逐帧 WebMessage 控制可覆盖跨域 iframe 播放器。
- 下载目录可选系统 Download 或 SAF 自定义目录，下载线程数可在 1–16 之间设置。
- 新增 HTTP Range 分段下载与 `dataSync` 前台服务，不支持 Range 时自动回退单线程。
- 下载列表支持失败/暂停重试，并可选择仅删除记录或同时删除对应的本地文件。
- Pure 浏览器品牌、清新图标、无痕模式、标签、下载、页面查找和开发者工具完成整合。

### 稳定性与边界

- WebView 池化、renderer 崩溃重建、旧回调隔离和 ActivityResult 回收路径完善。
- 下载/标签/搜索引擎偏好 JSON 有长度和条目上限；文件名、header 和 URL 输入经过清理。
- Cookie 清理等待异步完成回调；SQLite 查询/关闭、DLNA 搜索取消和 WebView 池容量竞态已修复。
- DLNA SOAP/SSDP/设备描述响应采用有界读取，避免局域网设备导致无限内存分配。
- 清除浏览数据增加确认并改为只清理浏览状态，不再删除书签和下载文件。
- 自定义过滤列表持久化、书签草稿、NativeCache 位图和重复提交补齐上限与校验。

### Rust

- 默认构建 `adblock`、`cache`、`url_utils` 三个实际产品模块。
- NativeCache JNI 句柄访问与关闭共用生命周期锁，Rust/Kotlin 两侧均限制 key/entry 大小。
- `downloader`、`filename_parser` 保留为 opt-in legacy；`database` 实验模块隔离，不进 APK。
- Rust workspace 通过 `cargo fmt`、`cargo test` 和 `cargo clippy -- -D warnings`。

### 工程与文档

- 构建、安装、诊断脚本改为基于脚本自身目录，移除旧任务临时路径和全局配置写入。
- 更新 README、架构审查、质量报告、测试报告和交付清单，删除未经验证的性能倍数和旧 APK
  信息。
- 删除重复标签页 UI、未使用的 SSL 包装器和 69 个无用资源；29 处调用统一为 KTX，修复
  安装脚本启动/变量解析，并在 API 34 arm64 模拟器复测最终 APK。

## 历史记录说明

早期版本曾规划 Rust database、text_search、json_parser 和自建 downloader。这些内容没有
进入当前 workspace 或默认 APK；旧实现如需兼容调用者，必须按 README 中的 opt-in 方式构建，
不能据此推断当前产品已使用这些模块。
