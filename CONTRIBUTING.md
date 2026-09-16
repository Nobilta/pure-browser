# 贡献指南

欢迎参与 Pure 浏览器的开发。下面介绍如何准备环境、运行检查和提交改动。

## 开发环境

支持 macOS 和 Linux（x86_64）。推荐与 [CI](.github/workflows/ci.yml) 使用相同的工具版本：

- JDK 21、Node.js 22、Python 3.9 或更高版本。
- Rust 1.98.0，通过 rustup 安装，包含 rustfmt、clippy 和 `aarch64-linux-android` target。
- Android SDK Platform 37（`platforms;android-37.0`）、Build Tools 37.0.0、Platform Tools，以及 NDK 27.2.12479018。

在项目根目录的 `local.properties` 中设置 `sdk.dir=/你的/Android/SDK/路径`，或设置 `ANDROID_HOME`。
NDK 可通过 `ANDROID_NDK_HOME` 指定；未设置时，构建脚本会从 SDK 的 `ndk/` 目录中查找。
JDK、Node.js、Python 和 Rust 工具需要在 `PATH` 中可用。

调试版使用自动生成的调试签名，不需要正式发布密钥。构建签名 Release 时需要自己配置
`keystore.properties`，流程见[发布指南](RELEASING.md)。这些本机配置与密钥不提交到仓库。

## 快速开始

```bash
git clone https://github.com/Nobilta/pure-browser.git
cd pure-browser
./build-and-test.sh --quick   # 提交前必跑：资源、Node、Rust 与 Android 单元测试
./gradlew :app:assembleDebug  # 构建调试 APK
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，默认面向 ARM64 设备。
模拟器配置与回归命令见[测试指南](TESTING_GUIDE.md)；连接设备后的安装诊断可用 `./diagnose.sh`。

## 提交前检查清单

- `./build-and-test.sh --quick` 通过；改动涉及界面、生命周期或 WebView 行为时，另外跑相关模拟器阶段：
  `python3 validation/run-regressions.py --serial emulator-5554 --apk <签名 APK> --profile smoke`。
- 用户可见行为变化 → 同步 [功能与行为边界](FEATURES.md)；依赖、构建、验证方式或产物变化 → 同步
  [README](README.md)。
- 新增能力如果依赖 WebView 特性，按 **运行时能力探测** 实现（`WebViewFeature.isFeatureSupported`），
  不要按 Android 版本判断——同一个 Android 版本上不同 WebView 版本的能力并不相同。
- 不提交签名材料（`*.jks`、`keystore.properties`）、`local.properties`、构建输出、APK、
  `validation/results/` 下的验证产物。

## Pull Request

- 一个 PR 做一件事，说明**动机**和**验证方式**（跑了哪些检查、在什么设备/模拟器上、结果如何）。
- 行为有变化时，PR 里写清"改动前 / 改动后"的实际表现，必要时附截图或抓取的过渡帧。
- 修缺陷时优先补一条能复现它的检查（单元测试或 `validation/` 里的回归用例），只改代码不补检查的 PR
  会被要求补上。
- 仓库使用 `master` 的普通提交维护，**不创建备份或回滚分支**；提交历史保持线性可读，不需要签名。

## 代码风格

- Kotlin / Compose：跟随现有文件的分组与命名，注释只解释"为什么"（尤其是反直觉的平台约束），
  不复述代码在做什么。
- Rust：`cargo fmt` 必须干净；`clippy` 在发布构建里以 `-D warnings` 运行。
- Python（`validation/`）：标准库优先，脚本要能在没有第三方依赖的环境直接跑。
- 新增字符串必须三语言齐全（简体中文、繁体中文、英文），
  `python3 validation/check-localization.py` 会校验键集合与格式化参数一致。

## 版本与发布

`versionCode` 每次发布递增，`versionName` 用 `主.次.修订`；发布由维护者执行，流程见
[发布与交付](RELEASING.md)。发布前必须完成构建、自动检查与模拟器回归，并把实际结果
写进 [回归报告](EMULATOR_TEST_REPORT.md)——没跑过的范围要如实标注为未验证。

## 行为准则

参与本项目即表示同意 [行为准则](CODE_OF_CONDUCT.md)。
