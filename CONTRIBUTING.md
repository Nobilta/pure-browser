# 贡献指南

欢迎为 Pure 浏览器报告问题、改进文档或提交代码。还不熟悉项目时，可以先看[功能介绍](FEATURES.md)和[架构说明](ARCHITECTURE_REVIEW.md)。

## 开发环境

支持 macOS，以及 x86_64 Linux。推荐与 [CI](.github/workflows/ci.yml) 使用相同的工具版本：

| 工具 | 版本 |
|---|---|
| JDK | 21 |
| Node.js | 22 |
| Python | 3.9 或更高 |
| Rust | 1.98.0，包含 rustfmt、clippy 和 `aarch64-linux-android` target |
| Android SDK | Platform 37（`platforms;android-37.0`）、Build Tools 37.0.0、Platform Tools |
| Android NDK | 27.2.12479018 |

在项目根目录的 `local.properties` 中设置 `sdk.dir=/你的/Android/SDK/路径`，或设置 `ANDROID_HOME`。
NDK 可通过 `ANDROID_NDK_HOME` 指定；未设置时，构建脚本会从 SDK 的 `ndk/` 目录查找。
JDK、Node.js、Python 和 Rust 工具需要在 `PATH` 中可用，连接设备时还需要 `adb`。

调试版自动使用调试签名，无需正式发布密钥。签名 Release 的配置见[发布指南](RELEASING.md#配置签名)。

## 构建和运行

```bash
git clone https://github.com/Nobilta/pure-browser.git
cd pure-browser
./build-and-test.sh --quick
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

调试版默认面向 ARM64 设备，包名为 `com.mybrowser.debug`，可以与正式版共存。
使用 x86_64 模拟器时，改用以下构建命令：

```bash
./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug
```

首次构建会下载依赖。遇到安装问题时，可连接设备运行 `./diagnose.sh` 查看诊断信息。

## 验证改动

代码改动先运行 `./build-and-test.sh --quick`，再在本地模拟器上验证受影响的行为。
界面、生命周期和 WebView 改动尤其需要实际操作验证；现有阶段、签名包要求和运行方法见[测试指南](TESTING_GUIDE.md)。
修复缺陷时，优先增加能重现原问题的检查，并说明修复前后的结果。

只修改文档时，核对链接、命令和相关源码即可。提交说明应写明实际做过的检查，以及尚未验证的范围。

涉及以下内容时，请同步对应文档：

| 改动 | 对应文档 |
|---|---|
| 用户可见行为或限制 | [功能介绍](FEATURES.md) |
| 安装、依赖、构建或验证方法 | [README](README.md)、[英文 README](README.en.md)及相关指南 |
| 发布步骤、版本和安装包信息 | [发布指南](RELEASING.md) |

## 提交 Pull Request

向远程 `main` 提交 PR。每个 PR 尽量解决一个问题，用一两句话说明原因和结果；修复缺陷时附上相关 Issue。
界面变化可以附截图，行为变化请给出具体的前后对比。

在 PR 中列出检查命令、结果，以及使用的设备、Android 和 WebView 版本。
不适用的检查可以注明原因，不必填写没有执行过的测试数量。

不要提交签名密钥、`keystore.properties`、`local.properties`、构建输出、APK 或 `validation/results/` 下的日志。
维护者本地使用 `master` 的普通提交维护源码，并推送到远程 `main`；不保留备份或回滚分支。

## 代码约定

- Kotlin / Compose 跟随现有文件的命名和结构。注释重点解释平台约束和设计原因。
- WebView 功能使用 `WebViewFeature.isFeatureSupported` 等运行时能力探测；同一 Android 版本可能安装不同能力的 WebView。
- Rust 提交前通过 `cargo fmt`；CI 和发布检查会以 `-D warnings` 运行 clippy。
- 验证脚本优先使用 Python 标准库，便于直接运行。
- 新增界面文字同时提供简体中文、繁体中文和英文。`validation/check-localization.py` 会核对资源键和格式化参数。

发布由维护者执行，具体步骤见[发布指南](RELEASING.md)。参与讨论和贡献时，请遵守[行为准则](CODE_OF_CONDUCT.md)。
