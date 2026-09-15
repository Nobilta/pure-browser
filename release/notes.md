Pure Browser 0.9.1

- 内部结构整理：消除 `core`/`filter` 与 `data`/`media` 两处包级循环依赖，共享类型下沉到 `core`。
- `ui` 包按功能拆分为 `shell`/`menu`/`settings`/`library`/`devtools`/`player`/`home`/`download`/`qr` 子包。
- 删除无引用的公开成员、未使用的三语言字符串资源与失效文档引用；JNI 导出与 Kotlin 声明一一对应。
- 不改变任何用户可见行为；测试与资源校验数量随删除项同步下降。

支持 Android 10 及以上、arm64-v8a。使用原签名覆盖安装并保留数据。
