# Haminn 1.4.0 自动化与 Android 11 兼容验证报告

验证日期：2026-09-11。验证对象为 `life.airen.haminn`，versionName 1.4.0、versionCode 6、minSdk 29、target/compileSdk 37。交付 APK 的准确摘要以 release 校验输出与同目录 `SHA256SUMS` 为准。

## 结论

旧国产 WebView 不再因为缺少 Multi-Profile、WebMessageListener 或 document-start script 而被整体阻断。荣耀 CMA-AN00、Android 11/API 30、华为 WebView 11.0.8.305 已实际进入兼容 Runtime，应用库、Bridge、数据持久化、本地包、离线图标、五 Tab、收藏与开发管理自动化无失败。该结果证明这一设备组合可以运行 Haminn 主链，但不把传统兼容模式描述成完整浏览器隔离。

当前质量等级是“已安装到目标 Android 11 实机的签名候选版，等待人工能力和视觉验收”，不是已完成公开商用发布审批。

| 门禁 | 结果 |
| --- | --- |
| 工具链 | 通过：JDK 17.0.14、Gradle 9.3.1、AGP 9.1.1、Build Tools/API 37 |
| Web/API 合同 | 通过：Node 8/8 |
| JVM 单元测试 | 通过：1/1 |
| Android lint | debug 0 error/29 warning；release 0 error/31 warning，warning 为版本提示和风格建议等非阻断项 |
| 荣耀 Android 11 instrumentation | 34 项：33 passed、1 skipped、0 failure、0 error；跳过项是需要外部电脑进程的互操作夹具 |
| 旧 WebView Runtime | 华为 WebView 11.0.8.305；Store/Bridge 握手、页面 API、代码更新后数据保留和 Font Awesome 资源路径通过 |
| release 签名 | 通过 APK Signature Scheme v3；RSA-4096，包名/版本/minSdk/targetSdk 校验通过 |
| release 冷启动 | Android 11 实机冷启动完成，未发现 Haminn `AndroidRuntime` FATAL |
| 国内运行依赖 | Gradle/Manifest 锁文件未引入 Play Services、Firebase 或 ML Kit；二维码使用 APK 内 CameraX + ZXing |

真机自动化重点覆盖：`compat-web-message` 的文档握手与 16 并发合同、Store-only 方法边界、本地代码更新后记录保留、事务式本地包安装、中断与路径穿越拒绝、记录 CAS/batch、逻辑文件、备份恢复、来源变化、Native HTTP 地址边界、开发端点认证/TLS、应用收藏和五 Tab、离线字体资源及旧 provider 的兼容 Runtime 注入。更旧 provider 使用的 `compat-javascript-interface` 已通过编译、契约和注入资源测试，但尚无对应真机证据。

## 边界与待人工验收

当前荣耀设备实际使用 `compat-web-message`：保留 WebMessage 的 Origin/frame 约束，但因缺少独立 Profile，网页 Cookie、localStorage、IndexedDB 等可能在实例间共享。若更旧设备进入 `compat-javascript-interface`，还不能证明调用来自主 frame。Haminn 管理的记录、文件和逐应用授权仍按 appId 分区，但不能据此宣称网页沙箱安全。只应安装用户信任的页面。

真实麦克风录音、系统语音识别、TTS、相机拍照、二维码取景、文件选择与分享、Launcher 固定入口、真实 Wi-Fi LAN、深浅色/字体放大/TalkBack 仍需用户按 `physical-device-checklist.md` 人工验收。API 29 实机和另一家国产 OEM 仍未形成当前版本证据；Android 17/API 37 的 19/19 是前序版本证据，三级 Runtime 修改后尚未重跑，不能替代此次 Android 11 结果。
