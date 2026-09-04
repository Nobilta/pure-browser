# Legacy / quarantined code

此目录只保留历史兼容实现，不属于默认 APK 的源码输入。

- `native-libs/`：旧命名的 `.so` 文件，仅用于排查早期安装问题；Gradle 不会扫描此目录。
- `artifacts/`：旧品牌 APK、调试 APK 和历史失败日志，仅供比对；根目录只保留当前可交付
  的 Pure 浏览器 Release APK，避免误装旧版本。
- 当前下载、文件名解析和数据库的权威实现分别是 Android `DownloadManager`/Kotlin parser、Kotlin parser 和 Android SQLite。

如果确实需要旧 JNI 接口，请先确认 ABI、符号和生命周期契约，再通过 `-Pmybrowser.includeLegacyRust=true` 构建；不要把本目录加入 `jniLibs` source set。
