## 0.16.0 验证范围

被测产物：`PureBrowser-v0.16.0-release.apk`（versionCode 58），本地签名 Release 候选。
SHA-256：`0bb8ddd00ead1f6c5315b7a702120050eb5b15e4ace61be6caacc65daefa7092`。

设备：`emulator-5554`，Android 17 / API 37、arm64-v8a，WebView 145.0.7632.218。

本次改动：

- 阅读模式（Readability，Apache-2.0）：从当前页提取正文重排，再次点击还原；
- 打印 / 保存为 PDF：菜单打开系统打印界面，由 WebView 渲染；
- 站点级 UA：网站设置可为单站点填写自定义 UA，确认后重载生效；
- 内置搜索引擎新增秘塔（`metaso.cn`）；
- 评审修复：下载页失败路径不再在写锁被占用时删文件、SHA-256 查表编码、
  菜单拦截计数实时刷新、历史/书签建议前缀已满时跳过全表扫描、WebView 安全开关测试。

验证：

- `./build-and-test.sh --quick` 通过；Android/JVM 单元测试 674 项全部通过（新增站点 UA 持久化、
  Metaso 模板、阅读模式资源打包、WebView 文件访问开关等用例）。
- 模拟器回归：
  - 阅读模式：打开本地长文页面，菜单 → 更多工具 → 阅读模式，正文以统一排版重排；再次进入
    阅读模式后原页面的导航与广告块恢复；
  - 打印：同一页面菜单 → 打印 / 保存为 PDF，系统打印界面显示「Page 1 of 1」，可取消；
  - 站点 UA：为 `localhost` 设置 `TestUA/1.0` 并保存，页面重载后 `navigator.userAgent`
    输出 `TestUA/1.0`。
- APK 通过 v2 单签名校验，包名 com.mybrowser、versionName 0.16.0、versionCode 58、arm64-v8a。

未覆盖：真机、其他 Android 版本与 WebView 版本、完整设备功能矩阵；站点 UA 未纳入设置备份；
「保存为 PDF」依赖系统打印界面的 PDF 目标，没有一键直存文件（框架回调不可公开构造）。
