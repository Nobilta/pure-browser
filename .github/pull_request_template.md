## 这个 PR 做了什么

<!-- 一两句话说明动机；缺陷类改动请附 Issue 链接 -->

## 验证

- [ ] `./build-and-test.sh --quick` 通过
- [ ] 涉及界面/生命周期/WebView 行为时，跑了相关模拟器阶段（写明阶段名与结果）
- [ ] 行为有变化时，PR 里写清"改动前 / 改动后"的实际表现

<!-- 把实际跑的检查与结果贴在这里，例如：
     ./build-and-test.sh --quick → EXIT=0；336 项 Android/Robolectric、57 项 Rust、55 项 Node、639 项资源校验
     python3 validation/run-regressions.py --stages resident → PASS（8 项检查）
-->

## 清单

- [ ] 用户可见行为有变化时，同步了 [FEATURES.md](../FEATURES.md)
- [ ] 依赖、构建、验证方式或产物有变化时，同步了 [README.md](../README.md)
- [ ] 新增字符串三语言齐全（简体中文、繁体中文、英文）
- [ ] 没有提交签名材料、`local.properties`、构建输出、APK 或 `validation/results/` 下的产物
- [ ] 新能力依赖 WebView 特性时，按运行时能力探测实现（`WebViewFeature.isFeatureSupported`）
