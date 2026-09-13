# 0.8.1 验证报告

日期：2026-09-13。验证对象为最终签名 Release，模拟器专项结果均绑定实际安装 APK 的 SHA-256。
本轮重点验证扫码相机预览、旋转、相机生命周期及图片识别；原始结果保留在本地 `validation/results/release-0.8.1/`，不提交 Git。

## 安装包与自动检查

- `PureBrowser-v0.8.1-release.apk`，versionCode 17，包名 `com.mybrowser`，Android 10+、arm64-v8a。
- **4,689,026 bytes**，约 4.47 MiB；SHA-256：`15a1e0bb3bd67d602850248956e8b33d014e10125a5eda8430dec977906a26ff`。
- v2 签名、16 KiB zipalign、三份 native 库的 ELF LOAD 16 KiB 对齐及两个运行时脚本一致性检查通过。
- 证书 SHA-256：`7d468e8b3a9be385a2386b27ef548e83cb55206e67911d81b721b5adbe1e8236`，与 0.8.0 相同。
- **330 项 Android/Robolectric、58 项 Rust、55 项 Node 测试通过**；Android 使用真实 host JNI，0 failures、0 errors、0 skipped。
- Rust fmt/clippy、622 项三语言资源校验、R8 和签名 Release 构建通过。lint：0 errors、19 warnings、1 hint。
- 完整 `build-and-test.sh` 于 20:33:13 开始，67.61 秒完成，退出码 0；没有将临时专项计入固定测试数量。

另有 **7 项临时几何测试通过**，覆盖 288 组传感器角度、显示旋转、缓冲区和视口组合：
传感器 0°/90°/180°/270°，显示四个方向，三种缓冲区尺寸与六种横竖/方形视口。
验证等长且垂直的像素轴、中心位置、裁切覆盖、旋转方向、镜像奇偶性和空视口。
临时 Kotlin 测试源码在完整构建前删除，测试 XML 保留。

依据：[完整构建](validation/results/release-0.8.1/build-final.json)、
[构建日志](validation/results/release-0.8.1/build-final.log)、
[交付审计](validation/results/release-0.8.1/delivery.json)、
[临时几何测试](validation/results/release-0.8.1/geometry-unit.xml)。固定单元测试和 lint XML 位于 `automated/`。

## 预览变形与方向

独立 Camera2 诊断程序使用恒等 TextureView 矩阵，记录原始 640×480 亮度帧、默认预览及传感器方向。
测试图包含 TOP/LEFT/RIGHT、不同角标、方格和正圆。先确认模拟器图像输入的实际投影，再与浏览器预览比较。
参考图只补偿显示旋转并等比裁切；同时比较四种旋转及额外镜像候选，检查真实预览的方向和比例。

旧版 0.8.0 在同一输入下复现重复旋转和拉伸：竖屏中央圆环为 **154×272 px**，宽高比 0.566；
横屏为 **232×131 px**，宽高比 1.771。90°→270° 时宽高不变，旧版画面也未更新。

| 最终包场景 | 显示方向 | 中央圆环尺寸（px） | 结果 |
|---|---|---|---|
| Android 17 后摄 | 0° / 180° / 90° / 270° | 204×204 / 203×203 / 174×174 / 174×174 | 方向正确、1:1 |
| Android 17 仅前摄可用 | 0° / 180° / 90° / 270° | 204×204 / 203×203 / 174×174 / 174×174 | 与系统默认预览一致，无重复镜像 |
| Android 10 后摄 | 0° / 180° / 90° / 270° | 167×166 / 168×166 / 128×128 / 128×127 | 方向正确、栅格边界差不超过 2 px |

两版系统的正确参考方向均优于其他旋转/镜像候选。Android 17 的 1382×691 横屏预览、Android 10 的
912×765 横屏预览，均在尺寸不变的 90°→270° 旋转中更新；Android 10 的 996×1153 竖屏也通过 0°→180° 检查。
150% 字体横屏下，两个系统的预览和选图操作可见，圆环分别为 174×174、128×128 px。
模拟器实际相机传感器角度为 90°；其他传感器角度由上述几何测试覆盖。

依据：[前后对比](validation/results/release-0.8.1/preview-comparison.png)、
[旧版测量](validation/results/release-0.8.1/baseline/geometry.json)、
[Android 17 后摄](validation/results/release-0.8.1/api37-preview/geometry.json)、
[前摄回退](validation/results/release-0.8.1/api37-front-preview/geometry.json)、
[Android 10 后摄](validation/results/release-0.8.1/api29-preview/geometry.json)。对应目录保留截图、预览边界和显示旋转记录。

## 扫码、生命周期与覆盖升级

PureBrowser_API37：Android 17 / WebView 145.0.7632.218，手势导航。
PureBrowser_API29：Android 10 / WebView 91.0.4472.114，三键导航。两者均为 arm64、主机 GPU。

两版系统各 **13 项生命周期/图片检查和 1 项实时相机检查通过**：

- 打开扫码获得相机客户端；Home 释放、同进程返回恢复、Back 释放；连续三次开关不残留占用。
- 150% 字体横屏仍可取景和选图；中文、多行、emoji、看似 HTML 的内容原样展示并复制；继续扫描恢复相机。
- `javascript:` 内容保持纯文本；空白图片和无效图片有对应错误；错误后再次选有效图片仍能识别。
- 拒绝相机权限后仍能选图。实时 Camera2 帧中的网页二维码成功打开本地验证页，离开扫码后释放相机。

两个模拟器均使用原签名从 0.8.0（16）覆盖安装到 0.8.1（17），没有卸载或清空浏览器数据。
安装前后、首次启动前采集的数据库与偏好文件校验值一致：Android 17 为 45 份，Android 10 为 13 份。
五份阶段日志未发现浏览器 Java/native 崩溃或 ANR 标记，结束时两个系统均无本应用活动相机客户端。

依据：[Android 17 功能](validation/results/release-0.8.1/api37-qr-lifecycle/results.json)、
[Android 17 实时相机](validation/results/release-0.8.1/api37-qr-camera/results.json)、
[Android 10 功能](validation/results/release-0.8.1/api29-qr-lifecycle/results.json)、
[Android 10 实时相机](validation/results/release-0.8.1/api29-qr-camera/results.json)、
[Android 17 升级](validation/results/release-0.8.1/api37-upgrade.json)、
[Android 10 升级](validation/results/release-0.8.1/api29-upgrade.json)、
[崩溃审计](validation/results/release-0.8.1/crash-audit.json)。

## 测试工具、覆盖边界与清理

测试期间修正了工具适配：文件选择器改用新读取的节点坐标点击，复制后等待系统剪贴板浮层消失，
避免浮层遮挡后续按钮；Android 10 从发布包映射识别 Compose 的原生视图容器，权限流程使用旧版支持的命令和界面。
失败尝试保留在专项记录中，完成结果均来自同一最终 APK；这些工具调整没有改变发布代码。

用户反馈 OPPO / ColorOS 多台手机、多个系统版本出现相同问题。本轮未连接 ColorOS 真机，
未验证具体 OPPO 机型、真实自动对焦和手电筒硬件；不能将模拟器通过视为这些设备均已通过。
本轮没有重跑 0.8.0 的全部开发工具、播放器或下载矩阵，旧包结果未计入本版。

临时测试源码、诊断程序、测试输入图片和工作目录已删除，两个模拟器上的诊断应用及 UI 辅助已移除。
模拟器和 QA 服务已停止；旧交付包与过期记录已清理，仅保留 0.8.1 APK 和本轮必要验证记录（含旧版预览对照）。
清理明细见 [cleanup.json](validation/results/release-0.8.1/cleanup.json)。
