# HaminnApp v1 可执行开发计划

版本：1.2 · 日期：2026-09-11
对应设计：[产品与技术设计 v1.1.0](../docs/haminnapp-product-technical-design.md)
当前状态：计划已进入执行；逐阶段实证状态见 [v1 执行记录](haminnapp-v1-execution-log.md)，不能用本计划中的目标文字替代验证结果。

## 1. 执行目标与完成定义

持续执行的关键原则：WebApp、内置应用库、官方示例和主要验收夹具使用纯原生 HTML + JavaScript + CSS，源文件直接运行，不要求前端构建。不得增加 React/Vue 等框架或 Vite/Webpack 等工具的额外支持、脚手架、插件、专属 HMR/路由/构建适配。已生成的第三方静态产物仍可按通用规则添加；不因来源使用框架而拒绝。Node 仅服务宿主维护与检查，.d.ts 仅服务编辑器提示，均不是 WebApp 作者的前提。详见 [关键指导](../AGENTS.md) 与 [作者指南](../docs/webapp-authoring.md)。本原则覆盖后续全部阶段与历史计划中含糊的“Web 构建”措辞。

本计划交付一个可日常使用的 Android 页面应用工作台，release applicationId 为 `io.github.zhyuzh3d.haminn`。用户能添加在线地址或本地副本、固定桌面入口、使用受控原生能力、持续推送新版本、备份恢复业务数据，并在升级 Haminn 后继续使用原实例。

执行者连续完成环境、原型、功能、集成、真机验收、修复和打包。S0—S7 是内部质量门禁；通过即进入后续工作，不向用户反复请求阶段批准。新证据允许调整实现细节和依赖版本，但必须同步设计、契约和测试。不得悄悄恢复共享 Profile、开放任意 SQL、覆盖运行目录或以 debug APK 代替最终交付。

每项工作只有在“代码可审阅、必要测试通过、集成路径验证、证据写入记录”后才标记完成。不能把文档中的目标、计划中的命令或原型测试当成现有产品事实。

### 1.1 计划编写时的环境快照

| 项目 | 2026-09-10 的只读核验结果 | 开发动作 |
| --- | --- | --- |
| 工作区 | 仅有设计文档，未建立 Git 仓库，没有 Android 源码、Wrapper 或测试 | S0 建工程与版本管理；先保留现有文档 |
| JDK | 系统默认 java 找不到 runtime；`/opt/homebrew/opt/openjdk@17/bin/java` 与 javac 可运行，17.0.14 | 项目脚本显式选 JDK 17，不修改全局系统默认值 |
| Android 工具 | PATH 未找到 adb、sdkmanager、emulator、gradle；常规 SDK 路径未发现 SDK | 安装项目所需 command-line tools、platform、Build Tools 和测试工具 |
| Node | Node 22.22.0、npm 10.9.4 可用 | 用于宿主仓库契约检查与资源维护；不编译 Store/SDK，不要求页面作者安装 |
| 设备/AVD | 因无可用 adb/emulator 尚未检查，不能据此认定没有设备 | SDK 就绪后读取实际设备/AVD清单 |
| 签名与 APK | 尚未检查正式密钥，未构建任何 APK | S0 规划保管位置，S7 固定正式升级链 |

此表只保留计划形成时的历史输入，不代表当前环境；实现阶段已经安装的工具、AVD 和已运行验证以执行记录及 `docs/validation/` 为准。

### 1.2 范围基线与变化

所有 v1 功能以设计文档当前版本为准。相较旧稿，采用 Kotlin 协程、统一本地版本事务、结构化 Native 数据服务和 Runtime feature 分级；扫码已经作为应用库本地能力进入当前版本，回收站、任意 SQL、多 Bridge Origin、实例自定义图标和后台能力不进入首发验收。这些是已写入设计的收敛决策，不是临近交付时删功能。

四个必须完整通过的用户任务为：

| 编号 | 验收任务 | 关键结果 |
| --- | --- | --- |
| U1 | 添加 HTTP 局域网控制页并从桌面使用 | 地址/来源正确、权限和离线错误清晰、重启入口不丢、可编辑服务地址 |
| U2 | 将 AI 静态包安装为离线语音笔记 | 数据持久化、朗读/识别/附件可用，系统拒绝与服务缺失有明确结果 |
| U3 | 同一实例连续两次 curl 部署并预览 | release 变化，appId/图标/grants/数据不变；坏包不影响旧版 |
| U4 | 导出→恢复，以及 Haminn APK 覆盖升级 | Haminn 数据与代码可验证，浏览登录态边界准确，升级身份与数据保留 |

U2 中离线保证仅覆盖自包含页面和持久数据；语音识别是否离线取决于实际引擎，验收不得以缺少离线引擎伪造识别成功。

## 2. 依赖与工作组织

```text
S0 环境与契约
 ↓
S1 架构验证、会话和在线主链
 ↓
S2 本地版本与数据 ─────→ S4 开发部署
 ↓                         ↓
S3 权限与原生能力 ─────→ S5 来源与备份恢复
             └─────────────┘
                      ↓
               S6 产品整合与回归
                      ↓
               S7 签名、升级与交付
```

S3、S4 在 S2 主合同稳定后可并行；S5 的来源适配器可提前开发，备份恢复依赖 S2 数据合同。S6 的用户体验和测试素材从 S1 起持续完善，不能留到全部功能写完才集成。每条分支的变更仍须在进入下一门禁前集成主工程。

职责可以由同一执行者承担，或按以下边界并行：Runtime/Bridge、安装与数据、Store/SDK/示例、能力 Adapter。共享 schema、模型和方法目录由集成负责人先冻结；不要让多个实现各自发明 appId、permission、operation 或 error 格式。

| 阶段 | 粗估有效工程日 | 主要不确定性 |
| --- | --- | --- |
| S0 | 1—2 | 工具下载、构建环境与设备就绪 |
| S1 | 3—5 | WebView provider 差异、文档导航握手、SW 策略 |
| S2 | 4—6 | 文件事务、掉电恢复、数据服务 |
| S3 | 3—5 | 系统服务、权限与 Activity Result 生命周期 |
| S4 | 2—4 | TLS 配对、HTTP 边界、前台服务时机 |
| S5 | 3—5 | GitHub 输入与备份恢复一致性 |
| S6 | 3—5 | UI 完整性、真机兼容与性能回归 |
| S7 | 1—2 | 签名、升级验证、交付整理 |

合计约 20—34 个有效工程日，是单执行者工作量区间而非日历期限或自动代理耗时承诺；可并行部分缩短墙钟时间，设备等待和外部阻塞另计。S1 后根据实证重估一次，后续只有范围或风险变化才调整。不要为追赶该估计放松质量门槛。

## 3. S0：工程环境与可执行合同

目标是得到可复现的空壳构建、明确的设备清单，以及各模块可以共同实现的接口。

| 工作包 | 实施内容 | 输出与验收 |
| --- | --- | --- |
| S0-01 | 复核目录，保留旧稿归档；建立 Git、忽略规则和初始目录 | 设计/计划可追踪；密钥、local.properties、SDK、临时导出不进 Git |
| S0-02 | 配置 JDK 17，安装 Android CLI/SDK，生成带分发摘要的 Wrapper | AGP 9.1.1 + Gradle 9.3.1 + compile/target 37 实际构建成功 |
| S0-03 | 创建单 Android 模块、debug 后缀、最小原生 Activity/恢复页 | debug 可安装；release 身份正确；没有混入假 Runtime 实现 |
| S0-04 | 锁定 AndroidX WebKit 1.17.0、Activity、协程、JSON、OkHttp 和离线 Web 资源 | 精确版本、锁文件、校验元数据、许可证清单；无动态版本或前端框架/打包器 |
| S0-05 | 建立 schema/API 合同、测试 fixtures 与基础脚本 | 公共/host/deploy 三种调用者权限分开；设计引用可解析 |
| S0-06 | 发现实际 adb 设备、AVD、CPU架构、WebView provider/features | `docs/validation/environment.md`，不在公共报告暴露设备序列号 |
| S0-07 | 设置 CI 本地等价命令和验证报告目录 | JVM/TS/lint 流程可跑；未配置外部 CI 账户也可本地执行 |

工具链按官方兼容表起步。AGP 9.1.1 支持 API 37、Gradle 9.3.1、JDK 17；采用 AGP 内置 Kotlin 时不重复应用不兼容的 Kotlin Android 插件。serialization 插件与 Kotlin 版本匹配；不以 suppressUnsupportedCompileSdk 掩盖未支持的组合。[AGP 官方兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、[Android 17 SDK 设置](https://developer.android.com/about/versions/17/setup-sdk)

S0 必须先输出这些契约，随后实现可以增补字段但不能各自分叉：

| 路径 | 合同内容 |
| --- | --- |
| `api/haminn.schema.json` | 包内/外部 haminn.json 的合法字段、冲突、路径与大小规则 |
| `api/rpc.schema.json` + `api/protocol-v1.md` | hello/challenge/ack、请求/响应/事件、取消与错误 |
| `api/capabilities.json` | descriptor 列表、角色、权限表达式、资源范围、生命周期和配额 |
| `api/deploy.openapi.yaml` | 窄部署路由、认证、摘要、幂等、expected release 和状态查询 |
| `api/backup.schema.json` | 可恢复数据、逻辑文件 ID、摘要、排除项和格式版本 |
| `sdk/src/` + `sdk/haminn-api.d.ts` | 原生 JS 页面接口及可选编辑器类型，直接校验与 Native 描述一致，无转译 |

OpenAPI 的 YAML 只作开发文档，不给 APK 引入 YAML runtime。普通包版本与协议版本分开。schema 是后续构建交付物，本次计划不伪造已经存在的文件。

S0 门禁：在干净工作目录执行受控构建，产生可安装 debug APK；JVM 与 Web 契约校验可运行；环境报告标明可用和缺失设备。设备暂缺时继续不依赖它的工作，并及早告知设备要求，不能等发布时才发现。

## 4. S1：架构验证、Runtime 与在线主链

本阶段的实验都要成为可重复运行的测试夹具，随后进入正式工程；避免原型验证一套实现、产品重新写另一套。

| 工作包 | 实施内容 | 必须通过的验证 |
| --- | --- | --- |
| S1-01 | Native 创建实例、Profile 和 RuntimeSession；注册库初版 | 同 URL 的 A/B 登录与数据不串；主图标/Shortcut 路由正确 |
| S1-02 | exact-origin Bridge、DocumentSession 与 challenge/ack | 未握手不可调用；直接 iframe、旧代理、错 epoch、跨 Origin 拒绝 |
| S1-03 | 顶层导航策略、前后台/旋转/renderer 处理 | SPA 不误取消；新文档失效旧任务；取消导航恢复当前文档 |
| S1-04 | 最小应用库、在线解析、添加/编辑、桌面图标与恢复页 | U1 的添加和启动路径贯通，拒绝固定图标不丢实例 |
| S1-05 | 本地网关及 SW 抑制技术切片 | HTML/ESM/WASM/URL Worker/Blob Worker 可加载；SW 各种注册失败 |
| S1-06 | Web 标准敏感入口统一拒绝；最小 LAN 系统授权 Broker 真实实现 | 空授权设备首次打开 LAN 可请求、拒绝与恢复；未知 Web 资源拒绝 |
| S1-07 | Profile 清理、三级兼容与不可用恢复 | loaded Profile 不被错误同步删除；缺少 Profile/Bridge feature 时自动降级并标识；provider 不可创建才进入恢复页 |
| S1-08 | 最小性能与生命周期基准 | 本地首屏/Bridge p50/p95、50 次切换资源趋势，记录设备条件 |

架构原型必须得到下列明确结论：

| 风险编号 | 实验 | 决策规则 |
| --- | --- | --- |
| R1 | Provider 的 MULTI_PROFILE、WebMessageListener、document-start 支持 | 缺失即拒绝 Runtime；原生恢复可用，不恢复 shared-fallback |
| R2 | 同 Origin 新文档、204/下载/取消、跳转、BFCache 恢复与乱序 hello | NavigationListener 或回调+握手须证明一致；不达标的 provider 不列入支持 |
| R3 | 强制跨 Origin Service-Worker-Allowed，classic/module、多 scope、嵌套目录 | 注册均失败且 URL Worker 不受损；若不成立，先修网关/支持矩阵，不上线伪保证 |
| R4 | 系统已经授予 microphone/location，另一 Web App 从 Web 标准入口请求 | 仍按 v1 策略明确拒绝，不能绕过主文档身份 |
| R5 | API 37 LAN：在线页面、Native 请求、入站部署 | 各路径分别验证允许/拒绝/撤销；WebView 继承 APK 权限的限制写入报告 |
| R6 | 删掉当前进程曾加载的 Profile，退出再启动清理 | 逻辑删除立即禁止访问；物理清理有可核对状态 |

R4 可在仅测试构建中声明并授予所需系统权限，验证平台入口拒绝路径；release 不因此预埋未实现 Adapter 的权限。S3 接入真实 Adapter 后，重新覆盖完整双层授权路径。S1 的最小 LAN Broker 必须是真实实现，不能用预授予权限或模拟同意代替 U1 首次使用。

S1 门禁：U1 在线主链在当前可测系统上通过；R1—R4/R6 在至少一个可用现代 provider 上有设备或模拟器实证；R5 如尚无 API 37 环境必须标记未完成并继续准备，进入依赖该保证的验收前补齐。核心 Bridge/隔离实验失败时暂停其上的功能堆叠，先修复基础，不以“以后再验证”进入签名交付。

## 5. S2：本地版本、受控数据与统一文件

| 工作包 | 实施内容 | 必须通过的验证 |
| --- | --- | --- |
| S2-01 | ZIP/SAF 导入、路径/大小/摘要/manifest 校验 | 无 manifest 可安装；原目录删除不影响副本；恶意与不完整包拒绝 |
| S2-02 | Operation、每实例写锁、CAS、staging/release 提交和启动恢复 | 各故障注入点重启后只有旧版或完整新版，没有半激活 |
| S2-03 | 会话 release 绑定、流租约、保留与垃圾清理 | 旧 HTML 与延迟 JS 在更新瞬间仍属于同 release |
| S2-04 | data 记录服务、revision、scan、CAS、batch | 冲突可见；batch 条件失败全回滚；进程重启后提交可读 |
| S2-05 | 逻辑文件、SAF、分块传输、被动媒体 URL | 文件归属校验；临时写不可见；越界句柄和 HTML/SVG 执行拒绝 |
| S2-06 | 应用库版本/存储/错误恢复与删除任务 | 可见版本来自唯一 active 指针；删除后所有旧入口拒绝 |
| S2-07 | 自包含笔记示例，使用 data/files SDK | 离线、冷启动、代码更新后笔记与附件保持 |

故障注入必须覆盖：传输未完成、验证后、rename 前后、DB 提交前后、已提交但响应前、reload 前后、配额刚耗尽。测试用内部注入点只存在 debug/test 构建；release 不包含可从 Web 触发的故障接口。

S2 门禁：本地导入→桌面启动→写数据→重启→更新→读回数据通过；失败更新不改活动指针；跨 release 混读为零；路径/数据隔离负面测试通过。后续来源和部署只能复用本阶段提交器，不能建立新的 active 目录写入路径。

## 6. S3：统一授权与原生能力

| 工作包 | 实施内容 | 必须通过的验证 |
| --- | --- | --- |
| S3-01 | PermissionBroker、系统观测、一次/持久/拒绝授权与设置 UI | A 获系统权限后 B 仍需自己的敏感 grant；系统撤销正确传播 |
| S3-02 | 条件权限与 Activity Result 协调 | coarse 可用、fine 拒绝；旋转/切应用期间结果不授予新页面 |
| S3-03 | 原始录音/播放、TTS、语音识别及声音/服务探测 | 麦克风不依赖识别服务；单活跃、时限、后台释放、文件持久化、无服务、引擎断连、部分/最终结果和取消均有终态 |
| S3-04 | 前台定位、系统拍照、分享、剪贴板、震动 | 后台采集停止；拍照/选择可取消；分享文件离开 Haminn 后可读 |
| S3-05 | Native HTTP、目标授权、DNS/redirect/凭据边界 | 无默认目标；拒绝 SSRF/重绑定；跨站不泄漏凭据；非幂等请求不自动重试 |
| S3-06 | CapabilityDescriptor → SDK 类型/清单与一致性测试 | 新 Adapter 不能漏掉权限、超时、取消、限额或错误合同 |
| S3-07 | 语音笔记与 LAN 控制参考应用接入真实 Adapter | U2 主链用真实实现完成，无模拟成功结果混入用户路径 |

首次 LAN 在线入口在 loadUrl 前申请系统权限；拒绝不删除实例。WebView 普通网络受 APK 系统 LAN grant 影响，测试与文案要承认无法实现逐实例全网络防火墙。对 HTTP 页面授权的额外确认在应用详情完成，不能由页面自画对话框代替。

S3 门禁：U2 的采集、输出、数据与附件闭环完成；权限拒绝/撤销/切换/旋转测试通过；所有 v1 Adapter 可探测、可取消且有结构化失败。系统不支持的功能应报告 unsupported，而非伪装成功或“开发中”。

## 7. S4：持续开发部署

| 工作包 | 实施内容 | 必须通过的验证 |
| --- | --- | --- |
| S4-01 | DevTransport 接口与 NanoHTTPD 核心锁定、窄路由、资源限制 | 请求头/body 大小、超时、连接数、Content-Length/Transfer-Encoding 冲突在读包前处理 |
| S4-02 | prepare/export/activate 配对流程、目标 token 与前台生命周期 | 导出配置不使待激活 token 失效；切 Store→目标页保持服务 |
| S4-03 | loopback + adb forward，上传调用统一安装服务 | 两次 push 后身份/数据保持，expected release 与幂等生效 |
| S4-04 | LAN TLS、带外 SPKI pin、系统 LAN 权限与撤销 | 错 pin 无应用请求送达；拒绝权限、切网、后台、锁屏、超时立即停止服务 |
| S4-05 | pack/deploy/status 脚本与诊断 | 计算摘要，配置文件受限，token 不出现在仓库/日志；提交和 reload 结果分开 |
| S4-06 | 自动重开与在途原生任务取消 | 旧页面任务不写新 generation 或回调新文档；坏包保持现有页面 |

DevTransport 原型若无法在认证前完成限流/长度检查，先修复或替换隔离的 HTTP 传输层，保持既定路由与安装服务不变，不把未验证的服务直接暴露到 LAN。是否引入额外依赖由此处可重复的性能、体积和边界测试决定，写入 ADR，不凭“零依赖”偏好手写通用协议栈。

LAN 配套脚本只向指定端点发送请求，不跟随重定向；使用带外获得的 SPKI pin，明确拒绝不支持 pinnedpubkey 的 curl 构建。短期自签名端点的认证与系统 CA 验证分开，禁止单独使用 -k。读取配置、计算摘要和上传均不把 token 写入命令日志。[curl 公钥固定说明](https://curl.se/libcurl/c/CURLOPT_PINNEDPUBLICKEY.html)

S4 门禁：U3 通过 ADB 与 LAN 两条路径；保持目标页面前台连续部署，旋转不误关、真正后台会关；断线查询返回原 operation。APK 无后台常驻服务，开发接口不能安装其他实例或调用 host 管理 API。

## 8. S5：来源适配与备份恢复

| 工作包 | 实施内容 | 必须通过的验证 |
| --- | --- | --- |
| S5-01 | HTTPS 描述/包下载与更新 | 摘要不符、版本冲突、channel 变更、过大包拒绝；旧实例不受损 |
| S5-02 | GitHub repo/ref/path 解析、锁 commit、archive 下载 | 带斜杠 ref 不误解析；分支移动不混版本；限额/LFS/submodule/入口缺失有明确错误 |
| S5-03 | 来源覆盖显示、手工回退、上游版本比较 | curl 不改变原上游；回退不清数据，不自动降低上游最高 code |
| S5-04 | 逻辑数据与文件的一致导出、独立备份预算 | 大于代码包上限的合法备份可验证；导出中断不产生可导入半包 |
| S5-05 | 新实例恢复、覆盖数据/完整恢复、维护屏障 | 保留 logicalFileId 与记录附件引用；旧写操作完成后才切 generation |
| S5-06 | 信任状态与 Web Profile 处理 | 新实例重新授权；完整覆盖提升 trustRevision、换 WebProfile、撤销开发连接 |
| S5-07 | 删除任务、恢复 UI、Registry 迁移与 Auto Backup 配置 | 失败不清空注册库；物理 Profile 清理可追踪，普通入口不能访问已删除身份 |

S5 门禁：U4 的备份恢复部分通过；逐字段比较记录与附件摘要，恢复后真的能打开附件。普通网页浏览存储、Cookie 和系统权限不进入备份，用户文案、示例与报告一致。覆盖恢复的数据原子性通过故障注入，不以文件复制完成冒充提交完成。

## 9. S6：产品整合、设备矩阵与回归

所有参考应用均通过同一正式 SDK，演示与测试不保留绕过授权、直接访问内部路径或独立更新脚本。应用库、Native 和 SDK 所显示的操作状态来自同一 Operation 与实例数据，取消/失败/重试不能各有一套解释。

| 工作包 | 实施内容 | 必须通过的验证 |
| --- | --- | --- |
| S6-01 | 应用库 UI、详情、搜索、进度、空态、错误、设置和诊断导出 | 所有 v1 功能有可到达入口；操作完成后可继续使用 |
| S6-02 | 系统分享接收 URL/ZIP、统一品牌图标与导航兼容 | 不可信输入不执行；输入失败可重选，Launcher 拒绝固定入口不影响安装 |
| S6-03 | 键盘、系统栏、旋转、深浅色、字体放大与 TalkBack | 无遮挡关键操作；授权来源清晰，宿主可访问性通过 |
| S6-04 | 固定设备矩阵执行 U1—U4 与负面测试 | 每份证据记录实际 APK、provider、feature 与预期/结果 |
| S6-05 | 性能、存储、内存/句柄、退出资源回收 | 相同条件基准回退受控；多次切换不持续泄漏 |
| S6-06 | API 文档、AI 页面模板、打包/部署/恢复说明与限制 | 按 README 从新机器/新数据状态能完成参考流程 |
| S6-07 | 全量集成回归与缺陷清零 | 阻断主链、越权、数据损坏、崩溃类问题全部修复 |

### 9.1 必需兼容矩阵

| 环境 | 必须覆盖 | 证据要求 |
| --- | --- | --- |
| API 29（Android 10） | 最低安装版本、定位兼容分支、原生恢复和 Runtime 核心链 | ARM64 模拟器或实体设备；验证 API 30 定位与 API 31 语音调用不会在旧系统执行 |
| 现代 Android + 主流 WebView | 全部自动化和 U1—U4 | 至少一套完整通过的 device/emulator 记录 |
| API 37 | LAN 三路径的允许/拒绝/撤销、系统行为变化 | 模拟器或真机可覆盖；厂商网络行为另由实机检查 |
| 缺少新 WebKit feature 的 provider | 兼容消息或传统桥接模式；页面主链、风险提示与诊断一致 | 真实旧 provider 必测；替身不能算真实兼容设备 |
| 国内无 GMS 实体手机 | 本地扫码、应用库、本地包、系统能力、桌面入口、Wi-Fi LAN、升级 | 至少覆盖华为/荣耀类 provider 与另一家国产主流 provider；扫码不得下载模块或访问外网 |
| 主用实体手机 | 桌面固定/更新/禁用、拍照、识别、系统授权、分享、Wi-Fi LAN、升级 | 至少一台完整 Runtime 可用实体手机；记录型号与 provider，不以仅模拟器通过替代 |

最低 API 的 Runtime 支持须有实际 provider 证据。API 29 或更高设备缺少新 feature 时，Haminn 必须进入对应兼容模式并继续运行应用库、本地页面和公开 API；不得把兼容通过写成“完整隔离”。测试通过的 WebView 版本、feature、runtimeMode 与设备组合形成支持矩阵，未验证组合明确列出。核心验收在关闭外网、设备无 GMS 的条件下重复本地导入、扫码和局域网开发；GitHub、海外站点或在线语音不可用不得阻断核心功能。

真机不足时继续完成源码、构建和自动测试，尽早请求可用设备；最终门禁保留未完成，不以推测签发“产品级验证通过”。不同厂商/第二类 provider 可扩展验证范围，首发先保证上述可执行矩阵和真实主用手机链路，避免空泛要求未知的“两类 provider”。

### 9.2 固定测试夹具与证据

| 用例组 | 夹具/方法 | 断言 |
| --- | --- | --- |
| T01 身份/导航 | 同 Origin A/B、直接 iframe、跨站、旧 hello/epoch/replyProxy、204/取消 | 无错配授权与响应；同源父子脚本共享信任事实如实记录 |
| T02 本地内容 | ESM/WASM、history/hash、404、Range、Worker、SW、外域资源 | MIME/状态正确，无网络兜底或混 release |
| T03 版本事务 | 各提交故障点、并发 base、相同幂等键/不同摘要 | 旧版或完整新版；无部分提交、重复实例或静默覆盖 |
| T04 数据/文件 | CAS/删除再创建、原子 batch、配额、越界 handle、分享租约 | 无 ABA/跨实例访问；大文件不耗尽 Bridge；分享目标可读 |
| T05 权限/系统 | 已授予系统后新 app 请求、精度、永久拒绝、旋转/后台 | 双层语义成立，资源停止，不把结果发给新文档 |
| T06 Native 网络 | 本地受控服务、DNS 替身、redirect、双栈/映射地址、POST 断线 | 目标检查和实际连接一致，凭据不跨站，无隐式非幂等重试 |
| T07 Deploy | 配对导出、TLS 错 pin、超长头/body、慢连接、两次上传 | 未认证不消费无界资源，后台关服，结果可查询 |
| T08 备份恢复 | 笔记+附件、较大合法包、破坏摘要、覆盖期间旧写 | 数据/附件摘要一致，逻辑 ID 保持，新信任不继承旧授权 |
| T09 升级/清理 | 已签名旧版本→新版本、loaded Profile 删除、损坏 Registry | 身份和数据保持；清理可恢复，不删库“修复” |
| T10 日常体验 | U1—U4、TalkBack、字体放大、键盘、50 次切换 | 主链和宿主交互可用，性能/资源趋势有记录 |

JVM 测试负责纯解析、状态机、路径/限制、权限决策和故障模型；Android instrumentation 测试真实 WebView、SQLite、生命周期与系统入口；原生 JavaScript 测试 SDK 编解码、超时、取消和 UI 状态，不引入 TypeScript 编译步骤。MockWebServer/DNS 测试替身只作为测试依赖。真实授权 UI、Launcher、相机和语音还需实体机验证。

上述测试已经在实现阶段落成；每次发布以执行记录和交付验证报告中的实际次数、环境与退出状态为准，计划表本身不构成通过证据。

## 10. S7：正式签名、升级与交付

| 工作包 | 实施内容 | 必须通过的验证 |
| --- | --- | --- |
| S7-01 | 核验现有密钥；若无，仓库外生成项目专用长期签名密钥 | 密钥备份和公开证书指纹可核对；口令不进入 Git/日志/产物包 |
| S7-02 | release 构建、R8/lint、manifest/组件/调试开关检查 | package/SDK/version 正确，WebView debugging 关闭，组件导出范围正确 |
| S7-03 | 同签名低 versionCode 测试 APK→最终 APK 的覆盖升级 | 不卸载清数据，实例/数据/授权/快捷方式保持；升级后主链通过 |
| S7-04 | 对最终 APK 重跑关键实际设备 smoke，不只测试 debug | U1—U4 的关键点与安全边界在交付二进制上成立 |
| S7-05 | 整理交付目录、摘要、SBOM、测试报告与说明 | 产物和源码 commit 一一对应；外部可复核签名、构建与限制 |

debug 使用 `.debug` 包名，不能拿 debug→release 安装当覆盖升级试验。升级夹具必须同 release applicationId、同正式证书、较低 versionCode；人工创建的测试旧版本应明确标注，不伪称存在已发布历史版本。

签名固定的是后续升级链；在第一次外部分发前完成备份并核对证书。Apache-2.0 作为源码许可证建议，必须核对依赖许可证并在公开发布前冻结。构建交付 APK 不等于授权自动公开发布、上架或发送给第三方；外部发布依用户明确范围执行。

### 10.1 最终交付目录合同

```text
artifacts/v1.0.0/
  haminn-v1.0.0-release.apk
  haminn-v1.0.0-debug.apk
  SHA256SUMS
  release-manifest.json
  validation-report.md
  known-limitations.md
  dependency-licenses.txt
  sbom.json
```

release-manifest 记录 source commit、versionName/code、applicationId、SDK、构建工具链、证书 SHA-256 和产物摘要。项目同时交付源码/Wrapper/依赖锁、README、Store、SDK、schemas、可运行示例、打包/部署/验证脚本；不把签名私钥或开发 token 放在 artifacts。

可复现是“相同锁定输入可重复构建和验证”的工程目标；若未验证逐字节一致，不声称 APK bit-for-bit reproducible。最终目录只存本次交付对应版本，测试日志链接到对应 commit 和 APK 摘要。

### 10.2 计划约定的验证命令

以下是项目当前冻结的本地等价验证入口；每次发布仍须以执行记录中的实际退出状态和产物摘要为证据：

```sh
./scripts/doctor.sh
npm ci
npm run check
npm run test
npm run build
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
./scripts/verify-device.sh --matrix docs/validation/device-matrix.json
./scripts/verify-release.sh --version 1.0.0
```

release 脚本读取仓库外签名配置，调用 assembleRelease、apksigner verify 和 APK 元数据检查，再输出摘要与清单。CI 如无正式密钥，运行编译/lint/测试并标注无签名产物；不能把正式签名跳过后仍输出同名“release 已验收”。

## 11. 需求追踪、风险处理与执行记录

| 产品合同 | 实现工作包 | 验证 |
| --- | --- | --- |
| 在线/局域网工具与桌面身份 | S1-01/04、S3-01/02、S6-02 | U1、T01/T05/T10 |
| 自包含本地工具与稳定数据 | S2 全部、S3-03/04/07 | U2、T02/T03/T04 |
| 统一安全调用与可扩展能力 | S1-02/03/06、S3-01/02/05/06 | T01/T05/T06 |
| 快速部署且保持实例 | S4 全部、S2-02/03 | U3、T03/T07 |
| GitHub/包更新与代码回退 | S5-01/02/03 | T02/T03 |
| 备份、附件、恢复与删除 | S5-04/05/06/07 | U4、T04/T08/T09 |
| 正式 APK 与升级连续性 | S6 全部、S7 全部 | U1—U4、T09/T10 |

技术风险通过原型和测试解决，不默认交给用户做架构选择。新增依赖、调整目录、补错误码、修生命周期等是执行者职责。需要用户提供的条件主要是无法替代的实体设备接入、已有正式签名材料，或新增的外部分发目标；常规阶段不暂停。

遇到问题按影响处理：隔离模式中的 Bridge/权限越权、Haminn 管理数据跨 appId 访问、事务损坏属于阻断缺陷；兼容模式已知的网页 Profile/iframe 弱隔离须明确标识而非伪装修复；缺某系统引擎应实现准确降级。第三方网站不兼容应给出复现与适配说明，仅削弱确实阻断旧 provider 的隔离机制，不顺带开放与兼容无关的 file URL、TLS 错误或任意混合内容。部分设备待验证允许继续独立开发，但阻止最终相应支持声明。

正式执行时建立 `plans/haminnapp-v1-execution-log.md`，每个工作包记录状态、代码 commit、测试命令、报告路径、失败原因及下一动作；状态只用未开始/进行中/待外部条件/已完成。对失败结论与未运行测试必须保留，不为好看的完成率改写历史。

最终完成仍要求 U1—U4、T01—T10、设备矩阵、release 签名升级和交付目录均有证据。当前源码、自动化、签名升级与交付打包已经执行；无法由模拟器替代的实体手机项目保持为用户验收门禁，详见执行记录和实体手机清单，不能因 APK 已生成而伪称商用环境已经通过。
