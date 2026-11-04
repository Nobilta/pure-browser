## 0.19 本地测试包验证范围

被测产物：`PureBrowser-v0.19-release.apk`（versionCode 71），本地签名 Release，尚未发布。
本次更新书签页：精简顶栏和菜单、显示缓存的网站图标、长按多选后通过手柄拖动排序，版本号保持 0.19。

自动检查通过：764 项 JVM、69 项 Rust、127 项 Node、14 项发布工具测试；三种语言的 1013 条字符串一致。
Rust fmt/clippy、Android lint、Release 构建和签名校验通过；lint 无错误，保留 84 条警告和 3 条提示。
构建日志保存在 `validation/results/bookmark-release-build.log`。

最终签名包在隐藏窗口的 ARM64 模拟器 Android 14 / API 34、WebView 113.0.5672.136 上通过
`validation/bookmark-drag-regression.py` 的 8 项场景：

- 根目录无清空按钮和根目录路径行；菜单仅含新建文件夹、导入、导出。
- 长按文件夹显示手柄并保存同级排序。
- 拖动中按返回取消，数据库顺序和原滚动位置均恢复。
- 书签向下拖动并松手保存；已选择的多条书签保持选中，短距离拖动不跳页。
- 书签向上拖动回到第一位。
- 持续拖到列表边缘，自动加载超过 50 条的下一页；75 条记录完整保留，保存后保留视图位置。
- 重新打开目录保留顺序；全局搜索中长按可多选，但不显示拖动手柄。
- 实际访问本地测试网页后缓存 WebView 图标，并在书签页显示；截图人工复核通过。

详细结果和被测 APK 校验值保存在 `validation/results/bookmark-drag-emulator-5554.json`，
执行日志为 `validation/results/bookmark-release-regression.log`，截图为同目录 `bookmark-*.png`。

同一签名包还通过 `validation/productivity-regression.py --section library` 的 4 项场景：
书签搜索和编辑、重复地址拒绝、手动加载下一页、历史搜索及删除确认、大字体 1.3 下控件可见。
结果为 `validation/results/api34-productivity-library.json`，日志为 `validation/results/bookmark-library-regression.log`。

未验证其他 Android / WebView 版本、真机、平板、折叠屏和 TalkBack 实际操作；手柄保留辅助排序操作。
本轮未重跑媒体播放设备回归。此前调试包无痕返回时发生过一次未定位的 WebView 原生 JNI 崩溃，
本次书签回归不能视为修复；诊断日志保留在 `validation/results/cast-referrer-debug-crash.log`。
既有媒体兼容范围见 `FEATURES.md`。
