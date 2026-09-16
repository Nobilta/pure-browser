# 发布指南

本文面向维护者，介绍从验证代码到发布 GitHub Release 的流程。
开发环境见[贡献指南](CONTRIBUTING.md#开发环境)，设备回归见[测试指南](TESTING_GUIDE.md)。

## CI 与本地发布的分工

[CI](.github/workflows/ci.yml) 在推送到 `main`、提交 PR 或手动触发时运行：

| 检查 | 内容 |
|---|---|
| 快速检查 | 三语言资源、Node、Rust fmt/test、Android/Robolectric 单元测试 |
| 静态检查 | Android lint、Rust clippy |
| Release 构建 | R8、资源裁剪、未签名 APK 打包 |
| 产物检查 | APK 中的 DEX 和两个 Rust JNI 库 |
| 保存结果 | 测试报告、lint 报告和未签名 APK，保留 14 天 |

签名密钥只在本机使用。签名验证、设备回归和正式发布由维护者完成；
CI 产物用于检查构建，不作为用户安装包分发。当前流程没有自动发布正式版的步骤。

## 配置签名

在项目根目录创建 `keystore.properties`，填写已有密钥的信息：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=填写密钥库密码
keyAlias=填写别名
keyPassword=填写私钥密码
```

这些字段由 `app/build.gradle.kts` 读取。密钥和配置文件不能提交到 Git，也不上传到 CI。
正式更新必须沿用现有签名；自己的测试密钥只能用于自己的安装，不能覆盖官方签名的应用。

Android 会同时核对包名、签名等更新条件。当前发布脚本和应用更新器要求签名证书与已发布版本一致，
不支持密钥轮换流程。请妥善备份密钥；丢失密钥会影响后续更新，泄露后应立即停止发布并评估处置方案。

## 发布步骤

1. 修改 `app/build.gradle.kts` 的 `versionName` 和 `versionCode`。版本名使用 `主.次.修订`，
   `versionCode` 必须高于上一正式版。
2. 更新 [Release 说明](release/notes.md)和[更新日志](CHANGELOG.md)。
   行为变化同步到 [FEATURES.md](FEATURES.md)；构建或使用方法变化同步到相关指南及两份 README。
3. 运行完整本地检查并生成签名安装包：

   ```bash
   ./build-and-test.sh --release
   ```

   脚本会执行自动检查、lint、R8、签名及 zipalign 验证，并在根目录生成
   `PureBrowser-v版本号-release.apk`。更新清单等附件生成在 `outputs/release/`。
4. 使用这份安装包运行相关模拟器回归，核对实际安装 APK 的 SHA-256。
   将结果和未覆盖范围写入[回归报告](EMULATOR_TEST_REPORT.md)，并更新本文的发布记录。
   应用源码变化后需重新构建和验证，不能沿用旧包的通过结果。
5. 提交源码和文档，确认工作区干净后推送，并等待该提交的 CI 完成：

   ```bash
   git push origin HEAD:main
   ```

6. 确认 GitHub CLI 已登录，且 `gh` 在 `PATH` 中，然后创建草稿。以下文件名请替换为本次版本：

   ```bash
   bash release/publish.sh PureBrowser-v0.9.1-release.apk release/notes.md
   ```

   脚本要求远程 `main` 与本地 HEAD 一致，并检查该提交的 CI、APK 与源码的版本号、
   相对上一正式版递增的 `versionCode`，以及一致的签名证书。首次发布会跳过与上一版的比较。
   检查通过后创建草稿，上传签名 APK、`update.json` 和 `SHA256SUMS`。
7. 在 GitHub 核对草稿的提交、说明和附件，再正式发布并设为 latest。标签采用 `v` 加版本名，
   例如 `v0.9.1`。草稿和预发布版不进入稳定更新入口。

版本和签名检查不能单独证明 APK 来自某个提交。维护者仍需确保上传的是本次源码构建、
经同一哈希验证的安装包。

## 发布后检查

用同签名的较旧测试包验证实际的更新检查、下载、来源授权和覆盖安装，并确认书签等数据保留。
记录安装前后版本与 APK 哈希；只打开系统安装器，不代表更新已完成。
临时测试包不对外分发，结果单独标明为发布后检查。

本地只保留最新交付 APK 和必要验证记录。安装包、`outputs/` 和 `validation/results/` 的生成文件不提交 Git。

## 发布记录

### 0.9.1 · 2026-09-15

[GitHub Release](https://github.com/Nobilta/pure-browser/releases/tag/v0.9.1)，标签指向 `1bc501a`。

| 项目 | 值 |
|---|---|
| 文件 | `PureBrowser-v0.9.1-release.apk` |
| versionCode | 19 |
| 系统 / 架构 | Android 10+ / arm64-v8a |
| 大小 | 4,716,822 bytes，约 4.50 MiB |
| SHA-256 | `0213b1f2716d18026e30e6f121b555ff9ebd65cdd0e92dabe4a15411c257b9f9` |
| 签名 | 与 0.9.0 相同 |

自动检查通过：336 项 Android/Robolectric、57 项 Rust、55 项 Node 测试，639 项三语言资源校验。
lint 为 0 errors、19 warnings、1 hint；clippy 和 R8 构建通过。
API 37 定向回归、不同候选包的结果及未覆盖范围见[回归报告](EMULATOR_TEST_REPORT.md)。
本版变化见[更新日志](CHANGELOG.md)。

### 0.9.0 · 2026-09-14

[GitHub Release](https://github.com/Nobilta/pure-browser/releases/tag/v0.9.0)。

| 项目 | 值 |
|---|---|
| 文件 | `PureBrowser-v0.9.0-release.apk` |
| versionCode | 18 |
| 大小 | 4,714,826 bytes，约 4.50 MiB |
| SHA-256 | `dd65ed4d44d91a6c48d830da7ffcc3348551b0fed317d867a1afb89ad896e5f4` |

通过 333 项 Android/Robolectric、58 项 Rust、55 项 Node 测试，646 项三语言资源校验，
以及 API 37 上七个定向阶段；完成 0.8.1 → 0.9.0 同签名覆盖升级。
