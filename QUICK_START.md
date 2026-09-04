# 5 分钟开始

```bash
cd /Users/zhangronghan001/pure-browser
./build-and-test.sh
./install_and_test.sh
```

应用包名：`com.mybrowser`（Release）或 `com.mybrowser.debug`（Debug）。启动后可按下面
顺序快速确认主流程：

1. 聚焦地址栏，输入 `example.com`，确认按钮显示“访问”；输入普通文字，确认显示“搜索”。
2. 打开菜单 → 添加书签，修改标题或 URL 后保存。
3. 新建标签并切换回来。
4. 打开一个有视频的页面；右下角出现投屏按钮后，确认选择页给当前播放流显示标记。
5. 连接同一局域网的 DLNA 设备，测试设备选择。

常用检查：

```bash
./diagnose.sh
adb logcat -d | rg 'FATAL EXCEPTION|UnsatisfiedLinkError|SIGSEGV'
```

分层、Rust 取舍和边界说明见 [ARCHITECTURE_REVIEW.md](./ARCHITECTURE_REVIEW.md)。
