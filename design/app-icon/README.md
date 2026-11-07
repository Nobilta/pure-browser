# 应用图标

Pure 浏览器使用 **Pure Planet（澄白星环）** 自适应图标：瓷白微光渐变底、海蓝星球与倾斜轨道。

实际资源位于 `app/src/main/res/`：

- `drawable/ic_launcher_background.xml`：`108dp` 瓷白渐变背景；
- `drawable/ic_launcher_foreground.xml`：海蓝星球与轨道前景；
- `drawable/ic_launcher_monochrome.xml`：Android 13+ 主题单色图标；
- `mipmap-anydpi/ic_launcher.xml`：自适应图标入口。

修改时同步这些 XML，并在启动器检查圆形、方形、圆角矩形与主题单色裁切效果。
