# Pure 应用图标比选

2026-09-08。A 为当前应用图标，B 为本轮绘制的原创候选「青叶 P」。
当前 0.3.1 安装包保留 A；B 尚未接入产品资源，供选择后替换。

- [浅色背景对比](./comparison.png)
- [深色背景对比](./comparison-dark.png)
- [可切换背景的预览](./comparison.html)
- [A 当前图标 SVG](./current.svg)
- [B 青叶 P SVG](./leaf-p.svg)

B 保留青绿色、P 字母和叶片，把窗口外框收敛为更饱满的字母轮廓。候选同时提供
Android 自适应图标和 Android 13+ 主题单色图标。所有前景轮廓位于 108dp 画布中央的
66dp 安全圆内；预览按中央 72dp 可见区域展示圆角方形和圆形裁切，包含 48px/32px 效果。

候选源文件位于 [leaf-p/android](./leaf-p/android/)，文件名与产品资源一致；选择 B 后可将
`drawable/` 中三个文件及 `mipmap-anydpi/ic_launcher.xml` 复制到 `app/src/main/res/`。
候选 XML 已通过 Android `aapt2 compile` 检查，尚未作为实际 Launcher 图标进行设备验收。
SVG、HTML 和 PNG 仅为设计预览，不进入 APK，也不增加产品依赖。

当前图标的唯一产品来源为 `app/src/main/res/`。预览直接读取这套 Android XML 和候选 XML，
避免手工绘制另一份外观近似的 A。重新导出 SVG/HTML：

```bash
python3 design/app-icon/render-previews.py
```

使用已安装的 Playwright 与 Chrome 生成对比 PNG（仅设计工具需要）：

```bash
node design/app-icon/capture-previews.cjs
```

修改 Android XML 后应重新生成预览；选用候选后还需重新构建、安装并核对圆形裁切与主题图标。
