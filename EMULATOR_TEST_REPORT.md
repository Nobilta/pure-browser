# 模拟器回归报告

更新时间：2026-09-05

## 环境

- 设备：`emulator-5554`
- Android：API 34，arm64
- 包：`com.mybrowser`（Release）
- 可控媒体回归页：`http://10.0.2.2:8765/media-fixture.html`（项目 `validation/` 夹具）
- APK：1,910,313 bytes；SHA-256 `6d533dc93958d7a7cd10bbc11843672d1d45800d1b606e58118f8a70287c48a5`

## 自动检查

```text
Rust fmt/test/clippy       通过（49 tests）
Android debug unit test    通过（134 tests）
Android lint                通过（0 errors / 2 warnings）
Release assemble/sign      通过
```

## 手工回归项目

| 项目 | 结果 | 备注 |
|---|---|---|
| 冷启动与 WebView 渲染 | 通过 | 无启动崩溃 |
| R8/压缩封装 | 通过 | 压缩 APK 覆盖安装成功；首页和分类菜单完整渲染 |
| 地址栏访问/搜索按钮 | 通过 | 仅聚焦时显示，位于输入框外 |
| 地址栏标题/滚动收起 | 通过 | 未编辑时显示标题和域名；向下收起、向上展开；点击后编辑完整 URL |
| 添加/编辑书签 | 通过 | 默认当前标题/URL，可手动修改 |
| 分类菜单 | 通过 | 四个分类可滚动，所有原入口均保留 |
| 导航/固定网址主页 | 通过 | 设置可切换并跨重启保留 |
| 首页快捷入口 | 通过 | favicon 正常；长按移除后书签仍保留 |
| HTTPS 证书详情 | 通过 | 展示主体、组织、签发者、起止时间与有效状态 |
| 清除浏览数据 | 通过 | 有确认框；清理后书签仍保留 |
| 标签新建/切换/关闭 | 通过 | 标签元数据可恢复 |
| 启动恢复设置 | 通过 | 默认进入主页；开启后恢复普通标签，关闭后清除恢复元数据 |
| 默认浏览器/退出 | 通过 | RoleManager 系统选择页可用；退出移除最近任务 |
| 无痕进入/退出 | 通过 | 清理完成后再加载普通页 |
| 媒体候选识别 | 通过 | 页面检测到可投屏媒体时出现右下角按钮 |
| 当前播放流标记 | 通过 | 选择页显示“正在播放” |
| 视频倍速 | 通过 | 压缩包复测主文档 2×；跨域 iframe 3× 及同页重载保持 2× 的既有回归仍通过 |
| DLNA 设备发现 UI | 通过 | 真实设备投送需同网实体接收器 |
| 下载设置持久化 | 通过 | SAF 创建/选择 `PureDownloads`，8 线程设置跨重启保留 |
| Range 分段与目录 | 通过 | 约 9.9 MB 文件以 8 段合并；系统 Download/SAF 均成功，重名自动编号 |
| 下载删除与后台服务 | 通过 | 仅删记录会保留文件；两种目录均可同步删文件；传输完成后 FGS 自动退出 |
| 页面查找/开发者工具 | 通过 | 入口和状态可用 |
| 崩溃关键词检查 | 通过 | 无 `FATAL EXCEPTION`、`UnsatisfiedLinkError`、`SIGSEGV` |
| 横屏/深色/大字体 | 通过 | API 34 横屏、深色和 1.3 倍字体下无重叠 |

## 截图证据

本次最终 APK 的回归证据位于 `validation/latest/`：

- `final-build-focus.png/xml`：聚焦地址栏及输入框外的访问按钮
- `final-build-search-label.png/xml`：非 URL 输入显示搜索按钮
- `final-build-bookmark-editor.png/xml`：带入当前标题和 URL 的书签编辑器
- `final-build-clear-confirm.png/xml`：清除浏览数据确认框
- `final-build-bookmarks-after-clear.png/xml`：清理后书签仍存在
- `final-build-media.png/xml`：检测媒体后右下角悬浮投屏按钮
- `final-build-cast.png/xml`：候选、变体和“正在播放”标记

本轮新增功能的证据位于 `validation/`：

- `menu-sections-2.png`、`menu-tools.png`：分类菜单及滚动后的设置/工具组
- `security-certificate.png`：Bing 当前 TLS 证书详情
- `bookmark-add-home-dialog.png`、`navigation-with-shortcut.png`：添加首页选项及 favicon
- `home-remove-confirm.png`、`bookmark-retained.png`：长按移除确认和书签保留
- `settings-homepage.png`、`settings-fixed-selected.png`：主页模式设置
- `final-navigation-home.png`：最终 Release 的导航首页

## 限制

模拟器没有实体 DLNA renderer 时，报告只确认媒体候选、当前播放排序、发现流程和 UI；
不能把网络 SOAP 的实际播放效果作为已验证结果。真机应再检查厂商 WebView、局域网发现、
摄像头/麦克风权限和文件选择器。

用户提供的两个直播地址在 2026-09-05 复测均返回 HTTP 404：
`m.jw1104.com/play/steam821622.html` 与 `m.sportsteam53.com/play/steam821587.html`。
因此没有把这两个远端页面写成通过；媒体功能以项目内可复现夹具验证。
