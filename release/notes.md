## 0.16.1 验证范围

被测产物：`PureBrowser-v0.16.1-release.apk`（versionCode 59），本地签名 Release 候选。
SHA-256：`39bdd791f5c25a235fece2787f8543f840d1b149027d6897d3b88b76b2fa4642`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

本次为内部重构，无界面变化：

- HTTP(S) 校验合并：导航、下载、过滤订阅、用户脚本资源统一走 `UrlUtils.isHttpUrl` 的选项
  （最大长度、userinfo、fragment）；主机字符校验改为纯 Kotlin，不再依赖 `android.net.Uri`。
- 过滤器启动：每个列表只读取/校验/哈希一次，首次 rebuild 复用，不再重复读 4 MB 级列表。
- 站点 UA 纳入设置备份（Absent 保留、null 清除、字符串覆盖）。
- 下载确认框与列表统一二进制单位。
- 过滤器防抖可注入，设置迁移测试不再真实等待合并窗口。

验证：

- `./build-and-test.sh --quick` 通过；Android/JVM 单元测试 674 项全部通过。
- 新增/调整用例：URL 校验器在纯 JVM 下的行为、站点 UA 的备份三态、过滤器启动缓存路径
  （现有 corrupt snapshot/304 用例）、统一后的下载校验。
- 模拟器冒烟：安装 0.16.1，打开本地长文页面，菜单中的「网站设置 / 桌面站点 / 更多工具」
  等页面操作均可用（说明导航 URL 校验未误杀），无崩溃。

未覆盖：真实订阅更新、真实下载、跨设备备份导入的端到端流程；设置迁移测试已从真实 80 ms
防抖改为 0，未再验证真实防抖在设备上的合并行为。
