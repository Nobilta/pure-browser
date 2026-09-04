Pure 浏览器文档说明

本文件替代早期“性能提升倍数/六个 Rust 模块”的草稿。当前真实状态请阅读：

  README.md                    功能、目录、构建
  ARCHITECTURE_REVIEW.md       分层和 Rust 取舍
  CODE_QUALITY_REPORT.md       代码审查与检查结果
  APK_TEST_GUIDE.md            APK 手工回归
  FINAL_DELIVERY_SUMMARY.md    当前交付摘要

默认 Release 只打包 adblock、cache、url_utils；downloader 和 filename_parser 需要显式
opt-in，database 实验模块不进 APK。请运行 ./build-and-test.sh 获取当前 APK 的实际大小
和 SHA-256，不要使用历史文档中的固定数字。
