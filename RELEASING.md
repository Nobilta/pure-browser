# 发布与交付（维护者手册）

本文件记录发布流程、交付包事实与签名密钥策略。[README](README.md) 面向使用者与贡献者，不写这些内容。

## 发布流程

1. 提升 `app/build.gradle.kts` 的 `versionCode`（整数，必须递增）与 `versionName`（`主.次.修订`）。
2. 更新 [release/notes.md](release/notes.md)（Release 说明）与 [CHANGELOG.md](CHANGELOG.md)。
3. 同步用户可见行为 → [FEATURES.md](FEATURES.md)；依赖、构建、验证方式或产物变化 → [README.md](README.md)。
4. 完成构建与验证，并把**实际结果与未覆盖范围**写进 [EMULATOR_TEST_REPORT.md](EMULATOR_TEST_REPORT.md)：
   ```bash
   ./build-and-test.sh --release      # 自动检查、lint、R8、签名与 zipalign 验证
   ```
   外加本机模拟器回归（见 [测试指南](TESTING_GUIDE.md)），交付包绑定实际安装 APK 的 SHA-256。
   没跑过的范围必须标注为未验证。
5. 先提交并推送源码，再上传产物：
   ```bash
   git push origin HEAD:main
   bash release/publish.sh PureBrowser-v<版本>-release.apk release/notes.md
   ```
   `publish.sh` 通过已登录的 GitHub CLI 创建**草稿**，上传签名 APK、自动生成的 `update.json` 与 `SHA256SUMS`，
   并绑定已推送的提交。
6. 在 GitHub 审核草稿并改为正式发布、设为 latest。标签必须是 `v` + 版本名（例如 `v0.9.0`）。
   草稿与预发布版本不会进入应用的稳定更新入口。`publish.sh` 会先跑 `release/gate.py` 的门禁
   （该提交的 CI 通过、APK 版本等于源码版本、versionCode 高于上一正式版、签名证书沿用上一正式版），
   任一项不满足就拒绝创建草稿；仓库尚无正式版时后两项跳过并打印说明。

后续版本必须沿用同一正式签名，否则已安装用户无法覆盖升级。

## 签名密钥

**不要把签名密钥提交到 GitHub，也不要放进 CI。**

- APK 签名是 Android 判断"这是同一个应用的更新"的唯一依据。拿到密钥的人可以签出一个你的用户会照常安装的"更新"，
  这等同于把发布权交出去。
- 泄露无法优雅收回：更换密钥意味着已安装用户必须**卸载重装**（数据丢失、书签与设置归零），
  所以一次泄露基本等于永久性事故。
- Git 历史是永久的：事后删除文件也删不掉历史中的副本，除非重写历史并强制所有协作者重新克隆。

仓库现状（2026-09-15 核查）：当前跟踪文件与**完整提交历史**中都没有 `*.jks`、`*.keystore` 或
`keystore.properties`；`.gitignore` 覆盖 `*.jks`、`keystore.properties` 与 `local.properties`。
签名材料只存在于本机，`release/publish.sh` 上传的是本地签好名的产物。

如果将来想把构建搬到 CI：

- **推荐的分工**：CI 只做验证（`.github/workflows/ci.yml` 跑 `--quick` 与 clippy），签名仍在本机完成，
  产物由维护者上传。构建可复现，所以 CI 能验证而无需持有密钥。
- 若确实要自动签名，用 Repository Secrets（**绝不入库**）配合受保护的 Environment（必须人工批准），
  并接受残余风险：任何能修改 workflow 的人都能把密钥外传，因此必须同时限制谁能改 `.github/`。

## 交付包

**已发布：0.9.0** —— `PureBrowser-v0.9.0-release.apk`，versionCode 18，**4,714,826 bytes（约 4.50 MiB）**，
SHA-256 `dd65ed4d44d91a6c48d830da7ffcc3348551b0fed317d867a1afb89ad896e5f4`，
[Release](https://github.com/Nobilta/pure-browser/releases/tag/v0.9.0)。通过 333 项 Android/Robolectric、
58 项 Rust、55 项 Node 测试与 646 项三语言资源校验；API 37 模拟器上通过七个定向阶段，并完成
0.8.1 → 0.9.0 原签名覆盖升级。

**本地构建：0.9.1（尚未发布）** —— `PureBrowser-v0.9.1-release.apk`，versionCode 19，**4,716,822 bytes（约 4.50 MiB）**，
SHA-256 `0213b1f2716d18026e30e6f121b555ff9ebd65cdd0e92dabe4a15411c257b9f9`，
签名者 SHA-256 与 0.9.0 相同，可覆盖升级。通过 **336 项 Android/Robolectric、57 项 Rust、55 项 Node 测试
与 639 项三语言资源校验**，lint 为 0 errors、19 warnings、1 hint，clippy 与 R8 全模式构建通过。

相对 0.9.0 的用户可见变化：

- 无痕模式不再屏蔽截屏、也不再隐藏最近任务预览（设置里的对应开关已下线）；
- 启动时自动检查更新，确认后自动下载、校验并打开系统安装器（可在设置关闭）；
- 弹层动效统一为一套节奏（进入 240ms 上滑、面板内换页 120ms 位移并按方向区分、换容器时表面交替）；
- 普通页面开启 WebView 的后退缓存（按设备能力探测；本机 WebView 145 声明支持但仍会重建文档）；
- 后台页面不再受固定槽位限制，内存不足时按最先停放顺序回收最老页面；
- 标签管理页移除驻留状态小字，无痕顶部提示精简为图标 + "无痕"徽标。

其余为内部结构整理（消除两处包级循环依赖、`ui` 包按功能拆分、删除无引用成员与资源）、
外部复核提出两条实现问题的修复（启动更新提示在配置重建后丢失、OOM 重试留下半配置实例）与回归框架提速。
API 37 模拟器定向阶段的结果、未覆盖范围与失败记录见 [回归报告](EMULATOR_TEST_REPORT.md)。

本地交付只保留最新 APK 及必要的验证记录；验证产物留在 `validation/results/`，不入库。
历史优化数字只说明当时的固定样本；低端真机的性能与功耗、实体 DLNA 画面、真实账号登录与全部在线视频站点
都不在已验证范围内。
