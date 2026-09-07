# 项目文件清单

## 必需源码

- [x] `app/src/main/java/com/mybrowser/`：Android/Kotlin 主程序与 Compose UI
- [x] `app/src/main/res/`、`app/src/main/assets/`：资源和广告规则
- [x] `rust/adblock/`、`rust/url_utils/`：默认 native 模块
- [x] `rust/cache/`、`rust/downloader/`、`rust/filename_parser/`：兼容性 legacy 模块
- [x] `rust/resolve-android-ndk.sh`、`rust/build.sh`：可移植 Rust 构建入口

## 验证与交付

- [x] `build-and-test.sh`：测试、lint、Release 构建、签名和摘要
- [x] `install_and_test.sh`：连接设备后安装启动
- [x] `diagnose.sh`：设备信息与崩溃关键词检查
- [x] `APK_TEST_GUIDE.md`：手工回归清单
- [x] `EMULATOR_TEST_REPORT.md`：模拟器验证记录
- [x] `PureBrowser-v0.3.0-release.apk`：当前 Release，体积与校验值见 README，不纳入 Git

## 架构与决策

- [x] `ARCHITECTURE_REVIEW.md`：分层、边界修复和 Rust 取舍
- [x] `RUST_OPTIMIZATION_ANALYSIS.md`：可迁移性评估
- [x] `CODE_QUALITY_REPORT.md`：静态检查和风险记录
- [x] `FINAL_DELIVERY_SUMMARY.md`：APK 与验证摘要

## 不应作为产品输入

- `rust/database/`：隔离的早期实验，不在 workspace
- `legacy/artifacts/`：历史 APK/日志，仅供比对，Gradle 不读取
- `app/build/`、`rust/target/`：构建生成目录
- `.cargo/config.toml`：只保留说明，不含个人绝对路径

## 维护规则

1. 新增 JNI 库必须同时更新 Kotlin 声明、Rust 导出、Gradle staging 和验证清单。
2. 任何偏好/网络/文件输入都要有长度和协议边界。
3. 不把未经 benchmark 的性能倍数写进交付指标。
4. 修改后至少运行 `./build-and-test.sh`，并在有设备时运行 `./diagnose.sh`。
