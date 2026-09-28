# Haminn 自带网页引擎迁移计划

日期：2026-09-11。状态：后备架构验证门禁，未进入主线开发。当前版本先以 System WebView 三级降级覆盖 Android 10/API 29 以上国内无 GMS 设备；本计划仅在设备矩阵证明旧引擎的网页标准能力仍不足时启动。

## 决策

若启动迁移，候选仍是 GeckoView 单引擎，不把 System WebView、Google WebView、X5 或两套 Runtime 长期并列为正式产品路径。Android System WebView 不能由普通 APK 稳定内嵌或替换；X5 的闭源和运行时内核/服务依赖不符合离线、可审计、无云依赖基线；已停止维护的 Crosswalk 不具备可持续维护条件。GeckoView 是当前后备验证候选，不是已经决定引入的依赖。

启动前必须先拿到三级 System WebView 方案在目标设备上的失败证据，并量化具体缺失的网页能力。之后建立独立验证分支和可回滚门禁，只有以下阶段全部通过才切换主线；失败时保留系统 WebView 兼容版本和原生恢复能力。

## E0：依赖、许可与国内构建

锁定一个 GeckoView stable 精确版本、MPL-2.0 许可和 SHA-256，保存可由国内构建机访问的受控镜像；运行时不得下载内核。排除 `play-services-fido`，对 APK 的依赖图、DEX、Manifest 和网络行为做无 GMS 断言；WebAuthn/FIDO 暂列 `supported=false`，不能在缺类时崩溃。分别生成 arm64-v8a 和 armeabi-v7a 候选包，记录 APK、安装后占用、冷启动内存与首帧时间。默认直装包为 arm64，商店使用 ABI split，不把约 241 MB 的多 ABI AAR 体积直接等同于最终单 ABI APK。

门禁：国内网络可重复构建；APK 不含 GMS/Firebase/ML Kit；飞行模式可冷启动；许可和源码获取说明完整；目标荣耀机可安装。

## E1：统一 Runtime 抽象

建立 `PageRuntime` 边界，统一 open/reload/back/close、导航事件、崩溃、可见性、消息、资源请求和诊断。先以现有 WebView 适配器跑过原测试，再实现 GeckoView 适配器；业务层、Registry、Installer、PermissionBroker、数据和文件 Adapter 不依赖具体引擎。迁移完成后删除 WebView 适配器，不长期保留双实现。

门禁：同一套宿主状态机测试覆盖两种临时适配器；切换或崩溃不会留下旧 Session、录音、定位订阅或开发授权。

## E2：实例隔离与本地内容

每个 WebAppInstance 使用稳定且互不复用的 Gecko contextId；验证 Cookie、localStorage、IndexedDB、Cache、认证缓存和权限状态不跨实例。重建只读本地内容协议，保留唯一 Origin、目录穿越防护、MIME、CSP、范围请求、历史路由、`/__haminn/` 保留命名空间和不可变 release 绑定。不得退回 `file://`、全局 localhost 目录或共享 Cookie 容器。

门禁：相同站点的两个实例登录态隔离；跨 appId/dataGeneration/release 的正反向读取测试全通过；失败更新仍只运行完整旧版或完整新版。

## E3：Bridge 与页面兼容

用随 APK 签名的内置 WebExtension/content script 在 document_start 注入 SDK，通过 Gecko 原生消息端口传输。Native 继续绑定主 Frame、Origin、appId、Session、documentId、epoch 和回复通道；页面不能自选身份。保持 API v1.1、错误码、60 秒终态、16 并发、256 KiB 消息和导航取消语义，不引入 `addJavascriptInterface` 等兼容债务。

门禁：Store-only 方法不可被 WebApp 调用；iframe、跨站跳转、重定向、BFCache、崩溃恢复和乱序回复负面测试通过；现有原生 HTML 示例无需框架或构建即可运行。

## E4：网页功能与原生能力

适配导航、新窗口、下载、文件选择、键盘、深浅色、无障碍、媒体播放和页面生命周期。原始录音/播放、TTS、语音识别、定位、系统拍照、离线二维码、分享、剪贴板、震动和 Native HTTP 仍走统一 Haminn Adapter；Gecko 自身的网页摄像头/麦克风权限默认拒绝，避免形成第二套授权通道。

门禁：文件、数据库、相机、麦克风、扬声器主链在无 GMS/断外网条件下闭环；可选 TTS/识别服务按设备事实暴露；权限拒绝、系统撤销、后台和旋转都有确定终态。

## E5：设备与性能矩阵

至少验证 Android 10/API 29、Android 11/API 30、Android 13/API 33 和当前主流版本；必须包含荣耀/华为无 GMS 设备与另一家国产 OEM。记录单 ABI 包体、安装占用、首次/再次启动、空闲和运行内存、长页面滚动、视频/音频、进程回收与低存储行为。当前荣耀 CMA-AN00 是最低实际门禁之一，不因原厂 WebView 过旧而跳过。

门禁：所有目标设备进入相同应用库并完成本地导入、数据、录音/播放、离线扫码和局域网开发；没有引擎下载；无 FATAL、跨实例数据泄漏或主链阻断。

## E6：切换与交付

提供从 1.4.0 升级后的数据/代码/授权迁移。WebView Cookie 和 WebStorage 不承诺迁移到 Gecko；升级前必须明确提示登录态会重置，业务记录和逻辑文件必须保留。通过签名覆盖升级、备份恢复和回退演练后，删除主线 WebView 运行代码、过时依赖和双引擎开关，再生成正式 APK、SBOM、许可、校验和及设备报告。

只有 E0-E6 全部通过，才能把“Android 10 以上、国内无 GMS、无需系统 WebView 更新”写入正式兼容声明。任何单次构建、模拟器通过或页面能打开都不是完成证据。
