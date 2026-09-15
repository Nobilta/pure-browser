# Pure 浏览器

[中文](README.md) · [English](README.en.md)

[![CI](https://github.com/Nobilta/pure-browser/actions/workflows/ci.yml/badge.svg)](https://github.com/Nobilta/pure-browser/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Nobilta/pure-browser)](https://github.com/Nobilta/pure-browser/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2010%2B%20%C2%B7%20arm64--v8a-3ddc84)](#安装)

**一个开源的 Android 轻量浏览器**：Jetpack Compose 界面 + 系统 WebView 内核 + Rust 广告过滤，
不依赖 Google 服务——注册登录、扫码识别、广告过滤与下载都在本地完成。

它把"够用"做扎实：标签与返回符合直觉、页面状态尽量不丢、能装能升级，而且每个版本都用可复现的检查
与模拟器回归自证结果（见[回归报告](EMULATOR_TEST_REPORT.md)），不把"没验证"说成"已完成"。
支持 Android 10（API 29）及以上，只发布 `arm64-v8a`，applicationId 为 `com.mybrowser`。

## 截图

| 浏览 | 菜单 | 标签面板 | 设置 | 无痕 |
|---|---|---|---|---|
| <img src="docs/screenshots/browsing.png" width="155" alt="浏览网页"> | <img src="docs/screenshots/menu.png" width="155" alt="浏览器菜单"> | <img src="docs/screenshots/tabs.png" width="155" alt="标签面板"> | <img src="docs/screenshots/settings.png" width="155" alt="设置"> | <img src="docs/screenshots/incognito.png" width="155" alt="无痕模式"> |

## 特性概览

- **浏览与标签**：多标签、标签分组与搜索、来源标签返回、网页弹窗、页内查找、长按查看历史。
  后台页面按实际打开的标签数保留，切回时表单、SPA 状态和滚动位置都还在。
- **地址栏**：顶部/底部可选，网址与搜索词识别、历史/书签建议、滚动时自动收起。
- **首页、书签与历史**：快捷入口可自定义；书签与历史支持分页搜索，Netscape HTML 导入/导出。
- **离线扫码**：Camera2 + ZXing，支持相机、手电筒和从图片识别，不需要 Google 服务或联网识别。
- **下载**：HTTP Range 分段、暂停/继续、断点续传与进程重启恢复；点击条目直接交给 Android 打开文件。
- **应用更新**：启动时自动检查 GitHub Releases，确认后下载并校验安装包，再交给系统安装器。
- **视频与投屏**：全屏增强播放器（倍速、亮暗音量手势、画中画）、后台媒体、DLNA 投屏。
- **隐私与安全**：无痕模式（支持 `MULTI_PROFILE` 时使用独立 Profile）、按站权限、隐私清理、
  系统 Autofill/WebAuthn。
- **广告过滤与脚本**：内置 EasyList/EasyPrivacy/EasyList China 快照，支持自定义订阅与用户脚本。
- **开发者工具**：有界控制台与网络日志、规则命中解释、源码高亮与页面信息。
- **界面**：Material 3、动态配色、深色模式、简体中文/繁体中文/英文；面板动效统一为一套节奏。

完整的能力清单与行为边界（含已知限制）见[功能与行为边界](FEATURES.md)。

## 安装

从 [Releases](https://github.com/Nobilta/pure-browser/releases) 下载 `PureBrowser-v<版本>-release.apk`
安装即可。升级请**直接覆盖安装**（同一签名，保留数据），不要先卸载。

```bash
shasum -a 256 PureBrowser-v<版本>-release.apk                     # 校验下载
apksigner verify --verbose --print-certs PureBrowser-v<版本>-release.apk
./install_and_test.sh PureBrowser-v<版本>-release.apk             # 安装到已连接设备
```

## 从源码构建

环境要求：macOS、JDK 17+、Android SDK Platform/Build Tools 37、NDK、Rust stable（含 `aarch64-linux-android`
target）、Node.js 18+、Python 3.9+。SDK 从 `local.properties` 或环境变量读取，NDK 由
`rust/resolve-android-ndk.sh` 解析，不需要修改全局 shell 或 Cargo 配置。

```bash
./diagnose.sh                    # 环境自检
./build-and-test.sh --quick      # 三语言资源校验、Node 协议测试、Rust fmt/test、Android 单元测试
./build-and-test.sh --release    # 追加 clippy、lint、R8、签名与 zipalign 验证，产出签名 APK
```

Release 构建需要本地 `keystore.properties` 指定签名信息；**签名密钥、口令、`local.properties`、
构建输出、APK 与验证结果都不提交 Git**（原因与 CI 取舍见[发布与交付](RELEASING.md)）。

模拟器回归（写入测试书签、下载与站点数据，只在一台专用模拟器上串行运行，构建期间不要同时跑）：

```bash
python3 validation/qa-server.py --apk PureBrowser-v<版本>-release.apk
# 另一个终端：
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v<版本>-release.apk \
  --label <标签> --profile tabs
```

`--profile smoke`（默认）覆盖基础浏览，`tabs` 覆盖标签与媒体生命周期，`full` 是完整矩阵；
阶段划分、失败重试与耗时说明见[测试指南](TESTING_GUIDE.md)。

## 架构

平台语义留在 Kotlin，纯计算下沉到 Rust JNI，网页内控制交给注入的 JavaScript：

```text
app/src/main/java/com/mybrowser/
  core/ data/ home/ tabs/ search/  WebView 与导航、SQLite、快捷入口、标签、搜索引擎
  ui/                              Compose 界面，按功能分子包
    shell/ 菜单 menu/ 设置 settings/ 书签与历史 library/
    devtools/ 播放器与投屏 player/ 首页·下载·扫码 home/ download/ qr/
  download/ filter/ userscript/   下载、过滤订阅及用户脚本
  privacy/ site/ security/        Profile、网站权限与安全
  media/ dlna/ qr/                视频、系统媒体、DLNA 与离线二维码
  update/                         GitHub Releases 检查、下载与校验
app/src/main/assets/              播放控制、脚本运行时和内置规则
rust/                             adblock、site_identity、url_utils
validation/                       可复现页面、自动检查及模拟器工具
```

分层约定：`core` 持有跨层共享的词汇与平台管线，业务包只依赖 `core` 及更低的包，不反向依赖。
依赖版本由 `app/gradle.lockfile`、`gradle/verification-metadata.xml` 与 `rust/Cargo.lock` 固定，
正常构建不会自动刷新锁与校验值。设计细节见[架构说明](ARCHITECTURE_REVIEW.md)与
[系统能力边界](design/system-integration.md)。

## 参与贡献

Issue 与 Pull Request 都欢迎，完整说明见[贡献指南](CONTRIBUTING.md)。要点：提交前跑
`./build-and-test.sh --quick`，涉及界面或生命周期的改动另外跑相关模拟器阶段；用户可见行为变化同步到
[功能与行为边界](FEATURES.md)；不要提交签名材料、`local.properties`、构建输出或 APK。

请遵守[行为准则](CODE_OF_CONDUCT.md)。

## 支持与反馈

- **缺陷与功能请求**：走 [Issue](https://github.com/Nobilta/pure-browser/issues)，模板会提示需要的信息
  （应用版本、Android 与 WebView 版本、复现步骤、浏览模式）。
- **安全漏洞**：请不要开公开 Issue，按[安全策略](SECURITY.md)私下报告。
- **已知限制**：[功能与行为边界](FEATURES.md)与[回归报告](EMULATOR_TEST_REPORT.md)逐条列出了能力边界
  与"已验证 / 未验证"范围，报缺陷前可以先对照。

## 路线图

以下明确**不在当前计划内**，需要时再评估：翻译与跨设备同步；完整 uBlock/AdGuard 兼容（当前是网络规则
子集 + 基础元素隐藏）；完整 Tampermonkey 兼容（无 `GM_xmlhttpRequest` 与任意原生网络/文件接口）；
非 arm64 设备、非 Android 平台与商店分发渠道。

近期变更见[更新日志](CHANGELOG.md)，版本计划以 [Release](https://github.com/Nobilta/pure-browser/releases) 为准。

## 许可与第三方

本项目使用 [MIT 许可](LICENSE)。过滤规则、Rust 依赖与第三方组件的来源和许可见
[第三方说明](THIRD_PARTY_NOTICES.md)。

## 相关文档

- 能力与边界：[功能与行为边界](FEATURES.md) · [架构说明](ARCHITECTURE_REVIEW.md)
- 验证：[测试指南](TESTING_GUIDE.md) · [回归报告](EMULATOR_TEST_REPORT.md)
- 设计与维护：[系统能力边界](design/system-integration.md) · [播放器与投屏](design/video-playback-and-casting.md) ·
  [菜单返回](design/menu-navigation-20260910.md) · [弹层动效](design/sheet-motion-consistency.md) ·
  [后退缓存](design/back-navigation-without-reload.md) · [发布与交付](RELEASING.md) · [更新日志](CHANGELOG.md)
