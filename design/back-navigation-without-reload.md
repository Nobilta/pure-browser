# 同标签网页后退与缓存

普通模式会在 WebView 支持时开启 back/forward cache（后退/前进缓存），尝试保留跨文档导航前的页面。
**开启开关不保证后退时保留文档。** 本次测试的 WebView 145.0.7632.218 声明支持，但后退仍会重建页面。

这个机制处理同一标签内 A → B → A 的导航。不同标签之间的切换由应用自己的页面驻留机制处理，
参见[标签与返回](../FEATURES.md#标签与返回)。

## 当前实现

[WebViewConfig.kt](../app/src/main/java/com/mybrowser/core/WebViewConfig.kt) 的 `applyBackForwardCache`
先检查 `WebViewFeature.BACK_FORWARD_CACHE`，再调用 `WebSettingsCompat.setBackForwardCacheEnabled`。
[MainActivity.kt](../app/src/main/java/com/mybrowser/MainActivity.kt) 在每次配置 WebView 时传入当前浏览模式。

- 普通页面启用；无痕页面和使用共享存储的无痕降级模式均关闭。
- 不支持该能力的 WebView 保持原有历史导航行为，不能只凭 Android 版本判断是否可用。
- 页面能否进入缓存由 WebView 决定，可能受监听器、响应头、内存和 Provider 策略影响。
- 缓存页数和超时使用 WebView 默认值，没有使用实验性的 `BackForwardCacheSettings` 调参。
- 清理数据时，`resetPageForDataRemoval()` 清空驻留页并丢弃当前 WebView，新实例从空历史开始。
- 内存压力下，应用可以回收驻留 WebView；WebView 内部历史缓存也有自己的回收策略。

## 实测结果

2026-09-15，在 API 37 模拟器、WebView 145.0.7632.218 上：

| 检查 | 结果 |
|---|---|
| `isFeatureSupported(BACK_FORWARD_CACHE)` | `true` |
| 设置后 `getBackForwardCacheEnabled` | `true` |
| 普通页面后退 | 文档 token 变化，页面被重建 |
| 未提交表单 / JavaScript 内存状态 | 丢失 / 重置 |
| `notRestoredReasons` | 仅返回 `masked`，没有暴露具体原因 |
| 追加 `--enable-features=WebViewBackForwardCache` | 确认标志已读取，文档仍重建 |
| 模拟器内存增加到 6 GB | 文档仍重建 |
| 关闭缓存开关作对照 | 同样重建，滚动表现相同 |

普通测试页不包含媒体、fetch 循环或 `pushState`，上述对照仍未找到保留文档的条件。
当前保留能力开关，后续 WebView 版本需要重新实测，不能据此承诺“后退不重载”。

滚动偏移在不同运行中出现过 0 和约 1050 CSS px，关闭缓存后也有相同现象。
因此滚动恢复不能单独作为命中后退缓存的证据。

## 如何验证

[resident-regression.py](../validation/resident-regression.py) 记录同标签后退前后的文档 token、
表单、JavaScript 状态和滚动位置，并根据实际结果检查：

- **文档保留**：表单和 JavaScript 内存状态必须保持。
- **文档重建**：测试页表单为空，JavaScript 状态归零；滚动偏移仅记录，不断言固定值。

结果写入 `result.json` 的 `sameTabBack`。两种分支都通过各自检查，意味着行为符合对应分支，
不意味着后退缓存已经命中。运行方式见[测试指南](../TESTING_GUIDE.md)，各版本结果见[回归报告](../EMULATOR_TEST_REPORT.md)。
