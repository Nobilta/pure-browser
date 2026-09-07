# Pure 浏览器当前状态

更新时间：2026-09-07

## 结论

项目目前是可构建、可安装的 Android 10+ 浏览器。核心浏览、标签、书签、历史、下载、
无痕、广告过滤、媒体检测和投屏选择流程已经接通；Release 交付目标为 arm64-v8a。
源码真实状态以 `app/src/main`、`rust/` 和 `ARCHITECTURE_REVIEW.md` 为准。

## 功能状态

| 模块 | 状态 | 说明 |
|---|---|---|
| WebView 浏览/导航 | 已完成 | 进退、主页、刷新/停止、弹窗、全屏、文件上传、权限回调 |
| 地址栏 | 已完成 | URL/搜索分类，聚焦显示“访问/搜索”按钮和建议 |
| 多标签 | 已完成 | 最多 32 个轻量标签，WebView 池最多保留 4 个实例 |
| 书签/历史 | 已完成 | Android SQLite；添加书签可编辑标题和 URL |
| 下载 | 已完成 | 系统/SAF 目录、1–16 线程分段传输、前台服务、重试、打开和可选同步删文件 |
| 无痕 | 已完成 | WebView 多 profile 优先；不支持时明确采用退出清理降级 |
| 广告过滤 | 已完成 | Rust matcher，内置列表和自定义列表均有大小上限 |
| 媒体/投屏 | 已完成 | 当前播放流标记、右下角悬浮入口、DLNA 设备选择 |
| 开发者工具 | 已完成 | 有界控制台和网络请求日志 |

## 工程审查结果

- 分层按职责组织在 `core/data/download/filter/privacy/search/tabs/media/dlna/security/ui`；
  `MainActivity` 是生命周期编排器，不是计算逻辑容器。
- JNI 句柄、JSON 偏好、网络响应、文件名和用户输入均有边界；关闭与后台查询有同步保护。
- 默认 Rust 产品路径为 `adblock`、`url_utils`。cache 等 legacy crate
  可 opt-in，实验性 database 被隔离。
- 项目 `.cargo/config.toml` 已去除个人绝对路径，脚本不再依赖旧临时目录或修改 shell 配置。

## 已知边界

- Release 当前只编译 arm64-v8a；x86_64 用 `-Pmybrowser.abi=x86_64` 显式构建调试包。
- 投屏需要同一局域网和真实接收器；无实体设备时只能验证候选识别、标记和 UI。
- 无痕降级模式在旧 WebView provider 上与普通模式共享 Cookie，退出时清空共享 Cookie；
  UI 会明确显示该差异。
- 同步、密码管理、阅读模式和浏览器数据导入导出属于未来产品范围，不应在当前文档中
  被描述为已实现。

## 验证入口

```bash
./build-and-test.sh
./diagnose.sh
```

详细架构与 Rust 取舍见 [ARCHITECTURE_REVIEW.md](./ARCHITECTURE_REVIEW.md)，最新 APK
校验信息见 [FINAL_DELIVERY_SUMMARY.md](./FINAL_DELIVERY_SUMMARY.md)。
