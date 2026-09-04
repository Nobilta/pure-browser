# 代码质量与架构审查

更新时间：2026-09-05

## 检查范围

审查覆盖 `app/src/main/java`、Android 资源、Gradle/Rust 构建配置、默认及 legacy Rust
crate、单元测试和设备验证脚本。构建生成目录（`app/build`、`rust/target`）不作为源码
审查对象。

## 结果

| 检查 | 结果 |
|---|---|
| Kotlin 编译 | 通过 |
| Android 单元测试 | 通过 |
| Android lint | 通过，0 errors / 2 warnings（AGP 更新、ChromeOS x86_64） |
| Rust format | 通过 |
| Rust tests | 通过 |
| Rust clippy `-D warnings` | 通过 |
| Release APK 签名 | 由 `apksigner verify --verbose` 验证 |

## 分层评价

- `core` 集中 WebView client、导航、安全、日志和池化；没有把页面业务塞进拦截回调。
- `data`、`download`、`filter`、`privacy`、`search`、`tabs`、`media`、`dlna` 各自持有
  明确的存储/线程边界。
- `ui` 是无副作用 Compose 表面；WebView 和 ActivityResult 仍由 `MainActivity` 编排。
- `MainActivity` 文件较大，但其复杂度主要来自 Android 生命周期回调。当前拆成多个
  ViewModel 不会减少 JNI/WebView 竞态；如果未来需要后台任务，再按生命周期边界拆分。

## 已修复的风险

- 偏好 JSON、标签、下载元数据、文件名、header、URL、DLNA XML/SOAP 响应全部有界。
- NativeCache 的 Kotlin 句柄访问/关闭共用锁；Rust 侧也拒绝过长 key 和 entry。
- 无痕 Cookie 删除等待异步回调；清除数据的提示和页面刷新不再提前发生。
- 数据库 repository 的查询与关闭同步；WebView 池容量计算和 DLNA 搜索 Job 竞态已修正。
- 项目 Cargo 配置不再包含开发者绝对路径，旧脚本不再读取其他任务临时文件。
- 清除浏览数据不再删除书签和下载文件，并增加不可逆操作确认；书签编辑草稿按标题与 URL
  共同更新，过滤器偏好、位图压缩入口和重复提交均已加边界。
- 删除重复 `TabSheet`、未调用的 SSL 包装器、无用 JNI/Rust 残留和 69 个未使用资源；
  29 处 Android API 调用换成一致的 KTX 写法，现代 WebView 的无效 `saveFormData` 设置移除。

## 仍需按产品计划处理的事项

同步、密码管理、阅读模式、跨设备标签同步和浏览器数据导入导出没有实现，也没有被伪装
成已完成。真实 DLNA 设备矩阵、不同 WebView provider 的兼容性和 Compose UI 自动化测试
是后续验证工作，不是 Rust 重写工作的理由。

剩余两条 lint warning 均经过显式评估：AGP 9.4 升级应单独做工具链迁移回归；当前交付仅
面向 arm64 真机，不能为消除 ChromeOS 提示就在未验证情况下扩大 Release ABI。它们不是
源码正确性问题。

## 维护门槛

修改 JNI、持久化格式或网络协议时，必须补对应单元测试/边界检查，并运行：

```bash
cd rust && cargo fmt --all -- --check && cargo test --all \
  && cargo clippy --workspace --all-targets -- -D warnings
cd .. && ./gradlew :app:testDebugUnitTest :app:lintDebug \
  --console=plain
```
