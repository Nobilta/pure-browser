# 0.7.1 验证报告

日期：2026-09-12。回归使用同签名 Release，模拟器 UI 操作串行执行。
结果按实际安装 APK 的 SHA-256 绑定；0.7.0 和开发阶段构建的通过项不计入最终包覆盖。

## 安装包与自动检查

- `PureBrowser-v0.7.1-release.apk`，versionCode 12，包名 `com.mybrowser`。
- Android 10+（minSdk 29）、targetSdk 37、arm64-v8a；4,682,751 bytes，约 4.47 MiB。
- SHA-256：`17c4063be72261d9f1eaf809edbff5d2e93adeff776491ef1831581d4bde7d06`。
- APK v2 签名、16 KiB zipalign、打包后的两份播放器脚本与源码逐字节一致检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，延续旧版签名。
- 332 项 Android/Robolectric 测试，0 failures、0 errors、0 skipped，包含真实 host JNI。
- 58 项 Rust、55 项 JavaScript 协议测试和 617 项三语言资源校验通过；fmt/clippy、R8 和 Release 构建通过。
- lint 为 0 errors、11 warnings、1 hint，未将现有兼容性和代码约定提示表述为零警告。

依据：[构建结果](validation/results/release-0.7.1/build-final.json)、
[构建日志](validation/results/release-0.7.1/build-final.log)、
[交付清单](validation/results/release-0.7.1/delivery.json)、
[签名](validation/results/release-0.7.1/signature.txt)。生成的 APK、测试结果与签名配置不提交 Git。

## 本版修复与验收方法

### 地址栏与搜索建议

0.7.0 基线已通过真实软键盘按键和清除操作复现失焦。原因是建议使用独立 Popup，
其外部点击处理也接收到输入法和清除按钮的操作，并提前结束编辑。
0.7.1 将建议移到编辑器同一窗口，明确提交、页面点击和返回的编辑结束时机，
保留光标、输入法组合文字与旋转草稿；清除及补全后可继续输入。

`omnibar-regression.py` 分别验证顶部/底部地址栏：实际 Gboard 连续按键、清除、Delete、
补全后继续输入、建议行导航、旋转后的草稿与继续输入、Go 和点击页面结束编辑。
同时核对应用窗口数为一，避免仅用程序设置文本代替软键盘验证。

### 系统媒体、后台播放与画中画

基线中视频结束、卸载、移除和移除跨域 frame 后存在旧媒体 token 或播放状态。
新版将媒体可继续播放状态与 DOM 元素存在分开；关闭时释放 MediaSession 本体、元数据、通知和前台服务。
无最终回执的媒体 frame 由独立超时清理，暂停中的旧媒体消息不能重新创建已释放的会话。
普通 Pause 保持可恢复；显式 Stop、视频结束/隐藏并暂停/卸载/移除/替换、关闭标签和离页会释放。

回归以实际播放遥测、`dumpsys media_session`、服务、活动通知和浏览器 PID 交叉核对，
保持浏览器进程不变，等待超过一轮失效时间，确认迟到消息不会重新激活会话。
开启后台播放后保持连续播放超过两轮失效时间，再通过系统 Stop 验证释放。

画中画修复包含三部分：全屏时不给浏览器工具栏保留布局高度；全屏宿主随 Activity content 容器缩放，
小窗提示区域使用进入前的窗口坐标；增强接管期间抑制 Chromium 自带的旋转退出逻辑，退出接管恢复原属性。
系统关闭小窗会暂停原视频，即使允许后台播放也释放媒体状态；返回浏览器后仍暂停。
截图与 DOM 几何同时核对小窗内是实际视频，不能只看到 pinned 或 PLAYING 就判定通过。

### 菜单返回与开发工具入口

开发工具仅保留菜单入口；设置关于页和设置搜索移除重复项。
菜单只允许一个子页面，恢复时拒绝反向及过深的来路；路由切换真正销毁旧组合与返回处理，
父级仍保留滚动位置。设置选项、分类、设置首页、菜单和浏览器按同一规则逐级返回。

0.7.0 的单次系统返回与边缘返回没有重现用户报告的整个卡死序列，
因此不将某次设备上的现象断言为 ANR 或唯一已复现原因。
新版通过反复输入、窗口数量、迟到回调、重建和立即返回验证加固后的行为。
菜单专项包含 24 轮快速往返及 0/15/50/100 ms 立即返回，设置专项另覆盖六类设置、选项和宽屏。

## 最终安装包模拟器结果

| 设备 | WebView | 通过阶段 | 结果 |
|---|---|---|---|
| Android 17 / API 37，PureBrowser_API37 | 145.0.7632.218 | 14 / 14 | [最终包套件](validation/results/api37-release-071-final-suite.json) |
| Android 10 / API 29，PureBrowser_API29 | 91.0.4472.114 | 12 / 12 | [最终包套件](validation/results/api29-release-071-final-suite.json) |

两台设备共同通过媒体关闭、顶部/底部地址栏、内嵌视频、系统媒体、菜单返回、基础浏览、
标准全屏、自定义容器、Blob、跨域播放器、CSP 回退和设置返回 12 个阶段。
Android 17 另通过无痕生命周期和下载完成行直接交给系统打开文件。
阶段数不等同于单元测试数，也不表示所有浏览器功能和在线站点都在本版重测。

Android 17 的[画中画补测](validation/results/release-0.7.1/api37-pip-geometry/result.json)
另确认缩放完成后的实际视频画面、系统关闭、会话释放和恢复后保持暂停；Android 10 的媒体关闭阶段
也按 DOM/窗口几何及截图检查小窗，而非仅检查 pinned 状态。
WebView 91 无法观察测试用跨域 frame，因此该平台不计入“移除跨域 frame 后独立超时清理”的覆盖；
这一能力已在 WebView 145 验证，旧 Provider 保留网页控件。

覆盖升级保留原应用数据，没有卸载或清空数据：

- Android 10：0.7.0（versionCode 11）直接升级到最终 0.7.1（12），旧版 UI 创建的书签仍可见，
  安装后 APK 哈希匹配。[升级记录](validation/results/release-0.7.1/api29-upgrade/result.json)。
- Android 17：0.7.0（11）先覆盖到开发阶段 0.7.1（12），随后覆盖安装最终包（12）；
  最终包再次确认旧版书签仍在。[首次升级](validation/results/release-0.7.1/api37-upgrade/result.json)、
  [最终包复核](validation/results/release-0.7.1/api37-upgrade/final-reinstall.json)。

## 开发阶段问题与验证修正

- 之前开发构建的输入框与媒体关闭部分已通过，但 PiP 截图仍出现浏览器界面；没有将那轮记录计为完整成功。
- 调用链显示 Chromium 在方向变化后的尺寸调整期间发出退出全屏回调，浏览器没有主动返回。
  自定义容器全屏对照和 Chromium 源码用于定位；最终通过临时 `controlslist` 条件抑制内建旋转退出，
  不使用私有 WebView 反射，也不创建第二份解码器。原属性恢复和页面夺回控制已纳入协议测试。
- 系统 pinned 状态早于 WebView 完成缩放；首轮最终包截图仍捕获了一帧过渡中的裁切画面，
  遥测随后显示视口缩到真实小窗尺寸且保持全屏。截图验收改为等待 DOM 尺寸匹配系统小窗边界，
  并单独复测该路径，避免固定延时冒充稳定画面。
- Android 17 部分 PiP 菜单不提供无障碍关闭节点。保留菜单截图，使用菜单出现后的实时小窗边界
  和实际关闭按钮位置执行系统触摸；不能沿用打开菜单前的尺寸。
- 一次小窗补核的关闭点击未生效；独立计时发现截图耗时约 3.85 秒，可能耗尽系统菜单的显示时间。
  相同位置的较短操作链可关闭小窗，浏览器 PID 保持不变且会话释放。脚本移除关闭点击前的截图编码，
  改为先触摸、确认退出 pinned，再保存关闭后的画面；原失败及输入计时继续保留。
- 旧 WebView 的跨域消息能力按 Provider 实际情况记录，不将无法观察的跨域 frame 计为已验证失效。
- Android 10 不支持 `cmd notification list`，首轮媒体专项在通知查询处失败；改读系统转储的活动
  `Notification List` 段，排除归档记录，媒体专项随后通过。原失败保存在 `api29-notification-query/`。
- Android 10 的一次界面采集在 shell `app_process` 的 ART/JIT 工作线程崩溃，浏览器进程仍存活。
  `api29-ui-helper/` 保留原失败、crash buffer 和浏览器 PID；全部窗口采集仅针对退出码 139 重试一次只读操作，
  不重放键盘、触摸或返回输入，连续失败仍终止。地址栏完整重跑通过。
- 基础浏览脚本按窗口高度 80% 定位的滑动，在底部地址栏布局中触碰了地址栏，导致收起断言失败。
  `api29-browser-scroll/` 保留前后画面与实际触摸坐标；同一 APK 在 WebView 实际边界内滑动可正常收起，
  回归脚本改为在当前网页边界内定位滑动起终点。

修复前的必要诊断保存在 `validation/results/release-0.7.1/`；最终 suite 保留失败与重跑记录。
验证结束后已关闭专用模拟器和 QA 服务，清理 0.7.0 APK、旧版回归产物及可重新生成的构建缓存，
共约 1,003 MiB；保留最终 APK、本版验证/诊断、单元测试 XML、lint 与 R8 映射。
详情见[清理记录](validation/results/release-0.7.1/cleanup.json)。

## 覆盖边界与复现

[测试指南](TESTING_GUIDE.md)列出统一 runner、软键盘、媒体关闭、下载、菜单和播放器的复现步骤。
只能在安装包、AVD 和阶段选择完全一致时使用 `--resume`；阶段包含多个断言，阶段数不等同于单元测试数。
下载仍为完成行直接交给 Android 打开文件；保留增强内嵌/全屏视频、无痕会话与基础浏览的相关回归。

本版没有使用真实账号、实体 DLNA 接收器、低端真机，未逐站验收全部生产视频服务、DRM、
HLS/DASH 直播、MSE 或鉴权 CDN。静态 Blob 夹具不能代表所有流媒体加载实现。
无法访问的节点、封闭 Shadow DOM、Canvas 或不能隔离的布局保留网页播放器；Android API 与 WebView 版本分别记录。
