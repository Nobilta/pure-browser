# 历史总结说明

本文件由早期迭代留下。早期总结中提到的 6 个 Rust 模块、FTS5、固定性能倍数和旧 APK
大小均不适用于当前源码。请以以下文件为准：

- [README.md](./README.md)：当前功能、构建和目录
- [ARCHITECTURE_REVIEW.md](./ARCHITECTURE_REVIEW.md)：系统审查与 Rust 决策
- [CODE_QUALITY_REPORT.md](./CODE_QUALITY_REPORT.md)：当前检查结果
- [FINAL_DELIVERY_SUMMARY.md](./FINAL_DELIVERY_SUMMARY.md)：当前 APK 校验信息
- [EMULATOR_TEST_REPORT.md](./EMULATOR_TEST_REPORT.md)：最近设备回归

当前默认 Release 只打包 `adblock`、`cache`、`url_utils`；`rust/database` 不在 workspace，
其它 legacy crate 需显式 opt-in。重新生成交付物请运行 `./build-and-test.sh`。
