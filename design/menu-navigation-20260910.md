# 菜单返回与弹层生命周期（0.9.0）

## 当前导航规则

菜单只能进入一个子页面：设置、书签、历史、下载、网站设置、开发工具或媒体选择。
设置内部返回路径为“选项 → 分类 → 设置首页 → 菜单 → 浏览器”。左上角、系统返回及边缘手势规则一致。
直接打开的标签、网页投屏没有菜单父级；菜单中的页面播放速度入口已删除。
全屏倍速与投屏由播放器窗口内的右下角浮层处理，不属于菜单路由；倍速选择只关闭当前浮层。
打开网页或切换模式关闭当前路径。普通后台切回保留界面，外部网址导航清空临时页面，配置重建保存来路。

## 一个窗口内切换内容

`BrowserSheetNavigation` 原子保存路由和当前 `Presentation`，仅 MENU 可以压入一个子页面。
路由 key 用于恢复父级滚动与分类，每次显示创建新的回调身份，迟到关闭和重复点击不能影响新页面。

`BrowserSheetHost` 在整条路径存续期间只创建一个 `BrowserSheetWindow`。切换路由仅替换窗口内内容，
不销毁旧窗口后再弹出新窗口；子页面切换保持不透明底板，只动画内容。
面板动效在 0.9.1 收敛为一套语义（进入 / 窗口内换页 / 换容器），见 [弹层动效一致性](sheet-motion-consistency.md)；此处只保留路由与位置规则。
设置及书签使用全屏 Surface，其余页面使用 `BrowserBottomSheet`；书签编辑弹窗打开时保留书签页。

`SheetWindowContent` 将窗口的返回请求交给当前页面，设置分类、书签目录及选择模式各自处理内部返回。
旧页面释放只能清理自己注册的回调，不能覆盖新页面。全部内容关闭后才销毁共享窗口。
二级选项、管理列表和确认使用独立输入窗口；打开时停用设置父级返回。
安全边距从宿主读取，兼容旧 Android；系统栏主题及键盘避让统一处理。

## 固定位置与边界

所有原底部弹层改用固定 Surface，没有拖动锚点或拖动手柄。表单与列表统一关闭 overscroll 拉伸，
包括用户脚本、过滤订阅、网站设置、管理网站、设置选项、历史、下载、标签、投屏、倍速和开发工具。
滚动到上下边界只停止内容滚动，不移动整页；使用返回、关闭/取消按钮或外部区域关闭。

## 验证

- 导航单元测试检查父子返回、旧回调、重复点击、直接入口、重建与浏览器退出确认。
- `menu-navigation-regression.py` 检查全部菜单子页、24 轮快速往返、0/15/50/100 ms 立即返回、
  窗口数量、已释放内容、后台切回、重建以及退出后的真实网页触摸。
- `settings-back-regression.py` 检查六个分类、选项、管理弹层、横屏两栏与实际左右边缘手势。
- 网站、过滤及脚本等表单使用实际像素和连续滑动检查；切换闪屏使用录像逐帧核对。
  无障碍坐标不变不能单独证明渲染稳定。

```bash
python3 validation/qa-server.py --apk PureBrowser-v<版本>-release.apk
python3 validation/setup-ui-probe.py emulator-5554
python3 validation/run-regressions.py --serial emulator-5554 --apk PureBrowser-v<版本>-release.apk \
  --label <标签> --stages menu-navigation settings-back site layout browser developer-tools features-scripts features-filters
```

实际设备、安装包及失败记录见 README 和 EMULATOR_TEST_REPORT；临时验证脚本完成后删除。
