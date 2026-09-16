# 测试指南

先按[贡献指南](CONTRIBUTING.md#开发环境)准备环境。本文介绍检查命令、模拟器运行方式和结果定位。

## 自动检查

```bash
./build-and-test.sh --quick
```

包含三语言资源校验、Node 协议测试、Rust fmt/test 和 Android/Robolectric 单元测试。
Android 测试会构建 host JNI，Node 使用内置测试运行器。

签名配置完成后，发布检查额外运行 clippy、lint、R8、签名及 zipalign 校验：

```bash
./build-and-test.sh --release
```

只运行 Android 单元测试和 lint：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
```

[CI](https://github.com/Nobilta/pure-browser/actions/workflows/ci.yml)执行快速检查、lint、clippy 和未签名 Release 构建；
设备回归在本地运行。签名与发布流程见[发布指南](RELEASING.md)。

## 模拟器回归

三个平台都可以本地运行这套回归：需要 API 30 以上的 `google_apis` 镜像（`adb root` 可用，
Play Store 镜像不行），ABI 与本机一致——Apple Silicon 用 arm64-v8a，x86_64 主机用 x86_64。
脚本会写入书签、下载和站点数据，请使用专用模拟器。
被测包须为 `com.mybrowser` 的签名 Release；调试版使用不同包名，不能直接用于这套回归。
runner 不接受 API 29。`full` 包含启动更新提示检查。

准备模拟器（用 Android Studio 的设备管理器创建也可以）：

```bash
python3 validation/manage-avd.py image                   # 查看本机该用哪个系统镜像
python3 validation/manage-avd.py create PureBrowser_API37
python3 validation/manage-avd.py start PureBrowser_API37
```

x86_64 主机（Windows、Linux、Intel Mac）装不上只含 arm64-v8a 的官方 APK，回归前用测试签名
构建 x86_64 包；`release/prepare.py` 的发布校验只接受 arm64，正式包不受影响：

```bash
./gradlew -Pmybrowser.abi=x86_64 :app:assembleRelease
```

Windows 上把 `python3` 换成 `python` 即可；单独运行某个回归脚本时设置 `PYTHONUTF8=1`，
经 `run-regressions.py` 运行时它会代为设置。脚本在 Git Bash 中执行。

下面以 `emulator-5554` 和 0.10.0 为例，替换为实际序列号与 APK 路径。

1. 安装并启动应用，部署 UI 辅助程序：

   ```bash
   ANDROID_SERIAL=emulator-5554 bash install_and_test.sh PureBrowser-v0.10.0-release.apk
   python3 validation/setup-ui-probe.py emulator-5554
   ```

   覆盖安装要求签名相同。使用自己的测试密钥时，应使用独立测试环境。

2. 启动 QA 服务，并保持该终端运行：

   ```bash
   python3 validation/qa-server.py --apk PureBrowser-v0.10.0-release.apk
   ```

3. 在另一个终端执行回归：

   ```bash
   python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.10.0-release.apk \
     --label release-091-smoke --profile smoke
   ```

一次只运行一个 UI 脚本，避免同时构建。QA 使用本机 8875/8876 端口，部分夹具使用 8877–8879，
通过 ADB reverse 访问。使用本机代理时，在回归命令前加
`NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost`。

### 选择阶段

| 参数 | 覆盖范围 |
|---|---|
| `--profile smoke`（默认） | 地址栏、标签驻留、基础浏览、网页对话框 |
| `--profile tabs` | 驻留、普通/无痕媒体生命周期、菜单、两种弹窗视频 |
| `--profile full` | 当前设备适用的完整矩阵 |
| `--stages 名称…` | 只运行指定阶段，覆盖 profile 选择 |

例如，只检查启动更新提示和标签行为：

```bash
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.10.0-release.apk \
  --label update-tabs --stages update-launch resident
```

`update-launch` 使用 root AVD 写入缓存测试清单，验证配置重建后提示仍存在；
不会下载或安装更新，结束时还原缓存、深色模式和进程。
完整阶段名称与检查项以 [run-regressions.py](validation/run-regressions.py) 及其调用的脚本为准。

## 结果与续跑

suite JSON 和阶段日志生成在 `validation/results/`，记录 APK SHA-256、设备、WebView、测试源码哈希及阶段结果。
已安装 APK 必须与传入文件一致。只有 APK、AVD、测试源码和阶段选择全部相同时，才能加 `--resume` 续跑。
失败尝试保留在 `priorAttempts`；换包或改测试后使用新的 suite。

CI 日志和报告从对应 Actions 运行下载；本地结果不提交 Git。
发布时只把设备、实际通过阶段和未覆盖范围写入 [Release 说明](release/notes.md)，不合并不同 APK 的通过结果。

## 排查与补充验证

- 运行旧版本时，启动更新提示可能挡住 UI。测试其他功能前可关闭自动检查，更新流程单独验证。
- 安装或 JNI 加载异常可运行 `./diagnose.sh`；崩溃信息使用 `adb logcat`，同时记录包哈希、操作和 WebView 版本。
- UI 定位优先使用资源名，并等待控件实际出现或获得焦点。全屏控件会自动隐藏，定位与触摸应尽量在同一辅助会话完成。
- 修改回归框架后，做一次已知缺陷的负向对照，确认对应检查仍会失败。
- 模拟器通过不能代替真机结果。扫码预览方向、实体投屏画面、真实账号登录和更新安装，需分别检查实际结果。

需要更高压力时可增加菜单往返次数：

```bash
python3 validation/menu-navigation-regression.py --serial emulator-5554 --cycles 24
python3 validation/settings-back-regression.py --serial emulator-5554 --cycles 6
```

性能问题可使用现有启动和规则计算基准：

```bash
python3 validation/benchmark-startup.py --serial emulator-5554 --output validation/results/startup.json
cargo run --locked --release --manifest-path rust/Cargo.toml -p adblock --example benchmark -- 10
python3 validation/cosmetic-benchmark.py
```

对照测量需使用相同设备、样本和负载条件；规则计算耗时不能代表网页加载速度或功耗。
