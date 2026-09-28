# HaminnApp happ 运行与通知能力统一开发计划

版本 2.1，2026-09-13。

本计划一次性交付 happ 安装实例、本地不可变版本、本地运行、线上实时运行、统一授权、系统通知、计划通知和后台通知同步。运行模型是通知能力的前置合同：HaminnApp 只有先准确确定当前实例、页面地址和标准 Web Origin，才能把 Bridge、Cookie、endpoint、数据与授权交给正确的 happ。

HaminnApp 是页面容器和 Native Bridge，不在后台运行 happ JavaScript，不允许 happ 创建常驻服务或定时执行任意代码。happ 对其代码、服务器、账号、令牌和业务内容负责；HaminnApp 只保障自己的实例隔离、用户授权、文件完整性、请求边界和系统功能调用。

## 一、统一实例与能力模型

每个加入 HaminnApp 的 happ 都先获得一个本机安装实例和专属目录，不再用“本地来源/线上来源”决定能力。来源只作为记录；是否具备某项能力由实际字段决定：

- 有本地 release 才能本地运行。
- 有 `liveUrl` 才能线上实时运行。
- 有 `updateUrl` 才能检查一键更新。
- 有 `downloadUrl` 才能从原地址重新下载安装。
- 在有效 Bridge 会话中登记过 endpoint，才能后台同步通知。

`runtimeMode` 只接受 `local` 或 `live`。安装包、Git、HTTP(S)、局域网或手机文件只是取得代码的方式，不能再推导运行方式、Origin、更新能力或通知资格。

实例身份收敛为两层：

- `happId` 是 `haminn.json` 声明的稳定包标识，例如 `com.example.clock`，不等于显示名称。
- `instanceId` 是 HaminnApp 为本机安装生成的 UUID，是数据、文件、grant、通知和运行会话的唯一归属键。现有内部 `appId` 迁移为这一语义，公开文档统一称 `instanceId`。

同一个 `happId` 可以在用户明确选择“全新安装”时产生多个 `instanceId`，实例之间仍完全隔离。

## 二、URL 与 Origin 的最小合同

只保存有独立业务含义的 URL，不保存可手填的 `origin`、`onlineOrigin` 或 `primaryOrigin`：

| 字段 | 规则 |
| --- | --- |
| `downloadUrl` | HaminnApp 实际下载安装包或 Git 仓库的地址，只读保存为来源记录，可用于重新安装；不等同于更新地址。 |
| `updateUrl` | 可选的最新版本查询或安装包地址，安装包可建议，用户可修改；Git 地址可在缺省时成为建议值。 |
| `liveUrl` | 实例唯一的网络页面入口，同时作为本地 release 的虚拟挂载入口。安装包只提供初始建议，用户确认后可以新增、修改或删除；缺失即为纯本地 happ，不支持实时运行和页面网络服务。 |
| `endpoint` | happ 通过通知接口登记的后台同步地址，不属于安装清单。 |

`haminn.json` 中的 `liveUrl` 只在首次安装时复制为实例初值，后续包版本不得静默覆盖用户当前值；包内原值随只读 release 保留，仅供用户比较并选择采用。实例不再保存第二份 `baseUrl`。

所有网络 URL 必须是规范化后的绝对 HTTP(S) URL。Origin 不是配置字段，始终在使用时按浏览器规则从 URL 计算，即 `scheme + host + effective port`；路径不属于 Origin，父域也不能合并子域。HTTP 与 HTTPS、不同主机或不同有效端口均为不同 Origin。界面可以只读显示计算结果，但不能单独编辑 Origin；用户要改变 Origin，必须修改完整 `liveUrl`。

公网域名、公网 IP 和局域网 IP 都可以使用；拒绝 `localhost`、`.localhost`、`127.0.0.0/8`、`::1` 和未指定地址。下载地址的 Origin 不自动成为页面或通知 Origin。

版本字段仅表达事实：`downloadVersion` 是原下载地址所安装的版本，`localVersion` 是当前激活 release 的版本，`latestVersion` 来自 `updateUrl`。缺失时保持未知，不能相互猜测。

## 三、发布清单与本机实例字段

### 3.1 包内 `haminn.json`

`haminn.json` 只声明这一份静态发布包是什么、从哪个本地文件启动，以及开发者建议使用哪些网络入口。它不能声明本机实例、用户选择、权限、收藏、通知或运行状态。

目标格式使用严格的 `schema: 2`：

```json
{
  "schema": 2,
  "happId": "com.example.voicenotes",
  "name": "语音笔记",
  "version": { "code": 104, "name": "1.4.0" },
  "entry": "index.html",
  "routing": "hash",
  "icon": "assets/icon.png",
  "liveUrl": "https://example.com/notes/",
  "updateUrl": "https://example.com/notes/latest.json"
}
```

字段合同保持最小：

| 字段 | 要求与职责 |
| --- | --- |
| `schema` | 必填整数；新格式固定为 `2`，APK 对 schema 1 只做兼容读取。 |
| `happId` | schema 2 必填；小写 ASCII 点分标识，安装后作为包身份，不因显示名称或 URL 改变。 |
| `name` | 必填的人类可读默认名称；只作为首次安装建议，实例显示名称可由用户修改。 |
| `version` | 必填；`code` 为正数、同一发布链单调递增的整数，`name` 只用于显示。 |
| `entry` | 可选安全相对路径，默认 `index.html`；必须存在，禁止绝对路径、反斜杠、路径穿越和 `__haminn/`。 |
| `routing` | 可选，默认 `hash`；只接受 `hash` 或 `history`。 |
| `icon` | 可选的包内相对图片路径；遵守与 `entry` 相同的路径边界，并限制格式和大小。 |
| `liveUrl` | 可选绝对 HTTP(S) 入口，是实例首次安装的建议值；缺失表示开发者发布的是纯本地 happ。 |
| `updateUrl` | 可选绝对 HTTP(S) 更新地址，是实例首次安装的建议值；不等于下载地址。 |

schema 2 的 `happId/name/version` 必填，是为了让正式发布包具有稳定身份和可比较版本；`happId` 只是发布者声明的命名空间，不证明其拥有同名域名。仍允许完全没有 `haminn.json` 的极简目录按 `index.html` 安装，但它属于匿名 legacy 包，没有自动识别同一 happ、自动恢复历史实例或比较版本的保证。支持的 schema 内继续拒绝未知字段并指出字段名，避免拼写错误被静默忽略。

安装时发现相同 `happId`，只能把它作为“可能是同一 happ”的候选：发布者公钥一致且签名有效时可以推荐更新或恢复；未签名、签名缺失或公钥不同都不能仅凭 `happId` 自动合并，必须让用户选择更新覆盖或全新安装。

以下内容明确不进入 `haminn.json`：`instanceId`、`downloadUrl`、`runtimeMode`、Origin、`localUrl`、`activeReleaseId`、`treeHash`、`latestVersion`、权限、通知 endpoint、Cookie、用户数据和任何 Android 系统状态。`downloadUrl` 必须由实际执行下载的 Native 记录；Origin 与 `localUrl` 必须由运行时计算；hash 必须由安装器对实际展开文件计算。

可选开发者签名放在包根保留文件 `haminn.sig`，不嵌入 `haminn.json`。`treeHash` 覆盖包括 `haminn.json` 在内的全部发布文件但排除 `haminn.sig`；签名内容只使用固定域标识和 `treeHash`，`haminn.sig` 只保存算法、公钥和签名。这样避免 hash 与签名互相包含形成循环，也不让签名参与权限判断。

### 3.2 APK 内的 `HappInstance`

`HappInstance` 是这台设备上的长期用户对象，不是安装清单的副本。以下是核心逻辑字段；SQLite 可以使用等价列存储，不要求保存嵌套 JSON：

| 字段 | 来源与规则 |
| --- | --- |
| `instanceId` | Native 生成 UUID，主键；页面、包和用户均不能指定。 |
| `happId` | 从 schema 2 清单复制；匿名 legacy 包为 `null`。同一实例内不得静默改变。 |
| `displayName` | 首次取清单 `name` 或导入名称，此后属于用户设置，包更新不得覆盖。 |
| `publisherKeyId` | 可选，由已验证签名公钥计算指纹；只表示发布连续性。 |
| `runtimeMode` | `local` 或 `live`；前者要求 `activeReleaseId`，后者要求 `liveUrl`。 |
| `liveUrl` | 可空的实例当前网络入口；首次取清单建议，此后由用户控制。为空即采用纯本地 `localUrl`。 |
| `activeReleaseId` | 可空，指向当前不可变 `CodeRelease`；为空即不能本地运行。 |
| `downloadUrl` | 可空，Native 如实记录最近一次“从原地址下载安装”所用地址。 |
| `downloadVersion` | 可空，记录上述下载成功时包的 `{code,name}`；不随其他更新方式改变。 |
| `updateUrl` | 可空，首次取清单建议，此后由用户控制；只用于查询最新版本。 |
| `dataGenerationId` | Native 生成，标识当前实例数据代际，代码更新和运行方式切换不改变。 |
| `favorite` | 用户收藏状态。 |
| `developerEnabled` | 用户是否允许该实例接受开发部署。 |
| `notificationEnabled` | 用户的逐 happ 通知总开关。 |
| `allowCrossOriginNetwork` | 用户是否允许主动跨 Origin 网络请求，默认关闭。 |
| `state` | 只接受 `ready / archived / deleting`；安装中的临时状态属于 `Operation`。 |
| `createdAt/updatedAt` | Native 维护的审计时间。 |

`ready` 实例必须至少有 `activeReleaseId` 或 `liveUrl`；只有 release 时强制本地运行，只有 `liveUrl` 时强制实时运行，两者都有时才允许用户切换。删除正在使用的 `liveUrl` 时，有 release 就先切回本地运行，没有 release 则必须先归档或安装本地代码，不能留下一个看似可启动却无入口的 ready 实例。

以下值只计算、不作为实例真相重复存储：

- `localUrl`：无 `liveUrl` 时由 `instanceId` 生成。
- `runtimeUrl/runtimeOrigin`：由 `runtimeMode`、`liveUrl`、本地挂载和当前会话计算。
- `localAvailable/liveAvailable`：分别由 `activeReleaseId`、`liveUrl` 是否存在计算。
- `localVersion`：从 `activeReleaseId` 对应 release 读取。
- `latestVersion`：由 `updateUrl` 最近一次检查结果缓存，过期或失败时可以未知。
- `icon`：优先从当前 release 的清单路径读取；无本地 release 时再使用受控的页面图标缓存。

通知 endpoint、cursor 和同步时间属于 `NotificationSync`；权限决定属于 `CapabilityGrant`；最新版本查询缓存属于 `UpdateStatus`；安装与更新过程属于 `Operation`。它们都以 `instanceId` 外键关联，不塞进实例主记录。旧字段 `mode`、`startUrl`、`primaryOrigin`、`onlineOrigin`、`trustRevision`、`webProfileName` 不再作为产品事实：需要兼容时只做派生输出或一次性迁移。

`CodeRelease` 保持为独立不可变记录，核心字段只有 `releaseId/instanceId/treeHash/version/entryPath/routing/provenance/sourceRevision/createdAt`。包内 `haminn.json` 随 release 原样保留，实例仅复制用户需要长期控制的 `liveUrl` 与 `updateUrl` 初值，不复制整份清单。

## 四、本地 release 与文件生命周期

每个实例使用统一目录：

```text
instances/<instanceId>/
  releases/<releaseId>/web/   # 已验证、不可变的页面代码
  data/                       # happ 可写文件；业务数据仍优先进入实例数据库
```

当前 release 指针、URL 配置、授权和版本元数据保存在 Native 数据库，不写回 `web/`。安装、ZIP 更新或 Git 更新都先解压到临时目录，完成路径安全检查并计算规范文件树 `treeHash`，再以 Native UUID `releaseId` 原子移动为 release；同一实例用 `instanceId + treeHash` 去重，避免不同实例的相同内容争用一个 release 身份。激活后文件设为只读，Bridge 不提供修改 release 的接口。

更新永远生成新 release，再原子切换当前指针；至少保留当前版和前一版用于回退，其余按存储策略清理。hash 只证明本地包内文件完整，不证明开发者身份，也不覆盖页面主动加载的远程内容。

“卸载但保留数据”删除代码并归档实例，暂停通知和任务，但保留 `instanceId`、数据、grant 与配置；相同 `happId + 发布者公钥` 的已签名包可自动恢复唯一历史实例，未签名包必须由用户手工选择并确认。“删除应用及全部数据”才级联清理且不可恢复。若整个 HaminnApp 被卸载或清除数据，只能通过备份恢复，Android 系统权限仍需按系统状态重新取得。

## 五、本地运行：统一 URL 挂载

本地运行不使用 `file://`，也不靠注入 `<base>`、改写 `fetch` 或字符串替换猜测远程地址。

有 `liveUrl` 时，本地运行与实时运行使用同一个页面地址。例如：

```text
liveUrl = https://example.com/app/
entry   = index.html
本地与实时页面地址 = https://example.com/app/
```

Native 将 `liveUrl` 的主请求映射到本地 `entry`，并按浏览器标准 URL 目录解析其余相对路径，在这个真实 URL 空间上挂载当前只读 release：

1. URL 位于挂载路径内且对应本地已有 GET/HEAD 文件时，返回该 release 文件。
2. 主 frame 的 SPA 路由可按 `routing=history` 回退到本地 `index.html`。
3. 本地没有对应文件，或请求为 POST 等动态请求时，交给 WebView 访问真实网络。
4. 本地文件与远程同路径资源冲突时，本地文件优先。因此 `./app.js` 可稳定读取本地文件，而 `/api/getinfo` 在包内无同路径文件时自然访问 `https://example.com/api/getinfo`。

这套“本地文件覆盖远程 URL”规则让 HTML、CSS、模块、图片和 API 按浏览器标准解析相对地址，不创建第二套路由语法。

没有 `liveUrl` 时，Native 仅在运行期生成 `localUrl=https://<instanceId>.apps.haminn.invalid/`，把本地 `entry` 和 release 挂载到该地址；`localUrl` 是内部派生值，不写入 `liveUrl`，也不代表实时运行能力。此类纯本地 happ 的页面网络访问全部关闭，相对 URL 只能读取 release；它仍可使用 Bridge 数据库、文件、通知等已授权能力，HaminnApp 也仍可通过实例的 `downloadUrl` 或 `updateUrl` 更新代码。

本地 Bridge 不只绑定 Origin，还绑定 `instanceId + releaseId + 挂载路径 + 主文档来源`。只有确认由当前 release 返回的主 frame 才能获得 Bridge；顶层页面转为远程 HTML、跳出挂载路径、跨 Origin 跳转或会话结束时立即撤销。Local 模式禁用 Service Worker，防止本地包在真实域名下留下超出 release 生命周期的持久控制逻辑。

用户新增或修改 `liveUrl`，表示同意本地代码作为其标准 Origin 下的页面运行。这不仅允许向该域名发送数据，也可能读取 Haminn WebView 内该 Origin 已有的 Cookie/Web Storage，并发起携带登录状态的请求；设置界面必须完整说明。修改路径但 Origin 不变时只重载页面；新增、删除或改变 Origin 时关闭现有会话，Native 实例数据和普通 grant 保留，但旧 endpoint 与 Origin 范围授权暂停，等待重新登记或确认。删除 `liveUrl` 后实例立即回到纯本地规则。

## 六、线上实时运行与跨域策略

线上实时运行直接加载实例当前 `liveUrl`。加载失败时显示 Native 友好失败页，明确指示用户回到 HaminnApp 关闭该 happ 的实时运行模式；不得悄悄切换 Origin 或执行远程回退代码。

Bridge 只在主 frame 的实际 Origin 与当前 `liveUrl` Origin 完全一致时启用；跨 Origin 重定向、导航或 iframe 均不能继承。安装包或新 release 中不同的 `liveUrl` 只是开发者建议，必须由用户明确采用；不能绕过实例设置自动改变运行 Origin。

有 `liveUrl` 时，本地运行和实时运行复用同一 URL 与标准 Origin，也复用同一 `instanceId`，因此自然共享 Cookie、localStorage、IndexedDB 以及 HaminnApp 的 Native 数据和 grant。纯本地 `localUrl` 只拥有自己的 Web 数据。不同 happ 即使网页同源，也不能因此访问对方按 `instanceId` 隔离的 Native 数据或权限。

纯本地 happ 的“禁止页面网络”优先于一切跨域例外。有 `liveUrl` 的 happ 才进入通用网络策略：当前运行 Origin 内的请求正常执行；跨 Origin 的图片、CSS、字体和音视频作为被动资源放行并记录目标域，CSS 引入的后续资源继续逐项检查。跨 Origin 的脚本、iframe、Worker、fetch/XHR、WebSocket、表单和顶层导航默认先阻止；用户为该 happ 开启“允许跨域网络”后才放行，并持续显示状态提示。该策略必须覆盖重定向和 Service Worker 请求，不能先泄露请求再弹提示。

## 七、统一授权模型

能力是否可执行由三项共同决定：

```text
该 instanceId 的持久 grant
    + Android 当前真实系统权限或特殊访问状态
    + 当前会话、Origin 和资源范围条件
    = 本次调用结果
```

happ grant 只保存 `ask / allow / deny`，按 `instanceId + capability + resourceScope` 持久化，不提供自创的“仅本次”。Android 的“仅本次、使用时、始终允许”等生命周期由系统决定；HaminnApp 每次调用都实时检查，不把系统状态复制成另一套永久状态。

首次请求遵循一条流程：已有 `allow` 时检查系统状态后执行；已有 `deny` 时返回拒绝并引导到详情页；`ask` 且系统权限缺失时先请求系统，成功后记录 `allow`；APK 已有系统权限或能力无需系统权限时，由 HaminnApp 显示一次原生 happ 授权确认。系统回调必须核对原 `instanceId`、session 与 document epoch。

HaminnUI 可随时关闭某个 happ 的 grant，但不替用户撤销 APK 的系统权限。系统权限被撤销也不删除 happ 的允许意图；下次调用重新走系统授权。Origin 范围能力只对已确认的精确 Origin 有效。

## 八、通知接口与本地计划

通知是统一能力 `notifications`。任何有效实例都可申请即时通知和计划通知，不再检查来源类型。公开方法收敛为：

```text
notify / schedule / cancel / cancelAll / getScheduled / setEndpoint / getStatus
```

通知 `id` 在单个 `instanceId` 内唯一，同 id 再次提交即更新。通知数据统一为 `NotificationSpec`；后台只保存和投递数据，不加载页面或解释业务字段。点击通知后打开对应实例，Bridge 就绪后发送 `notifications.opened`。

计划支持单次、每日、每周、每月、每年，使用设备系统时间和当前系统时区。单次过期在重启恢复时补发一次；循环任务跳过历史次数并计算下一次。Android 12+ 使用用户授予的“闹钟和提醒”特殊访问执行精确 Alarm；缺失时明确显示不可调度，不用可能延迟很久的非精确 Alarm 冒充准时。重启、覆盖升级、时间或时区变化后统一重建。

Android 8+ 每个实例只建立一个稳定通知渠道；Android 13+ 在首次实际需要时请求 `POST_NOTIFICATIONS`。HaminnUI 的逐 happ 通知开关默认关闭，收藏与通知无关；关闭后暂停该实例的计划和后台同步，重新开启后按现有数据恢复。

## 九、后台通知同步

`setEndpoint` 只在有效 Bridge 会话中执行。相对 endpoint 按当前页面 URL 解析，绝对 endpoint 必须与当前 `runtimeOrigin` 严格同源；Native 保存规范 endpoint 及 `endpointOrigin`。因此本地挂载和实时页面使用同一规则，不需要 `source=ONLINE`、`onlineOrigin` 或来源适配器特判。

没有 `liveUrl` 的纯本地实例不能登记远程 endpoint。endpoint 可在同一 Origin 内更换路径；`liveUrl` Origin 改变时必须从新 Origin 的有效会话重新登记。切换 `runtimeMode` 不删除已有 endpoint；只要其注册 Origin 与配置仍有效，后台同步继续运行。

HaminnApp 使用一个全局 WorkManager 周期任务统一同步，正常周期为 15 分钟，并接受 Android 调度延迟；应用回到前台、网络恢复或用户手工刷新时可补做一次。不使用一分钟 Alarm、常驻 WebSocket、前台服务或依赖 GMS 的 FCM，不承诺实时推送。

请求使用 GET，支持 HTTP、HTTPS、域名、公网 IP 和局域网 IP，继续拒绝本机回环。每次只从 `endpointOrigin` 的 WebView Cookie 中取名为 `notify-token` 的字段并作为 Cookie 发送；HaminnApp 不解释、改写或评价其内容，HTTP 明文风险由用户选择承担。响应只允许统一通知数据、取消 id、cursor 和 ETag，不得要求执行代码或调用其他能力。

## 十、实现结构与迁移

运行层增加统一的 URL 挂载和策略组件，负责 `liveUrl/localUrl` 映射、release 文件响应、主文档来源、导航边界和跨域决定；Bridge 只接收 Native 已确认的 `RuntimeSession`，不能相信页面提交的 `instanceId`、Origin 或 release 信息。现代 `WEB_MESSAGE_LISTENER` 模式校验主 frame 和实际 Origin；旧 `JavascriptInterface` 无法达到同等 iframe 隔离，必须在 HaminnUI 标记为兼容弱隔离模式，高敏感能力不得宣称完整 Origin 保证。

通知层保持四个职责：`NotificationRepository` 保存计划、endpoint、cursor 和投递状态；`NotificationScheduler` 注册 Alarm；`NotificationDispatcher` 管理渠道和系统通知；`OnlineNotificationWorker` 拉取并交给统一校验器。所有入口共用 `PermissionBroker`、`AppRegistry` 和同一 `NotificationSpec`。

数据迁移取消以 `source` 判断 live、update 或通知能力，`source` 最多保留为旧数据的来源说明；移除权限判断中的 `onlineOrigin`、`primaryOrigin` 和旧 `mode`。现有 `appId` 迁移为稳定 `instanceId`，`trustRevision` 变化不再自动删除普通 grant。旧实例有 `liveUrl` 时直接作为实例当前值；没有时按纯本地规则生成临时 `localUrl`，不猜测网络地址。

## 十一、一次性交付顺序

1. 冻结 `haminn.json` schema 2，迁移实例、URL、release、版本和归档数据结构，落地不可变 release 与回退指针。
2. 实现本地虚拟 URL 挂载、真实相对路径解析、主文档来源约束、实时运行失败页和跨域策略。
3. 重构统一 grant 与系统授权流程，并完成 Origin 范围授权失效规则。
4. 实现即时通知、通知渠道、点击回传、五种计划和精确 Alarm 恢复。
5. 实现同源 endpoint、`notify-token`、15 分钟统一 Worker、cursor/ETag 和手工同步。
6. 完成 HaminnUI 的实例、运行方式、`liveUrl`、更新、回退、通知和授权管理，同步 SDK、能力目录和作者文档。
7. 统一完成自动检查与目标设备验收后构建 APK 并覆盖安装，不交付只完成部分链路的中间版本。

## 十二、完成标准

清单与实例验收必须覆盖：无清单极简目录、schema 1 兼容、schema 2 必填与可选字段、未知字段、非法路径和非法 URL；相同/不同 `happId` 与发布者公钥组合不会错误合并；包不能指定 `instanceId`、Origin、下载来源或权限；实例不重复保存 `startUrl/primaryOrigin/localVersion/latestVersion` 等派生事实；`runtimeMode`、`activeReleaseId`、`liveUrl` 和 `state` 的组合始终满足可运行约束。

运行验收必须覆盖：不同 `liveUrl` 取得正确标准 Origin；`./`、`../`、根路径、CSS URL、模块和 SPA 路由读取正确本地文件；GET 缺失文件、`/api` 和 POST 到达真实服务器；本地文件覆盖同路径远程资源；没有 `liveUrl` 时生成稳定内部 `localUrl` 并阻止页面网络访问；顶层远程 HTML、越过挂载路径和跨 Origin 后 Bridge 失效；修改 `liveUrl` Origin 后旧 endpoint 与范围授权暂停；有 `liveUrl` 的本地与实时模式共享网页 Origin，但 Native 数据始终按 `instanceId` 隔离。

安装验收必须覆盖：ZIP/Git/HTTP(S)/手机文件最终进入同一 release 结构；hash 不符拒绝激活；当前版与前一版切换；代码目录不可由 happ 修改；重新下载、检查更新和实时运行互不混用 URL；保留数据卸载后可恢复原实例，彻底删除后不可恢复。

授权和通知验收必须覆盖：两个实例分别取得 grant；APK 已有或缺失系统权限时流程正确；HaminnUI 撤销后页面不能自行恢复；即时、单次、每日、每周、每月、每年、替换、取消、点击、重启和时间变化；HTTP/HTTPS、公网/局域网、同源 endpoint、跨 Origin 拒绝、回环拒绝、token 有无、200/204/304、cursor、重复 id、离线与超限响应；无论当前本地还是实时运行，已登记且仍有效的 endpoint 均可后台同步。

实体设备至少覆盖现有荣耀 Android 11 和一台 Android 13+ 设备。验证 WebView 现代与兼容 Bridge 模式、通知权限、通知渠道、精确提醒、重启恢复和 15 分钟后台同步；系统或厂商调度造成的误差如实记录，不用常驻服务规避。

参考：[Android 运行时权限](https://developer.android.com/training/permissions/requesting)、[通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)、[通知渠道](https://developer.android.com/develop/ui/views/notifications/channels)、[AlarmManager](https://developer.android.com/develop/background-work/services/alarms)、[WorkManager 周期任务](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)、[WebView 请求拦截](https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest))。
