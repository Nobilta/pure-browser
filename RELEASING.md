# 发布指南

发布由维护者在本机签名，再上传 GitHub Release。
开发环境见[贡献指南](CONTRIBUTING.md#开发环境)，设备回归见[测试指南](TESTING_GUIDE.md)。

## CI 与本地分工

[CI](.github/workflows/ci.yml) 在 `main` 推送、PR 和手动触发时执行快速检查、lint、clippy、
未签名 Release 构建及 DEX / Rust JNI 库检查。报告和未签名 APK 附件保留 14 天，执行日志见对应 Actions 运行。

正式签名和设备回归在本地完成。CI 不持有签名密钥，也不自动发布正式版本。
每版的变化写在 [CHANGELOG.md](CHANGELOG.md)；发布说明由 `release/prepare.py` 从该版本的段落生成，
只在仓库外多出这一份，不需要维护两份。验证范围写在 [release/notes.md](release/notes.md)，
它是仓库内的记录，不进入发布页，也不进入应用内的更新提示。
历史版本从 [GitHub Releases](https://github.com/Nobilta/pure-browser/releases)查阅。

## 配置签名

在根目录创建 `keystore.properties`，填写已有正式密钥的信息：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=填写密钥库密码
keyAlias=填写别名
keyPassword=填写私钥密码
```

密钥和配置不提交 Git，也不上传 CI。当前更新流程要求包名与签名一致，不支持密钥轮换。
自己的测试密钥用于独立测试安装，不能覆盖官方版本。

## 发布步骤

1. 修改 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode`，确保 code 高于上一正式版。
   把 [CHANGELOG.md](CHANGELOG.md) 里的「未发布」段落改成本版号（`## 0.13 - 2026-09-24`），
   并更新受影响的使用文档。
2. 构建并验证签名包：

   ```bash
   ./build-and-test.sh --release
   ```

   根目录生成 `PureBrowser-v版本号-release.apk`，`outputs/release/` 生成更新清单、校验和及包信息。
3. 使用该 APK 运行相关设备回归，把实际设备、通过阶段和未覆盖范围简要写入
   [release/notes.md](release/notes.md)：这份记录留在仓库里，供review 时核对"这一版到底测了什么"，
   不对外发布。
   用 suite JSON 的 SHA-256 核对结果属于本次安装包；应用源码变化后重新构建和验证。
4. 提交源码和文档，确认工作区干净后推送，等待该提交的 CI 通过：

   ```bash
   git push origin HEAD:main
   ```

5. 确认 `gh` 在 PATH 中并已登录，创建草稿。替换下面的文件名为本次 APK：

   ```bash
   bash release/publish.sh PureBrowser-v0.12-release.apk release/notes.md
   ```

   脚本要求远程 `main` 与本地 HEAD 一致、该提交 CI 通过、APK 版本与源码一致，
   并检查版本递增和沿用上一正式版的签名。首次发布跳过上一版比较。
   它先调用 `release/prepare.py`，由 CHANGELOG 的对应段落生成 Release 说明
   （`outputs/release/release-notes.md`），草稿上传的是这份说明；`release/notes.md` 只用于核对
   版本并留档，内容不进发布页，也不进应用内的更新提示。
   草稿包含签名 APK、`update.json` 和 `SHA256SUMS`，标签为 `v` 加版本名。
6. 核对草稿的提交、说明和附件，再正式发布并设为 latest。草稿和预发布版不进入应用稳定更新入口。

版本与签名一致不能单独证明 APK 来自某个提交，上传前仍需确认它是本次源码构建并验证的文件。

## 发布后

用同签名的较旧测试包检查真实下载、来源授权、覆盖安装和数据保留。
仅看到更新提示或打开系统安装器不算完成安装；未完成的环节写明验证范围，临时包不对外发布。

安装包、更新附件和原始日志留在 Git 外。本地只保留最新交付 APK 和必要验证记录。
