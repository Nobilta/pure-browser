# 安全策略

## 报告漏洞

请通过 GitHub 的[私密漏洞报告](https://github.com/Nobilta/pure-browser/security/advisories/new)提交安全问题
（仓库的 Security → Report a vulnerability），避免在公开 Issue 中披露利用细节。
如果该入口不可用，可以开 Issue 提醒维护者启用私密报告，先不要附上漏洞详情。

报告中请尽量提供：

- 应用、Android 和 WebView 的版本。
- 复现步骤或最小页面；涉及恶意网页时，最好提供可离线复现的 HTML。
- 可能的影响，例如读取其他站点数据、绕过权限，以及是否需要用户操作。
- 问题是否已公开披露。

维护者会核实影响并安排修复。修复发布后可在版本说明中致谢；希望匿名时请在报告中注明。

## 支持范围

安全修复面向最新正式版。旧版本出现的问题，请尽可能确认最新版是否仍然受影响。
涉及 WebView / Chromium 的缺陷，也请报告给 [Chromium 问题跟踪](https://issues.chromium.org/issues/new?component=1456367)；
本项目能做的修复取决于问题所在层，部分情况需要更新系统 WebView。

## 相关说明

无痕、脚本和网站兼容性的使用注意事项见[功能介绍](FEATURES.md)。
实际行为偏离这些说明，或现有机制存在可利用的缺陷时，仍欢迎报告。

正式签名密钥由维护者在本机保管，不进入仓库或 CI；发布要求见[发布指南](RELEASING.md)。
