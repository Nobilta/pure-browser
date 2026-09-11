# 0.7.0 验证报告

日期：2026-09-11。回归使用签名 Release，模拟器 UI 操作串行执行。
安装包哈希用于区分交付验证和开发阶段记录；旧包的通过项不计入本版设备覆盖。

## 安装包与自动检查

- `PureBrowser-v0.7.0-release.apk`，versionCode 11，包名 `com.mybrowser`。
- Android 10+（minSdk 29）、targetSdk 37、arm64-v8a；4,682,919 bytes，约 4.47 MiB。
- SHA-256：`bb878a3cb0cb6ef728e2dd6518f340bba58cf3f1186a748213f74aa048e6945e`。
- APK v2 签名、16 KiB zipalign、打包后的播放器脚本与源码逐字节一致检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，延续旧版签名。
- 321 项 Android/Robolectric 测试，0 failures、0 errors、0 skipped；包含真实 host JNI 检查。
- 58 项 Rust、51 项 Node 测试及 619 项三语言资源检查通过；Rust fmt/clippy、R8 和 Release 构建通过。
- lint 为 0 errors、11 warnings、2 hints，主要涉及兼容性提示、工具版本、Compose 约定、英文复数、
  arm64 交付范围及代码简化建议；没有将其表述为零警告。

最终构建为 `build7`，包含旧 WebView 实时播放消息桥修复，完整构建检查通过。
原始依据：[构建结果](validation/results/release-0.7.0/build7.json)、
[构建日志](validation/results/release-0.7.0/build7.log)、
[交付清单](validation/results/release-0.7.0/delivery.json)、
[签名](validation/results/release-0.7.0/signature.txt)。APK 和生成的验证记录不提交 Git。

## 本版重点

### 下载交互

完成行本身是打开入口，浏览器发送不指定 MIME 的 `ACTION_VIEW`、可读 `content:` URI 和临时读取授权，
由 Android 根据内容提供方解析文件类型。没有独立“打开/安装”按钮，也没有浏览器自己的 APK 安装或来源授权分支。

下载专项核对未知总长时已下载字节的变化、拒绝通知权限后仍完成下载、最终文件 SHA-256，
并通过实际触摸进入系统图片查看器和安装器。缺失文件不会启动外部 Activity；Toast 已通过截图复核。
安装器的来源授权由 Android 设置处理。完成通知可在进程已结束时打开浏览器并定位到对应下载行。

截图中的系统安装授权不是浏览器的二次确认。部分进度截图抓取时下载已经完成，
进度变化以执行时连续读取的无障碍文字和字节校验为依据，不能把完成截图当作进行中进度的证明。

### 内嵌与全屏播放器

内嵌专项覆盖标准、自定义容器、Blob、160×90 小视频和跨域 frame，核对真实播放状态、
原节点/来源保留、网站控制层隔离、倍速、跳转、静音、显式恢复、滚动、尺寸变化、弹窗遮挡、
样式丢失、视频替换、全屏进出和设置即时开关。CSP 或 Provider 能力不足时保留网页播放器。

全屏与系统媒体的独立阶段继续检查标准/自定义/Blob/跨域/CSP 路径、手势、锁定、旋转、
画中画、系统暂停和显式后台播放。具体脚本与边界见 [播放器验收说明](design/enhanced-player-validation.md)。

### 功能精简与私密生命周期

阅读/离线文章、Google Cast、本地整体备份恢复及双窗口的界面、实现、专用依赖和回归阶段已删除。
普通标签、书签 HTML、打印/PDF、DLNA、脚本及系统媒体继续保留。

同时修复真实的私密会话隔离问题：WebView 145 拒绝删除当前进程已加载的 Profile，
旧实现复用固定名称会使下次私密会话重新读到上次 Cookie/localStorage。
现在每次会话使用新名称，退出时清理数据并先退役名称；重启时清理专用前缀下的遗留 Profile。
新增 5 项单元检查覆盖删除失败、配置重建、重启清理及连续会话名称不复用，模拟器另核对真实站点数据。

## 模拟器结果

最终 `build7` 的两轮选定回归均完成，所有阶段的安装包 SHA-256 与上方交付包一致，均无失败尝试：

| AVD | Android / API | WebView | 最终包阶段 | 原始记录 |
|---|---|---|---|---|
| PureBrowser_API29 | 10 / 29 | 91.0.4472.114 | 12 / 12 通过 | [API 29 套件](validation/results/api29-release-070-build7-suite.json) |
| PureBrowser_API37 | 17 / 37 | 145.0.7632.218 | 10 / 10 通过 | [API 37 套件](validation/results/api37-release-070-build7-suite.json) |

两台设备均覆盖私密会话生命周期、内嵌视频、下载进度与系统打开、系统媒体、基础浏览，以及标准、
自定义、Blob、跨域和 CSP 全屏路径。Android 10 另执行 Range 下载和下载管理阶段。
阶段内包含多个操作断言，阶段数不等同于单元测试数。Android 10 的跨域回退和共享站点存储限制按实际
Provider 能力验收；Android 17 验证独立私密 Profile 和跨域增强控制。API 34 没有最终包覆盖。

前一构建 `build6` 的 Android 17 套件完成 28 个阶段，保留在
[前一构建套件](validation/results/api37-release-070-delivery-suite.json)。最后在 Android 10 验证中发现旧 WebView
后台播放回传延迟，修复后生成 `build7`；旧包的 28 项不计为最终包已通过的阶段。
该轮额外覆盖保留的菜单/设置、布局、书签、打印、权限、拍摄上传、脚本和登录能力边界等；
其哈希为 `6941e47a3a1b5177bc0cf1e1033b8ee99d941b5ab81fd0701dccf05120e8f072`。

Android 10 实际升级链为 **0.6.0（versionCode 10）→ 0.7.0 build6（11）→ 最终 build7（11）**，
全程同签名覆盖安装，未卸载或清空数据。旧版 UI 新建的唯一书签在两次覆盖后仍可见。
[升级结果](validation/results/release-0.7.0/upgrade-api29/result.json)保存首次升级两版哈希及书签证据；
升级成功后清理旧 APK，再覆盖最终修复包，设备哈希核对见
[最后一次覆盖安装](validation/results/release-0.7.0/upgrade-api29/final-reinstall.json)。

## 失败与重跑记录

- 私密 Profile 删除失败属于应用缺陷，已修复；原始复现见
  `validation/results/release-0.7.0/private-profile-before-fix.log`。
- 旧 WebView 缺少文档开始注入时，原实现也关闭了已支持的消息桥。开始播放后较快按 Home，
  应用可能尚未收到下一次周期探测结果，后台播放偏好未及时生效。现将两项能力分别检测，加载后注入也保留实时消息。
  Android 10 修复后的后台连续播放、系统暂停及 PiP 专项通过；修复前的遥测和服务状态保存在
  `validation/results/release-0.7.0/api29-background-media/`。
- 默认浏览器入口曾在系统角色弹窗尚未出现时立即断言失败；后续实际界面正常，脚本改为有界等待系统窗口。
- Android 17 的系统媒体脚本曾在全屏转场尚未稳定时按 Home，并只等待 1 秒。
  同一 APK 在播放稳定后可以进入 PiP；脚本改为等待实际全屏/横屏状态，再有界等待系统 pinned 模式，仍只发送一次 Home。
  原始失败与诊断保存在 `validation/results/release-0.7.0/media-home-during-fullscreen-transition/`，重跑由 suite 的 `priorAttempts` 保留。
- 系统安装器、Toast 与权限弹窗的无障碍暴露和动画时序依 Android 版本而异。
  使用窗口/实际 Activity/文件字节和截图交叉核对，不把缺少无障碍 Toast 节点写成已自动识别提示。
- 菜单 SPA 按钮和网页对话框曾因 WebView 尚未提供无障碍子节点而误报缺失。
  辅助程序在同一服务连接中等待节点建立；SPA 另用夹具提供的实时几何位置执行真实触摸，并检查实际导航结果。
  大字号横屏设置需要更多滚动，脚本的有界查找次数由 8 次增至 24 次。
- 全屏视频从 PiP 返回时，Activity 已恢复但屏幕仍在旋转；旧脚本缓存 1080×2400 后，
  将点击发送到已变为 2400×1080 的屏幕之外。现在等待退出 pinned 模式以及两次一致的目标屏幕尺寸。
  同一 APK 在实际横屏坐标下可以正常退出全屏，并恢复原视频的单个内嵌控制层。
  依据见 `validation/results/release-0.7.0/video-resume-controls/`。
- Android 17 沉浸模式下第一次边缘滑动只唤出导航栏。旧脚本读取整个 WebView 树耗时超过系统栏显示时间，
  第二次滑动又只唤出系统栏；系统记录为 `navbar_hidden`、`backGestureDisabled=true`。
  改为快速查询原生锁定按钮后，实际系统返回手势成功解锁并保持全屏。
  依据见 `validation/results/release-0.7.0/video-edge-back/`。
- 一次冷启动页面在 25 秒内未产生遥测，已保留失败记录。夹具服务器返回完整页面；重启专用模拟器后加载恢复。
  该次超时的根因尚未确定，没有计为通过；前一构建的套件记录保留该失败尝试及后续完整成功阶段。
- Android 10 的 shell 夜间模式切换未触发测试所需的即时 Activity 重建，改用字体比例变化；
  仍要求进程 PID 不变、文档 token 改变，并核对无痕 Cookie/localStorage 保留和退出后的清理。
  内嵌视频离屏测试改为根据实际画面边界执行最多四次滑动，避免假定所有屏幕一次滑动的距离相同。
  其系统媒体 shell 命令使用 `media dispatch`，较新 Android 使用 `cmd media_session dispatch`；实际播放状态仍需核对。

## 覆盖边界与复现

复现入口为 [测试指南](TESTING_GUIDE.md)，套件 JSON 保存实际选定阶段、包哈希、WebView 版本、退出码和日志。
只能在 APK、AVD、阶段选择完全相同时使用 `--resume`；失败记录不能用旧包结果替换。

最终包的系统安装器、缺失文件提示及稳定后的 160×90 小视频控件另经截图复核，记录见
[视觉复核](validation/results/release-0.7.0/visual-review.json)。API 37 套件最初的小视频截图捕获了旋转动画，
因此另存转场结束后的截图；没有用该动画截图代替稳定布局的确认。

清理后项目只保留最终交付 APK；删除旧包、过期结果和可重新生成的构建缓存，共 17,509 个文件、
2,785,913,914 bytes，详见[清理清单](validation/results/release-0.7.0/cleanup.json)。保留本次缺陷与失败诊断、
前一构建扩展验证、最终包回归及升级依据。QA 通过 `--apk` 直接读取交付文件，清理构建目录后
[完整文件及 Range 下载检查](validation/results/release-0.7.0/qa-delivery-route.json)仍通过。

本版没有使用实体 DLNA 接收器、真实账号、低端真机，也未逐站测试全部生产视频服务、DRM、
HLS/DASH 直播、MSE 或鉴权 CDN。静态 Blob 夹具不能代表全部流媒体加载实现。
无法访问的视频节点、封闭 Shadow DOM、Canvas 或无法隔离的布局，恢复网页播放器属于预期行为。
