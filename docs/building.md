# macOS / Windows 构建与模拟器

所有命令从仓库根目录运行。Windows 使用 Git for Windows 自带的 **Git Bash**；本项目不要求 WSL。
运行检查见[测试指南](testing.md)，正式密钥与发布见[发布流程](releasing.md)。

## 环境与版本来源

| 工具 | 当前要求 | 维护来源 |
|---|---|---|
| JDK | 21 | [CI 配置](../.github/workflows/ci.yml) |
| Node.js / npm | Node 22 | CI 配置、[validation/package-lock.json](../validation/package-lock.json) |
| Python | 推荐 3.12，脚本最低 3.9 | CI 配置；截图配色脚本另需 Pillow |
| Rust | 1.98.0，rustfmt、clippy | CI 配置、[Cargo.lock](../rust/Cargo.lock) |
| Android SDK | Platform 37、Build Tools 37.0.0、Platform Tools、Command-line Tools；设备测试另需 Emulator | [版本目录](../gradle/libs.versions.toml)、CI 配置 |
| Android NDK | 27.2.12479018 | CI 配置、[NDK 解析脚本](../rust/resolve-android-ndk.sh) |

`compileSdk`、`targetSdk` 和 `minSdk` 目的不同；以版本目录为准，不为运行测试随意调整应用 SDK 目标。
Gradle 与 Rust 锁文件、依赖校验文件保持现有约束。无需安装全局 Gradle，使用仓库的 `gradlew`。

JDK、Node、Python、Rust、adb 和 emulator 应在当前 shell 的 PATH 中可用。用 `java -version`、
`node --version`、`python3 --version`（Windows 用 `python --version`）、`rustc --version` 和 `adb version` 确认。
macOS 的 `JAVA_HOME` 可指向本机 JDK 21；Windows 将它设置为 JDK 安装目录。不要复制其他机器的个人绝对路径。

通过 Android Studio 管理 SDK，在根目录 `local.properties` 设置 `sdk.dir`，或使用 `ANDROID_HOME`。
`ANDROID_SDK_ROOT`、`ANDROID_HOME` 与 `sdk.dir` 若同时配置，应指向同一个 SDK；模拟器镜像也安装在这里。
Windows 推荐让 Android Studio 生成 Java properties 转义路径，或使用正斜杠，例如 `sdk.dir=C:/Android/Sdk`。
构建找不到 Bash 时，通过 `PURE_BASH` 或 `-Pmybrowser.bash=...` 指向 Git Bash。
NDK 可用 `ANDROID_NDK_HOME` 指定，否则脚本从 SDK 查找。签名配置与本机路径均不提交。

## 宿主、设备和 APK 架构

| 环境 | 模拟器镜像 / APK ABI | Gradle 参数 |
|---|---|---|
| Apple Silicon Mac | `arm64-v8a` | 默认值，可显式 `-Pmybrowser.abi=arm64-v8a` |
| Intel Mac、常见 Intel/AMD Windows、x86_64 Linux | `x86_64` | `-Pmybrowser.abi=x86_64` |
| ARM64 Android 实体设备 / 正式交付 | `arm64-v8a` | 默认值 |

其他宿主先核对 `python -c "import platform; print(platform.machine())"` 与已安装模拟器能力，不把 Windows 一概视作 x86_64。
不能把 ARM64 正式包的安装失败误判为应用回归；安装包 ABI 必须与模拟器一致。

为对应的 APK 安装 Rust target（需要同时构建两种包时安装两项）：

```bash
rustup target add aarch64-linux-android x86_64-linux-android
```

## 编译与安装

macOS：

```bash
./build-and-test.sh --quick
./gradlew :app:assembleDebug --console=plain
# Intel Mac 的模拟器改用：
# ./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -W -n com.mybrowser.debug/com.mybrowser.MainActivity
```

Windows Git Bash（x86_64 模拟器）：

```bash
PYTHON=python ./build-and-test.sh --quick
./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -W -n com.mybrowser.debug/com.mybrowser.MainActivity
```

Debug 包名为 `com.mybrowser.debug`，可与正式版共存。回归总控检查 `com.mybrowser`，需要已签名的 Release 测试包；
单独脚本只有提供 `--package` / `--debug` 参数时才能使用 Debug 包，先读它的 `--help`。

正式交付在任一宿主都使用默认 ARM64：

```bash
./build-and-test.sh --release
# Windows Git Bash：PYTHON=python ./build-and-test.sh --release
```

需要先配置已有签名；没有密钥时构建得到的 unsigned APK 不可当作交付物。脚本包含完整自动检查、lint、
R8、签名和对齐验证，生成根目录交付 APK 与 `outputs/release/` 元数据。版本从源码读取，不手写旧版本文件名。
本地测试包使用相同构建入口，无需写 changelog；此时生成的说明只有版本标题，正式发布时再提供公开变化正文。

x86_64 模拟器使用独立测试签名配置，构建方式为：

```bash
./gradlew -Pmybrowser.abi=x86_64 :app:assembleRelease --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
```

此包仅用于宿主对应的测试；发布准备脚本只接受 ARM64。不要用它覆盖根目录正式交付 APK。
覆盖安装要求签名一致；不能为了消除安装失败而卸载用户的真实安装或替换正式密钥。

已有签名 APK 可用安装工具安装并启动；它不执行回归测试：

```bash
ANDROID_SERIAL=emulator-5554 bash scripts/install-apk.sh app/build/outputs/apk/release/app-release.apk
ANDROID_SERIAL=emulator-5554 bash scripts/diagnose-device.sh
# 构建后直接安装：ANDROID_SERIAL=emulator-5554 INSTALL=1 ./build-and-test.sh --release
```

两个工具都沿用 adb 的 `ANDROID_SERIAL` 选择设备；只有一个已连接设备时可省略。
诊断工具导出 Logcat 并搜索异常关键词，日志可能包含其他应用，需核对进程，不能代替回归测试。

## 无窗口模拟器

1. 先运行 `adb devices` 和 `emulator -list-avds`，优先复用配置匹配且未被其他任务占用的专用测试设备；同一设备一次只运行一套 UI 脚本。
2. 镜像选择 `google_apis`，完整测试需可 `adb root`，不要使用 Play Store 镜像。总控接受 API 30+，三语专项需 API 33+；最低支持版本/API 分支另按场景补测。
3. 日常验证默认复用一个专用 AVD，不按任务或日期重复创建。只有没有合适设备、需要不同 API／ABI 或并行隔离测试时才新建临时 AVD；记录本次创建的设备和启动的进程，结束后关闭并删除临时设备。`create` 不覆盖同名 AVD。

启动已有设备，示例名称替换为实际可复用的 AVD：

```bash
python3 validation/tools/manage-avd.py start PureBrowser_QA --serial emulator-5554
```

确需新建临时设备时：

```bash
python3 validation/tools/manage-avd.py image
python3 validation/tools/manage-avd.py create PureBrowser_QA_Temp
python3 validation/tools/manage-avd.py start PureBrowser_QA_Temp --serial emulator-5554
# Windows 把 python3 换成 python；可通过 create/image 的 --api 明确选择其他版本。
```

`--serial emulator-5554` 对应启动端口 `5554`，端口已被连接设备占用时拒绝启动。
启动入口固定传入 `-no-window -no-audio`，SDK 路径沿用构建配置。手动启动时也必须保持这两个参数，例如：

```bash
emulator -avd PureBrowser_QA -port 5554 -no-window -no-audio -no-snapshot-save
```

手动命令在独立终端运行。自动化启动时记录自己创建的进程，结束后只清理这些进程。
Windows 需先配置 Android Emulator 可用的硬件加速；先用 `emulator -accel-check` 检查，失败时报告环境问题，不能伪称设备验证通过。

安装后记录设备与 WebView 信息：

```bash
adb -s emulator-5554 shell getprop ro.product.cpu.abi
adb -s emulator-5554 shell getprop ro.build.version.sdk
adb -s emulator-5554 shell dumpsys webviewupdate
python3 validation/tools/setup-ui-probe.py emulator-5554
```

测试结束、失败或中断后，关闭本次启动且已无人使用的模拟器，释放内存：

```bash
adb -s emulator-5554 emu kill
```

如果它是本次新建的临时 AVD，确认进程退出后还必须删除设备及其磁盘数据，释放硬盘空间：

```bash
avdmanager delete avd --name PureBrowser_QA_Temp
```

QA 服务在所属终端用 Ctrl+C 停止；后台启动的服务按记录的 PID 停止。最后检查 `adb devices` 和任务服务进程。
同时检查 `emulator -list-avds`，确认临时 AVD 已删除。不要关闭或删除其他任务正在使用的设备，不要留下无窗口模拟器、
使用宽泛的 kill 命令、或为一次调试修改全局 shell 配置。

长期复用的专用 AVD 保留配置，测试结束清除测试应用、文件和辅助进程。旧数据积累导致磁盘镜像膨胀时，
确认其中只有可丢弃的测试数据，再在停机状态下使用 `-wipe-data` 启动重置；它会删除设备内全部应用和设置。
AVD 数据与 SDK 系统镜像分别占用空间，删除 AVD 不会删除共享系统镜像。
