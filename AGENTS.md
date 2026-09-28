# HaminnApp 开发总则

本文件约束 HaminnApp 的设计、开发、测试、发布和文档维护。开始任务时，先用一句话明确本轮对象、动作与验收点；只处理这个边界。必须独立判断用户设想的真实性、正确性和可行性，发现问题时直接说明并给出明确方案，不能机械附和，也不能为了形式严谨而扩张任务。

## 工作与仓库边界

- 默认流程是“理解边界 → 最少必要分析 → 修改目标 → 直接相关的最小检查”。只读取本轮必需文件；保留用户已有改动，不清理、覆盖或顺手修改无关内容。
- 默认按当前最优方案彻底改造并维持唯一主路径，禁止主动保留历史字段、旧合同、兼容分支、双轨逻辑或无实际需求的迁移层，不能让历史错误和垃圾继续累积。只有用户明确说明存在必须保留的真实外部数据、已发布客户端或迁移约束时，才设计范围明确、可删除的一次性迁移；测试设备和开发数据默认可以直接清理或重建。
- 只有用户明确要求部署，或本轮交付本身就是指定环境中的运行产物时，才安装 APK、发布网站、操作 Mutagen 或修改服务器状态。
- `haminnapp/` 与同级 `haminnweb/` 是两个独立仓库。HaminnApp 唯一规范远程是 `git@github.com:zhyuzh3d/haminnapp.git`，HaminnWeb 唯一规范远程是 `git@github.com:zhyuzh3d/haminnweb.git`。共同父目录不得初始化 Git，两个仓库不得合并、嵌套、改作 subtree 或 submodule。
- 跨仓任务必须分别检查、暂存、提交和推送。每次提交或推送 HaminnApp 前，执行 `git rev-parse --show-toplevel` 与 `git remote get-url origin`，确认顶层目录以 `/haminnapp` 结尾且远程精确匹配；不匹配立即停止。修改远程、迁移仓库和强制推送必须另有明确授权。
- 进入 HaminnWeb 工作前先读取其 `AGENTS.md`。HaminnWeb 的修改、验证和发布遵循其仓库规则，不能借 HaminnApp 任务自动获得授权。
- 修改归属、跨仓顺序与缺陷处理统一遵循上级 `../AGENTS.md`，本文件不复制第二套规则。

## 本仓库归属

- 本仓库只处理上级规则判定为 HaminnApp 的部分。HaminnUI 和具体 happ 的语义源码不得因为最终运行在 APK 中而移入本仓库；需要内置 HaminnUI 时只接收同步工具生成的快照。

## 统一术语与领域不变量

- “Haminn 项目”指包含 `haminnapp`、`haminnweb` 及后续同项目目录的整体；HaminnApp、HaminnAPK、APK 只指本仓库的 Android 宿主。
- HaminnWeb、Web 指同级 `haminnweb/` 仓库。HaminnUI、UI、Shell、原生 UI、原生 happ、官方 app 均指 `haminnweb/public/shell/` 的官方管理 happ。它是默认加载且唯一具有宿主管理权限的 happ。
- happ、WebApp，以及上下文明确指页面应用时的 app，均指 HaminnApp 加载的页面应用；不得用 Web App 指整个 Haminn 项目或 APK。
- “本地 happ / 线上 happ”只描述来源，代码和接口使用 `source`；“本地运行 / 线上实时运行”只描述当前执行方式，使用 `runtimeMode`。两者正交，禁止用一个 `mode` 混合表达。
- 来源不决定能力：有 `activeReleaseId` 才能本地运行，有 `liveUrl` 才能线上实时运行，有 `updateUrl` 才能一键更新，有 `downloadUrl` 才能从原地址重装。URL 来源的 happ 下载到手机后仍是线上来源。
- `happId` 是发布包声明的稳定身份，`instanceId` 是 Native 为本机实例生成的唯一身份。显示名、URL、代码版本和运行方式变化不得隐式改变实例、数据或普通授权。
- Origin 不是可独立编辑的配置，必须按浏览器规则从完整 URL 推导为协议、主机和有效端口。路径不属于 Origin，不同子域名也不是同一 Origin。
- 本地代码以不可变 release 管理。更新先生成新 release，再原子切换指针；动态配置和用户数据不得写入 release。来源、版本、摘要和用户设置必须各自表达事实，禁止互相猜测。

## 产品与实现边界

- HaminnApp 是 happ 容器与 Native Bridge，负责实例、安装、版本、数据、授权和系统能力。它不为 happ 执行后台 JavaScript，不允许 happ 创建常驻服务或定时执行任意代码；happ 对自己的代码、服务器、账号、令牌和业务内容负责。
- 有 `liveUrl` 的本地 release 在该真实 URL 空间内运行：包内存在的 GET/HEAD 资源由本地只读 release 优先响应，缺失资源和动态请求交给网络。没有 `liveUrl` 的纯本地 happ 使用 Native 派生地址，只能访问包内资源和已授权 Bridge 能力。
- 页面能力必须通过公开 Bridge 合同提供，并按 `instanceId` 隔离 Haminn 数据、文件、grant、通知和运行会话。Android 系统权限与 Haminn 的逐 happ 授权是两层独立决定；宿主已获系统权限也不能替页面授权。
- 通知只提供系统通知转接、设备端单次/每日/每周/每月/每年计划，以及按规范轮询的服务器通知。通知需要逐 happ 用户授权；HaminnApp 不因通知能力承担 happ 服务端内容或凭据设计责任。
- WebView 站点数据遵循标准同源规则并使用共享资料空间；Haminn 自有记录继续按实例隔离。不同路径不能被描述成安全隔离。Bridge 优先使用可校验 Origin 和主 frame 的 WebMessage 能力，旧 provider 回退传统 `JavascriptInterface`，并如实显示其 iframe 风险。
- 安装、解压、URL、文件路径、大小、数量、摘要和事务边界由 Native 校验。安全机制保护 HaminnApp 自身及用户明确授予的数据，不为 happ 建立超出容器职责的内容审查或复杂信任体系。
- HaminnApp 管理或产生的图片、音视频、附件和其他文件默认以文件系统对象存储，数据库只保存受控对象 URL、逻辑 ID、摘要和必要元数据；禁止把文件内容、Base64 或 data URL 写入数据库。只有协议明确要求内联字节且数据不持久化时才能临时使用 Base64，并必须设置大小和生命周期边界。happ 自己写入的任意业务 JSON 不由宿主猜测或改写，但官方接口和示例不得引导其持久化内联文件。
- 面向智能体的开发信息按需分层：先返回带独立摘要的连接清单，再返回全部工具名和一句话用途，随后只读取选定工具的 schema/详细说明，最后按需读取开发指南或 Bridge 合同。各层应可独立缓存和失效；不得默认拼接全文、重复复述 schema，或把图片、Base64 和其他二进制塞入上下文。标准 `tools/list` 仅在客户端明确需要时读取，并按 schema 摘要缓存。

## 页面与 HaminnUI 规则

- happ 默认并首选纯原生 HTML、JavaScript、CSS，源文件就是可运行页面；不假定 React、Vue、Vite、Webpack、转译器、包管理器或编译步骤。Haminn 不为第三方框架建立特殊分支，但接受其已经生成的最终静态产物。
- 页面创作不要求 Node/npm。ZIP 只是归档传输格式，不是构建产物；官方示例、模板、教程与验收夹具遵守同一原则。公共图标通过 APK 内置 Font Awesome Free 使用，不依赖运行时 CDN。
- HaminnUI 的语义源码只在 `haminnweb/public/shell/` 编辑；`app/src/main/assets/store/` 是 APK 内置快照，不允许两边手工修改。需要更新快照时运行 `node tools/sync-shell-assets.mjs`，然后校验源文件、清单、版本和发布 ZIP 一致。
- Host API、Bridge、权限和 Android 生命周期属于 HaminnApp；HaminnUI 只消费公开合同。普通 happ 永远不能获得 HaminnUI 的宿主管理权限。
- 新增页面合同、示例或依赖前，确认仅含 `index.html`、`app.js`、`style.css` 的目录仍可直接使用。以 `docs/webapp-authoring.md`、`docs/haminnapp-product-technical-design.md`、`api/` 与 `sdk/` 的当前合同为准。
- 文档或旧计划与本总则、用户最新明确指示冲突时，以后两者为准；同步修正文档，不能在代码中继续扩大冲突语义。

## 国内设备与运行环境

- 核心链路以国内销售、无 GMS 的 Android 手机及普通国内网络为基线。安装、启动、应用库、本地页面、扫码、数据、备份和局域网开发不得依赖 Google Play 服务、Firebase、海外 CDN、海外账号、在线许可校验或运行时下载模块。
- 随 APK 打包的开源库必须锁定版本、校验构建输入并保留许可。GitHub 等海外来源只作为可选适配器，失败不得阻断本地目录/ZIP、局域网开发或通用 HTTP(S) 包。
- Android 版本不能替代 WebView 能力检测。必须在实际 provider 上检测所需 WebKit 能力并按受支持路径降级；旧 provider 的页面可用性优先，但不得伪装不存在的安全边界。

## 验证、设备与发布

- 验证范围严格等于修改范围。网页资源默认做语法和直接合同检查；局部 Kotlin 修改编译受影响变体并运行直接相关测试；文档修改不触发构建。同一批代码未变化时不得重复构建、安装或验收。
- 每轮开始只做一次必要预检：确认目标仓库、工作树和直接依赖工具；未涉及 Android 构建时不检查 JDK/SDK/签名，未涉及线上发布时不检查 Mutagen/服务器。已经通过且输入未变化的检查不得重跑。
- HaminnUI 小改的快路径是“在 HaminnWeb 修改 → 定向语法/DOM 检查 → 增量同步公开文件 → 原位刷新 Shell”，到此结束。禁止为了让 UI 生效而复制到 `assets/store`、升 APK 版本、跑 Gradle 或覆盖安装。
- Native 开发先用 `compileDebugKotlin` 或直接相关单测快速反馈；代码稳定后只执行一次正式构建。用户要求更新设备时使用 `scripts/update-device.sh` 完成“必要时构建 → `adb install -r` → 启动 → 核对版本/进程”，不得先后手工重复 `compileReleaseKotlin`、`assembleRelease` 和安装同一产物。
- 双仓变更按“Native 合同和实现 → HaminnWeb 消费端 → 各自定向检查 → 必要的线上 Shell 发布 → 稳定 Shell 快照同步 → 一次 APK 构建安装”推进。若 Shell 不需要公开发布或 APK 不需要内置更新，省略对应阶段。
- 构建或部署失败只重试失败阶段；源码未变化时复用 Gradle 增量结果和已生成 APK。正式构建前冻结本轮代码与快照，避免安装后再因小改重新构建。版本号只在正式交付节点确定一次，禁止用连续试编译消耗版本号。
- 除非用户明确要求全面检查，不执行全项目 lint、完整 instrumentation、设备矩阵、截图分析、视觉验收、反复 release 校验或长篇报告。已确认缺陷按上级规则修复；仅有风险或猜测时报告并询问，不擅自扩修。
- 用户要求真机更新时，生成必要 APK、使用覆盖安装，并确认版本和进程可启动；安装包生成不等于设备交付，`adb install` 成功也不等于功能或视觉验收。
- 正式包名固定为 `life.airen.haminn`。发布签名、口令、token、`local.properties` 和私有路径配置不得进入 Git、日志或文档。发布产物使用 `haminn-v<version>-release.apk`，版本化产物发布后不得覆盖。
- HaminnUI 上线属于 HaminnWeb 发布。发布前确认 Mutagen Alpha 精确指向 `haminnweb/public/`、同步模式为本机到服务器的单向副本且状态正常；上线后核对 `/shell/manifest.json` 的版本、包路径和 SHA-256，并确认公开 Shell 资源可访问。APK 内置 Shell 不得高于尚未发布且不可用的线上版本。
