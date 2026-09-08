# 模拟器回归报告

更新时间：2026-09-08。本报告按 APK 校验值区分本次图标/视频调整与此前首页编辑器的结果。

## 本次图标与视频调整

- 产物：`PureBrowser-v0.3.1-release.apk`，versionCode 4，2,041,658 bytes（约 1.95 MiB）。
- SHA-256：`05020b81fc04ac42b297ddfbd81b9094330926a379622fcbe81a84bced95c68d`。
- Android 10+，arm64-v8a，APK Signature Scheme v2 验证通过。
- 49 项 Rust、180 项 Android/Robolectric、25 项 Node 网页协议测试通过，414 项三语言资源校验通过。
- Android lint：0 errors / 3 warnings；仍为 AGP 更新、ChromeOS ABI 和低版本 localeConfig 提示。
- 图标产品 XML 与「青叶 P」三个候选 drawable 逐项解析一致，预览基于正式产品资源生成。

本轮重点是标准视频控件交接、网页播放器独占控制及全屏投屏弹层。代码仍使用 WebView 解码；
没有新增独立播放器、Google Cast、凭据代理或远端播放会话界面。详细方案见
[`design/video-playback-and-casting.md`](./design/video-playback-and-casting.md)。

### 最终安装包设备结果

下表各通过项均由设备读取已安装 APK 的 SHA-256，确认是上面的 `05020b81...95c68d` 产物。

| 模拟器 | WebView | 标准视频 | 自定义网页播放器 | 跨域 iframe | Blob 视频 |
|---|---|---|---|---|---|
| Android 10 / API 29 / emulator-5554 | 91.0.4472.114 | 通过 | 通过 | 网页控件回退通过 | 最终包未重测 |
| Android 14 / API 34 / emulator-5556 | 113.0.5672.136 | 通过 | 通过 | 增强控制通过 | 通过 |

均为 arm64、2 核、SwiftShader、无快照启动，每次只运行一台；模拟器实际内存为
API 29 的 2048 MiB 和 API 34 的 2560 MiB。本地夹具只监听 127.0.0.1:8875/8876，经 ADB reverse 访问。

- 标准视频覆盖控件自动隐藏、暂停/继续、倍速、长按临时加速、后台恢复后重新全屏、
  双击、亮度/音量/进度手势、锁定返回、网页/增强控件切换及退出后的方向/亮度恢复。
- 增强全屏内打开投屏选择窗口并取消后，确认全屏保留且暂停按钮仍可操作；网页内没有悬浮媒体按钮。
- 自定义容器只有网页播放按钮，触摸可暂停/继续，退出后可重新全屏。API 29 跨域回退保留网页播放并可用系统返回退出。
- 暂停与后台恢复后重进的截图确认仅显示一套播放控件。API 34 的 Blob 夹具仍能交接增强控制；
  该夹具通过 fetch 加载了 MP4，投屏候选来自请求地址，不能据此宣称 Blob 本身可直接投送。
- 青叶 P 图标在 Android 14 启动器的圆形裁切和应用名称已做截图检查；主题单色 XML/设计预览已核对，启动器主题开关未实测。

最终结果位于 `validation/results/`：`api29-{standard,custom,cross}.json` 和
`api34-{standard,custom,cross,blob}.json`，七个文件均为 `error: null`。
对应 case 分别为 `api29-standard-1788881214`、`api29-custom-1788881411`、
`api29-cross-1788881422`、`api34-standard-1788881699`、`api34-custom-1788881745`、
`api34-cross-1788881756`、`api34-blob-1788881939`。截图以 case 命名，包含 `paused`、`reentered`、
`cast-sheet` 等阶段；启动器截图为 `api34-launcher-leaf-p.png`。

本轮日志中未发现浏览器进程的 Java/native 崩溃、JNI 链接错误或 ANR。API 29 的 PID 3432、7355 是
shell 启动的 FastUiDump，正常输出后在 ART JIT 退出阶段 SIGSEGV；API 34 的 PID 7074 是同一
辅助程序触摸注入失败，导致第一次 Blob 补测中断。保留失败记录后重跑通过，没有将该次中断记作通过。
这些辅助程序不进入 APK。API 34 另有 SystemUI 和 Pixel Launcher 的手势监听 ANR，以及 Google Docs
进程的可选 native 库加载警告，均保留在环境记录中，不作为浏览器通过项或浏览器崩溃。
日志保存在 `api{29,34}-video-05020-{logcat,crash}.txt`。

### 修复与边界

本轮回归发现并修复：

- WebView 全屏内置控件在暂停/重新进入时仍可能显示，接管期间增加只匹配目标视频的临时隐藏样式。
- Android 10 再次打开同一标准 MP4 时，网络嗅探候选可能为空；使用已加载的 `currentSrc` 补充候选，保留签名参数。
- Android 10 关闭投屏弹层后系统栏覆盖全屏按钮；窗口重新获得焦点时恢复沉浸全屏。

测试脚本在关闭弹层后等待新的页面遥测和弹层消失，避免使用关闭前的状态。
旧 Provider 可能不向辅助工具提供全屏网页节点；自定义按钮用页面报告的实时位置执行真实触摸，
并通过暂停/继续遥测确认结果。该坐标回退不修改播放器代码或页面播放状态。

本轮未验证真实 YouTube 在线页面、实体 DLNA 接收器、Google Cast、DRM、字幕/清晰度定制、
低端真机和完整性能基准；最终包没有重测方形视频和 API 29 Blob 的完整流程。
YouTube 域名策略和自定义网页夹具通过不能代替真实站点验收。

下面的历史部分对应 SHA-256 为 `5c3cfe9e...a160c37` 的首页编辑器安装包，不能作为本次产物的通过项。

## 历史：产物与环境

- APK：`PureBrowser-v0.3.1-release.apk`，versionCode 4，包名 `com.mybrowser`。
- 大小：2,046,530 bytes（约 1.95 MiB），比 0.3.0 增加 14,756 bytes（约 0.7%）。
- SHA-256：`5c3cfe9ea5dda25fc55f7b5a6db66d9dcebb071a3a0853df3fe2904b5a160c37`。
- APK Signature Scheme v2 验证通过；最低 API 29，compile/target API 37，Release 仅为 arm64-v8a。
- 同一个最终 APK 在两套系统上覆盖安装成功。构建日志：`validation/build-home-shortcut-completion.log`。

| 模拟器 | 系统 | WebView | 分辨率 |
|---|---|---|---|
| PureBrowser_API29 / emulator-5554 | Android 10 / API 29 | 91.0.4472.114 | 1080 x 1920 |
| MyBrowser_Pixel7 / emulator-5556 | Android 14 / API 34 | 113.0.5672.136 | 1080 x 2400 |

均为 arm64、2 核、1536 MiB、SwiftShader、无快照启动，每次只运行一台。
本地服务器只监听 127.0.0.1:8875/8876，通过 ADB reverse 提供可控页面、图片及媒体。
Release 没有启用 WebView 调试；UI 辅助程序位于模拟器的 `/data/local/tmp`，不进入 APK。

## 历史：自动检查

| 检查 | 结果 |
|---|---|
| Rust fmt / test / clippy | 通过，49 tests |
| Android / Robolectric | 175 tests，0 failures / errors / skipped |
| Node 网页视频协议 | 15 tests，全部通过 |
| 三语言资源 | 414 个资源键及格式参数一致，含 1 个复数资源 |
| Android lint | 0 errors / 3 warnings |
| R8 Release、资源裁剪、签名 | 通过 |
| 默认 JNI 库 | 仅 adblock、url_utils 两个产品库及 AndroidX graphics.path |

三条 lint 提示为 AGP 更新、ChromeOS x86_64 支持、API 33 以下忽略 localeConfig。
新增单元覆盖包括分页偏移、旧查询结果失效、失败重试、重复网址编辑保护、稳定标签 ID、
后台标签不加载及上下文 URL 校验。cache 的旧 JNI 门面仍可 opt-in，默认 APK 不包含它。

## 历史：新增浏览流程

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

本轮首页快捷入口回归在 API 29 与 API 34 上均通过真实触摸验证：

- 长按入口打开一个同时包含标题、地址、图标选择和删除按钮的编辑窗口。
- 无效地址、重复地址、损坏图片会停留在编辑窗口；取消不改变已保存记录。
- 取消图片草稿不产生新图标文件；切换编辑另一个入口时标题和地址来自另一个入口。
- 自定义图片按最长边 96px 保存，旋转与 1.3 倍字体后草稿和预览保留，重启后编辑结果仍在。
- 使用文字图标、取消删除及确认删除都验证了旧图清理、入口位置和书签保留。
- 两台设备记录的安装 APK SHA-256 均为 `5c3cfe9e...a160c37`，完整结果位于
  `validation/results/api29-home-shortcut.json` 和 `api34-home-shortcut.json`。

列表回归只接受模拟器序号，通过 adb root 写入夹具记录。数据库单元测试另行验证编辑保留
ID/创建时间、分页无重复或缺失，以及失败重试的具体偏移量。
API 34 另经截图确认网页文本长按仍显示原生选区及 Copy/Share/Select all 工具栏。
新增菜单、标签、书签与历史列表在简体中文、繁体中文、英文下的实际文案通过校验；
截图确认繁体历史列表与横屏书签列表布局正常。

恢复标签时发现并修复了旧 WebView 的问题：已导航实例上的 restoreState 可能恢复为空白页。
现在使用未导航的新实例恢复，并释放旧实例；恢复完成前不保存中间地址。
普通后台标签仍只有元数据/快照，不为每个标签常驻一个 WebView。

## 历史：现有功能回归

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

## 历史：复现与证据

```bash
python3 validation/qa-server.py
# 另开终端，每次只测试一台已安装最终 APK 的 QA 模拟器。
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/home-shortcut-regression.py --serial emulator-5554
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

## 历史：未验证范围

- 未对 0.3.1 做完整启动/内存/耗电基准，不把 0.1.0 与 0.2.0 的历史性能样本当成当前结果。
- Android 11/12/13、API 35-37、厂商 WebView、低端真机和 32 位系统；当前 Release 不支持 32 位。
- 实体 DLNA、第三方 DRM/MSE/直播网站、字幕/清晰度定制、长期视频帧率和耗电。
- 本轮未重新执行 Blob/方形视频全部设备用例，以及 SAF、上传、权限、下载重试/删除的完整矩阵。
- 文本选区仅在 API 34 做了额外截图检查；清空确认的执行数据路径有单元覆盖，设备回归主要检查取消路径。

修改前 Git 备份：`backup/pre-home-shortcut-editor-20260907`，指向 0.3.0 的 `bf75a92`。
