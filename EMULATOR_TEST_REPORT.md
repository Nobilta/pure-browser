# 0.9.0 验证报告

日期：2026-09-14。发布前验证对象为最终签名 Release；模拟器阶段均绑定实际安装 APK 的 SHA-256。
本轮验证新窗口来源页保留、标签返回、菜单过渡、媒体生命周期和应用更新。原始结果保留在本地 `validation/results/`，不提交 Git。

## 安装包与自动检查

- `PureBrowser-v0.9.0-release.apk`，versionCode 18，包名 `com.mybrowser`，Android 10+、arm64-v8a。
- **4,714,826 bytes**，约 4.50 MiB；SHA-256：`dd65ed4d44d91a6c48d830da7ffcc3348551b0fed317d867a1afb89ad896e5f4`。
- v2 签名、16 KiB zipalign、三份 native 库的 ELF LOAD 16 KiB 对齐及两个运行时脚本一致性检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，与原交付 0.8.1 相同。
- **333 项 Android/Robolectric、58 项 Rust、55 项 Node 测试通过**；Android 使用真实 host JNI，0 failures、0 errors、0 skipped。
- Rust fmt/clippy、646 项三语言资源校验、R8 和签名 Release 构建通过。lint：0 errors、24 warnings、1 hint。
- 最终完整命令为 `./build-and-test.sh --release`，退出码 0。最终构建后只修改文档与验证工具，没有改变 APK 对应的应用源码。

另有 **15 项临时更新器测试通过**，覆盖 HTTPS 重定向边界、清单类型与兼容性校验、缓存到期、服务端限流退避、
截断/损坏/未签名文件、取消下载与拒绝降级。临时 Kotlin 测试源码在完整构建前删除，XML 结果保留，不计入固定 333 项。
标签管理固定测试共 11 项，包含来源关系、最近使用回退及关闭后台标签保持当前选择。
既有文件打开用例另外核对相机与更新 Provider 的组件独立、正确内容读取和目录边界。

依据：[完整构建日志](validation/results/release-0.9.0/build.log)、
[自动检查汇总](validation/results/release-0.9.0/automated-checks.json)、
[专项单元测试](validation/results/release-0.9.0/focused-unit-tests.json)、
[文件分享测试](validation/results/release-0.9.0/TEST-com.mybrowser.download.DownloadFilesTest.xml)、
[安装包校验](validation/results/release-0.9.0/package-validation.json)。

## API 37 模拟器回归

设备为 `PureBrowser_API37` / `emulator-5554`：Android 17（API 37）、arm64-v8a、WebView 145.0.7632.218。
UI 回归串行运行；使用上面的签名安装包。以下七个定向阶段均通过，另完成原版本覆盖升级检查。

| 阶段 | 验证范围 | 耗时 | 结果 |
|---|---|---:|---|
| resident | 来源页 DOM、表单、SPA、滚动与五类子标签返回路径；标签分组 | 47.1 秒 | 通过 |
| media-lifecycle | 普通页面媒体生命周期、后台与返回 | 140.0 秒 | 通过 |
| private-lifecycle | 无痕页面媒体与会话生命周期 | 31.4 秒 | 通过 |
| capture | 相机授权、拍照/录像完整字节回传、取消与旧回执处理 | 45.1 秒 | 通过 |
| menu-navigation | 菜单、设置与子页面导航、返回及界面状态 | 354.0 秒 | 通过 |
| video-popup | 全屏播放器倍速和投屏弹层、临时倍速与后台 | 101.1 秒 | 通过 |
| video-popup-cross | 跨域播放器的对应弹层及生命周期 | 97.4 秒 | 通过 |

来源页专项共七项检查：切回后页面 token 与首次加载时间不变，未提交文本、SPA 内存和滚动位置不丢失，离开时媒体暂停；
标签分组正常；`window.open` 后系统返回、`target=_blank` 先返回自身历史再关闭、`window.close`、底栏返回、标签列表“×”
均正确回到来源页，且没有退出提示。来源页的滚动位置在往返前后均为约 1049.9 CSS px。

相机专项共六项检查通过：拒绝权限和取消操作保持原表单；JPEG 的 75,270 bytes、MP4 的 609,104 bytes 全部返回网页；
相机期间旋转或网页导航不会提交旧回执。

依据：[最终七阶段汇总](validation/results/api37-release-090-final-suite.json)、
[来源页详细检查](validation/results/api37-release-090-final-resident/result.json)、
[相机文件回传](validation/results/api37-release-090-final-capture/result.json)。汇总记录准确 APK 哈希、设备、测试源码哈希和原始日志文件名。

## 覆盖升级与 GitHub 更新

已用原签名从原交付 **0.8.1（17）覆盖安装到 0.9.0（18）**，没有卸载或清空应用数据。
旧版创建的书签在升级后仍可见，已安装 APK 的 SHA-256 与交付文件一致。
依据：[原版覆盖升级](validation/results/release-0.9.0/upgrade/result.json)。

初次线上安装验收发现相机与更新共用了 FileProvider 组件名，系统安装器因 URI 读取权限冲突退出。
修正为独立 `UpdateFileProvider` 后重新构建，并对最终 APK 完整重跑上述七阶段。
同签名临时旧包配合预置更新缓存，已通过真实系统来源授权、17 → 18 安装器覆盖升级、包哈希核对、书签保留和再次检查为最新版。
依据：[安装器验收](validation/results/release-0.9.0/local-update.json)、
[初次安装失败记录](validation/results/release-0.9.0/initial-candidate/result.json)。

更新器使用公开仓库 `Nobilta/pure-browser` 的最新正式 Release 下载直链读取 `update.json`，避开匿名 REST API 共享限额。
首次发布后，线上验收使用带更新器、同签名且版本号设为 17 的临时测试包作为起点；原版 0.8.1 没有该更新入口。
流程包括实际获取 GitHub 清单、下载正式 APK、校验包、处理系统来源授权、由 Android 安装器覆盖升级、检查书签保留及再次检查为最新版。
临时包不对外发布。发布后验收结果单独上传为 [v0.9.0 的 github-update.json](https://github.com/Nobilta/pure-browser/releases/download/v0.9.0/github-update.json)，
该附件记录下载和安装后的 SHA-256；本文件中的发布前自动测试与模拟器数量不包含这项发布后验收。

正式分发附件为签名 APK、`update.json` 和 `SHA256SUMS`，位于 [v0.9.0 Release](https://github.com/Nobilta/pure-browser/releases/tag/v0.9.0)。

## 工具修正与覆盖边界

验证中修正了两个夹具时序问题：添加书签时等待键盘退出后“保存”按钮位置稳定；来源页夹具的新控件在滚动后才显示，
避免挡住视频播放按钮。修正后重新运行通过；失败尝试保留在本次记录中，未混入通过数量。
最终阶段曾因本地 QA 服务未运行而中断；恢复服务后，按相同 APK、设备及测试源码续跑全部通过，原始失败日志记录在汇总的 `priorAttempts`。

本轮只执行上述定向阶段，没有重跑历史完整 43 阶段矩阵，也没有连接 OPPO / ColorOS 等实体设备。
API 29 专项测试与工具兼容分支已移除，`minSdk=29` 及原签名保持不变；本版安装支持 Android 10，未将历史 Android 10 结果视为本次实测。
扫码硬件、实体 DLNA 接收器画面、真实账号登录和全部在线视频站点不在本轮已验证范围。

本地仅保留最新交付 APK 和必要验证记录；临时更新测试包、测试脚本及模拟器快照在发布后验收结束时清理。
源码按 `master` 的一次普通提交维护并推送到远程 `main`，签名材料、生成输出和 APK 不入 Git。
