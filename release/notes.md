## 0.19 本地测试包验证范围

被测产物：`PureBrowser-v0.19-release.apk`（versionCode 71），本地签名 Release，尚未发布。
本次提前应用主文档的广告元素隐藏规则，减少加载过程中先显示后隐藏；版本号保持 0.19。

自动检查通过：766 项 JVM、69 项 Rust、129 项 Node、14 项发布工具测试；三种语言的 1013 条字符串一致。
Rust fmt/clippy、Android lint、Release 构建和签名校验通过；lint 无错误，保留 83 条警告和 3 条提示。
构建日志保存在 `validation/results/cosmetic-release-build.log`。

最终签名包在隐藏窗口的 ARM64 模拟器 Android 14 / API 34、WebView 113.0.5672.136 上通过
`validation/cosmetic-flash-regression.py` 的 11 项场景：

- 冷启动首屏、动态设置广告 class、替换 head 后恢复样式；正常内容保持可见。
- 重定向后按实际目标地址应用规则。
- elemhide、generichide、document 三种例外分别按预期保留元素或放行网络请求。
- 弹窗新标签的首屏隐藏。
- 临时关闭本站过滤后放行，恢复过滤后再次隐藏。
- 无痕页面的首屏隐藏。
- 持久站点开关及全局开关关闭后放行。

逐个 requestAnimationFrame 检查匹配广告的计算样式，故意延迟图片 2.5 秒，确认隐藏不依赖 load 完成。
旧签名包基线 175 个采样帧中有 163 帧广告可见；新签名包所有开启隐藏的场景均为 0 个可见广告采样帧。
这不是屏幕逐像素录制，不代表未覆盖的广告或全部脚本执行时点；正常正文在所有场景保持可见。
测试使用 10.0.2.2，避免 EasyList 对 localhost/127.0.0.1 的 generichide 例外干扰。
测试检查页面伪造桥接凭据被拒绝；JVM 另覆盖非法网址、实例关闭及查询异常。

详细结果、WebView 版本与被测 APK 校验值在 `validation/results/cosmetic-flash-emulator-5554.json`，
旧包基线在 `cosmetic-flash-baseline-emulator-5554.json`，执行日志为 `cosmetic-release-regression.log`。
Node 测试覆盖 DOM 尚未建立、动态元素、替换 head、规则更新和关闭隐藏后的恢复。

未验证真机、平板、折叠屏、不支持 DOCUMENT_START_SCRIPT 的旧 WebView 兼容路径及多 Profile 无痕隔离。
本机 Android 17 模拟器缺少系统镜像，未计入通过范围。跨源 iframe 内部、Shadow DOM、CSP/页面强制覆盖样式、
未匹配规则的广告不保证首帧隐藏。本轮未重跑书签和媒体播放设备回归；此前未定位的调试包 WebView JNI 崩溃
不能视为已修复，既有诊断日志仍保留在 `validation/results/cast-referrer-debug-crash.log`。
