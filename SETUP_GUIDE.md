# Pure 浏览器环境配置

## 必需工具

- JDK 17+
- Android SDK Platform 37、Build Tools、Platform Tools
- Android NDK（推荐与本机相同的 29.x 版本）
- Rust stable、cargo、rustup；Android target 为 `aarch64-linux-android`
- 可选：Android 模拟器或已授权的 USB 调试设备

检查当前环境：

```bash
java -version
cargo --version
rustup target list --installed
bash rust/resolve-android-ndk.sh .
```

如果没有 NDK：

```bash
./install-ndk.sh
```

脚本只操作 Android SDK 和 rustup，不修改 `.zshrc`、全局 Cargo 配置或项目外的源码。

## 构建

```bash
./build-and-test.sh
```

默认 Release 为 arm64-v8a。调试 x86_64 模拟器：

```bash
./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug --console=plain
```

默认只编译/打包 `adblock`、`url_utils`。cache 等 legacy crate 需要显式 opt-in：

```bash
./gradlew -Pmybrowser.includeLegacyRust=true :app:assembleRelease --console=plain
```

## 安装

```bash
./install_and_test.sh
```

或手工：

```bash
adb install -r PureBrowser-v0.3.0-release.apk
adb shell am start -n com.mybrowser/com.mybrowser.MainActivity
```

## 目录原则

Android 业务按 `core/data/download/filter/privacy/search/tabs/media/dlna/security/ui` 分层；
`MainActivity` 只做生命周期编排。`rust/database` 与 `legacy/native-libs` 是隔离参考资料，
不会被 Gradle 自动读取。

## 故障排查

```bash
./diagnose.sh
adb logcat -d | rg 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV'
```

完整架构与测试说明见 [README.md](./README.md) 和 [ARCHITECTURE_REVIEW.md](./ARCHITECTURE_REVIEW.md)。
