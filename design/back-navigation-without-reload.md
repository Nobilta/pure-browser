# 同标签跨文档后退不重载（已实现）

按本文档的**方案 A** 实现：开启 WebView 自身的 back/forward cache。
实现位于 `core/WebViewConfig.kt`（`applyBackForwardCache`）与 `MainActivity.configure`（按浏览模式开关）。

## 问题与实测数据

同一标签内 A → B 后退回 A 时，WebView 原本重新创建文档。`validation/resident-regression.py`
的同标签用例在 API 37 模拟器上的实测（改动前）：

```
documentRebuilt: true          ← 文档被重建
draft: 'Unsubmitted A'  → ''   ← 未提交表单丢失
spa:   1                → 0    ← SPA 内存状态丢失
scroll: 1049.90         → 1049.90  ← 滚动位置保留
```

文档内历史（`pushState`、锚点）的后退不受影响；标签之间的切换不重建，因为那种情况走应用自己的驻留机制，
不是 WebView 历史。

## 实现

```kotlin
// core/WebViewConfig.kt
fun applyBackForwardCache(webView: WebView, enabled: Boolean) {
    runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE))
            WebSettingsCompat.setBackForwardCacheEnabled(webView.settings, enabled)
    }
}

// MainActivity.configure，每个 WebView 每次 acquire 都会重放
com.mybrowser.core.WebViewConfig.applyBackForwardCache(view, !privacy.isIncognito)
```

决策与边界：

- **按能力探测，不按 Android 版本**：WebView 侧的 `kWebViewBackForwardCache` 默认关闭，
  是否可用取决于设备上已安装的 WebView 版本，所以用 `WebViewFeature.isFeatureSupported` 判断；
  不支持时保持原行为（后退重载）。
- **无痕与降级模式不启用**：缓存文档会活过会话，且没有公开的 flush API（关闭开关才会让 WebView 丢弃缓存页），
  因此私密页面一律关闭；正常页面在退出无痕、切换模式时都会重新 `configure` 并带上正确开关。
- **清理数据时不需要额外钩子**：`resetPageForDataRemoval()` 已经 `clearResidentTabs()` 并丢弃当前 WebView，
  缓存页随之销毁，新的 WebView 从空历史开始。
- **不可缓存的页面照旧重载**：注册了 `unload` 监听、`Cache-Control: no-store` 等页面本来就不进 bfcache，
  后退仍然是重建 + 平台恢复滚动偏移。
- 页面数与超时沿用 WebView 自身的默认值（`BackForwardCacheSettings` 在 1.17.0 仍是实验 API，
  没有引入；需要调参时再显式 opt-in）。
- 与标签驻留的关系：驻留（`RecentTabStore`）管标签切换，bfcache 管同一 WebView 的历史，两者叠加；
  内存压力下应用侧的 FIFO 回收仍然生效，WebView 侧也有自己的页数上限。

## 验证

`resident-regression.py` 的同标签用例改为**两种契约各自断言**，避免在支持缓存的设备上静默通过：

- 文档被保留：`draft` 与 `spa` 必须与后退前一致（`documentRebuilt == false`）；
- 文档被重建：`draft` 必须为空、`spa` 必须归零（滚动偏移只记录不断言，见下）。

同时把 `documentRebuilt`、前后 token、表单、SPA 与滚动值写入 `result.json` 的 `sameTabBack`，
回归报告记录本次实测的实际分支。

`resident` 阶段其余检查不变：来源页 DOM/表单/SPA/滚动、离开页媒体暂停、标签分组，
以及 `window.open`／`target=_blank`／`window.close`／工具栏返回／标签列表 × 五条返回路径。

## 实测结果：本机 WebView 仍会重建（2026-09-15）

在 API 37 模拟器、WebView `145.0.7632.218` 上，开启后文档**仍被重建**。排查过程与证据：

| 检查 | 结果 |
|---|---|
| `WebViewFeature.isFeatureSupported(BACK_FORWARD_CACHE)` | `true` |
| 设置后 `getBackForwardCacheEnabled` | `true` |
| 普通页面（无媒体、无 fetch 循环、无 pushState）后退 | `t=back_forward`，标题里的文档 token 变化 → 文档重建 |
| 页面的 `notRestoredReasons` | `reasons:[{"reason":"masked"}]`（WebView 未暴露真实原因） |
| 追加 WebView 特性标志 `--enable-features=WebViewBackForwardCache` | 日志确认标志已读取（`cr_CommandLine`），文档仍重建 |
| 模拟器内存加到 6 GB（排除 Android 专有的 bfcache 内存控制） | 文档仍重建 |
| 关掉开关做 A/B（`applyBackForwardCache(view, false)`） | 行为一致：同样重建、滚动表现相同 |

结论：这个 WebView 版本**声明支持该能力但实际不保留文档**，因此本版不宣称"后退不重载"。
应用侧代码保留（能力探测 + 仅普通模式开启），原因是 A/B 显示它没有可测量的副作用，而在真正实现了
bfcache 的 Provider 上会立刻生效；届时把 `resident` 用例的断言切到"文档保留"分支即可验证。

一个容易误判的观察：同一检查点的滚动偏移在不同运行间会 0 与 1050 交替，关掉开关后同样如此，
因此它与 bfcache 无关（夹具用脚本滚动，是否被历史项恢复依运行时序而定），用例只记录不断言。

