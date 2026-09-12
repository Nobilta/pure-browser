# 0.7.2 验证报告

日期：2026-09-13，开发与基线检查始于 2026-09-12。验证使用同签名 Release，UI 操作串行执行。
每个最终阶段均按实际安装 APK 的 SHA-256 绑定；开发包的通过项单独记录。

## 安装包与自动检查

- `PureBrowser-v0.7.2-release.apk`，versionCode 13，包名 `com.mybrowser`。
- Android 10+（minSdk 29）、targetSdk 37、arm64-v8a；4,661,251 bytes，约 4.45 MiB。
- SHA-256：`c738eb6c2d19e963ee0d1f00ec2a8d401d74df366e343eee1bd6b32c0180cbc2`。
- APK v2 签名、16 KiB zipalign 和全部三份 native 库的 ELF LOAD 16 KiB 对齐检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，延续旧版签名。
- 328 项 Android/Robolectric 测试：0 failures、0 errors、0 skipped，包含真实 host JNI。
- 58 项 Rust、55 项 JavaScript 协议测试和 603 项三语言资源校验通过，fmt/clippy、R8 和 Release 构建通过。
- lint：0 errors、10 warnings、1 hint；删除闲置文案后 UnusedResources 警告消除，其余为既有兼容性与约定提示。
- APK 中三份播放器/脚本运行时代码与源码逐字节一致；14 个移除文案资源和 Android PrintManager 引用均不存在。

依据：[最终构建](validation/results/release-0.7.2/build-final.json)、
[构建日志](validation/results/release-0.7.2/build-final.log)、
[交付清单](validation/results/release-0.7.2/delivery.json)、
[签名](validation/results/release-0.7.2/signature.txt)。Android 测试 XML 和 lint 报告保留于
`validation/results/release-0.7.2/automated/`。APK、签名配置和生成的验证记录均不提交 Git。

## 本版检查范围

菜单删除分享网页、复制链接与打印/PDF；标签删除最近关闭、重开、清空和撤销关闭。
源代码同时删除存储/序列化/恢复路径、专用资源、四个过时单元测试和两个旧模拟器阶段。
保留当前标签操作与链接/图片上下文菜单，清理历史提示不再包含最近关闭。

设置采用独立全屏 Dialog，使画面、触摸和系统返回由同一个窗口持有。
返回路径验证选项、分类、设置首页、菜单、浏览器；检查系统/工具栏/左右边缘返回、
24 轮快速往返、0/15/50/100 ms 立即返回、六轮手势和工具栏交替往返、后台切回、重建及横屏两栏。
返回菜单后检查所有窗口中不再有设置内容，返回浏览器后只剩主窗口，并执行真实网页触摸。
0.7.1 在 Android 17 的单次手势基线没有复现整个卡死序列，不把它表述为已复现的 ANR。

网站表单与管理列表关闭弹层拖动和边界拉伸。对网站表单上下边界各连续执行六次快/慢滑动，
比较标题和固定操作栏的实际像素；停止后三次表单截图哈希一致，再验证取消、修改、保存和设置生效。
横屏及 150% 字体下继续检查底部操作和网站权限行可达性。

## 覆盖升级

Android 10 从 0.7.1（12）直接覆盖安装最终 0.7.2（13），没有卸载或清空应用数据。
升级前后 126 条书签的 ID、标题、URL 和创建时间整体校验值一致，旧最近关闭存档已删除。
安装后包哈希与本地交付一致，见 [升级记录](validation/results/release-0.7.2/api29-upgrade/result.json)。

## 开发阶段的测试定位修正

- WebView 145 在关闭弹层后可能漏报可见的网页无障碍节点；失败截图中链接仍在。
  浏览器、菜单和上下文回归共用实时夹具几何，校验当前网址与新鲜遥测后执行真实触摸，
  不调用网页 click() 代替用户操作，不继续使用旧坐标。原浏览器和上下文定位失败保留。
- 网站设置滚动后，Compose 无障碍树报告标题的裁剪底边变化，但前后标题和按钮的像素完全一致。
  边界检查改为锚点加真实像素比对，保留原失败、连续采样与像素诊断，避免把语义裁剪误报为页面抖动。
- API 相关截图名称改用实际 SDK，移除固定写成 API 34 的结果路径。
- 网站夹具在导航前启用无障碍读取，移除紧接外部 Intent 的额外刷新；仍按完整目标 URL 匹配遥测。
- 书签夹具补齐当前数据模型的 `position` 与 `host`，按正常新增书签的顺序生成，避免旧夹具全部使用默认排序值。
  Android 10 首次上下文阶段受上一失败阶段遗留的 JavaScript 禁用影响；重置测试 origin 后相关操作通过，
  再修正书签夹具后，完整 `productivity` 阶段通过。两次失败均保留。

开发阶段套件：[原始运行](validation/results/api37-release-072-suite.json)。
诊断位于 `validation/results/release-0.7.2/`；最终结果与开发阶段分开统计。

## Android 10 旧 WebView 的未通过项

最终包在 WebView 91.0.4472.114 上通过网站设置上下边界的连续滑动与像素稳定检查。
随后切换 JavaScript 设置及端口的完整 `site` 阶段未通过：日志记录 Chromium 渲染进程 code 11/code 5 崩溃，
主进程仍存活并恢复旧文档，导致目标网址没有遥测。取消额外刷新、核对地址的诊断也没有消除异常，
不能把该完整阶段计为通过；单独端口切换成功也不能替代整段回归。

依据：[失败套件](validation/results/api29-release-072-final-suite.json)、
[渲染进程日志](validation/results/release-0.7.2/api29-site-origin/provider.log)。
诊断期间一次地址读取还因编辑器未退出而失败，保留在 `priorAttempts`；临时地址探测代码已移除。

## 设备与覆盖边界

| 最终包设备 | WebView / 导航 | 实际结果 |
|---|---|---|
| PureBrowser_API29，Android 10 / API 29 | 91.0.4472.114，三按钮 | `menu-navigation`、`browser`、`layout`、`settings-back`、`productivity` 共 5 个阶段通过；网站表单边界专项通过，完整 `site` 未通过 |
| PureBrowser_API37，Android 17 / API 37 | 145.0.7632.218，手势 | `menu-navigation`、`browser`、`site`、`layout`、`settings-back`、`productivity`、`locale`、`productivity-visual` 共 8 个阶段一次通过 |

Android 10 的边缘手势因系统使用三按钮导航而跳过；Android 17 的左右边缘手势与六轮交替返回均实际执行，未跳过。
Android 17 最终运行日志没有应用 FATAL EXCEPTION、ANR 或 Chromium 渲染进程崩溃标记。
三语言界面、原生文字选择、横屏列表、标签关闭/后台打开/搜索、图片保存与剪贴板，以及书签和历史分页均有真实交互检查。
两台模拟器另行核对菜单全滚动范围没有分享/复制/打印入口，普通与无痕标签没有最近关闭或撤销，
关闭最后一个标签和关闭全部标签后仍可使用新标签，重启不再创建旧存档。
Android 17 还核对管理网站列表的左上角返回可依次回到设置、菜单和浏览器。

依据：[Android 10 初始套件](validation/results/api29-release-072-final-suite.json)、
[Android 10 后续套件](validation/results/api29-release-072-final-ui-suite.json)、
[Android 17 最终套件](validation/results/api37-release-072-final-suite.json)、
[Android 10 功能补查](validation/results/release-0.7.2/api29-feature-removal/result.json)、
[Android 17 功能补查](validation/results/release-0.7.2/api37-feature-removal/result.json)。复现命令见 [测试指南](TESTING_GUIDE.md)。
只在 APK、AVD 与阶段选择完全相同时使用 `--resume`；失败保留，阶段数不等同于单元测试数。

本次没有使用低端真机、实体 DLNA 接收器或真实账号，未重新逐站验收所有在线视频、DRM、MSE 和直播服务。
播放器实现沿用 0.7.1，协议与单元测试仍执行；旧版 UI 通过项不计入本次最终包设备覆盖。

## 交付清理

删除旧版 APK、旧验证输出和生成的构建缓存；本地只保留本版 APK 及必要验证记录。
本轮未新增永久测试文件，临时地址探测代码已撤销，模拟器辅助 JAR、编译输出和 Python 字节码已清理，
模拟器与 QA 服务已停止。清理范围见 `validation/results/release-0.7.2/cleanup.json`。
