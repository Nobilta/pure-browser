## 验证范围

被测产物为本地构建的 0.13（versionCode 27）签名 Release；APK 的 SHA-256 与 `SHA256SUMS` 一致。
设备为 API 37 的 `google_apis` arm64-v8a 模拟器（数据重建、系统默认区域设置、font scale 1.0），
签名、zipalign、R8 与 ABI 校验在打包时通过。
播放器与镜像的定向设备验证在**同一源码的 versionCode 26 测试包**上完成（除版本号外与本次交付包相同），
其余设备检查在本次交付包的前序同源码构建上完成；下面逐项给出的是实测数值。

- 自动检查：Android/JVM 525 项、Rust 69 项、Node 70 项测试通过；三语言 744 项资源及格式参数一致；
  Rust fmt/clippy 与 Android lint（0 error）通过；`arm64-v8a` Release 构建与签名校验通过。
- 设备检查：过滤引擎的 JNI 决策表（118,828 条规则，12 项冻结期望全部一致）与系统登录能力通过。
  这两项是自定义 `Instrumentation`，`connectedDebugAndroidTest` 会以 0 个用例通过，
  必须按[测试指南](../TESTING_GUIDE.md)里的 `am instrument` 命令显式运行。
- 播放器画面菜单（镜像、3:4 / 16:9 / 铺满全屏、还原）在设备上逐项核对：进入全屏后分别切换镜像与比例并
  逐张截图，五项在同一屏内可见（最低一项底边 y=777，屏高 1080）。截图留在
  `validation/results/aspect-verify/`（本地，不提交）。
- 镜像以画面自身的量化特征判定，而不是靠肉眼：源视频左上角有烧录的时间码，开启镜像后它必须出现在画面
  右侧。实测在 2400×1080 横屏下从 x≈369 移到 x≈2041（画面区 240–2159），关闭后回到左侧，
  两次切换 `lastCommandError` 均为空。这条路径此前在“视频自己进入全屏”的页面上完全无效，
  原因是浏览器 UA 样式表对 `:fullscreen` 元素强制 `transform:none!important`（作者来源压不过），
  现在按 `transform` → `scale` 的顺序下发并回读计算值。
- 画面比例用同一套量化判定：3:4 横屏时画面区为 795–1605（宽 810 = 3:4 盒宽，等比缩放会得到 607），
  即画面被拉伸到该比例；旋转到竖屏后 padding 按新视口重算为 480–1919（高 1439，预期 1440），
  旋转前是整屏铺满——这条重算路径此前因尺寸取自元素自身而被自身写下的 padding 污染。
- 模拟器回归：本版改动涉及的阶段在设备上重跑并通过：`profiles`、`system-media`、`developer-tools`、
  `features-filters`、`download`（其中 `features-filters` 第 3 次尝试通过，其余一次通过）。
- 完整矩阵（46 阶段）本轮**没有跑完**，未通过阶段的结果不用于判定代码：验证期间主机持续 3–4 倍超载
  （应用冷启动约 8 秒、SystemUI 反复 ANR、UI 自动化探针报 `Cannot call disconnect() while connecting`、
  adb 调用超时）。受此影响的阶段是 `features-scripts`（夹具脚本的安装步骤未生效）、`downloads`
  （等待「继续下载」标签超时）与 `resident`（adb 超时）；它们在负载较轻时、同一功能集上曾通过。
  建议主机空闲时用 `validation/run-regressions.py --profile full` 重新完整回归。
- 未覆盖：真机（扫码预览方向、实体接收器投屏、真实账号登录）、升级数据保留的专项断言、
  非 arm64-v8a 以及低于 API 30 的设备。
