# 构建记录

更新时间：2026-09-05

这份文件只记录当前构建方式；早期记录中的 27.x NDK、35MB APK 和“全部 Rust 模块”描述
已废弃。

## 当前环境

- JDK：17+（本机可用版本以 `java -version` 为准）
- Android SDK：Platform 37 / targetSdk 37
- minSdk：34
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
5. 将产物复制到 `PureBrowser-v0.1.0-release.apk`，输出大小与 SHA-256。

## Native 产物

默认 Release 只包含以下三个项目自有 JNI 库（Compose 依赖另带一个很小的
`libandroidx.graphics.path.so`）：

```text
lib/arm64-v8a/libmybrowser_adblock.so
lib/arm64-v8a/libmybrowser_cache.so
lib/arm64-v8a/libmybrowser_url_utils.so
```

`downloader`、`filename_parser` 是显式 opt-in legacy；`database` 不在 workspace。这样可
避免无用 native 代码、过大的 APK 和 JNI 符号漂移。

## 当前 Release 校验值

- 文件：`PureBrowser-v0.1.0-release.apk`
- 大小：9,984,278 bytes（约 9.52 MiB）
- SHA-256：`9826d200baec3890fa715375f6be672db2663bea822c63284052bf63abe4bec9`
- `apksigner verify --verbose`：通过（APK Signature Scheme v2）
- Rust：49 tests passed；Android 单元测试：123 passed；lint：0 errors / 2 warnings

## 安装

```bash
./install_and_test.sh
# 或
adb install -r PureBrowser-v0.1.0-release.apk
adb shell am start -n com.mybrowser/com.mybrowser.MainActivity
```

如需构建 x86_64 调试包：

```bash
./gradlew -Pmybrowser.abi=x86_64 :app:assembleDebug --console=plain
```
