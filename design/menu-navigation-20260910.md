# 菜单导航与返回

菜单采用“菜单 → 一个子页面”的结构。设置内部再管理自己的分类和选项，
左上角返回、系统返回键和边缘手势遵循相同路径。

## 导航规则

- 从菜单进入设置、书签、历史、下载、网站设置、开发工具或媒体选择，返回时回到菜单。
- 设置内部按“选项 → 分类 → 设置首页 → 菜单 → 浏览器”逐级返回。
- 直接打开的标签管理或网页投屏没有菜单父级，关闭后回到浏览器。
- 全屏播放器的倍速与投屏浮层由播放器管理，不进入菜单路由。
- 打开网页或切换浏览模式会关闭当前路径；普通后台切回保留界面，外部网址导航关闭临时页面。
- Activity 配置重建保留来路。菜单操作会清除浏览器之前的退出确认计时。

## 窗口与状态

[BrowserSheetNavigation.kt](../app/src/main/java/com/mybrowser/ui/shell/BrowserSheetNavigation.kt)
同时保存路由和当前 `Presentation`，仅菜单可以压入一个子页面。
路由 key 用于恢复父级滚动和分类；每次显示创建新的回调身份，避免迟到的关闭回调影响新页面。

`BrowserSheetHost` 在整条路径中保留一个 `BrowserSheetWindow`，切换时只替换窗口内容。
设置、书签和扫码使用全屏布局，其余页面使用固定底部面板。具体过渡见[动效设计](sheet-motion-consistency.md)。

`SheetWindowContent` 将窗口返回请求交给当前页面，设置分类、书签目录和选择模式再处理自己的内部返回。
旧页面释放时只清理自己注册的回调，所有内容关闭后才销毁共享窗口。
选项、管理列表和确认对话框可以使用独立窗口，打开时停用父级返回处理。

## 滚动与关闭

底部面板没有拖动锚点或拖动手柄，表单和列表关闭边界拉伸。
滚到列表顶部或底部时只停止内容滚动，不移动整个面板。
通过返回、关闭或取消按钮退出；底部面板也可点击外部区域关闭。

安全边距从宿主读取，统一处理系统栏和键盘避让。书签编辑时保留书签页，关闭编辑窗口即可继续操作列表。

## 检查方法

导航单元测试覆盖父子返回、过期回调、重复点击、直接入口、重建和退出确认。
模拟器重点检查：

| 阶段 | 范围 |
|---|---|
| `menu-navigation` | 全部菜单子页、快速往返、立即返回、窗口释放、后台切回、重建及关闭后的网页触摸 |
| `settings-back` | 六个分类、选项、管理窗口、横屏两栏及左右边缘手势 |
| `site`、`layout` | 网站设置、布局和滚动边界 |

先按[测试指南](../TESTING_GUIDE.md#模拟器回归)安装签名包并启动 QA 服务，然后运行：

```bash
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v0.9.1-release.apk \
  --label menu-check --stages menu-navigation settings-back site layout browser developer-tools features-scripts features-filters
```

文件名需替换为实际被测版本。菜单往返默认 8 轮，设置返回默认 4 轮；
更高压力可直接调用对应脚本并指定 `--cycles`。
视觉抖动或闪帧需要截图、录像检查，不能只用无障碍坐标证明。已有结果见[回归报告](../EMULATOR_TEST_REPORT.md)。
