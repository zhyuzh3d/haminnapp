# HaminnApp v1 执行记录

记录日期：2026-09-11。本记录只把实际代码、命令退出状态和设备结果视为证据；“已实现”与“实体手机已验收”严格分开。

## 1.4.0 国内设备与网络基线修正

最低安装版本扩大为 Android 10 / API 29。页面 Runtime 从新 WebKit feature 硬门槛改为三级降级：完整隔离、WebMessage 兼容、传统 JavascriptInterface 兼容；旧 provider 优先直接运行，应用库与诊断公开隔离差异。Android 10 增加单次定位兼容实现；Android 10/11 的 on-device SpeechRecognizer 调用增加版本保护。普通语音识别与 TTS 按已安装服务报告真实能力，没有服务时明确返回不可用。新增独立的原始麦克风录音与逻辑文件播放能力：录音不依赖语音识别服务，受逐应用授权和系统 RECORD_AUDIO 双层约束，限制时长并在退出前台时释放；`runtime.capabilities` 分别报告麦克风录音与扬声器播放事实。

移除 Google Play Code Scanner、ML Kit 动态模块元数据和全部 GMS 运行时依赖。应用库扫码改为 CameraX 实时取景与 ZXing Core 本地解码，代码随 APK 打包，只接受 HTTP(S) URL 并返回表单确认。GitHub 改为海外可选来源；本地 ZIP/目录、通用 HTTPS 包和局域网智能体开发组成国内主链。荣耀 Android 11 + 华为 WebView 11.0.8.305 已完成兼容 Runtime 自动化与正式包冷启动；API 29、扫码权限拒绝、弱光/旋转/OEM 相机仍待人工或目标设备验证。

## 1.3.0 增量更新：五栏导航、收藏、扫码与支持页

应用库主导航收敛为收藏、全部、开发、设置、支持五个等宽 Tab。收藏作为实例字段持久化并从 Registry v3 迁移到 v4；收藏与全部共享筛选、添加和应用卡片，入口只影响过滤条件和新增默认收藏值。开发模式改为独立页面开关，并以 Native 服务状态驱动底栏绿色状态点。设置集中外观、恢复、图标、诊断、许可、版本、作者与仓库信息。支持入口先做网络预检，成功后使用无 Bridge 的独立 Web Profile，失败返回本地重试页。

1.3.0 当时的二维码入口使用 Google Play Code Scanner；该实现已被 1.4.0 的 CameraX + APK 内置 ZXing 完全替代，不再是当前技术事实。界面统一为黑白中性底色、彩色功能提示与克制毛玻璃材质，底栏通过五列网格和固定图标容器保证中心线一致。历史证据见 [1.3.0 更新报告](../docs/validation/v1.3.0-navigation-favorites-report.md)，当前验收以 1.4.0 报告为准。

## 1.1.0 增量更新：界面、离线图标与原生页面原则

已完成纯原生 Store 重构、黑白主题与彩色功能图标、Font Awesome Free 7.3.1 全集离线资源和调用接口、图标检索/复制、管理与导入流程优化、名称优先级修正，并同步根指导、设计、计划和作者指南。没有引入前端框架、编译器或打包器；第三方最终静态产物按普通页面处理。

2026-09-11：4/4 Web 测试、1/1 JVM 测试、debug/release lint 0 error、Samsung Android 16 真机 24/24 instrumentation 通过；1.1.0/code 3 正式 APK 同签名覆盖安装成功，保留首装时间，冷启动正常。测试与安装详情见 [1.1.0 更新报告](../docs/validation/v1.1.0-ui-icons-report.md)。用户自行进行视觉验收，此处不标记视觉或全部商用场景验收完成。

## 1.0.0 原始阶段记录

| 阶段 | 工程状态 | 已有证据与剩余门禁 |
| --- | --- | --- |
| S0 工具链与合同 | 已完成 | JDK 17.0.14、Gradle 9.3.1、AGP 9.1.1、API/Build Tools 37、Node 22.22.0；Wrapper 摘要、依赖锁与校验元数据、schema/SDK/构建脚本均已固化 |
| S1 Runtime 与在线主链 | Android 11 兼容模式通过 | 现代 provider 使用 exact-origin WebMessage、document handshake 与独立 Profile；荣耀旧 provider 通过 HTML 注入进入 `compat-web-message`，Store/Bridge 主链通过；更旧 JavascriptInterface 模式仍缺对应实体机证据 |
| S2 本地版本与数据 | 自动化完成 | ZIP/目录快照、Operation/事务、release lease、CAS/batch、逻辑文件、故障恢复完成；安装穿越、失败传输、提交恢复、代码更新后数据保持等测试通过 |
| S3 授权与原生能力 | 实现完成，待实机系统交互 | 双层授权与系统观测、TTS/语音/定位/拍照/分享/剪贴板/震动/Native HTTP 完成；SSRF、地址类别授权、IPv4-mapped IPv6 等边界已自动化，实际麦克风、定位、相机和厂商服务仍需实体手机 |
| S4 开发部署 | 自动化完成，待真实 Wi-Fi | 单目标 ADB、LAN TLS、短期 token、SPKI pin、CAS/幂等/摘要、限流、后台与闲置停服完成；loopback 更新及 AndroidKeyStore TLS 测试通过，真实 Wi-Fi 与错误 pin 的端到端客户端体验待实机 |
| S5 来源与备份 | 自动化完成 | HTTPS/GitHub 来源、代码回退、逻辑备份摘要、新实例恢复和覆盖数据恢复完成；导出持有代码 lease 并停止同实例开发会话，备份恢复代码/记录/文件 ID 测试通过 |
| S6 产品整合 | 自动化完成，待实机体验验收 | 应用库、搜索、详情、来源、版本、授权、备份、开发入口、诊断、许可页、统一图标和语音笔记示例完成；深浅色、TalkBack、输入法、Launcher/OEM 行为保留给实体手机清单 |
| S7 签名交付 | Android 11 已覆盖安装，待人工能力验收 | 仓库外 RSA-4096 正式密钥；1.4.0/code 6 在荣耀 Android 11 同签名覆盖安装成功并保留 firstInstallTime；构建 APK 与设备 base.apk SHA-256 完全一致 |

## 已执行的发布门禁

- `scripts/doctor.sh` 通过；Node 安装审计为 0 vulnerability。
- `npm run check`、`npm test` 通过；8/8 Web/API 合同测试通过。
- `testDebugUnitTest` 通过 1/1；debug/release lint 均为 0 error。debug 29 个、release 31 个 warning 为版本提示和风格建议等非阻断项。
- 荣耀 CMA-AN00、Android 11/API 30、华为 WebView 11.0.8.305：34 项 instrumentation 中 33 passed、1 个外部客户端进程夹具 skipped、0 failure/error。Store 实际显示 `compat-web-message`，说明安全消息通道可用但独立 Profile 不可用。
- 1.4.0/code 6 正式 APK v3 签名校验、minSdk 29/targetSdk 37 校验和冷启动通过；设备已安装 base.apk 与构建产物 SHA-256 均为 `283fbf5d823bd054bc62543beb3c03441efa7ad661003204c81f41bcf558a2e7`，firstInstallTime 保持 2026-09-11 18:16:28。
- Android 17 / API 37 ARM64 AVD 上最终 instrumentation 为 19/19，通过 Store/Bridge、安装事务、数据、文件、备份、来源身份、部署认证/TLS、本地网关和 Native HTTP 负面测试。
- Android 12 / API 31 ARM64 AVD 的旧记录来自三级兼容实现之前；当前预期进入兼容模式，尚未重跑，不能继续把当时的恢复页行为当作当前结论。
- 0.9.0 release 测试包实际加载在线夹具并经 `window.haminn.data.put` 写入 `created-by-0.9.0`；`adb install -r` 升级到 1.0.0 后记录值、revision、实例 ID 与 dataGeneration 均未变化，release 冷启动无应用 FATAL logcat。

## 当前结论

1.4.0 正式包已安装并启动在目标荣耀 Android 11 实机，旧 WebView 的页面阻断、Font Awesome `:is()` 选择器和 Flex gap 均已提供兼容降级。它仍不能被科学地标记为“商用环境验收完成”：二维码实扫、相机、麦克风、定位、系统分享、Launcher、真实 Wi-Fi LAN、TalkBack 和至少另一家 OEM 必须按清单走完。实体手机若出现崩溃、数据丢失、越权或主链阻断，应重新打开相应阶段，而不是忽略失败直接发布。
