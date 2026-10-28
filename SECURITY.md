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

## 外部协议与深链

网页可以把任何非 `http(s)`、非内部 scheme 的地址交给系统处理，包括 `intent://` 和
第三方 App 自定义的 scheme。可跳转的 scheme 不做白名单枚举（枚举永远追不上已安装的
App），安全性由三层控制承担：

1. **按站点确认**：默认每次询问，用户可为站点选择允许或拒绝；无用户手势的子框架跳转一律拒绝；
2. **节流**：同一页面短时间内多次请求会被限制，避免连续弹框或批量拉起；
3. **Intent 清洗**：去掉显式 component/selector/package，强制 `BROWSABLE`，撤销 URI 授权
   flag；`intent://` 中页面自带的 extras 会保留，与 Chrome 的行为一致，需要目标 App 自行
   校验来源。`EXTRA_FALLBACK_URL` 由浏览器自己消费，不传给目标 App。

`QUERY_ALL_PACKAGES` 仅用于在确认框里显示目标 App 名称（`queryIntentActivities` 对未声明的
自定义 scheme 查不到），不枚举或上传应用清单；启动本身不需要该权限。该权限属于 Google Play
的受限权限，若通过 Play 分发需单独申报，未获准则只能显示 scheme、不显示 App 名称。
