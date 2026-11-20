# 发布指南

发布由维护者在本机签名，再将同一套发行附件上传 GitHub 和 Gitee。
开发环境见[构建指南](building.md#环境与版本来源)，设备回归见[测试指南](testing.md)。

## CI 与本地分工

[CI](../.github/workflows/ci.yml) 在 `main` 推送、PR 和手动触发时执行快速检查、lint、clippy、
未签名 Release 构建及 DEX / Rust JNI 库检查。
三个宿主都执行快速检查；Windows 验证 ARM64 Debug 包，Linux 验证 ARM64 Release 和 x86_64 Debug 包，macOS 当前只运行快速检查。报告和未签名 APK 附件保留 14 天，执行日志见对应 Actions 运行。

正式签名和设备回归在本地完成。CI 不持有签名密钥，也不自动发布正式版本。
快速检查包含发布工具的 Python 单元测试；推送标签不会触发当前 CI，也不会自动生成 Release。
本地维护分支为 `master`，远程发布分支和 CI 监听分支为 `main`。
[更新日志](changelog.md) 只收录已正式发布的版本。测试包、未发布改动与发布计划不写入其中。
正式发布前在 Git 外准备公开变化正文，`release/prepare.py` 添加 APK 版本标题并生成附件；发布成功后再将已发布说明归入更新日志。
实际验证结果保留在测试报告与日志中，交付时说明设备、覆盖范围和未通过项，不另写人工验证记录。
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

1. 修改 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode`，确保 code 高于上一正式版及已交付测试包。
   更新受影响的使用文档，changelog 等正式发布成功后再追加。
2. 构建并验证签名包：

   ```bash
   ./build-and-test.sh --release
   ```

   这一步运行快速检查、clippy、lint、R8、签名与 zipalign 校验。
   根目录生成 `PureBrowser-v版本号-release.apk`，`outputs/release/` 生成更新清单、校验和及包信息。
3. 使用该 APK 运行相关设备回归，保留生成的报告、日志和截图，交付时说明实际设备、通过阶段、失败与未覆盖范围。
   用 suite JSON 的 SHA-256 核对结果属于本次安装包；应用源码变化后重新构建和验证。
4. 在本地 `master` 提交源码和文档，确认工作区（含未跟踪文件）干净后推送，等待该提交在远程 `main` 的 CI 通过：

   ```bash
   git push origin HEAD:main
   ```

5. 将审核好的公开变化正文写到 Git 外的 `outputs/release/changes.txt`，不含版本标题或测试报告。
   确认 `gh` 在 PATH 中并已登录，读取本次构建的文件名并创建草稿：

   ```bash
   release_apk="$(python3 -c 'import json; print(json.load(open("outputs/release/package-info.json"))["apk"])')"
   bash release/publish.sh "$release_apk" outputs/release/changes.txt
   ```

   发布脚本默认调用 `python3`，也接受 `PYTHON=python`；Windows Git Bash 按实际解释器设置，
   上例读取 APK 路径的 Python 命令也相应改为 `python`。

   脚本要求远程 `main` 与本地 HEAD 一致、该提交最新一次 `main` 的 CI 成功、APK 版本与源码一致，
   并检查版本递增和沿用上一正式版的签名。较早的失败/取消记录不会阻塞后续成功运行，
   较新的未完成/失败运行仍会阻塞；PR 检查不能代替 `main` 检查。首次发布跳过上一版比较。
   两个 APK 都须通过完整验签，不能只从失败输出中读取证书。
   `GH=/完整路径/gh bash release/publish.sh …` 可覆盖 CLI 路径，子门禁使用相同路径。
   它先调用 `release/prepare.py --release-notes`，将输入正文加上 APK 版本标题，生成
   `outputs/release/release-notes.md`；草稿与应用内更新提示使用同一份说明。本地构建不依赖 changelog。
   草稿包含签名 APK、`update.json` 和 `SHA256SUMS`，标签为 `v` 加版本名。
6. 在 GitHub Releases 打开该草稿，核对目标提交、说明和三个附件，再点击 **Publish release** 并设为 **latest**。
   草稿和预发布版不进入应用稳定更新入口，因此只上传 APK 不构成完整发布。
7. 同步 Gitee：GitHub 草稿核对无误并发布后，设置 `GITEE_TOKEN` 运行 `bash release/sync-gitee.sh`；
   也可在创建草稿时加 `--gitee`（`bash release/publish.sh --gitee <apk> outputs/release/changes.txt`）一并同步，但 Gitee
   发行版没有草稿状态，会先于 GitHub 公开。脚本按 `package-info.json` 的标签复用或创建发行版，上传
   APK、`update.json`、`SHA256SUMS`。脚本会在连接 Gitee 前核对本地两个文件的摘要，重复运行会跳过已有附件。
   应用优先查询 Gitee 的最新正式发行版并读取该标签的清单，网络失败时才回退 GitHub；两边不能各自重建 APK。
   仅同步 Git 仓库不会同步发行附件。Gitee 可访问但版本落后时不会触发故障回退，发布时须确保两边版本一致。

无需另建自动发布 workflow 或预先推送同名 tag；脚本把本次 HEAD 指定为 Release 的目标提交。
如果已有同名草稿，先核对或处理该草稿再重试，脚本不会静默覆盖已存在的 Release。

版本与签名一致不能单独证明 APK 来自某个提交，上传前仍需确认它是本次源码构建并验证的文件。

## 发布后

确认正式 Release 已公开后，把实际发布的正文写入 [更新日志](changelog.md)，标题为 `## 版本号 - 发布日期`。
该文档更新单独提交；不因补记日志重建已发布 APK，也不移动已发布标签。临时说明随对应生成附件管理，不另存草稿文档。

用同签名的较旧测试包检查真实下载、来源授权、覆盖安装和数据保留。
仅看到更新提示或打开系统安装器不算完成安装；未完成的环节写明验证范围，临时包不对外发布。

安装包、更新附件和原始日志留在 Git 外。本地只保留最新交付 APK 和必要验证记录。
