# Pure 应用图标比选

2026-09-08。A 为旧版应用图标，B 为本轮绘制并已接入产品的原创图标「青叶 P」。
当前 0.3.1 产品资源和后续 Release 使用 B；对比图保留 A，便于在设备上核对切换效果。

- [浅色背景对比](./comparison.png)
- [深色背景对比](./comparison-dark.png)
- [可切换背景的预览](./comparison.html)
- [A 旧版图标 SVG](./current.svg)
- [B 青叶 P SVG](./leaf-p.svg)
- [当前选用图标 PNG](./selected.png)

B 保留青绿色、P 字母和叶片，把窗口外框收敛为更饱满的字母轮廓。候选同时提供
Android 自适应图标和 Android 13+ 主题单色图标。所有前景轮廓位于 108dp 画布中央的
66dp 安全圆内；预览按中央 72dp 可见区域展示圆角方形和圆形裁切，包含 48px/32px 效果。

候选源文件位于 [leaf-p/android](./leaf-p/android/)，文件名与产品资源一致；B 已接入
`app/src/main/res/`。产品 XML 和候选 XML 均通过 Android `aapt2 compile` 检查；最终 Release
已在 Android 14 启动器核对圆形裁切和应用名称。主题单色资源已检查 XML 与预览，尚未验证启动器主题开关。
SVG、HTML 和 PNG 仅为设计预览，不进入 APK，也不增加产品依赖。

当前图标的唯一产品来源为 `app/src/main/res/`，其中已接入 B。预览中的 B 直接读取产品 XML；
A 读取 `legacy/android/`，它是备份分支 `backup/pre-home-shortcut-editor-20260907` 的原始 XML 快照。
重新导出不会让 A、B 变成同一套图标。重新导出 SVG/HTML：

```bash
python3 design/app-icon/render-previews.py
```

使用已安装的 Playwright 与 Chrome 生成对比 PNG（仅设计工具需要）：

```bash
node design/app-icon/capture-previews.cjs
```

修改 Android XML 后应重新生成预览；选用候选后还需重新构建、安装并核对圆形裁切与主题图标。
