# HaminnApp 产品与技术设计

版本：1.7.0 · happ 来源与运行方式解耦版
日期：2026-09-12
状态：v1 实施合同；实现与验收状态以执行记录和发布报告为准
配套计划：[HaminnApp v1 开发计划](../plans/haminnapp-v1-development-plan.md)

## 1. 产品定位与决策依据

HaminnApp 是个人 Android 页面应用工作台：把用户自己制作、维护或信任的网页，变成能从桌面打开、长期保存数据、使用手机能力并持续更新的 happ。一个 APK 承载多个 happ，业务页面更新不必重新编译或安装 APK；“Haminn 项目”则同时包括 HaminnApp、HaminnWeb 与后续项目目录。

主要服务两类人：用 AI 或前端工具制作个人应用的创作者，以及使用自托管服务、家庭设备控制页和这些个人应用的使用者。首要价值是让一个页面工具从“能打开”变成“能长期使用和维护”；任意网站兼容是入口能力，不承诺所有网站都能获得与原生应用相同的行为。

本文替代 v0.4.1 的实施方案，保留用户确认的产品方向，重新选择技术路线。旧稿归档在 [v0.4.1 历史参考](archive/haminnapp-product-technical-design-v0.4.1.md)。旧稿与本文冲突时以本文为准；两个文档都不是已实现证据。

### 1.1 已确认且继续保留的约束

| 项目 | 决策 |
| --- | --- |
| 术语 | HaminnApp / HaminnAPK / APK 指 Android 宿主；HaminnWeb / Web 指官网与 HaminnUI；HaminnUI / UI / Shell / 原生 happ / 官方 app 指具备宿主管理权限的官方 happ；happ / WebApp / 上下文明确的 app 指宿主加载的页面应用 |
| 页面开发原则 | 纯原生 HTML + JavaScript + CSS 直接编写，无框架、无构建前置依赖；应用库、示例、教程与验收使用相同模式 |
| 第三方工具边界 | 不为 React/Vue 等框架或 Vite/Webpack 等工具增加专属支持；已生成的静态产物可按普通页面添加，不检测或拒绝其框架来源 |
| 公共图标 | 完整 Font Awesome Free 7.3.1 离线字体/CSS；不包含 Pro；页面直接使用 HTML 图标类名，无需 npm/CDN |
| Android 身份 | release 的 applicationId 与 namespace 均为 `life.airen.haminn` |
| 主导航 | 收藏、全部、开发、设置、支持五个等宽入口；收藏是应用属性，不复制实例 |
| 桌面入口 | Haminn 主图标进入内置应用库；Web App 快捷方式携带稳定 appId |
| 扫码添加 | 收藏与全部均使用 APK 内置离线解码器识别 URL；不依赖 Google Play 服务或运行时下载；识别后只预填表单，仍需用户确认 |
| 授权 | 系统对 Haminn 的授权，与 Haminn 对每个 Web App 的授权共同决定可用能力 |
| 本地目录 | 导入到 Haminn 管理的快照，不依赖原目录继续存在；支持外部 curl 等工具更新 |
| 交付方式 | 开发阶段是内部连续执行顺序，最终交付产品级签名 APK、源码与验证证据 |

Android 最低安装基线为 Android 10 / API 29，compileSdk / targetSdk 为 37。“APK 可安装”“页面能运行”“页面具备完整隔离”是三个不同等级。Android 10/11 的系统差异与 WebView feature 使用显式分支处理；只要 provider 可创建，页面可用性优先，不再因缺少新 WebKit feature 整体阻断，但必须如实暴露兼容等级。

国内设备和网络是正式产品基线。安装、启动、应用库、本地页面、二维码、数据、备份和局域网开发不依赖 Google Play 服务、Firebase、海外 CDN、海外账号、在线许可、远程配置或运行时下载。Font Awesome、二维码解码器等依赖随 APK 固化并离线运行。官方联网功能必须使用国内可访问端点并有本地失败态；GitHub 等海外来源只作可选适配器，不能成为创建、更新或开发页面的唯一方式。用户自己配置的在线页面与 API 是否可达由用户选择，不算 Haminn 的隐式运行依赖。

### 1.2 首要使用场景

| 场景 | 用户实际要完成的事 | 产品必须保证的结果 |
| --- | --- | --- |
| AI 生成个人工具 | 把记事、清单、朗读、语音录入等静态产物放进手机 | 不要求 PWA 或额外构建；自包含代码可离线运行；数据不随代码更新消失 |
| 自托管与家庭设备 | 把域名、HTTP 私网 IP、带端口控制台放到桌面 | 地址可编辑，连接失败可恢复；原生能力按需授权 |
| 持续开发 | 直接修改电脑上的 HTML/JS/CSS 目录，推送到手机并立即查看 | 无前端编译；同一 appId 连续部署；无需重装 APK、重建图标或重新授予已有权限 |
| 长期维护 | 升级页面、回到旧版、备份、换机、更新 Haminn | 代码、业务数据、浏览登录态的保留和恢复边界清楚 |

“本地副本”只说明代码在手机，不意味着远程 API、CDN、字体或登录服务离线可用。Haminn 不递归镜像网站；参考应用和离线模板必须自包含。

### 1.3 范围与成功标准

v1 完成上述四条场景的闭环，包括在线与本地安装、通用 HTTPS 包、可选公开 GitHub 来源、桌面入口、按应用授权、持久数据、必要原生能力、开发部署、更新、备份和恢复。应用库不建设公共应用市场、账号体系、收费体系或云同步。

不在 v1 建设浏览器标签页、手机端 npm/Git 构建、独立 APK 生成、多窗口、后台录音定位或传感器采集、任意 Intent/系统插件执行、NFC/无障碍服务。私有仓库、差分包、云发布和动态原生插件留作扩展；普通二维码只识别 HTTP(S) URL，Haminn 设备分享二维码使用独立 `haminn://share` 合同，扫码后仍须显示包信息并由用户确认安装，不能静默执行。蓝牙只提供前台 BLE 扫描与通用 GATT 连接，经典蓝牙配对和设备专属协议仍交给系统设置与 happ 自身。

成功以“桌面工具可以稳定使用和更新”验收，不以 API 数量验收。四条参考用户任务、负面隔离测试、故障恢复、APK 覆盖升级必须通过；构建成功或生成 debug APK 不代表产品完成。

## 2. 产品体验

### 2.1 应用库与运行页面

内置 Store 在用户界面显示为“应用库”，避免被理解为公共应用商店。它随 APK 发布，离线可用，管理所有 Web App。首页只有已安装应用、搜索和“添加”；应用卡片提供打开、收藏、桌面图标、设备分享和管理，应用详情提供地址/来源、版本、权限、存储、备份、开发连接和删除。

普通运行页面占据内容区，无常驻地址栏或悬浮管理按钮。系统栏、键盘和安全区域正确避让；错误时显示 Haminn 控制的恢复界面，提供重试、回应用库和查看原因。用户随时可通过 Haminn 主图标返回应用库。

应用库使用 APK 内置的原生 HTML、JavaScript、CSS 源文件直接运行，不引入框架或前端构建步骤；授权对话框、系统选择器、WebView 不兼容/数据库损坏恢复页使用 Android 原生 UI。恢复入口不能依赖已经失效的 WebView 或 Bridge。

v1.3 采用黑白与中性灰主题，蓝、紫、橙、绿功能图标用于区分动作；卡片、搜索、底栏与状态层使用低对比半透明材质、细边框、内高光和克制阴影，在深浅主题中保持可读性。主导航固定为“收藏、全部、开发、设置、支持”五个等宽入口，图标容器与按钮中心线一致。收藏与全部复用同一应用列表和添加流程：收藏只过滤 `favorite=true`，卡片爱心即时切换。二维码入口对普通 HTTP(S) 内容预填在线网址表单；对 `haminn://share` 则读取临时包信息并打开独立安装确认。

开发页集中控制全局智能体开发服务，开关状态、局域网地址、六位密码、改密和最近操作位于同一页面；服务开启时开发 Tab 显示绿色状态点，状态由 Native 服务真值轮询驱动。设置页只承载 Haminn 自身配置和软件信息，包括主题、备份恢复、图标库、运行诊断、开源许可、真实版本、应用标识、作者 `10knet·zhyuzh3d` 与 GitHub 仓库。支持 Tab 先由 Native 使用短超时请求检测 `https://haminn.10knet.com/pages/donate.html`，成功后在无 Bridge、拒绝权限请求的独立 Web Profile 中打开；失败或主文档出现 HTTP/网络错误时返回本地可重试页面。站外链接交系统处理，支持页面不能获得 Store 管理接口。

弹窗应管理焦点、背景不可交互和滚动锁定。应用库返回键先关闭顶层弹窗，再回到应用库或清空筛选；危险动作单独确认，取消不改变数据。进行中的操作防止重复点击，错误提示可手动关闭，新增成功清除旧搜索条件以显示新应用。

### 2.2 添加与身份维护

用户选择“从网址”或“从文件夹”，也可直接粘贴输入，由解析器给出明确候选。手机本地文件或目录导入的是本地 happ；HTTP(S)、HTTPS 包和 GitHub 等 URL 来源的是线上 happ。来源不会因是否下载到手机而改变。

添加页面 URL 时，HaminnApp 按入口类型处理：以 `.zip` 结尾的地址直接下载并安装；文件名为 `haminn-install.json` 的地址必须读取该清单并下载其中的同 Origin ZIP；普通页面 URL 尝试读取其 Origin 根目录的 `/haminn-install.json`。前两种以及普通页面发现有效清单时都会产生本地 release，可创建开发副本；普通页面没有可用清单时保存 URL 并采用线上实时运行，没有本地代码，不能进入开发模式。显式安装包或清单读取、格式、摘要或包校验失败时明确报错，不能降级成实时网页。裸域名默认 HTTPS，显式 scheme 必须保留；用户明确添加的局域网 HTTP(S) 地址仍属于 URL/线上来源，并单独经过局域网与明文风险确认。

本地 happ 接受 ZIP 和 SAF 目录。HTTPS 包地址与公开 GitHub 静态目录是只能本地运行的线上来源，除非来源另行提供页面 URL。无包内 `haminn.json` 时推断名称与入口；不遍历任意源码目录猜测构建目录。手机不执行构建命令，ZIP 是传输归档，不是编译产物要求。

确认后先完成安装，再可选添加桌面图标。安装成功与 Launcher 接受固定图标是两个结果；用户拒绝或 Launcher 不支持时，仍可从应用库打开。快捷方式只记录 appId，不记录 URL 或物理目录。[Android ShortcutManager](https://developer.android.com/reference/android/content/pm/ShortcutManager)

本地导入的名称优先级为：用户明确填写的名称 → 包内 manifest 名称 → 来源默认名（如 GitHub 仓库名）→ 本地应用。代码更新不改已有实例名称。

设备间分享只分享代码包。A 设备锁定当前正式 `activeReleaseId`；若实例正在运行开发副本，则锁定点击瞬间的 `devRevision`。Native 将快照生成为标准 ZIP，保存到短期文件目录，并在检测到 Wi-Fi、以太网或手机热点局域网地址时启动一小时临时服务。二维码中央显示 happ 图标，没有图标时显示名称首字符；载荷只含协议版本、临时地址和独立六位分享密码。B 扫码后无需输入密码，但必须确认 happ 名称、版本、作者、大小、快照类型和签名状态，随后由现有安装事务下载、校验和原子安装。安装确认中的桌面图标开关默认开启；Android Launcher 是否最终接受固定图标仍由系统决定。A 端同时提供保存 ZIP、系统分享 ZIP 和停止分享。纯线上实时页面没有可锁定的本地代码，不能伪装成可分享安装包。完整实现合同见 [设备间 happ 分享开发计划](../plans/haminnapp-device-happ-sharing-plan.md)。

同一来源可安装多个实例，分别拥有 Haminn 身份、授权和原生数据；它们的网页站点数据则遵守标准同源规则。修改展示名、更新代码或切换本地/实时运行不改变实例、数据和普通授权。修改 `liveUrl` 导致 Origin 改变时，仅清除旧 endpoint 与按 Origin 保存的范围授权；普通 grant 保留。

### 2.3 启动、导航和返回

应用库与每个运行中的 happ 使用独立 Android task；每个 task 只有一个 Activity 和一个可见 WebView。主图标无条件请求应用库 task；应用库、桌面快捷方式和通知统一用稳定的 appId document URI 创建或唤回对应 happ task。同一 happ 不重复创建卡片，不同 happ 可同时保留在系统最近任务中。每张卡片通过 `TaskDescription` 请求实例名称与实例图标；未声明图标时生成稳定的名称首字符图标。部分厂商最近任务界面会接受名称却强制显示 APK 图标，此时只能如实回退，不能为动态 happ 预埋有限数量的静态 Activity alias。Intent 只表达打开目标，不能直接授予权限、指定文件路径或调用管理方法。

在线应用的同 Origin 页面留在当前 WebView。其他 Origin 默认交系统浏览器；确需网页登录跳转时，可在应用详情启用“允许站内跨站导航”，但 Bridge 仍仅属于主 Origin，跨站页面不获授权。v1 不开放多 Bridge Origin 白名单。外部浏览器中的 OAuth 不能被承诺自动把登录态带回 WebView；需要站点支持或选择站内登录模式。

返回键优先处理网页历史；到根页后结束当前 happ task，系统自然回到此前任务。应用库 task 独立保留，不在 happ Activity 内重建第二份应用库。新窗口按导航规则处理，不创建隐藏的额外 WebView。非 HTTP(S) 协议只允许固定的系统用途，并由明确用户操作触发。

前后台切换、屏幕旋转和进程回收不承诺保存 JS 内存。应用应及时持久化业务状态；Haminn 保留可恢复的入口和页面位置，旋转重建遵循同一会话恢复流程。恢复的浏览历史也必须重新经过导航策略。

### 2.4 更新、删除和故障恢复

是否可回滚由 `activeReleaseId` 决定，而不是由来源决定。有本地代码的 happ 显示当前版本和上一保留版本；采用线上实时运行时仍保留本地版本，切回本地即可使用。没有本地代码的线上 happ 只提供重新加载。下载与校验不打断正在使用的版本，新包失败时保留旧版和数据。

应用详情明确区分两种操作：“卸载并保留数据”删除本地代码、归档实例并暂停通知；重新安装同一 `happId` 时，签名一致可自动恢复，未签名包必须由用户确认。“彻底删除”才级联清理代码、数据、授权和通知且不可恢复。Launcher 残留图标可能需用户自行移除。

## 3. 统一领域模型

来源只作审计记录，不决定能力；运行能力直接由字段决定：

```text
activeReleaseId != null ──────────→ 可以本地运行
liveUrl != null ──────────────────→ 可以线上实时运行
updateUrl != null ────────────────→ 可以一键更新
downloadUrl != null ──────────────→ 可以从原地址重装
```

`WebAppInstance` 是长期身份；`CodeRelease` 是不可变本地代码；`RuntimeSession` 是一次运行；`Operation` 是一次可追踪操作。不要再用一个含糊的“Profile”同时指业务身份和 WebView 存储。

| 对象 | 关键字段 | 唯一职责 |
| --- | --- | --- |
| WebAppInstance | instanceId、happId、displayName、runtimeMode、liveUrl、updateUrl、downloadUrl、activeReleaseId、favorite | 本机身份、用户设置、当前运行方式与代码指针；旧 `appId/mode/startUrl/primaryOrigin` 仅作内部迁移兼容 |
| SourceBinding | appId、adapter、spec、updatePolicy | 用户认可的上游来源和更新方式 |
| CodeRelease | releaseId、appId、treeHash、entryPath、版本、provenance、sourceRevision | 一次完整且不可变的本地代码 |
| RuntimeSession | sessionId、appId、role、releaseId、dataGeneration、documentEpoch | 当前运行上下文；由 Native 创建；WebView 使用共享默认 Profile |
| CapabilityGrant | instanceId、capability、resourceScope、decision | Haminn 层的持久授权意图 |
| Operation | operationId、appId、kind、state、idempotencyKey、result | 安装、更新、备份、删除、部署的状态和恢复 |
| FileHandle | opaqueId、appId、logicalFileId、access、expiry | 对受控文件的引用 |
| DevelopmentSession | targetAppId、transport、expiresAt、autoReload | 显式开启且短期存活的开发连接 |

instanceId 采用 Native 生成的 UUID；发布方声明的 `happId` 只用于识别候选，只有一致的发布者签名才能自动恢复，未签名包必须由用户确认。releaseId 也由 Native 生成；规范化文件树摘要用于识别相同内容。treeHash 使用固定域标识，并逐文件编码 UTF-8 路径长度、路径、内容长度和内容，避免不同文件边界得到同一摘要输入。ZIP 文件摘要与展开后 treeHash 是不同值，不能混用。

当前版本号、来源、摘要只从 activeReleaseId 关联得到，不在实例表重复存多份“当前版本”字段。SourceBinding 与 release.provenance 分离：一次 curl 覆盖不修改原 GitHub 上游；界面显示开发覆盖，再从上游更新需明确说明会覆盖该版本。

## 4. 技术路线与模块边界

页面层的首要约束是原生优先、产物中立。官方实现和示例只用 Web 标准 HTML/JS/CSS；不增加框架工程脚手架、框架插件、专属路由/HMR、Node 开发服务器或源码自动构建。第三方最终静态产物走现有通用安装与 WebView 规则，不做框架品牌检测。通用浏览器能力不等于框架专属适配。宿主 APK 的 Gradle 构建、仓库检查与上游资源同步工具不会成为页面开发前置条件。完整作者合同见 [WebApp 编写指南](webapp-authoring.md)。

采用 Kotlin + Coroutines + Android Framework/AndroidX Activity + System WebView + AndroidX WebKit。网络统一用 OkHttp，系统注册库和应用数据使用系统 SQLite，文件使用 SAF。二维码使用 CameraX 取景和随 APK 打包的 ZXing 本地解码，不调用 Google Play 服务。原生只有一个 Android 模块，通过包和接口分离责任；不为预计的规模建立多进程服务、复杂依赖注入或跨平台抽象。

选择依据是总实现与维护成本，而非依赖数量最少。协程使页面退出、超时、系统授权和网络取消可以遵守统一生命周期，减少 Java 手工回调状态；OkHttp 统一下载、请求取消、超时与测试替身。依赖仍需锁定、审计和按用途隔离。[Android 协程实践](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)、[OkHttp 官方说明](https://github.com/square/okhttp)

| 路线 | 判断 |
| --- | --- |
| Kotlin + 精简自有 WebView 宿主 | 采用；运行身份、来源和权限确为产品核心，直接表达这些模型 |
| 保留 Java 并手写异步/HTTP 基础设施 | 不采用为默认路线；省掉语言依赖却增加取消、竞态和网络实现成本 |
| Capacitor / Cordova 整体接入 | 不采用；插件生态有价值，但单业务包配置不能直接解决动态多实例身份与授权，仍需重做核心宿主 |
| 自带独立网页引擎 | 保留为中长期验证；当前先用 System WebView 的三级兼容运行覆盖旧国产设备，若实际网页兼容性仍不足，再评估 GeckoView 单引擎迁移 |
| Compose / Flutter / React Native | 不采用为主 UI；产品 UI 本来以页面为主，少量原生系统界面用 Views 即可 |
| Room / ORM、NDK SQLite、动态插件加载 | 首版不引入；受控而少量的表用显式迁移，扩展 Native 能力随 APK 编译发布 |

Capacitor 官方仍把 `server.url` 描述为开发 live reload 用途。这里的选型是对 Haminn 多实例目标的工程判断，不是声称该框架不能加载在线页面或其桥必然不安全。[Capacitor 配置](https://capacitorjs.com/docs/config)

```text
应用库 UI / 快捷方式 / Deploy HTTP
                 ↓
      Application Services
    安装事务 · 实例管理 · 备份恢复
                 ↓
  Registry ── RuntimeHost ── LocalContentGateway
                  ↓
        Bridge + CapabilityRegistry
                  ↓
       PermissionBroker + Adapters
                  ↓
          Android / SQLite / Files
```

入口不直接读写业务表或目录。应用库管理调用、单应用 Deploy 与全应用 Agent Development 共用安装和注册服务，但凭据边界不同：原单应用 Deploy 仅更新指定目标；智能体开发模式使用一个持久六位密码，授权所有已开放开发工具和全部应用，不配对或识别电脑。二者都不继承任意 Store RPC 调用权，不绕过系统/逐页面能力权限，也不开放业务数据或登录态导出。

建议代码包为 `model/`、`registry/`、`install/`、`runtime/`、`bridge/`、`capability/`、`data/`、`deploy/`、`ui/`。纯解析、状态机、验证规则不依赖 Activity，便于普通 JVM 测试；禁止为了分层给每个类创建无意义的接口。

### 4.1 构建基线

| 项目 | 初始锁定目标 |
| --- | --- |
| release applicationId / namespace | `life.airen.haminn` |
| debug applicationId | `life.airen.haminn.debug`，可与 release 并存，不参与正式升级链 |
| minSdk / compileSdk / targetSdk | 29 / 37 / 37 |
| JDK / AGP / Gradle | JDK 17 / AGP 9.1.1 / Gradle 9.3.1 |
| AndroidX WebKit | 1.17.0，实际能力仍逐项检测 |
| 扫码 | CameraX 1.5.3 + ZXing Core 3.5.4；全部随 APK 分发，无 GMS/网络/模型下载 |
| Kotlin | 优先使用 AGP 内置 Kotlin 支持；与 serialization 编译插件版本匹配 |
| JSON / HTTP | kotlinx.serialization / OkHttp，精确版本在 S0 构建验证后锁定 |
| Store / SDK | 原生 HTML + JavaScript + CSS 源文件直接随 APK 分发；无前端编译或打包器；.d.ts 仅作可选类型提示 |

AGP 官方列出的 9.1.1 兼容 API 37、Gradle 9.3.1、JDK 17；AndroidX WebKit 当前稳定版为 1.17.0。以上是经资料核对的候选构建组合，尚未在本机完成构建验证。Build Tools 及其他依赖在 S0 固定到实际可获取且兼容的精确版本，不使用 `+` 或浮动最新版。[AGP 兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、[WebKit 发布记录](https://developer.android.com/jetpack/androidx/releases/webkit)

## 5. 安装、来源与版本事务

### 5.1 一个描述格式

包内可选 `haminn.json` 只描述已下载代码的身份、展示作者、入口、路由和版本。`author` 是可选署名文本，不是发布者签名或信任凭据。线上 Origin 根目录可选的 `/haminn-install.json` 只描述同源 ZIP 的相对路径和可选 SHA-256；两者职责不同，不得互相替代。本地包没有 `haminn.json` 也可按默认入口安装。

以下为 ZIP 内的最小应用描述：

```json
{
  "schema": 1,
  "name": "语音笔记",
  "entry": "index.html",
  "routing": "hash",
  "version": { "code": 104, "name": "1.4.0" }
}
```

包根就是可运行 Web 根，原生 HTML/JS/CSS 目录直接导入或归档为 ZIP，无需生成 dist。第三方工具产物需要作者自行选择已生成的静态 Web 根，Haminn 不自动构建或猜测输出目录，不在 Runtime 中保留 webRoot 的二次映射。

`haminn-install.json` schema 1 仅接受 `schema/package/sha256`。`package` 必须是同 Origin 相对路径；自动发现不跨 Origin、不跟随重定向，响应与 ZIP 均有大小限制。SHA-256 校验完整性，但同一服务器可同时替换清单和 ZIP，因此不等同发布者签名。HTTPS 包直链与 GitHub 更新继续按各自 SourceBinding 重新获取；相同文件树不制造重复 release。

`entry` 使用本地路径校验器：禁止绝对路径、反斜杠、路径穿越和保留命名空间。v1 manifest 采用严格 schema，只接受 `schema/name/entry/routing/version`；未知字段拒绝并给出可恢复错误。能力是否可用由运行时 `runtime.capabilities()` 探测，授权只在实际敏感调用或显式 `permissions.request()` 时发生，manifest 不能自行取得能力。

### 5.2 统一来源适配器

适配器接口只有 `resolve(input)`、`materialize(candidate, sink)` 和可选 `check(binding)`。所有本地适配器写入同一受限 staging sink，不持有 active 指针写权限。

| 输入 | 处理方式 | 更新语义 |
| --- | --- | --- |
| 在线 HTTP(S) 页面 | 保存页面 URL，探测同 Origin `/haminn-install.json`；有包则产生本地 release，无包则实时运行 | 清单包更新或实时重新加载；设置可在可用方式间切换 |
| SAF ZIP / 目录 | 本地来源；完整复制到 staging；遍历有数量/大小边界 | 再次选择导入，不依赖旧 URI grant |
| HTTPS ZIP | 线上来源；公网地址校验后受限流式下载；没有页面 URL 时只能本地运行 | 用户显式触发重新下载；以内容树识别相同版本，不把自报 version 当安全边界 |
| GitHub 公开仓库 | 线上来源；解析 repo/ref/path，锁定 commit 后下载；没有页面 URL 时只能本地运行 | 比较锁定来源的 commit；不在下载过程中跟随变化的 branch |
| curl 部署 | 向已有本地实例提交完整快照 | 同一事务；不要求开发时反复递增业务版本号 |

GitHub 首版只保留 archive 一条下载路径。目录不明确时要求显式指定 ref 与 path；包含斜杠的 ref 不能靠截断 URL 猜测。静态入口缺失、LFS 指针、submodule、限额、仓库过大均给出具体错误及“在电脑打包后导入”的路径。下载全仓库 ZIP 的体积上限按传输量计算，即使只提取一个目录也不能绕过限额。

HTTPS 更新 code 仅比较同一 SourceBinding；手工回退不降低已见过的上游最高 code，避免下一次检查误判版本。摘要验证完整性，发布者真实性依赖已确认来源和 TLS；代码包签名不是 v1 既有能力。

### 5.3 提交与并发

```text
创建 Operation
→ 获取每 appId 的写操作锁
→ 传输/复制 staging
→ 校验文件树、入口、manifest、配额、摘要
→ 完成文件写入和同步
→ 同一文件系统 rename 到 releases/<releaseId>
→ Registry 事务写 release、CAS 切换 activeReleaseId、写 operation 成功
→ 发布变更事件
→ 按用户策略重建 RuntimeSession
```

对于新安装，appId 在操作开始时预留，但成功前不成为可启动实例。对于更新，WebAppInstance 仍可运行旧版，忙碌状态由 Operation 表达，不复制为另一套“应用更新状态机”。

安装、开发覆盖、恢复、删除对同一 appId 串行。提交必须比较 `expectedActiveReleaseId`；并发旧请求返回 `E_CONFLICT`，不能用最后写入静默覆盖新版本。不同实例最多两项传输，一项解压，避免争用手机存储。

Operation 状态为 `queued → transferring → validating → committing → succeeded`，另有 `failed / cancelled`。进入 committing 前可取消；提交后取消只停止后续展示，不撤销已经提交的结果。幂等键与目标、输入摘要绑定，同键不同输入冲突；调用方断线后查询 operation，不盲目再安装一次。

进程重启时，未提交 staging 可清理；有完整 release 而 DB 未引用的目录是待清理孤儿；DB 已提交但 operation 响应丢失时返回原成功结果。缺失/损坏的活动目录先停止该实例启动，再恢复到仍可验证的保留版本或进入恢复页。文件和数据库不是跨资源事务；通过写入顺序、journal、CAS 和启动核对保证可恢复，不承诺所有掉电场景零损失。

### 5.4 会话版本与回滚

LocalContentGateway 在 RuntimeSession 创建时绑定固定 releaseId，所有 HTML、JS、CSS、Worker 和媒体请求读取同一文件树，绝不逐请求读取 activeReleaseId。旧会话退出前，版本清理不能删除其 release；响应流也持有版本租约，关闭后才释放。

保留当前版与至少一个上版，额外版本按存储配额清理。校验失败不会激活；代码目录损坏等确定的基础设施错误可以恢复旧指针。JavaScript 业务错误、页面空白和超时不能可靠判定“坏版本”，仅提供显式回退，不反复自动重试造成循环。

回退只切换代码，既不回退业务数据，也不恢复服务器状态。示例应用使用追加、向后兼容的数据变更。需要破坏性数据迁移时，应用先导出或保留自己的迁移版本；v1 不运行任意包内 Native 迁移脚本。可选 `haminn.app.ready()` 只记录页面自报就绪，不证明业务正确，也不作为唯一健康依据。

## 6. WebView、内容加载与兼容

### 6.1 三级运行合同

Runtime 启动时检测实际 WebView provider，而不按 Android 版本或品牌猜测。三级模式共用同一 `PageBridge`、会话、权限和 Host API 合同：

| 模式 | 条件 | 行为与边界 |
| --- | --- | --- |
| `shared-web-message` | 可用 `WEB_MESSAGE_LISTENER` | 使用共享默认 Profile；网页数据严格按 Origin 共享/隔离；消息通道校验 Origin 与主 frame；可用时在文档起始注入 SDK |
| `shared-legacy-bridge` | provider 存在但无 `WEB_MESSAGE_LISTENER` | 同样按 Origin 共享站点数据；使用固定 `JavascriptInterface`，接口会暴露给页面中的 frame，不能证明消息来自主 frame |

两种模式都由 Native 固定 appId、Session、documentId、epoch、并发上限和逐应用授权，Haminn 管理的记录与逻辑文件继续按 appId 隔离。共享 Profile 是产品的数据协作策略，不是兼容降级；安全消息能力才是 provider 差异。传统桥接不能恢复缺失的 frame 校验，因此不应把恶意页面、第三方脚本或 iframe 当作互不信任代码放在同一实例中。应用库以文字按钮说明实际模式，`runtime.info()` 与诊断返回机器可读事实。只有 provider 缺失、WebView 无法创建或渲染进程反复失败时才进入原生恢复页。

“同源”必须精确定义为协议、主机和端口相同。`https://example.com/a/` 与 `https://example.com/b/` 同源，文件夹路径不是隔离边界；`https://a.example.com/` 与 `https://b.example.com/` 默认不是同源，即使它们同属一个主域。Cookie 可由服务端通过 Domain 属性扩大到父域，但 localStorage/IndexedDB 不会因此跨子域共享。Local App 目前仍使用每个 appId 独立的内部虚拟主机，所以不同本地 App 不会自动共享网页存储；若未来需要多个本地页面组成一组，应增加显式 `storageGroup`，不能靠模糊的“同一文件夹”猜测身份。

本地页面的兼容 SDK 由 `LocalContentGateway` 插入到主文档 `<head>` 前部，并从保留的同 Origin 路径提供；在线页面无法改写服务端 HTML时，在 `onPageFinished` 后注入，因此作者必须监听 `haminnready`，不能假设解析首行时 API 已存在。注入限制为主文档和 2 MiB 以内 HTML；过大或非 HTML 文档使用完成后注入。旧内核所需的宿主 Store 与 SDK 保持无构建的保守 JavaScript 语法基线，并为关键 DOM API提供小型本地降级。

Profile 删除要在下次进程启动、该 Profile 尚未加载前处理。仅销毁 WebView 不足以保证能调用 deleteProfile：已在当前进程通过 getProfile/getOrCreateProfile 加载的 Profile 也受到限制。逻辑删除立即生效，物理清理完成情况另记；不宣称安全擦除磁盘。[ProfileStore](https://developer.android.com/reference/androidx/webkit/ProfileStore)

### 6.2 LocalContentGateway

应用库 Origin 固定为 `https://store.haminn.invalid`；本地实例为 `https://<appId>.apps.haminn.invalid`。这些域名禁止作为在线来源安装。

通过 AndroidX WebKit 的本地内容映射机制和自有受限处理器提供资源，不启动 HTTP 服务承载日常页面。每个 WebView 只映射自己的代码根；其他 Haminn 内部域名一律拒绝，不回落网络。内部保留 `/__haminn/` 路径用于临时媒体 handle，不允许包占用。

网关仅支持 GET/HEAD，返回明确 MIME、`nosniff`、长度和状态；媒体支持单区间 Range/206/416，拒绝多区间。不存在的 JS/CSS 返回真正 404；只有 manifest 显式选择 history routing、请求是 HTML 导航且无对应文件时才回退 index.html。默认 hash routing。外部数据 API 不被伪装成本地静态文件服务器。

本地代码响应采用 `Cache-Control: no-store`，稳定 Origin 保留 WebStorage，而会话锁定 release 保证资源一致。Web App 自己主动使用 CacheStorage 缓存业务内容属于应用逻辑，Haminn 不替其迁移缓存。

本地包不启用 Service Worker；离线与版本缓存由 Haminn 负责，避免 SW 控制旧页面和更新缓存再形成第二套发布系统。网关对所有代码及受控资源响应强制设置跨 Origin 的 `Service-Worker-Allowed: https://sw-disabled.haminn.invalid/`。按 SW 更新算法，这使注册因 scope 的 Origin 不匹配而失败，同时不限制普通 URL/Blob Worker。此方案有标准与 Chromium 实现依据，但仍必须在支持的 WebView 上验证 classic/module、各种 scope 和 Worker 行为；不能仅靠注入 JS 覆盖 register 作为保证。[W3C SW 更新算法](https://www.w3.org/TR/service-workers/#update-algorithm)、[Chromium scope 校验实现](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/content/browser/service_worker/service_worker_loader_helpers.cc)

每个 Local Profile 的 ServiceWorkerClient 也把内部虚拟域请求交给固定网关或明确失败，不允许 DNS 兜底。v1 从新 Profile 开始，不继承外部已有的本地 SW；未来迁移旧 Profile 若发现 controller，暂停运行并走显式清理流程。依赖 SW 才能启动的包必须提供不注册 SW 的构建。

### 6.3 在线内容与系统接口

v1.1 新增公共离线图标网关 `SharedAssetGateway`，在当前应用精确 Origin 下提供 `/__haminn/icons/fontawesome/css/all.min.css`、四个 WOFF2 字体文件及许可文本。它位于页面资源网关之前，仅允许固定白名单和 GET/HEAD；未知保留路径返回 404，不映射用户路径，不暴露私有文件。在线和本地页面使用同一接口，CSS 相对字体路径保持同源。

document-start 脚本提供 `haminn.icons` / `window.HaminnIcons`，在 DOM 就绪后自动挂载字体样式，作者可直接写标准 `fa-solid` / `fa-regular` / `fa-brands` 类名。显式 link 引用也可用。资源不随页面版本复制，无网络/CDN依赖，不扩大 Bridge 授权范围。在线站点自己的 CSP、Service Worker 仍可限制资源加载；不替站点弱化 CSP 或绕过已有 Service Worker，站点作者需允许该同源样式与字体并为保留路径使用网络转发。图标字体/CSS/目录版本随 APK 固定更新，默认 no-store 防止覆盖升级后读取旧字形。

在线内容保留网站缓存、Service Worker 和普通浏览网络行为，不承诺可靠离线、后台执行或完全兼容特定站点登录。TLS 错误直接失败，用户可修改地址或证书部署，不提供全局“忽略 SSL”。

关闭 file/content URL 任意访问、universal file access 和 mixed content；允许声明过的 HTTP 在线入口，应用详情始终标识明文来源。第三方 Cookie 默认关闭，可按实例开启；release 关闭 WebView debugging。

### 6.4 自带引擎迁移门禁

“Haminn 自带标准 WebView”不能通过把 Android System WebView APK 塞进应用实现。零售设备的 WebView Provider 受系统配置、签名和更新服务控制，普通应用不能可靠替换；Chromium 的系统集成文档也明确把该路径限定给设备厂商或自定义 ROM。[Chromium WebView 系统集成说明](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/aosp-system-integration.md)

可行的自带方案是把 GeckoView 作为应用库嵌入。它不依赖设备的 WebView Provider，具备独立 Session 与 contextId，可从 Android 10 起建立统一渲染基线；但这不是替换一个 View 类，而是重写页面会话、导航、消息桥、本地内容、下载、权限、崩溃恢复和测试夹具。当前三级兼容模式先解决“旧 provider 不能打开 Haminn”的直接问题；只有设备矩阵证明渲染/网页 API 本身仍大量不兼容，才承担这项迁移。[GeckoView 官方嵌入指南](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/geckoview-quick-start.html) · [GeckoSessionSettings](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSessionSettings.html)

不采用双引擎长期方案：它会让 Cookie、CSP、Service Worker、Bridge、媒体和页面兼容性产生两套行为，验证成本高于其包体收益。迁移以单独工程门禁推进；通过后 GeckoView 成为唯一页面 Runtime，系统 WebView 仅保留到旧版分支。无 GMS 是硬门禁：显式排除和验证 GeckoView 元数据中的 Play Services FIDO 依赖，WebAuthn/FIDO 在无可替代实现前报告不支持；所有引擎二进制随 APK/App Bundle 交付，不允许运行时从境外下载。

包体采用 arm64-v8a 主 APK 与应用商店 ABI split，另行决定是否提供 32 位包，不发布包含全部 ABI 的超大通用 APK作为默认下载。引擎升级跟随 Haminn 正式版本，至少按浏览器安全发布节奏维护；若做不到持续更新，就不能用“内置内核”换取表面兼容。迁移执行门禁见 [自带网页引擎迁移计划](../plans/haminn-embedded-engine-migration.md)。

原生相机/麦克风、Web geolocation 等敏感入口不能绕过 PermissionBroker。v1 对 WebChromeClient 的 getUserMedia、地理位置等授权请求统一明确拒绝，能力通过主文档 Haminn API 提供；未知资源种类也拒绝。原因是这些回调未提供与 Bridge 等价的主 Frame 身份合同。文件输入允许调用系统选择器，但结果只归属发起会话，并遵循文件导入边界。标准网页摄像头/麦克风站点可能因此需要适配，必须写入兼容说明。

## 7. Bridge 与能力扩展合同

### 7.1 身份、文档与信任

隔离模式在首次加载前注册 exact Origin 的 WebMessageListener 和 document-start SDK，公开 `window.haminn`。兼容消息模式保留 WebMessageListener，在本地主文档或加载完成时补注入 SDK；传统桥接模式改用 `JavascriptInterface` 和 `evaluateJavascript` 往返。三种模式都由 Native 固定 WebView 实例、role、appId、sessionId、documentEpoch 和参数，网页传入 appId 不能选择执行身份，host 方法只属于 Native 创建的 Store 会话；只有隔离/兼容消息模式能够验证 sourceOrigin 与 isMainFrame，只有支持 Profile 的设备能提供独立网页资料空间。

一次 WebView 会话可以经历多个 HTML 文档，其下建立独立 DocumentSession。SDK 每个文档生成随机 documentId，先发送 hello 并暂存 API 调用；hello 本身不激活权限。Native 确认主导航已提交且不为错误页后，经该文档的 replyProxy 发出一次性 challenge，收到匹配 ack 才绑定 documentId、epoch 和代理并放行。完整文档导航开始即撤销旧绑定，副作用执行前与结果返回前再次检查；204、下载或取消等未提交导航须恢复原文档的握手，不能永久卡住。

同文档 hash/history 变化不重置。v1 不主动开启 BFCache，支持控制时明确关闭；仍测试系统可能产生的 pageshow 恢复，不把“从未开启”当成不会发生。SDK 的 pagehide/pageshow 配合 Native 导航回调完成失效与重新握手，不能只相信页面事件。

优先使用支持时的 NavigationListener 区分导航、提交与同文档变化；旧 provider 采用 WebViewClient + 文档握手，导航未完成时关闭敏感调用并取消待决授权，重新握手后恢复。此处必须通过乱序、重定向、BFCache、同 Origin 换页测试；无法保持合同的 provider 不能列入支持矩阵。NavigationListener 是可选优化，不能因为依赖库含有方法就认为 provider 实现了它。[NavigationListener](https://developer.android.com/reference/androidx/webkit/NavigationListener)

WebMessage 模式的异步回复只使用发起消息的 replyProxy，并附带原 epoch；传统桥接模式因没有 replyProxy，只能通过当前 WebView 的固定接收函数寻址，但同样在发送前复核 documentId 与 epoch。页面离开后 Native 必须取消可取消任务、丢弃失效结果。[WebMessageListener](https://developer.android.com/reference/androidx/webkit/WebViewCompat.WebMessageListener)

在 WebMessage 模式中，Origin 是信任单位，不是 URL 路径；直接来自 iframe 的桥消息拒绝，同 Origin iframe 能通过 DOM 访问父窗口时仍与父页面共享信任。传统桥接模式无法可靠识别消息 frame，任何子 frame 都可能看到接口，因此整个文档树必须视为同一信任域。XSS 和页面加载的第三方脚本具有当前页面权限；Native 桥不提供“可信页面里的不可信 JS”沙箱。

### 7.2 一个调用模型

协议统一为请求、响应、事件；公共方法和 host 方法共享编解码及调度基础设施，使用不同 Native 方法目录和角色白名单。

```json
{
  "v": 1,
  "id": "r17",
  "sessionId": "s1",
  "documentEpoch": 3,
  "method": "tts.speak",
  "params": { "text": "你好" }
}
```

```json
{
  "v": 1,
  "id": "r17",
  "documentEpoch": 3,
  "ok": false,
  "error": {
    "code": "E_OS_PERMISSION_DENIED",
    "message": "系统未授予所需权限",
    "retryable": false
  }
}
```

Promise 用于一次性结果；持续任务返回 subscriptionId，事件包含 epoch、subscriptionId 和 sequence。stop/cancel 幂等；页面退出取消订阅。取消不撤销已经完成的数据库提交、分享或网络副作用。长安装/备份任务返回 operationId，页面重开后从应用库查询，避免无限等待的 Promise。

能力目录由 `api/capabilities.json` 冻结公共与 Store-only 方法，TypeScript 类型、注入 SDK 与 Native dispatch 由契约测试交叉检查；运行时再补充设备是否支持以及当前是否可用。扩展新能力需要同时修改目录、adapter、类型和测试，但不修改传输协议。

`runtime.capabilities()` 分开返回 implemented、supported 与 usable；具体逐应用 grant 和系统权限通过 `permissions.status()` 查询，不把“系统未装识别服务”显示成权限拒绝。API 主版本不兼容就明确失败；同一主版本只做兼容增加。当前尚未发布 ABI，不为旧稿的方法别名建立长期兼容层。

基础错误码包括 `E_INVALID_ARGUMENT`、`E_UNSUPPORTED`、`E_ORIGIN_DENIED`、`E_CAPABILITY_DENIED`、`E_OS_PERMISSION_DENIED`、`E_SESSION_EXPIRED`、`E_CANCELLED`、`E_TIMEOUT`、`E_CONFLICT`、`E_QUOTA`、`E_STORAGE`、`E_NETWORK` 和 `E_INTERNAL`，详解和修复动作在协议中冻结。仍在当前文档中的请求必须终止一次，不以永远 pending 表示失败。

## 8. 双层授权与系统权限

Haminn 保存自身声明权限的最近观测状态，也保存每个 Web App 的授权；Android 仍是系统权限事实来源。进程前台恢复和敏感调用前重新检查，并处理调用期间的 SecurityException、精度变化和服务关闭。

```text
用户对该 Web App 的 Haminn grant
    + 当前操作所需的 Android 权限表达式
    + 支持的系统服务、资源范围与前台条件
    = 当前这次调用是否可执行
```

权限表达式可为 ALL/ANY/精度条件，不能一律要求所有声明权限同时存在。例如近似定位只需 coarse；只有明确请求精确位置才要求 fine，用户拒绝精确而授予近似时应返回实际精度或可操作错误。

首次敏感调用显示 Haminn 原生授权界面，写明 happ 名称、能力与用途。若 APK 已有系统权限，Haminn 仍要求用户确认当前 happ 的 grant；若系统层缺失，Haminn 先请求 Android 系统权限，成功后直接记录当前 happ 的允许意图。系统权限的“仅本次、使用时或长期”等生命周期完全由 Android 决定。

Haminn 不再自创“仅本次”级别；grant 按 `instanceId + capability + resourceScope` 持久化为 allow 或 deny。拒绝后不反复弹窗，由用户在应用详情重置。撤销 happ 授权不撤销整个 APK 的系统权限；撤销系统权限使所有依赖它的调用暂不可用，但保留用户对各 happ 的意图。

| 能力类别 | 默认 Haminn 决策 | 系统层 |
| --- | --- | --- |
| 运行信息、自有记录和文件 | 自动允许，受配额限制 | 无 runtime permission |
| 前台朗读、传感器、Wi-Fi/BLE、红外 | 按实例首次询问，限制订阅数、频率与会话时长 | 麦克风、位置、附近设备、活动识别等按实际调用请求 |
| 震动、剪贴板写入、电池/网络状态 | 自动允许，限制频率；剪贴板写入需明确交互 | 按 Adapter 实际所需 |
| 选择文件、分享、系统拍照 | 允许发起可信系统 UI，用户可取消 | SAF/授权 URI；系统 Camera Intent 不为此预申请 CAMERA |
| 语音识别、定位、剪贴板读取 | 按实例首次询问 | 麦克风/精度等实际需求 |
| 截屏、录屏 | 按实例首次询问；录屏每次还需系统投屏同意，该同意不记忆 | MediaProjection 同意（每次）；录麦克风时另需 RECORD_AUDIO |
| Native 网络请求 | 按实例与明确目标 Origin 询问，默认无目标 | INTERNET；局域网按系统版本另检查 |
| 安装、删除、授权管理、开发连接 | Store 角色专属 | 不向普通 Web App 暴露 |

系统授权 UI 由串行 PermissionBroker 协调，最多一个系统弹窗；返回时核对原 instanceId、session 和 documentEpoch。Android 可能已经授予整个 APK 权限，但页面已离开时不能把结果误发或授予新页面。

截屏与录屏多一层用户当面同意：Haminn grant 按实例持久化，而 Android 的投屏同意（MediaProjection）每次调用都要用户重新确认，且宿主不缓存、不复用该同意。这两个同意语义不同，不能互相替代 —— 已授予的 Haminn grant 只是省去 Haminn 自己的弹窗，系统投屏弹窗仍然出现。投屏会话由 `foregroundServiceType="mediaProjection"` 的前台服务持有，因此录屏可以跨页面后台继续，而 Haminn 自己的录音能力仍只在前台存活。受平台限制，一次投屏同意只允许建立一个虚拟显示，所以录制进行中不能同时截屏。

Android Manifest 只声明实际实现所需权限与组件。语音使用 RECORD_AUDIO；定位和 Wi-Fi 扫描使用 coarse/fine；Android 12+ 蓝牙使用 nearby scan/connect，Android 13+ Wi-Fi 连接使用 nearby Wi-Fi，计步使用 ACTIVITY_RECOGNITION；红外仅声明普通 TRANSMIT_IR。TTS/识别/拍照按需配置 package visibility；FileProvider 仅暴露专用分享目录；不申请 all-files、后台位置或传感器高采样率权限。

Android 17、target 37 的 LAN 访问需检查并请求 ACCESS_LOCAL_NETWORK，WebView 的 LAN 流量继承 APK 的系统权限。有 `liveUrl` 时，同 Origin 请求正常执行，被动图片、样式、字体与音视频可跨 Origin；脚本、接口、iframe、Worker、表单和顶层导航默认阻止，只有用户开启逐 happ 的“允许跨 Origin 网络”后放行并持续显示提示。纯本地实例的页面网络始终关闭。具体运行和通知合同以 [统一开发计划](../plans/haminnapp-notifications-development-plan.md) 为准。[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)

首次打开已识别的 LAN 在线入口时，Runtime 在 loadUrl 前由 Broker 处理该系统权限，不等待尚未加载的页面调用 Bridge。私网 IP、明确的本地域名和用户标注的局域网入口均走此流程；解析后发现私网目标也补做检查。拒绝时保留实例，提供重试或系统设置入口；无法可靠识别的网络失败允许用户主动选择“此地址在局域网”。这不是对全部网页子资源的逐目标授权承诺。

HTTP 在线实例首次启用敏感能力时额外说明其代码可被网络替换；授权需从应用详情显式开启。此例外不取消逐实例 grant、目标校验或系统层检查。

## 9. 数据、文件与原生能力

### 9.1 持久数据的明确边界

Haminn 管理的数据与网页浏览数据是两类存储：

| 类别 | 用途 | 更新/迁移保证 |
| --- | --- | --- |
| Haminn Data / Files | 笔记、清单、配置、附件等业务数据 | 按 appId 保留，可导出恢复，与代码 release 分离 |
| Web Profile 数据 | Cookie、localStorage、IndexedDB、CacheStorage 等 | 同实例更新时保留；由 WebView 管理，不承诺跨设备完整导出 |
| 页面 JS 内存 | 临时界面状态 | 不保证进程回收后恢复 |

v1 将网页可调用的任意 SQL 收敛为 `haminn.data` 的键控 JSON 记录服务，底层仍用 Native SQLite。网页不能提交 SQL、数据库文件路径、PRAGMA 或 ATTACH。这样消除“用字符串黑名单实现 SQL 沙箱”的不确定性；代价是没有跨表 JOIN、任意 SQL 和原生全文检索。复杂查询可由 Web App 自用 IndexedDB 等 Web 存储解决，但须接受其备份边界。

首发接口为 `get(collection,key)`、`put(...,expectedRevision?)`、`delete(...,expectedRevision?)`、`scan(collection,{prefix,afterKey,limit})`、`batch(operations)`。记录返回 revision；创建专用条件与 CAS 冲突语义明确。scan 只按 key 排序，不承诺多页快照；需要多条一致更新使用一次 batch。

每实例一个 Native SQLite 数据库，表键为 `(collection,key)`，值为受限 JSON 文本。revision 对外是包含 dataGeneration 与序号的不透明字符串；序号由该 generation 全局单调计数器产生，删除再创建不重用，避免 ABA。expectedRevision 缺省表示无条件写，`absent` 表示仅创建，具体 revision 表示 CAS；指定具体 revision 时记录缺失视为冲突，`absent` 在记录不存在时满足。batch 拒绝重复目标键，一次提交全部操作与条件检查，失败全部回滚；不跨 Bridge 持有开放事务，不让 JavaScript 回调决定 SQL 锁的生命周期。首版不建立通用查询 DSL 或自研 ORM。[SQLiteDatabase API](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase)

### 9.2 文件与二进制

应用文件采用本地 OSS 风格的对象存储：文件本体写入 `filesDir/instances/<appId>/data/<dataGeneration>/files/`，SQLite 只保存 `logicalFileId`、对象 URL、名称、MIME、大小和 SHA-256 等元数据。保存时先写临时文件、刷盘，再原子提交对象索引；App 代码目录只读。SAF 选入文件默认复制到当前实例，移除原文件不影响已导入内容。导出走系统目标选择器，不让网页提交 Android 物理路径。

原生选择、拍照、较大 Native HTTP 响应和 TTS 导出均产生相同逻辑文件对象，并返回同源对象 URL；视频缩略图也作为文件对象。只有不超过 256 KiB 的文本可经 RPC 读取；较大内容由原生流直接导入、导出或分享，不通过巨大 Base64 JSON。文件写入绑定当前实例和 dataGeneration，完成前不进入索引。分享给其他 Android 应用的只读文件使用独立副本和 URI grant，不能因切到分享目标就立即删除；该副本按短期 TTL 清理，不授予对原数据目录的访问。写入侧提供分块通道：`files.beginWrite`/`appendBytes`/`finishWrite`/`abortWrite` 让页面把任意大小的字节写进文件库，每块不超过 64 KiB，句柄绑定页面会话与 dataGeneration，完成前不进索引，因此“单条消息 256 KiB”只约束 RPC JSON，不再约束页面能够传输的字节总量。读取侧没有分块 API，页面仍通过对象 URL 或 8 MiB 以内的内联文本使用文件。

本地网关保留 `/__haminn/` 命名空间，v1 只向当前页面发布当前 appId/dataGeneration 的对象 URL；HaminnUI 可读取宿主图标对象。跨应用或跨 dataGeneration 即使知道 logicalFileId 或对象 URL 也不能读取对应文件。

### 9.3 v1 能力面

| API | 首发功能 | 生命周期与限制 |
| --- | --- | --- |
| runtime | info、capabilities、事件订阅 | 设备和 API 能力探测，不读取其他实例数据 |
| app | info、reload、ready、checkUpdate | 仅当前实例；真正激活更新由应用库或预授权开发会话处理 |
| permissions | status、request | 统一 PermissionBroker |
| data | get、put、delete、scan、batch | 持久化、CAS、分页、原子批量 |
| files | 选择导入、文本创建/读取、删除、列举、导出、分享 | 逻辑 ID 隔离；大文件只走原生导入/导出/分享 |
| audio | startRecording、stopRecording、cancelRecording、play、stopPlayback | 原始麦克风录音与逻辑文件播放；不依赖语音服务；单录音/单播放；退出前台立即释放 |
| tts | availability、preferences、voices、speak、stop、导出音频 | Bridge 不暴露厂商，引擎选择由用户在 HaminnUI 管理；引擎可能联网，会话退出停止输出 |
| speech | availability、preferences、languages、start、recognizeOnce、stop、cancel | 统一连续识别、系统真实语言目录、on-device 与系统界面降级；不暴露厂商，不伪称离线 |
| location | getCurrent、watch、clearWatch | 前台、精度/超时/最大缓存年龄明确，后台停止 watch |
| sensors | availability、watch、clearWatch | 逐传感器硬件事实；方向由旋转向量计算；最高 60 Hz；后台释放 |
| camera | capturePhoto、torchStatus、setTorch | 系统 Camera Intent + FileProvider；闪光灯另行授权；取消清临时文件 |
| screen | availability、capture、startRecording、stopRecording、cancelRecording | 抓取整个设备屏幕（含其他应用）；每次需系统投屏同意；录屏由 `mediaProjection` 前台服务持有，页面退到后台不中断；一次同意只允许一个虚拟显示，故录制中不能截屏；产物导入当前实例文件库 |
| shell QR | CameraX 预览 + ZXing 本地二维码解码 | 属于应用库功能；单独请求 Haminn 相机权限，不把相机能力授权给页面，不联网 |
| share | text、file | 系统 chooser，回调不能证明对方已读取或发布 |
| clipboard | write、read | 读取受前台/系统与逐实例限制 |
| haptics | vibrate、impact | 有限时长，频率限制 |
| network | status、watch、clearWatch、request | 连接类型/验证/计费状态订阅与受控 Native HTTP，普通页面优先标准 fetch |
| wifi | status、scan、requestNetwork、releaseNetwork、openSettings | 扫描受位置与节流约束；Android 10+ 临时连接由系统确认，不改写已保存网络 |
| bluetooth | status、paired、scan、connect、services、read、write、subscribe | 前台 BLE GATT；系统确认附近设备权限；切换页面或后台自动断开 |
| infrared | status、transmit | 仅设备实际具备的消费级红外发射；单次脉冲不超过 2 秒；无通用接收 API |
| system | language、openSettings | `language` 无需授权，返回 Android 系统首选 BCP-47 语言及有序偏好列表；不读取或覆盖 happ 自己的语言选择 |
| battery | status、watch、clearWatch | 前台电量、充电、温度、电压和省电状态 |
| system | openSettings | 只允许固定的 Wi-Fi、蓝牙、位置、语音、TTS 和本应用设置页，不接受任意 Intent |

麦克风、系统文件选择器、拍照、扫码、截屏和录屏等排他资源同时只允许一个操作，其余返回忙碌或有界错误。截屏在请求的投屏会话有效期内生成一张整屏静帧；录屏可选择无音轨、仅系统声音、仅麦克风或两者混音，混音不经过 MediaRecorder 的音轨（它只能有一个音源），而是由两个 AudioRecord 按墙钟时间轴在原生侧混成 PCM 再编码 AAC，最后与视频轨合流；系统声音受 `AudioPlaybackCaptureConfiguration` 限制，只覆盖媒体、游戏与未知用途，被采集应用可主动排除，DRM 内容永远采不到。录屏单次时长默认三分钟、上限 30 分钟，`maxBytes` 是单个交付文件的上限（默认 48 MiB，最大为 Long 可表示值；文件库本身没有单文件字节上限），达到本次录制指定上限即正常收尾并返回已落盘文件。较长录制可走**分段**：`MediaRecorder.setNextOutputFile` 在 90% 通知窗口排队下一个文件、在 `NEXT_OUTPUT_FILE_STARTED` 时切换，编码器不重启所以段间不丢帧；音频侧不随段重启，整场一条连续流，每段在合流时按音频采样时钟切出自己的时间窗（`positionUs()`），因此段边界没有声音空洞。每段独立导入文件库并触发 `screen.recording.segment`。原始录音是 Haminn 本地基础能力，单次默认上限五分钟、绝对上限 30 分钟，停止后原子导入当前实例的逻辑文件；取消、切应用或退出前台时释放录音器并删除临时文件。扬声器既可由 `audio.play` 播放逻辑文件，也可由标准 `<audio>` / Web Audio 播放页面资源，后者继续遵守用户手势策略。

Android 10/11 不调用 API 31 才提供的 on-device SpeechRecognizer；定位在 Android 10 使用单次更新兼容路径。语音适配层枚举标准 Android TTS Service、RecognitionService 和 Recognizer Activity，吸收系统默认绑定、用户选择、已卸载服务和仅界面识别等差异。普通 happ 只看到是否支持朗读、连续识别、一次性识别、离线模式、局部结果和语言检测等稳定事实；服务包名与组件名只进入 Store-only 设置和诊断。选定 TTS 失效时尝试系统默认引擎并发出通用回退事件；ASR 缺失或失败返回明确状态和可处理错误，不导致宿主异常。传感器、Wi-Fi、蓝牙、红外和闪光灯也逐项读取设备事实，不能按 Android 版本、品牌或提供商猜测；系统开关关闭与 Android/happ 授权不足分别返回。

### 9.4 Native HTTP

用途是让自包含页面访问用户明确授权的设备或 API，解决 CORS/mixed-content 的特定集成问题。默认目标集合为空，本地虚拟 Origin 不是默认授权网络目标。

目标按规范化 scheme、host、port 和解析后的地址类别授权；持久 grant 的内部 resourceScope 同时编码 `public/private` 与 Origin，不使用字符串前缀或通配 `*`。同一域名在公网与私网解析间变化时必须重新授权，不能沿用另一地址类别的 grant；环回、链路本地、云元数据地址、Haminn 开发端口默认拒绝。Host/userinfo、代理头、跳跃头由客户端控制，不能由 JS 伪造；不自动共享 WebView Cookie、系统凭据或安装器凭据。

Native network.request 使用独立配置的 OkHttp client：关闭自动重定向和 retryOnConnectionFailure，仅 GET/HEAD 最多手工跟随 5 跳，其他方法返回重定向结果供页面决定；每跳重新验证目标和解析地址，跨 Origin 清除 Authorization、Cookie 等凭据，HTTPS 不降为 HTTP。经校验的 DNS 地址集交给实际连接使用，不能先检查再由另一解析器重查。公网授权遇到私网解析结果拒绝；代理关闭；按授权范围分隔连接池，避免复用绕过地址检查。证书和 hostname 仍按原 URL 验证。实现测试必须覆盖 DNS rebinding、IPv4-mapped IPv6 和重定向。

原生网络请求可有外部副作用，重试默认仅对明确可重试、幂等操作；POST 等不能因页面没收到响应而自动重发。默认返回小 JSON/text，大响应返回当前实例的文件 handle。安装器与 Web App 网络共用代码库，但不能共用授权身份。

## 10. Developer Deploy

### 10.0 统一智能体开发工作区

在 Store 的“开发”Tab 中显式开启开发服务，默认端口 8766。LAN 服务绑定全部本机接口但只公布当前可信局域网 IPv4；USB 备用在设备 loopback 启动同一服务，通过 `adb forward tcp:8766 tcp:8766` 访问。两条传输共享工具、密码、开发工作副本和并发控制。密码首次生成后保存在宿主私有偏好，应用升级/服务重启不变；修改密码后旧值立即失效。多台电脑可共享当前密码，不设配对、客户端名单、独立令牌或应用白名单。

MCP Streamable HTTP（无状态 JSON 响应，无 SSE）是设备自身的 JSON-RPC 传输层，工具、资源和提示词发现都由它承载，标准库 Python 助手直接使用它；服务不注册、不宣传也不需要任何客户端连接器，服务端不再生成 MCP 配置，也不写客户端插件市场登记。根地址和 `/.well-known/haminn-agent` 是无需密码的接入入口，`/skills/haminn-dev-plugin/SKILL.md` 和 MCP guide/resource 提供随 APK 同步更新的实时指导；公开入口只描述认证方式和能力目录，不返回密码、应用或代码数据。缺少 Authorization 的受保护请求返回带发现地址、说明地址和认证头模板的结构化 401，且不计入错误密码限流；只有实际提交错误凭据才累计失败次数。密码只允许放在 `Authorization: Bearer` 请求头，禁止放进 URL 路径、查询参数或 fragment。开发流程由 `haminn-agent.py` 的命令行完成（`develop-dir` 等），不增加页面框架或构建依赖。

智能体在初始化 happ 开发前按统一规范锁定本地目录：用户指定目录优先，其次复用用户目录 `~/haminn/happ-dev.json` 中以精确 `happId` 为键的绝对路径；没有记录时由智能体判断是否使用当前项目工作空间，否则落到 `~/haminn/happs/`。新目录统一命名为 `happ-<happId 中的点改为横线>`。一个 `happId` 只有一个活动路径，用户移动或替换时原子更新记录；旧路径失效时不扫描磁盘或暗中创建副本。记录允许智能体保存自己的版本备忘，但 Haminn 服务不读取它决定目录、版本或同步方向，也不允许其中出现开发密码或设备凭据。

每个普通 happ 以 `appId` 对应一份开发工作副本和 `stable | dev` 启动通道。首次进入 dev 时从当前正式 release 建立内容寻址快照；后续创建、覆盖、移动、重命名和删除文件只写新的 blob 和原子清单，不修改正式 release。写入必须使用 `expectedDevRevision`，同一 happ 串行、不同 happ 可并行。正式版本外部更新时，干净副本自动跟随；有未发布修改的副本保留原基线并显示过期状态。HaminnUI 不进入普通实例清单，保留 appId/happId 也在安装与开发层拒绝，因此该服务不能破坏管理界面。

快速保存优先提交调用者已知的变化文件。纯 CSS 变化替换样式链接，其他变化复用当前 WebView 并刷新当前路径，不清除页面 Profile、数据或 grant。Native 注入 revision 标识和最小就绪回执，服务以 `renderOperationId` 提供有界等待；控制台、HTTP 和页面错误写入有界诊断环，不能采集业务数据。打开特定页面只接受相对同源路由，禁止任意脚本执行。

发布分为构建和安装两个动作：构建以当前开发 revision 生成确定性 ZIP、覆盖 manifest 版本并完整验证；安装使用现有 InstallCoordinator 的 staging、摘要、身份、CAS 和原子激活事务，成功后开发副本重基线并切回 stable。签名 happ 必须由原发布者在设备外签名，Haminn 不代造发布签名。新 happ 先安装最小 schema 2 包，再创建并进入开发副本。

开发模式开关持久化，用户明确关闭前不会因退到后台、空闲或 Wi-Fi 临时断开而失效；应用进程重建后会自动恢复监听。服务每 15 秒检查 Wi-Fi、手机热点或以太网地址，地址或端口变化时由 HaminnUI 提醒用户重新告知智能体；普通蜂窝数据接口不作为可直连地址发布。采用 Host/Origin 防护、错误尝试限速、请求大小限制和有界线程池。六位密码只有一百万种组合，LAN HTTP 也不加密，因此明确仅用于可信局域网，不做公网安全承诺。完整交付与验收见 `plans/haminnapp-unified-dev-workspace-plan.md`。

两个手机开发入口互斥：开启智能体模式即停止单应用部署，开启旧部署即停止智能体模式；修改智能体密码也停止旧部署监听，避免遗留 token 绕过当前密码。导出备份和替换应用数据期间停止智能体服务。

### 10.1 原单应用 Deploy（兼容路径，以下 token/TLS 设置仅适用于 8765）

Developer Deploy 是“更新已有本地实例”的窄接口，不是远程 shell 或管理员接口。用户先在应用库创建/选择实例，开启短期开发会话并明确是否部署后自动重开；外部工具只需 appId，无需知道手机私有目录。

旧的单应用部署会话仍属于 Haminn 前台生命周期。智能体开发服务使用独立的持久开关：切换页面、进入后台、锁屏、空闲或 Wi-Fi 断开都不关闭，应用进程重新启动后按保存的连接模式和端口恢复；只有用户明确关闭开发模式或切换到互斥的旧部署模式时才清除开关。Android 强制停止或进程尚未重新启动期间没有监听进程，这一边界不伪装成系统常驻服务。

默认 ADB 转发设备 loopback HTTP；LAN 为显式开启的 TLS 连接，不在不可信 Wi-Fi 上明文发送 token 和代码。LAN 使用短期自签名证书，连接配置含 `sha256//base64(SHA256(SPKI))` 公钥 pin，通过 ADB 或用户控制的本地文件传递到电脑，不能从尚未认证的 LAN 连接取得信任根。配套脚本对该开发端点强制使用 `--insecure --pinnedpubkey` 的组合：端点认证来自带外 pin，不声称通过公共 CA/hostname 校验，绝不允许单独 `-k`。证书只服务开发连接，不能改变 WebView 对外站点的 TLS 校验。

连接由用户在目标实例卡片显式开启；配置只显示在当前应用库面板，不自动写入公共目录。监听、token 与闲置计时在面板返回后立即生效，Haminn 真正退出前台后必须重新开启连接。

开发客户端不得跟随重定向，必须检测当前 curl 的公钥固定能力；不支持则给出明确错误。pin 只用于初始端点，不能让重定向变成带凭据的无校验请求。[curl 公钥固定说明](https://curl.se/libcurl/c/CURLOPT_PINNEDPUBLICKEY.html)

嵌入式传输优先使用 NanoHTTPD 核心，经 DevTransport 薄适配；不使用其通用文件服务、目录列表、multipart 管理或默认无限线程。锁定源码/版本并审核请求长度、超时和 TLS 接口，边界测试未通过前不对 LAN 开放。这里选择的是成熟小型 HTTP 解析器加窄路由，并不假设库自带资源限制。[NanoHTTPD 官方源码与说明](https://github.com/NanoHttpd/nanohttpd)

| 接口 | 语义 |
| --- | --- |
| GET /v1/status | 仅返回此 token 绑定的目标、剩余期限、活动 release 和限额 |
| PUT /v1/apps/{appId}/release | 完整 ZIP；要求 Idempotency-Key、内容摘要及 expected release |
| GET /v1/operations/{operationId} | 仅查询当前目标的操作状态，区分 installed 与 reload 结果 |
| POST /v1/apps/{appId}/reload | 仅重开已获准目标，不扩大应用管理权限 |

token 至少 256 bit 熵，只存内存、单目标、空闲 15 分钟失效；不是应用库 JS 可随意读取的全局密钥。所有请求鉴权，拒绝浏览器 Origin 请求和跨站 CORS，检查 Host，错误不暴露文件路径。上传固定 Content-Length，禁止 chunked、multipart 和压缩 HTTP body；校验后再读 body，单上传、有限连接、持续计时。接入层限制解压后的 ZIP，不能只信请求头。部署网络中断查询幂等 operation，提交成功但 reload 失败明确分开返回。

```sh
# 以下为将来产品接口示例；token 从本地受限配置读取，不放入项目或日志。
adb forward tcp:8765 tcp:8765
curl --config "$HAMINN_CURL_CONFIG" \
  --request PUT --upload-file page.zip \
  --header "Content-Type: application/zip" \
  --header "Idempotency-Key: dev-build-104" \
  --header "X-Haminn-Expected-Release: release-103" \
  --header "X-Haminn-Content-SHA256: <page.zip 的实际 SHA-256>" \
  http://127.0.0.1:8765/v1/apps/<appId>/release
```

v1 传完整快照。未来若添加差分上传，只能改变 materialize 的传输成本，仍需产出完整 staging、验证与提交，不逐文件覆盖运行目录。

## 11. 备份、恢复与数据生命周期

应用库导出的是“应用配置与 Haminn 管理的数据”，可包含当前本地代码；不叫完整浏览器备份。包中包含格式版本、来源、用户展示配置、记录数据、附件和内容摘要。不导出系统权限、Haminn 敏感 grants、Cookie、WebStorage、临时 token、操作句柄或不可迁移 SAF 授权。用户恢复后重新授权并重新登录。

备份默认不加密，可能包含个人内容；导出确认应准确提示并使用系统文件选择器。密码加密属于后续独立协议，不自创弱加密。没有实现时不能宣传端到端加密。

页面也可以请求导出自己：`haminn.app.backup()` 只处理发起调用的实例（实例来自会话，参数不能指定 `appId`），先由系统文件选择器确定保存位置，再按与“应用库导出”完全相同的格式、容量上限和内容集合写出。它不因此获得管理接口，也不能读取或导出别的实例。

导出前暂停目标实例与写操作，读取一致的应用记录和文件清单。记录以受限 JSONL 等逻辑格式导出，避免把来自外部的任意 SQLite 文件直接作为宿主数据库打开。文件和代码流式归档并计算摘要。空间不足或中断不产生可导入的半完成备份。

恢复首先验证大小、结构、格式和摘要，再走统一 staging。默认恢复为新实例，生成新 appId 和 Web Profile；保留实例域内的 logicalFileId，使任意 JSON 中的附件引用仍成立，旧 appId 仅作来源元数据。短期 FileHandle/media URL 不得作为持久附件引用或进入备份；恢复后重新取得 handle，记录 revision 也按新 generation 重新产生。

替换已有实例数据必须由用户在应用库选择目标。数据导入新的 dataGeneration 后用 Registry 条件更新切换指针；对应 happ task 下次恢复时必须先核对 Registry，发现 dataGeneration、release、运行模式或信任版本变化便销毁旧会话并重建。所有旧写操作绑定原 generation，失败保持旧 generation，切换成功后才清理旧数据。开发连接在替换前关闭。

v1 提供两条明确路径：完整备份恢复会创建新实例、新 appId 和新 Web Profile；替换已有实例时只恢复 Haminn 记录与附件，保留目标来源、代码、appId 和桌面入口，同时提升 trustRevision、使旧敏感 grants 失效并关闭开发连接。v1 不提供“以备份完整覆盖现有实例代码和来源”的第三种隐式模式。

BackupValidator 与代码 PackageValidator 共享路径/流量校验底座，但有独立容量预算。默认备份支持 1 GiB 传输/展开、50,000 文件；用户提高数据配额后，导出前必须同步核验备份上限与预计峰值空间，必要时明确提高该次备份预算并写入头部。导入按声明量、用户批准的预算和本机容量预检，不能让本机合法导出的包仅因复用了代码 ZIP 上限而无法恢复。

测试设备的 APK 覆盖升级采用当前 schema 的干净重建，直接清理历史 Registry 字段和旧索引，不保留已废弃的 Base64 图标或兼容双轨；正式外部迁移若成为需求，另行设计一次性迁移工具。默认关闭 Android Auto Backup，并配置适用版本的数据提取规则；不同厂商设备迁移行为需实测，不能靠一个布尔配置保证完整排除。

## 12. 数据结构、执行模型与诊断

Registry 为单独系统 SQLite，不向公共 data API 开放。v1 核心表为 instances、releases、grants、system_permission_observations、operations、profile_cleanup 和预留的 diagnostic_events。SourceBinding 作为领域概念收敛存放在 instances 的 `source_adapter/source_spec` 字段，等出现多来源或 channel 的真实需求再拆表。实例保存 activeReleaseId 与 activeDataGeneration；release 表按 appId 外键归属；删除与清理任务在同一 Registry 事务记录。

```text
files/
  system/registry.sqlite
  instances/<appId>/
    releases/<releaseId>/web/
    staging/<operationId>/
    data/<generationId>/records.sqlite
    data/<generationId>/files/
  temporary/exports/
assets/
  store/
  bridge/haminn-v1.js
```

开外键、显式迁移、参数绑定；需要一致提交的 Registry 更新走单事务。业务数据库与 Registry 不跨连接伪装成一个事务。逻辑层限制记录/文件总量，真实磁盘写入仍处理 ENOSPC。

UI 线程只负责 WebView、系统 UI 和轻量消息验证；协程调度器承担 I/O，传输、解压、数据库、能力任务各有并发上限。RuntimeSession scope 管短生命周期操作；应用级 OperationCoordinator 管可查询的安装/备份任务，但不保证进程退出后继续执行。它靠持久日志恢复，不靠后台常驻。

初始资源预算如下，是防止失控的上限，不是实测性能承诺。集中配置，后续根据参考设备数据调整：

| 资源 | v1 初始上限 |
| --- | --- |
| manifest / RPC JSON | 64 KiB / 256 KiB；嵌套深度 16 |
| ZIP 传输 / 展开 / 单文件 / 文件数 | 64 MiB / 256 MiB / 64 MiB / 10,000；异常压缩比另拒绝 |
| 实例 Haminn 数据总量 | 逻辑文件最多 10,000 个，不设单文件或应用总量字节配额；记录 JSON 128 MiB / 100,000 条 |
| 单 JSON 记录 / scan / batch | 64 KiB；100 条且响应不超 RPC 上限；100 操作且请求不超 RPC 上限 |
| 普通 RPC / 瞬时队列 | 每文档最多 16 个待决请求；消息 256 KiB、JSON 嵌套 16 层，超出立即错误 |
| 分块文件写入 | 每块 64 KiB；不设单文件或应用总量字节配额；每会话 2 个、全局 4 个未完成句柄；闲置 2 分钟回收 |
| Native HTTP | 受 Bridge 在途上限约束；连接 15 秒、读写各 30 秒；内联 1 MiB，文件响应 64 MiB |
| 部署连接 / 上传 | 最多 4 执行线程、8 个排队连接、1 个上传；会话闲置 15 分钟，ZIP 64 MiB |
| 诊断 | 按需生成脱敏状态摘要；不保留页面正文、文件内容、token 或设备序列号 |

磁盘预检考虑 staging、新旧代码、数据与导出同时存在的峰值；不只检查 ZIP 压缩大小。压缩包拒绝绝对/穿越路径、链接、重复及规范化冲突路径、特殊文件、加密包、异常元数据和保留路径，不能只检测字符串 `../`。

诊断记录方法、实例、Origin、operationId、结果、耗时和字节数，不默认记录语音正文、文件内容、数据值、Cookie、请求体或 token。URL 去 userinfo/query/fragment。开发日志默认本地，导出前显示范围；产品不要求云遥测。

原生恢复 UI 覆盖 WebView 不可用、renderer 退出、活动目录缺失、Registry 迁移失败和权限变化。只重建故障会话，不删除数据“修复”；启动到恢复页仍须让用户能导出可读数据或诊断。

## 13. 扩展方式与质量合同

扩展只沿三条边界进行：SourceAdapter 增加输入来源；CapabilityDescriptor + Adapter 增加手机能力；Artifact materialize 增加传输策略。它们共享实例、授权、Operation、资源限制和诊断，不新增并行身份系统。原生扩展必须随签名 APK 发布，页面包不加载 dex、so 或任意反射类。任何新核心能力先证明在无 GMS、无海外网络和离线状态下能进入可用或明确降级终态；不能把“等待服务下载”当作本地能力。

新增云端账号、后台任务、多进程或全网络隔离时，需要对应的真实场景和单独架构决策。现阶段的“可扩展”指有稳定合同和替换点，不表示预先构建一个动态插件平台。

| 合同 | 不可违反的行为 |
| --- | --- |
| 身份 | 外部 appId、URL、manifest 或 handle 不能替换 Native 执行身份 |
| 管理边界 | 普通 Web App 和开发 token 均不能成为 Store 管理员 |
| 版本 | 未验证代码不激活；一个会话不混读两个 release |
| 授权 | 系统 grant 不自动变为全部 Web App 的敏感 grant |
| 数据 | 更新代码不清理业务数据；回滚代码不谎称回滚数据 |
| 隔离 | 不支持 Web Profile 隔离时不提供共享回退运行 |
| 国内可用性 | 核心链路不依赖 GMS、海外 CDN/账号/接口或运行时模块下载；海外来源仅可选 |
| 结果 | 每个有效调用有终态；提交成功与页面显示成功分别记录 |
| 恢复 | 失败可诊断、可重试；不以清空数据伪造恢复成功 |
| 交付 | APK、签名、测试、设备和限制有可核验记录 |

v1 用户界面以中文为主，文本资源集中可本地化；空状态、进度、失败、取消、拒绝均有可操作表达。应用库和原生对话框支持 TalkBack、字体放大、深浅色、边到边布局和键盘避让。Haminn 只保证自己 UI 的可访问性，不能替第三方页面自动修复。

参考设备分别记录：应用库/本地自包含页面的冷启动与首屏 p50/p95、Bridge 往返、20 MiB 包导入/部署、50 次实例切换的内存与句柄趋势。S1 建立数值基准，后续相同条件 p95 回退超过 20% 必须解释和修复或记录决定；没有基准前不编造绝对秒数或 APK 大小保证。

## 14. 重构决策与执行入口

| 原设计的主要问题 | 本版选择 | 获得的收益与明确代价 |
| --- | --- | --- |
| 各来源与 sourceType 混成多套流程 | 两种运行形态 + 一个本地安装事务 | 来源可扩展；GitHub 首版 archive 会下载非目标内容 |
| Java/少依赖被当成不可改合同 | Kotlin 协程 + 适量成熟库 | 减少手工状态与网络代码；增加明确锁定的依赖 |
| Web Profile 或新 Bridge feature 缺失 | 三级 Runtime 自动降级并显式标识 | Android 10/11 旧 provider 可直接运行；兼容模式牺牲网页数据与 Bridge 安全隔离 |
| Local 与 SW 各管理一套缓存 | Local 禁 SW，网关管理离线代码 | 更新可预测；SW 专用应用须适配 |
| 任意 SQL 加黑名单 | Native SQLite 支撑受限记录事务 API | 无 SQL 越界入口；首版无通用 SQL/全文查询 |
| 智能体开发开关误随页面或后台关闭 | 持久化开关与进程启动恢复 | 连续推送不因切页、锁屏、空闲或换网失效；地址变化明确提示 |
| 删除身份但“保留数据” | 明确备份 + 删除任务 | 恢复路径确定；首版不提供回收站 |
| 代码回滚/备份承诺过宽 | 代码、Haminn 数据、Web 数据分开定义 | 不夸大可恢复性；恢复后可能需重新登录 |
| 多 Origin bridge 与标准敏感 Web API 双入口 | 一个主 Origin，敏感能力统一 Haminn API | 权限合同收敛；部分站点需适配 |
| P0/P1/P2 只有功能清单 | 工作包、依赖、原型门禁、测试和交付证据 | 可以连续执行，不能靠阶段名称认定产品完成 |

下一步按 [开发计划](../plans/haminnapp-v1-development-plan.md) 从环境与架构验证进入实现。计划中的技术验证失败由执行者据实修正内部实现并同步合同；不能静默削弱隔离、数据或交付要求，也不因常规阶段完成反复要求用户批准。
