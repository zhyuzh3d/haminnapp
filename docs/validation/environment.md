# Haminn v1 验证环境

记录日期：2026-09-11。本文只记录可复核的本地环境，不包含设备序列号、签名口令或部署 token。

| 项目 | 实际值 |
| --- | --- |
| 主机 | macOS arm64 |
| JDK | OpenJDK 17.0.14 |
| Android 构建 | AGP 9.1.1、Gradle 9.3.1、compileSdk/targetSdk 37、minSdk 29 |
| Node | Node 22.22.0、npm 10.9.4 |
| 目标旧系统验证 | 荣耀 CMA-AN00、Android 11 / API 30；华为 WebView 11.0.8.305；无 GMS |
| 旧模拟环境 | Android 12 / API 31 ARM64 AVD；WebView 91.0.4472.114 |
| 现代环境验证 | Android 17 / API 37 ARM64 AVD；WebView 145.0.7632.218 |

API 31 镜像的记录来自三级兼容实现之前：当时完成 APK 安装、冷启动、原生恢复页及持久化/安装事务/来源隔离/部署 TLS 测试。该结果只作历史基线，当前实现预期改走兼容 Runtime，尚待重跑。

API 37 镜像的现代 WebView 满足完整 Runtime feature，前序版本全量 instrumentation 为 19/19，并完成同签名升级与真实 Bridge 记录保留验证。当前 1.4.0 在荣耀 Android 11 实机完成 34 项 instrumentation：33 passed、1 个外部进程夹具 skipped、0 failure/error；厂商 WebView 缺少独立 Profile，因此该设备是兼容模式通过证据，不是完整隔离模式证据。相机、原始录音、语音引擎、Launcher、真实 Wi-Fi LAN 和系统授权对话框仍必须按实体手机清单人工验收。

逐环境机器可读快照位于 `generated/api31.json` 与 `generated/api37.json`。它们来自 `scripts/verify-device.sh`，故意不采集 serial。
