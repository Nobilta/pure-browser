# 开发与测试

先按[构建指南](building.md)准备 macOS / Windows 环境。本文维护检查方法；实际结果保留在 Git 外的测试报告与日志中，交付时说明结果及未覆盖范围。

## 开发约定

- 修改前检查 Git 状态，保留其他工作区内容。按调用链核实问题；等价简化应保持正常行为与失败反馈。
- 遵循现有模块、协程和状态所有者，不增加假设性兜底、重复重试或未被调用的抽象。真实的系统/网络/文件边界继续处理，取消不能被转成普通失败。
- UI 先读[设计规范](design-system.md)，会话/存储先读[无痕契约](incognito-policy.md)。不因修复而改产品设计或降低旧测试期望。
- 新增/删除界面文字同步英文、简体和繁体，运行资源检查。Kotlin/Rust JNI 契约同步，Rust fmt/clippy 保持通过。
- 一次改动更新相应文档，规则见[文档中心](README.md)。向远程 `main` 提交 PR；描述问题、结果和实际验证，遵循用户是否提交/发布的明确要求。
- 不提交密钥、路径配置、生成文件和测试日志。原生脚本/验证工具优先标准库；确需 Pillow 的配色检查使用具备该依赖的 Python 环境。

## 按改动选择检查

| 改动 | 检查 |
|---|---|
| 只改说明文档 | 本地链接、命令参数和源码一致性；无需重建 APK |
| 发布/验证脚本 | 对应单元检查、语法及实际入口验证；无需因工具路径调整重跑应用全量测试 |
| 应用逻辑 | `./build-and-test.sh --quick` 与相关设备阶段；缺陷增加能复现原错误的必要测试 |
| 签名交付 | 直接 `./build-and-test.sh --release`，已包含 quick；再用同一 APK 检查受影响设备路径 |

低影响可逆修改不强制新增镜像实现的单测。检查通过后，只有新改动、失败或未解决疑点才扩大或重复验证。
同一工作区 Gradle 串行执行，同一模拟器 UI 脚本串行执行。

```bash
./build-and-test.sh --quick
# 有签名配置、需要交付时改用：
./build-and-test.sh --release
# 单独检查发布工具：
python3 -m unittest discover -s release -p 'test_*.py'
# 定向 JVM 示例：
./gradlew :app:testDebugUnitTest --tests 'com.mybrowser.download.*' --console=plain
```

Windows Git Bash 中 Python 命令用 `python`，构建入口可设置 `PYTHON=python`。
quick 包含三语资源、发布工具、Node、Rust fmt/test 和 JVM；release 另含 clippy、lint、R8、签名、zipalign 和附件准备。
具体任务由 [build-and-test.sh](../build-and-test.sh) 维护，不在其他文档重复测试数量。

## 脚本归属与维护

| 位置 | 职责 |
|---|---|
| 根目录 `build-and-test.sh`、Gradle Wrapper | 构建与自动检查入口 |
| `scripts/` | 安装 APK、设备诊断；设备选择沿用 `ANDROID_SERIAL` |
| `release/` | 发布校验、附件准备、GitHub / Gitee 操作及其测试 |
| `rust/`、`rust/tools/` | 原生库构建、NDK 定位与字符表生成 |
| `validation/run-regressions.py`、`check-localization.py` | 设备回归总控、三语资源检查 |
| `validation/regressions/` | 按功能维护的设备回归，保留独立入口 |
| `validation/tools/` | 共用 ADB/UI 操作、模拟器管理、UI 探针与宿主工具 |
| `validation/node/` | 应用 JavaScript 的 Node 测试；依赖仍由 `validation/package*.json` 管理 |
| `validation/fixtures/` | 被测试使用的网页、媒体、用户脚本、服务及大媒体生成工具 |
| `validation/benchmarks/` | 启动、内存及 Rust 规则查询性能测量 |
| `validation/results/` | 生成报告、截图及临时夹具，不入 Git |

新增脚本须有明确用途，在现有总入口或本指南提供调用方式；优先扩展同职责脚本，不保留一次性探针、
无人使用的演示页面和旧入口兼容壳。移动或删除时同步调用方、夹具路径、源码摘要范围和文档链接，不能降低原测试断言。
目录分类不需要新的调度框架；独立测试的不同包名、设备和网络要求在这里说明。

## 设备回归流程

使用[构建指南](building.md#无窗口模拟器)启动的专用、无窗口、无音频 AVD；脚本会写入测试书签、下载和设置。
总控需要包名 `com.mybrowser` 的签名 Release，x86_64 宿主需构建对应 ABI 的测试包，不能直接装 ARM64 交付包。
下面的 `test_apk` 填写本次实际构建路径，不从旧记录猜文件名。

```bash
test_apk="app/build/outputs/apk/release/app-release.apk"
adb -s emulator-5554 install -r "$test_apk"
python3 validation/tools/setup-ui-probe.py emulator-5554
python3 validation/fixtures/qa-server.py --apk "$test_apk"
```

QA 服务在这个终端前台运行。另一个终端从仓库根目录执行：

```bash
test_apk="app/build/outputs/apk/release/app-release.apk"
python3 validation/run-regressions.py --serial emulator-5554 --apk "$test_apk" \
  --label current-smoke --profile smoke
```

QA 默认使用 8875/8876，部分夹具使用其他本地端口，按脚本配置 ADB reverse。
本机代理干扰回环地址时设置 `NO_PROXY=127.0.0.1,localhost` 和 `no_proxy`，不修改应用网络策略。
修改 FastUiDump 后先重跑 setup-ui-probe。所有用完的模拟器和 QA/夹具进程在成功、失败或中断时均须停止。

| 总控参数 | 用途 |
|---|---|
| `--profile fast` | 地址栏、标签驻留、设置 |
| `--profile smoke` | 基础浏览与网页对话框等快速设备检查 |
| `--profile tabs` | 标签、普通/无痕媒体生命周期、锁屏和弹窗 |
| `--profile full` | 当前 API 支持的全部已注册阶段，不含下方单独入口 |
| `--stages 名称…` | 选择实际受影响阶段，覆盖 profile |
| `--keep-going` | 保留所有阶段失败，继续其他独立阶段 |
| `--resume` | 仅在 APK、AVD、测试源码和阶段选择相同时续跑 |
| `--cycles N` / `--shard N/M` | 显式压力或分片；分片用独立设备，不能共享有全局状态的夹具服务 |

阶段是否存在、API 限制及前置条件以 [run-regressions.py](../validation/run-regressions.py) 为准。
`compact-ui` 自动补 FTP/HLS 夹具；过滤订阅阶段会更改 QA 服务状态，不能与其他分片共用该服务。

## 按功能选择

| 范围 | 总控阶段 / 单独入口 | 观察结果 |
|---|---|---|
| UI 与导航 | `compact-ui`、`site`、`settings-back`、`motion-gesture`、`motion-interruption` | 操作可达、表单不误关、返回正确、动画中断后不残留遮挡 |
| 阅读与分享 | `reader-sharing`、`reader-popup`、`image-search` | 字体/配色、文章末尾、后台文档同步、当前会话 Cookie |
| 下载 | `download-management`、`ftp-hls`、`blob-download`、`download-opening` | 实际保存字节/摘要、确认/目录/多选、取消与恢复 |
| 媒体 | `media-lockscreen`、`media-lifecycle`、`system-media`、`video-*` | 同一媒体元素、进度、暂停次数、全屏/PiP 和服务归属 |
| 无痕/标签 | `private-lifecycle`、`resident`、`profiles`（支持的 API） | 环境与生命周期，按下方契约逐项核对 |
| 开发工具 | `developer-tools` | 控制台、网络详情、DOM/源码与存储的实际内容 |
| 设置/规则 | `desktop-mode`、`site-storage`、`features-filters`、`features-imports` | 站点偏好与恢复、导入持久化、真实规则隐藏及失败保留 |
| 更新与安装 | `update-launch`；[update-sources-regression.py](../validation/regressions/update-sources-regression.py) | 启动提示归属；真实下载/覆盖安装另行验证 |
| 来源与网络边界 | [media-fallback-regression.py](../validation/regressions/media-fallback-regression.py)、[media-network-regression.py](../validation/regressions/media-network-regression.py)、[cast-referrer-regression.py](../validation/regressions/cast-referrer-regression.py)、[cast-media-failure-regression.py](../validation/regressions/cast-media-failure-regression.py) | 重定向、认证、一次性响应、Cookie/Referer、不完整响应与播放失败反馈 |
| 投屏、摄像头 | [cast-play-regression.py](../validation/regressions/cast-play-regression.py)、[realtime-camera-regression.py](../validation/regressions/realtime-camera-regression.py)、[capture-regression.py](../validation/regressions/capture-regression.py) | 入口和选择器之外，还需真实接收器、预览/方向、上传结果 |
| 书签 | [bookmark-drag-regression.py](../validation/regressions/bookmark-drag-regression.py) | 多选、分页边缘排序、取消和持久化 |
| 元素隐藏闪烁 | [cosmetic-flash-regression.py](../validation/regressions/cosmetic-flash-regression.py) | 逐帧检查首次加载和动态节点，脚本自带本地服务 |
| 翻译 | [translation-regression.py](../validation/regressions/translation-regression.py) | 真实 WebView 对接本地翻译服务；依赖 Debug 包的 `run-as`，不进入 Release 总控 |

单独入口先读 `--help` 与文件头的夹具要求；不要假定它接受总控的所有参数。网络夹具的本地账号只用于测试。
TLS 夹具按 qa-server 要求在 `validation/results/` 创建临时自签名证书，不修改应用信任配置。
大媒体测试使用 [create-large-media-fixtures.py](../validation/fixtures/create-large-media-fixtures.py) 的稀疏文件，不把能定位到大偏移写成持续高码率通过。

<a id="incognito-regression-contract"></a>

## 无痕验收

保持 [incognito-policy](incognito-policy.md) 的原设计，下面是要求与入口，不是当前产物已通过的声明。

| 边界 | 检查入口与期望 |
|---|---|
| 已接受下载 | `DownloadHandlerTest.resumabilityFollowsTheRunningPrivateSession`、`DownloadPrivateScopeTest`：退出保留记录及传输，旧记录 `canResume=false`；新会话不能恢复旧任务 |
| 凭据与记录 | `DownloadMetadataStoreTest`：来源/Cookie 不持久化，管理字段与页面文件标记保留 |
| 共享偏好 | `SiteSettingsTest`：无痕期间导入新设置后，仅改镜像仍保留最新字号/JavaScript；两种权限不互相覆盖 |
| 页面与脚本 | `private-lifecycle`、`UserScriptStoreTest`：Cookie、网站存储和 GM 值隔离；重建保持会话，退出不泄漏到普通或新会话 |
| 普通功能 | 下载、书签、分享、阅读、脚本、后台播放等保持可用，不能以禁用功能替代隔离 |
| 补充设备断言 | 私密访问不新增历史、真实权限隔离、无痕锁屏分别验证；不能由普通模式或 JVM 结果推断 |

<a id="ui-regression"></a>

## UI 与媒体验收

UI 按 [design-system](design-system.md) 检查浅/深色、中英繁、320/360dp、100%/200% 字体及低高度横屏。
根据改动覆盖长标题、错误文案、IME、换行操作、触控热区与唯一可访问目标。实际 TalkBack 试听不能由 XML 语义树替代。
阅读末尾需滚到最终表格/内容行，并在地址栏收起后重新读取工具条位置。

媒体后台播放开启时，全屏锁屏期间进度继续且 pauseCount 不增；解锁保持同一元素/来源和增强全屏。
显式暂停不能被自动恢复覆盖；关闭后台播放则按既有策略暂停。另测 Home/PiP、转屏、切标签、退出无痕和系统媒体旧 token。
模拟器运行无音频，不能证明实体设备声音连续；API 31 前后 PiP 分支、最低支持 API 和不同 WebView 需实际补测。

## 证据、失败与性能

- 总控在开始/结束核对安装包 SHA-256，结果位于 Git 外 `validation/results/`，含设备、WebView、测试源码哈希与阶段状态。同版号或同签名不能替代同包校验。
- 应用源码变化后重新构建并验证受影响行为；测试变化后使用新 suite。保留首次失败及复测来源，不能混合不同 APK 的通过结果。
- 定位失败先区分测试假设与产品错误。不能放宽业务断言让测试变绿；必要时用原缺陷做负向对照。
- 记录跳过原因与未验证范围。远程服务可用性、真机音频/投屏、真实安装不由模拟器结果推断。
- 控制台命令使用现有无障碍 setText 并读回校验，不能用会截断 shell 字符的逐字符输入。长按遵循设备超时，布局坐标在实际动作时重新取得。
- 性能采用同机同负载比较；SwiftShader 模拟器不能证明真机帧率、耗电或音频连续性。不凭文件长度或主观判断增加缓存/状态层。

已有测量入口：

```bash
python3 validation/benchmarks/benchmark-startup.py --serial emulator-5554 --output validation/results/startup.json
cargo run --locked --release --manifest-path rust/Cargo.toml -p adblock --example benchmark -- 10
python3 validation/benchmarks/cosmetic-benchmark.py
```
