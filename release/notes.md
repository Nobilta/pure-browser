## 验证范围

被测产物为本地构建的 0.13.7（versionCode 36）签名 Release，SHA-256 `dda042a74537ab52858f8ca57f97d181a39b1286d7def7d97e3f5a56dc964b91`
（与 `SHA256SUMS`、`outputs/release/package-info.json` 一致）。设备为 API 37 的 `google_apis`
arm64-v8a 模拟器（数据重建、系统默认区域设置、font scale 1.0）；签名、zipalign、R8 与 ABI 校验在
打包时通过。

本版改的是投屏设备发现搜不到设备，根因在平台而不在应用，结论是量出来的：

- 现象：真机上 M-SEARCH **一个包都发不出去**。诊断报告（临时构建，`dlna/CastDiagnostics.kt`，已删除）
  在 API 37 上给出：公网 TCP `ok`、公网 UDP `ok`、**局域网单播 UDP 与 SSDP 组播组发送全部
  `IOException sendto failed: EPERM`**，且与组播锁、`Network.bindSocket`、`MulticastSocket` 都无关；
  无 VPN、出口网卡正确、省电/流量节省三项限制标志全为关。
- 机制：`targetSdk` ≥ 36 时平台把 `ACCESS_LOCAL_NETWORK` 这个 app op 强制设为 `ignore`——
  `cmd appops set`（含 `--uid`）都改不动，平台上 1452 个权限中也**不存在**可声明或可申请的相关权限。
- 阈值：用同一份源码只改 `targetSdk` 在 API 37 上实测：**34/35 全部 `ok`（含组播），36/37 全部 `BLOCKED`**。
  这也解释了同一台手机上相册与其它投屏应用能搜到：它们 `targetSdk` 更老，不受此限制。
- 修法：`targetSdk` 固定 35（仍强制 edge-to-edge 的最高值，界面行为不变），原因写在
  `gradle/libs.versions.toml` 与 `ARCHITECTURE.md` 里，并注明将来 Android 提供可申请权限后如何升回去。
- 另外补上 `ssdp:all`：原注释声称会发它，代码里其实从未发出（第二个目标是 `service:AVTransport:1`）。
- 自动检查：Android/JVM **533 项**测试通过；Rust 与 Node 测试、三语言资源一致性、Rust fmt/clippy 与
  Android lint（0 error）通过。
- 模拟器回归：改动涉及的 12 个阶段在**一个 scope 内全部通过**，`apkSha256` 等于上面的交付包
  （`api37-motion-suite.json`）：`omnibar`、`motion-gesture`、`motion-interruption`、`menu-navigation`、
  `security`、`download`、`developer-tools`、`browser`、`features-dialogs`、`settings-back`、`settings`、
  `home-shortcut`。因为 `targetSdk` 会影响 edge-to-edge 与 inset 行为，其中与面板几何、inset 有关的阶段
  （`settings-back`、`menu-navigation`、`motion-gesture` 会逐像素断言面板边界）是这次最该看的部分。
- **未覆盖（必须说明）**：模拟器的局域网里没有 DLNA 设备，所以"改完能不能搜到你的电视"**只能在你的真机上确认**；
  模拟器能证明的只是"包现在发得出去了"（组播发送由 `BLOCKED` 变为 `ok`）。
- 未覆盖：真机上的其它投屏功能（投送、播放控制）、升级数据保留、非 arm64-v8a 与低于 API 30 的设备。
  完整 46 阶段矩阵只跑了改动相关的 12 个阶段。

## 本版未覆盖的动效

- 其余原生 Material 3 `AlertDialog` 仍用平台的窗口动画。Android 的对话框动效由窗口承担，
  它把遮罩和对话框表面作为一体一起淡入；应用自己实现的 `BrowserAlertDialog` 只驱动表面，
  遮罩由窗口立即出现，因此不把它们统一迁移反而更接近平台一致的行为。
- 设置页在窄布局下从分类返回列表时，进入的一侧有动效，被移除的一侧仍是即时消失。
  改成两侧同时动画需要保留离开的窗格，属于设置页的组合结构调整。
- 各处"列表 ↔ 空状态"的内容切换（下载、标签页、网络日志等）仍是即时切换，未套 `AnimatedContent`。
- 安全指示图标的图标与颜色保持即时切换，这是刻意的：它描述的是当前页面的安全状态，
  淡出上一个页面的锁形图标会在渐变期间显示一个错误的结论。
- 下拉关闭的"甩出速度"判定没有自动化：速度来自最后约 100ms 内采样点的拟合，
  而本机 adb 注入单个事件的耗时与这个窗口同量级，无法提供与手指同密度的采样，该路径手工核对。
