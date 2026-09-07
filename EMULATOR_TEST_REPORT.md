# 模拟器回归报告

更新时间：2026-09-07。本报告记录 0.3.0 签名 APK 的实际检查；旧版结果不计入当前通过项。

## 产物与环境

- APK：`PureBrowser-v0.3.0-release.apk`，versionCode 3，包名 `com.mybrowser`。
- 大小：2,031,774 bytes（约 1.94 MiB），比 0.2.0 减少 99,473 bytes（约 4.7%）。
- SHA-256：`983e5b4311f8680a7dbc2ff5b4565658825f7ad8038deca07506459169bd4514`。
- APK Signature Scheme v2 验证通过；最低 API 29，compile/target API 37，Release 仅为 arm64-v8a。
- 同一个最终 APK 在两套系统上覆盖安装成功。构建日志：`validation/build-productivity.log`。

| 模拟器 | 系统 | WebView | 分辨率 |
|---|---|---|---|
| PureBrowser_API29 / emulator-5554 | Android 10 / API 29 | 91.0.4472.114 | 1080 x 1920 |
| MyBrowser_Pixel7 / emulator-5556 | Android 14 / API 34 | 113.0.5672.136 | 1080 x 2400 |

均为 arm64、2 核、1536 MiB、SwiftShader、无快照启动，每次只运行一台。
本地服务器只监听 127.0.0.1:8875/8876，通过 ADB reverse 提供可控页面、图片及媒体。
Release 没有启用 WebView 调试；UI 辅助程序位于模拟器的 `/data/local/tmp`，不进入 APK。

## 自动检查

| 检查 | 结果 |
|---|---|
| Rust fmt / test / clippy | 通过，49 tests |
| Android / Robolectric | 164 tests，0 failures / errors / skipped |
| Node 网页视频协议 | 15 tests，全部通过 |
| 三语言资源 | 407 个资源键及格式参数一致，含 1 个复数资源 |
| Android lint | 0 errors / 3 warnings |
| R8 Release、资源裁剪、签名 | 通过 |
| 默认 JNI 库 | 仅 adblock、url_utils 两个产品库及 AndroidX graphics.path |

三条 lint 提示为 AGP 更新、ChromeOS x86_64 支持、API 33 以下忽略 localeConfig。
新增单元覆盖包括分页偏移、旧查询结果失效、失败重试、重复网址编辑保护、稳定标签 ID、
后台标签不加载及上下文 URL 校验。cache 的旧 JNI 门面仍可 opt-in，默认 APK 不包含它。

## 新增浏览流程

以下项目在 API 29、API 34 上均通过真实触摸验证：

- 链接长按后后台打开保持原页面；服务器日志确认选中前没有请求目标网页，选中后才请求。
- 标签标题/网址搜索后能选中和关闭正确条目；新标签返回按钮禁用，不继承其他标签的记录。
- 关闭前台新标签后恢复原页面，原页面自己的返回记录仍可继续使用。
- 图片链接同时显示网页链接和图片操作；复制图片地址后粘贴到地址栏，得到正确图片 URL。
- 普通图片只提供图片操作；保存到系统 Download 的文件与服务器字节 SHA-256 一致。
- 分享打开 Android 系统选择器后取消，没有向外部应用发送消息。
- 关闭其他标签可取消或确认；关闭全部同样需确认。产品不提供最近关闭列表。
- 在 123 条测试书签/历史记录中搜索最旧条目，确认搜索覆盖首次加载范围以外的数据。
- 书签直接编辑成功；改成已有网址时保留原记录并停留在编辑器，可取消返回列表。
- 书签加载下一页成功；清空书签/历史的确认可取消，取消后原记录仍可搜索。
- 历史按日期分组；1.3 倍系统字体下重新打开列表，搜索和条目操作可见，截图无文字重叠。

列表回归只接受模拟器序号，通过 adb root 写入夹具记录。数据库单元测试另行验证编辑保留
ID/创建时间、分页无重复或缺失，以及失败重试的具体偏移量。
API 34 另经截图确认网页文本长按仍显示原生选区及 Copy/Share/Select all 工具栏。
新增菜单、标签、书签与历史列表在简体中文、繁体中文、英文下的实际文案通过校验；
截图确认繁体历史列表与横屏书签列表布局正常。

恢复标签时发现并修复了旧 WebView 的问题：已导航实例上的 restoreState 可能恢复为空白页。
现在使用未导航的新实例恢复，并释放旧实例；恢复完成前不保存中间地址。
普通后台标签仍只有元数据/快照，不为每个标签常驻一个 WebView。

## 现有功能回归

| 项目 | API 29 | API 34 |
|---|---|---|
| 六类设置、选项弹层返回、过滤管理返回 | 通过 | 通过 |
| 深浅主题与下载线程数在进程重启后保留 | 通过 | 通过 |
| 大字体设置与横屏 | 单栏可用 | 分类与详情双栏可用 |
| SPA 地址/标题、滚动工具栏、弹窗标签、重启恢复、退出任务 | 通过 | 通过 |
| 6 MiB Range 下载、完整文件校验及服务完成后退出 | 通过 | 通过 |
| 默认浏览器系统入口 | 通过 | 通过 |
| 应用语言切换并保留设置分类 | 系统不支持该入口 | 简体、繁体、英文通过 |
| 主页面 MP4 全屏控制与手势 | 通过 | 通过 |
| 跨域 iframe 视频 | 网页控件回退通过 | 增强控制通过 |

视频回归结合真实触摸与页面遥测，覆盖播放/暂停、长按临时倍速与后台恢复、双击中央控制、
暂停时横向跳转、亮度/音量滑动、锁定返回、网页/增强控件切换及退出后的方向/亮度恢复。
四个当前视频结果均为 `error: null`。没有新增独立解码器，仍由 WebView 与网站播放。
下载文件 SHA-256 为 `e338caefa380bafe02a98dac6b2865a8c4783d80f5d813906abd01c250463d70`。

API 29 日志中的 5 次 ART JIT SIGSEGV 均对应 shell/root 启动的 FastUiDump 辅助进程，
日志显示其在退出时发生问题；浏览器与 WebView 进程不属于这些 PID。未发现浏览器 Java
崩溃、JNI 链接错误或 ANR。该辅助程序不进入交付 APK。
API 34 当前回归的崩溃缓冲区为空，未发现浏览器 Java/native 崩溃、JNI 链接错误或 ANR。

## 复现与证据

```bash
python3 validation/qa-server.py
# 另开终端，每次只测试一台已安装最终 APK 的 QA 模拟器。
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/productivity-regression.py --serial emulator-5554
# 可单独运行 --section browser 或 --section library。
python3 validation/settings-regression.py --serial emulator-5554
python3 validation/download-regression.py --serial emulator-5554
ANDROID_SERIAL=emulator-5554 python3 validation/emulator-ux.py regress
python3 validation/video-regression.py --serial emulator-5554 --variant standard
python3 validation/video-regression.py --serial emulator-5554 --variant cross
# API 34 替换 serial，再运行：
python3 validation/locale-regression.py --serial emulator-5556
python3 validation/productivity-visual-regression.py --serial emulator-5556
```

本地证据不纳入 Git：`validation/*-v030-api*.log`、`productivity-library-api29.log`、
`validation/results/api*-productivity-*.json/png/xml`、`api{29,34}-{standard,cross}.json`、
`api*-settings.json`、`api*-download.json`、`api34-locales.json`、`api*-v030-{logcat,crash}.txt`。
截图与 XML 用于核对布局；旧 Provider 偶尔不再发布网页子节点时，夹具测试复用已取得的固定
目标坐标。弹层等待有超时，英文原生按钮的全大写形式也纳入匹配。

## 未验证范围

- 未对 0.3.0 做完整启动/内存/耗电基准，不把 0.1.0 与 0.2.0 的历史性能样本当成当前结果。
- Android 11/12/13、API 35-37、厂商 WebView、低端真机和 32 位系统；当前 Release 不支持 32 位。
- 实体 DLNA、第三方 DRM/MSE/直播网站、字幕/清晰度定制、长期视频帧率和耗电。
- 本轮未重新执行 Blob/方形视频全部设备用例，以及 SAF、上传、权限、下载重试/删除的完整矩阵。
- 文本选区仅在 API 34 做了额外截图检查；清空确认的执行数据路径有单元覆盖，设备回归主要检查取消路径。

修改前 Git 备份：`backup/pre-browser-productivity-20260907`，指向 0.2.0 的 `82d0cb4`。
