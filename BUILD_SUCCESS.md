# 构建记录

更新时间：2026-09-08

这份文件只记录当前构建方式；早期记录中的 27.x NDK、35MB APK 和“全部 Rust 模块”描述
已废弃。

## 当前环境

- JDK：17+（本机可用版本以 `java -version` 为准）
- Android SDK：Platform 37 / targetSdk 37
- minSdk：29（Android 10+，Release 为 arm64）
- NDK：由 `rust/resolve-android-ndk.sh` 自动解析（本机当前 29.0.14206865）
- Rust：stable；默认目标 `aarch64-linux-android`

## 可复现构建

```bash
./build-and-test.sh
```

脚本依次运行：

1. Rust `fmt --check`、测试和 clippy；
2. Android debug 单元测试和 debug lint（当前 AGP 配置只生成 `testDebugUnitTest`，没有独立的 `testReleaseUnitTest` 任务）；
3. `app:assembleRelease`；
4. `apksigner verify --verbose`（系统安装了 Build Tools 时）；
5. 将产物复制到 `PureBrowser-v0.3.1-release.apk`，输出大小与 SHA-256。

构建前还会运行网页视频协议测试及三语言资源校验。实际数量、当前体积和校验值统一见
[README](./README.md)。

## Native 产物

默认 Release 只包含以下两个项目自有 JNI 库（Compose 依赖另带一个很小的
`libandroidx.graphics.path.so`）：

```text
lib/arm64-v8a/libmybrowser_adblock.so
lib/arm64-v8a/libmybrowser_url_utils.so
```

`cache`、`downloader`、`filename_parser` 是显式 opt-in legacy；`database` 不在 workspace。这样可
避免无用 native 代码、过大的 APK 和 JNI 符号漂移。

## Release 体积策略

- R8 使用 full mode 和优化规则，AndroidX consumer rules 负责其反射/运行时边界；项目不再
  以包级规则保留整个 Compose、`data` 或 `tabs`。
- 默认 Android JNI 规则只保护可达 native 方法的名字，不再强制保留未使用的 legacy JNI
  门面。
- 未使用的 Compose Preview 依赖已从主依赖移除；预览工具仍只存在于 debug 配置。
- DEX 和 `.so` 在直发 APK 中使用 ZIP 压缩；manifest 会生成
  `android:extractNativeLibs="true"`，由目标 Android 10+ 在安装时解压。代价是安装工作和
  安装后磁盘占用可能略增，换取明显更小的 APK 下载体积。
- 早期相同功能基线的压缩对照从 9,984,278 bytes 降至 1,898,669 bytes；这是历史结果。

## 当前 Release 校验值

当前产物及签名摘要见 [README](./README.md)，实际设备验证见
[回归报告](./EMULATOR_TEST_REPORT.md)。本文件不再重复维护另一套校验值。

## 安装

```bash
./install_and_test.sh
# 或
adb install -r PureBrowser-v0.3.1-release.apk
adb shell am start -n com.mybrowser/com.mybrowser.MainActivity
```

如需构建 x86_64 调试包：

```bash
./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug --console=plain
```
