# 历史归档：v0.4.1

仅保留决策历史，不作为当前开发合同；当前方案见 [统一设计](../haminnapp-product-technical-design.md)。

# HaminnApp 统一产品与技术设计

版本：0.4.1
状态：已确认的实现基线（不代表已经实现）
平台：Android 8.0 / API 26 及以上
更新时间：2026-09-10

## 0. 文档目的

本文把以下两份输入收敛为一套可以指导开发、测试和发布的产品与技术设计：

- `deep-research-report (9).md`：确立极简原生 WebView Runtime、Origin-aware Bridge、Android 权限代理、SAF 与 Native SQLite 等基础方向。
- `haminnapp_补充升级设计_v0.3.md`：把单入口壳升级为“一份 APK 安装并运行多个 Web App”，引入内置 Store、App Profile、Pinned Shortcut、安装与更新协议。

本文只把两份输入当作候选方案，不把其中的建议当作已实现事实。当前代码仓库为空，所有“必须”“应当”均表示后续实现合同。

本文同时修正几项会影响可行性或长期正确性的设计：

1. `appId` 使用 Haminn 生成的随机稳定 ID，不直接信任网页声明的 ID，也不把可变化的来源 URL 当主键。
2. 每次切换 App Profile 都销毁并重建 WebView，确保 Profile、Bridge 和 Origin 规则在首次加载前绑定。
3. 本地更新使用“不可变版本目录 + 数据库中的活动版本指针”，不依赖覆盖 `current/` 目录的伪原子操作。
4. 权限采用“双层有效授权”：Haminn 记录并实时核验自身获得的 Android 系统权限，同时独立管理每个 Web App 的 Haminn 能力授权。只有两层同时满足，Web App 才获得真实能力。
5. `native fetch`、SQLite 和文件接口设定明确边界，不能因为页面已安装就允许其任意访问宿主进程中的其他数据或网络。
6. Developer Deploy 默认优先使用 ADB 端口转发；局域网监听是显式开启的开发选项，不作为后台服务常驻。

### 0.1 统一术语

本文统一使用以下术语，代码标识符和 API 命名除外：

| 可能出现的说法 | 统一含义 | 本文首选写法 |
| --- | --- | --- |
| `Haminn`、`HaminnApp`、`haminnapp`、容器、shell、壳 | 完整的 Haminn APK 应用，包括 Store、Registry、Runtime、Bridge 和 Native Capability | `Haminn`；产品全名使用 `HaminnApp` |
| app、Web App、webapp、页面应用、寄居应用 | 安装或寄居在 Haminn 中运行的页面应用 | `Web App` |
| Store | Haminn APK 内置的可信管理页面应用 | `Haminn Store` 或 `Store` |
| Runtime | Haminn 内负责运行当前 Web App 的宿主组件 | `Haminn Runtime` 或 `Runtime` |
| App Profile | Haminn 为某个 Web App 保存的身份、来源、版本、权限和数据命名空间 | `App Profile` |

“App”单独出现时容易与 Android APK 混淆，因此正文原则上使用“Web App”；`appId`、`haminn.app.*` 等已经冻结的代码术语保持不变。

### 0.2 已确认的产品决策

- Android application ID 与 Java namespace 固定为 `io.github.zhyuzh3d.haminn`；
- 本地目录采用导入快照，不依赖原目录长期存在；
- 任意 local-delivery Web App 都可以成为稳定的 Developer Deploy 目标，外部工具通过 `appId` 更新；
- Web App 的有效能力必须同时满足 Haminn 对该 Web App 的授权和 Android 对 Haminn APK 的系统授权；
- P0、P1、P2 是连续开发中的内部质量门禁，不是等待用户逐阶段确认的暂停点；最终交付目标是产品级 APK 与完整验证产物。

## 1. 产品定义

### 1.1 一句话定义

**HaminnApp 是一个极简 Android Web App Runtime 与安装器：把 URL、本地 Web 产物或 GitHub 中的静态 Web 目录变成桌面入口，并通过稳定的 JavaScript API 为其提供 Android 原生能力。**

### 1.2 产品公式

```text
HaminnApp
= Haminn Store
+ Web App Registry
+ Haminn Runtime
+ Origin-aware Native Bridge
+ Native Capability Adapters
+ Android Pinned Shortcuts
```

用户在桌面上看到多个 Web App 图标；Android 实际只安装一个 Haminn APK。每个图标都启动同一个 Activity，但携带不同的 `appId`。Haminn 主图标永远进入内置 Store。

### 1.3 Haminn 不是什么

Haminn 不是：

- 带地址栏、书签和标签页的通用浏览器；
- 要求站点先满足 PWA 规范的 PWA 容器；
- 为每个网页生成独立 APK 的打包器；
- 在手机上执行 `npm install`、前端构建或 Git 合并的 CI；
- 任意网站的递归离线镜像器；
- Electron 式自带 Chromium 的运行时；
- 能提供真正 Android UID 级多应用隔离的系统沙箱；
- 第一阶段的跨 Android/iOS 框架。

### 1.4 核心价值

Haminn 的价值不是“显示网页”，而是同时提供：

- 任意 Web 入口到桌面应用入口的转换；
- 页面代码和 Android APK 的解耦；
- HTTP、IP、本地静态产物等普通 PWA 难以覆盖的运行场景；
- 版本化、可探测的 Android Native API；
- 按 App Profile 组织的浏览数据、Native 数据和调用记录；
- 不重建 APK 的网页开发、部署与更新闭环。

## 2. 目标用户与首要场景

### 2.1 目标用户

- 用 AI 或低代码工具快速生成 Web App，希望直接在 Android 桌面使用的人；
- 维护自托管、局域网、家庭自动化或设备控制页面的开发者；
- 希望用静态 Web 技术调用 TTS、语音识别、定位、文件和 SQLite 的个人开发者；
- 希望 APK 低频升级，而业务页面可以高频迭代的团队；
- 需要把 GitHub 中已构建静态产物快速安装到手机的人。

### 2.2 首要使用场景

1. 粘贴 `https://example.com/app/`，创建桌面入口并远程运行。
2. 粘贴 `http://192.168.1.20:8080`，作为局域网控制台运行。
3. 选择包含 `index.html` 的本地目录或 ZIP，导入后离线运行。
4. 输入公开 GitHub 仓库及目录，下载已构建的静态产物后运行。
5. 开发工具通过 ADB 或临时局域网 Deploy Server 推送 `dist.zip`，原子切换并刷新。
6. Web App 通过 `window.haminn` 使用 Native SQLite、TTS、STT、定位、文件等能力。

### 2.3 可衡量的产品成功条件

Haminn 的首要指标不是“支持多少 API”，而是主链是否可靠：

- 一个从未接触 Haminn 的用户，可以只经过“输入来源—确认候选—确认系统快捷方式”完成首个 Remote App 安装；
- Store、Remote App、Local App 在离线、进程重启和 APK 覆盖升级后都能按既有 Profile 恢复；
- 任一安装或更新步骤失败时，不破坏上一个可运行版本和用户数据；
- 自动化负面测试中，iframe、错误 Origin、旧 session 和其他 appId 对 Native API 或 Native 数据的越权成功数必须为零；
- 所有 Native 调用都能得到结构化成功或错误响应，不以无响应作为正常失败语义；
- 参考设备上的 WebView 创建、首屏、Bridge 往返、安装和更新耗时需要建立版本基准；指标以实测分位数记录，不提前承诺脱离设备环境的固定秒数；
- 每个降级事实都可见，例如 Launcher 不支持固定快捷方式、Multi-Profile 不可用、WebView 过旧或系统服务缺失。

## 3. 统一产品模型

### 3.1 三个固定组成部分

#### Haminn Store

Store 是 APK 内置、不可被第三方页面替换的管理 Web App，固定运行于：

```text
https://store.haminn.invalid/
```

Store 负责安装、重装、卸载、更新、快捷方式、权限设置、数据管理、审计查看和开发连接。只有 Store Origin 可以获得 `haminn.host.*` 管理 API。

#### Haminn Runtime

Runtime 负责为当前 App Profile 创建 WebView、绑定浏览 Profile、注册 Bridge、加载入口、调度 Native Capability，并处理 Activity 生命周期。普通 Web App 只获得 `haminn.*` 公共 Runtime API。

#### Web App Registry

Registry 是 Haminn 的真实控制面数据源，保存 App Profile、来源、安装版本、活动代码版本、快捷方式、能力策略和状态。桌面快捷方式只保存 `appId`，不保存 URL 或目录路径。

### 3.2 一个 APK，多桌面入口

```text
Haminn 主图标 ───────────────→ Store

Web App Shortcut(appId=A) ──→ Runtime ──→ Web App A
Web App Shortcut(appId=B) ──→ Runtime ──→ Web App B
Web App Shortcut(appId=C) ──→ Runtime ──→ Web App C
```

Pinned Shortcut 是入口，不是独立 APK。各 Web App 共享同一个 Android package、UID 和系统权限；Haminn 只能在自身内部提供逻辑隔离，不能宣称实现了 Android 进程或 UID 级隔离。

## 4. 核心概念与身份规则

### 4.1 App Profile

App Profile 是 Haminn 中一个已安装 Web App 的稳定记录。

| 字段 | 含义 | 规则 |
| --- | --- | --- |
| `appId` | Haminn 内部身份 | 安装时生成 UUID/ULID，之后不变 |
| `publisherId` | 来源声明的逻辑 ID | 可选、不可作为数据库主键 |
| `name` / `shortName` | 展示名称 | manifest、页面元数据或来源推断 |
| `sourceType` | 首次安装或长期上游来源 | `remote`、`package`、`github`、`local-import`、`push` |
| `sourceSpec` | 可复现的来源参数 | URL 或 repo/ref/path 等结构化数据 |
| `deliveryMode` | 运行时交付方式 | `remote` 或 `local` |
| `startUrl` | 当前启动 URL | Remote 真实 URL；Local 虚拟 URL |
| `primaryOrigin` | Bridge 主 Origin | 标准化的 scheme + host + effective port |
| `activeVersionId` | 当前本地代码版本 | Remote 为空 |
| `activeProvenance` | 当前代码版本的产生方式 | `package`、`github`、`local-import`、`deploy` |
| `developerDeployEnabled` | 是否允许外部开发部署 | 默认关闭，由 Store 对具体 Web App 开启 |
| `webProfileName` | WebView Profile 名 | 从 `appId` 派生，不由网页指定 |
| `shortcutId` | Launcher Shortcut ID | 从 `appId` 派生，稳定不变 |
| `state` | 生命周期状态 | `installing`、`ready`、`broken`、`removing` |

### 4.2 为什么 appId 不由来源或 manifest 决定

同一来源可能需要安装两个实例；来源 URL、branch 或目录可能改变；第三方 manifest 也可能伪造另一个应用的 ID。因此：

- `appId` 由 Haminn 随机生成；
- manifest 的 `id` 只记录为 `publisherId`；
- 重装时由用户选择“替换现有实例”或“安装新实例”；
- 更新只沿已绑定的 update channel 更新，不根据第三方 `id` 自动接管其他 Profile。

### 4.3 Source、Delivery 与 Origin 必须分离

```text
source       = 文件或页面从哪里来
deliveryMode = 运行时从网络读还是从本地版本目录读
origin       = WebView 与 Native Bridge 识别页面身份的来源
```

推荐映射：

| 输入 | sourceType | deliveryMode | Origin |
| --- | --- | --- | --- |
| 普通 HTTP(S)/IP URL | `remote` | `remote` | 真实 Origin |
| HTTP(S) ZIP package | `package` | `local` | appId 独立虚拟 Origin |
| GitHub 静态目录 | `github` | `local` | appId 独立虚拟 Origin |
| 本地目录/ZIP | `local-import` | `local` | appId 独立虚拟 Origin |
| curl/ADB Push 新建应用 | `push` | `local` | appId 独立虚拟 Origin |
| curl/ADB 更新已有 Local App | 保留原 `sourceType` | `local` | 保持既有 appId 和虚拟 Origin |

MVP 中“选择本地目录”表示把目录内容导入 Haminn 私有存储，不表示长期实时挂载原目录。原目录之后被移动或删除，不影响已导入版本。这样版本、读取性能和失败恢复更加确定；需要快速开发更新时使用 Developer Deploy。

导入后的专用存储必须是一个稳定的部署目标，但“允许外部更新”不等于把 Android 私有目录路径暴露给外部逐文件修改。外部 `curl`、ADB 或未来其他部署工具都通过 `appId` 调用部署接口；Haminn 把上传内容写入 staging、生成新的不可变版本、切换活动指针并 reload。这样既能快速刷新，也不会让运行中的 Web App 看到上传到一半的文件集。

Developer Deploy 是“新代码版本的来源”，不必改变 App Profile 的原始 `sourceType/sourceSpec`。例如从本地目录导入的 Web App 接受一次 curl 更新后，原始导入来源仍被保留，当前版本的 `activeProvenance` 标记为 `deploy`。Store 必须显示“开发版本覆盖中”；再次从原始上游更新前提示它将替换当前开发版本。

## 5. 用户体验与主流程

### 5.1 安装流程

```text
输入 URL / 扫码 / 选本地文件 / GitHub URL
→ Install Resolver 归一化
→ 元数据与可运行性探测
→ 候选应用预览
→ 用户确认名称、图标、交付方式和能力风险
→ 生成 appId 与 App Profile
→ Remote 保存入口；Local 下载/导入并校验
→ Profile 进入 ready
→ 请求 Launcher 固定快捷方式
→ 可立即启动
```

“安装成功”与“快捷方式固定成功”是两个不同结果。`requestPinShortcut()` 返回可发起请求，不代表用户已经同意，也不代表 Launcher 最终已经创建图标。即使 Launcher 不支持或用户拒绝，应用也必须保留在 Store 的“已安装应用”列表中并可从 Store 启动。

### 5.2 启动流程

```text
Intent 无 appId
→ 打开 Store

Intent 有 appId
→ 查询 Registry
→ 不存在/已删除：显示明确的失效入口页
→ ready：创建该 appId 的全新 WebView
→ 绑定 WebView Profile、Origin 和 Bridge
→ 加载 startUrl
```

Activity 收到另一个 `appId` 的 `onNewIntent()` 时，不复用现有 WebView 的安全上下文，而是取消旧会话任务、销毁旧 WebView、创建新 WebView，再绑定新 Profile 和 Bridge。

### 5.3 返回键与外部导航

- 当前 WebView 有同一 App 内可返回历史时，返回上一页；
- 返回目标属于其他 Origin 时，仍可显示网页，但不会获得 Bridge；
- 无可返回历史时退出当前 Activity，而不是自动跳到 Store；
- `tel:`、`mailto:`、`intent:` 等非 HTTP(S) scheme 只通过明确 allowlist 和系统 Intent 处理；
- 下载、分享和文件选择走 Haminn API 或系统 UI；
- 新窗口默认在同一 WebView 导航或交给系统浏览器，不实现标签页。

### 5.4 卸载流程

Store 提供两个动作：

- “移除应用，保留数据”：禁用快捷方式，删除代码版本和 Profile 主记录，但将数据放入可恢复区一段有限时间；
- “移除并删除数据”：禁用快捷方式，删除本地代码、Native DB、文件、授权策略和审计记录。

Haminn 不能保证从 Launcher 删除用户已经固定的图标，因此必须禁用对应 Shortcut，并在 Store 中说明残留图标需要用户自行移除。

## 6. Install Resolver

### 6.1 输入优先级

Resolver 按以下顺序识别输入：

1. 显式 Haminn 安装描述 URL；
2. 本地目录或 ZIP 的 SAF URI；
3. GitHub 仓库或目录 URL；
4. 完整 HTTP(S) URL；
5. 裸私网 IP、`host:port`；
6. 裸域名；
7. 无法识别时返回可操作的错误，不做搜索引擎跳转。

用户显式输入 scheme 时必须保留。裸域名默认补 HTTPS；明显的 RFC1918 私网地址或带端口主机可建议 HTTP，但在安装确认页显示最终解析结果。

### 6.2 宽容不等于不可预测

无 manifest 时可以降级安装，但推断必须有限、稳定、可解释：

- Remote URL：使用 URL、页面标题、favicon 和 Web Manifest 元数据；
- GitHub：只尝试根目录、`dist/`、`build/`、`public/` 中的 `index.html`；
- 找不到静态入口时，不在手机上构建源码；允许作为普通 GitHub 网页远程打开，或让用户选择目录；
- 所有推断结果在安装前展示，不静默改变 scheme、branch 或路径。

## 7. 安装与更新协议

### 7.1 `haminn-install.json`

该文件只定义 Haminn 安装语义，不复制 Android permission 名称，也不授予权限。

Remote 来源第一版只探测同 Origin 根目录 `/haminn-install.json`；GitHub、本地和 package 在所选应用根目录探测。不存在时继续使用宽容回退。

```json
{
  "schema": 1,
  "id": "example.voice-notes",
  "name": "Voice Notes",
  "shortName": "Notes",
  "icon": "icon-512.png",
  "webRoot": "dist",
  "entry": "index.html",
  "minHaminnApi": 1,
  "install": {
    "mode": "auto",
    "package": "webapp.zip",
    "sha256": "optional-on-first-install"
  },
  "update": {
    "versionFile": "haminn-version.json"
  },
  "capabilities": [
    "tts.speak",
    "speech.recognize",
    "location.current",
    "db"
  ]
}
```

约束：

- 所有相对路径都相对于 manifest 所在的应用根目录解析；
- `entry`、`webRoot`、`icon`、`package` 禁止绝对路径、反斜杠和 `..`；
- 未知字段忽略并保留诊断信息；
- 未知 `schema` 主版本拒绝按 manifest 安装，但仍可回退为普通 Remote URL；
- `capabilities` 是兼容性声明和安装提示，不是授权结果；
- manifest 内容按不可信输入解析，并设置大小、深度、字符串长度和列表数量上限。

### 7.2 `haminn-version.json`

```json
{
  "schema": 1,
  "versionCode": 104,
  "version": "1.4.0",
  "publishedAt": "2026-09-10T09:00:00Z",
  "package": "webapp.zip",
  "sha256": "hex-encoded-sha256"
}
```

规则：

- 比较依据只有单调递增的非负整数 `versionCode`；
- `version` 与 `publishedAt` 只用于显示；
- Remote package 更新必须提供 `sha256`；
- `sha256` 只能验证完整性，不能替代 HTTPS、可信 GitHub 来源或未来的包签名；
- update channel 在首次安装时固定。网页代码不能静默把更新地址切换到另一个 Origin；更改 channel 必须回到 Store 确认。

### 7.3 Local 安装的事务模型

目录结构：

```text
files/
  system/
    haminn-system.sqlite
  apps/
    <appId>/
      versions/
        <versionId>/web/
      staging/
      data/
        files/
        db/
      exports/
```

安装或更新步骤：

1. 下载或导入到随机 `staging/<operationId>`；
2. 流式计算摘要，并检查压缩包、总展开大小、文件数、单文件大小与路径；
3. 拒绝 ZIP Slip、绝对路径、`..`、重复冲突路径和异常压缩比；
4. 校验 manifest、入口文件和可选摘要；
5. 把 staging 目录重命名为不可变 `versions/<versionId>`；
6. 在 Registry 事务中切换 `activeVersionId`；
7. reload 新版本；
8. 新版本加载失败时可回滚指针到上一版本；
9. 成功稳定运行后异步清理超出保留数量的旧版本。

“原子更新”指 Registry 活动指针的原子切换，而不是假定整个目录覆盖操作在所有设备上都原子。

### 7.4 各来源的更新语义

| 来源 | `checkUpdate()` | `update()` |
| --- | --- | --- |
| Remote URL | 可读取版本文件；否则返回 unknown | fresh reload，不替换本地代码 |
| HTTP(S) package | 比较 `versionCode` | 下载、校验、安装新本地版本 |
| GitHub | 比较目标 commit/tree SHA 或版本文件 | 重新下载相同 repo/ref/path |
| Local import | `NOT_UPDATABLE` | 用户从 Store 再次导入 |
| Push | `NOT_UPDATABLE` | 下一次 Push 本身就是更新 |

不在 MVP 中做后台轮询或 WorkManager 定时更新。允许当前 Web App 主动检查、Store 手动检查，以及启动时可选的低频检查。

## 8. GitHub Adapter

GitHub Adapter 只负责获取公开仓库中已经可运行的静态文件，不实现 Git 客户端。

输入规范化为：

```text
owner + repo + resolvedRef + path + resolvedCommit
```

实现策略：

1. 解析仓库默认分支或用户明确给出的 ref/path；
2. 使用 GitHub REST API 定位目标 tree；
3. 文件量较小时获取 tree 并通过 raw URL 下载目标 blob；
4. tree 返回 truncated、文件过多或路径解析不确定时，下载该 ref 的 archive 并只解压目标目录；
5. 不处理 history、merge、submodule、Git LFS 指针文件和 SSH；
6. 匿名请求遇到限额时给出明确重试信息，第一版不要求 GitHub 登录；
7. 下载结果仍必须通过统一 staging 校验流程。

GitHub Adapter 属于正式 v1 能力，但不放进第一个技术闭环；它在 Remote URL、Local package、Registry、Shortcut 和 Bridge 稳定后实现。

## 9. Runtime 与 WebView 生命周期

### 9.1 单 Activity、单活动 WebView

第一版只有一个 `MainActivity` 和一个当前活动 WebView，不实现多窗口或每个 App 独立 Recents task。这里的“单 WebView”是同一时刻只有一个实例，不是整个 Activity 生命周期永远复用同一个实例。

每次从 Store 切到 App、从 App 切到另一个 App，或者 Profile 发生变化时：

```text
cancel session-scoped native work
→ detach clients/listeners
→ stopLoading
→ destroy old WebView
→ create new WebView
→ setProfile before other WebView operations
→ configure settings and clients
→ register exact-origin bridge
→ load startUrl
```

这样满足 Multi-Profile 的初始化约束，也避免旧页面、旧 listener 或旧 reply proxy 穿越 App 会话。

### 9.2 WebView Profile

当 `WebViewFeature.MULTI_PROFILE` 可用时，每个 `appId` 使用独立 Profile，Store 也使用独立 Profile。Profile 隔离 Cookie、HTTP cache、Service Worker、WebStorage 和 Web 层地理位置许可。

当设备不支持时：

- 回退到默认 Profile；
- Native DB、Native files、Capability Policy 和审计仍按 `appId` 隔离；
- Store 明确显示 `webDataIsolation = shared-fallback`；
- 不宣称 Cookie、IndexedDB、CacheStorage 或 Service Worker 已按 App 隔离。

删除 Profile 前必须先销毁关联 WebView。Profile 删除可能异步完成，因此 UI 显示“已安排清理”，而不是宣称磁盘数据已同步彻底擦除。

### 9.3 Local 虚拟 Origin

每个 local-delivery App 使用：

```text
https://<appId>.apps.haminn.invalid/
```

通过 `WebViewAssetLoader` 或严格的 `shouldInterceptRequest` PathHandler 映射到该 App 当前版本的 `web/` 目录。不得使用 `file://`、`data:` 或开启 universal file access。

只向 WebView 暴露当前活动代码版本的专用目录，绝不把整个 `filesDir`、数据库目录或其他 App 目录映射进去。MIME 类型不能识别时返回保守类型；HTML、JS、CSS、WASM、JSON、字体和常用媒体需要明确映射。

虚拟 HTTPS Origin 提供稳定同源身份和更接近 HTTPS 的 Web 行为，但不能被文档描述成“保证所有 Web Powerful APIs 在所有 WebView 版本可用”。Haminn Native API 才是稳定能力入口。

### 9.4 Remote 加载和缓存

- `browser`：使用 WebView 默认 HTTP 缓存语义；
- `no-cache`：开发模式下使用 `LOAD_NO_CACHE`，但只能承诺尽量绕过 HTTP cache；
- 不把 `no-cache` 描述为清除 Cookie、IndexedDB 或 CacheStorage；
- Service Worker 可能自行返回缓存内容，Runtime 不承诺单一 cacheMode 能彻底规避；
- “临时干净会话”作为后续能力，只在 Multi-Profile 可用时创建一次性 Profile。

## 10. Native Bridge

### 10.1 基线

主桥使用 `WebViewCompat.addWebMessageListener()`，并在 `loadUrl()` 前完成注册。Bridge 的内部传输对象使用不易冲突的名字，例如 `window.__haminnTransportV1`；document-start 注入极小的 Promise SDK，向页面提供 `window.haminn`。

禁止：

- 以 `addJavascriptInterface()` 作为未知页面的兼容回退；
- 把 `*` 作为默认 `allowedOriginRules`；
- 仅检查字符串 URL、不检查回调提供的 `sourceOrigin`；
- 接受 iframe 发起的 Native 调用；
- 在页面跨 Origin 导航后继续沿用旧调用会话。

如果设备不支持所需的 WebMessageListener 或 document-start 能力，Store 显示“不兼容的 Android System WebView”，引导升级 WebView；不静默降级到来源不可控的 Bridge。

### 10.2 Origin 与会话校验

每条消息同时满足以下条件才进入 Dispatcher：

1. `sourceOrigin` 与当前 App Profile 的 exact allowed origin 相等；
2. `isMainFrame == true`；
3. 当前顶层导航仍属于相同 App 会话；
4. 请求携带的 `sessionId` 与 Native 当前会话一致；
5. 方法属于当前 App 可见的 API 面；
6. 参数通过方法级 schema、长度和类型校验。

Remote App 导航到其他 Origin 时可以继续显示网页，但 `window.haminn` 不被注入或调用返回 `E_ORIGIN_NOT_ALLOWED`。v1 不允许第三方 manifest 自行扩大 Bridge Origin；需要多 Origin 的应用由用户在 Store 中显式添加，并逐项显示。

### 10.3 协议

请求：

```json
{
  "v": 1,
  "id": "req-184",
  "sessionId": "session-random",
  "method": "tts.speak",
  "params": { "text": "Hello", "rate": 1.0 }
}
```

成功响应：

```json
{
  "v": 1,
  "id": "req-184",
  "ok": true,
  "result": { "utteranceId": "u-92" }
}
```

失败响应：

```json
{
  "v": 1,
  "id": "req-184",
  "ok": false,
  "error": {
    "code": "E_CAPABILITY_DENIED",
    "message": "Location is disabled for this app",
    "retryable": false
  }
}
```

事件：

```json
{
  "v": 1,
  "event": "speech.partial",
  "subscriptionId": "sub-21",
  "data": { "text": "hello" }
}
```

### 10.4 协议约束

- request ID 在一个 session 内唯一；
- Native 操作不得阻塞 UI 线程；WebView 回调只做校验与投递；
- 页面卸载或 App 切换时取消所有 session-scoped 操作和订阅；
- JSON 消息设置上限，建议首版 256 KiB；
- 大文件不通过 Base64 JSON 传输，使用受控虚拟资源 URL、文件 handle 或流式接口；
- 每个方法都有超时、取消和幂等性定义；
- API 主版本不兼容时拒绝调用；小版本只增加可探测能力；
- 网页必须通过 `haminn.runtime.capabilities()` 做能力发现，不能只按 APK 版本猜测。

### 10.5 基础错误码

| 错误码 | 含义 |
| --- | --- |
| `E_BAD_REQUEST` | 消息或参数无效 |
| `E_METHOD_NOT_FOUND` | 当前 API 不存在 |
| `E_NOT_SUPPORTED` | 设备、WebView 或系统服务不支持 |
| `E_ORIGIN_NOT_ALLOWED` | 来源或 frame 不允许 |
| `E_CAPABILITY_DENIED` | App Profile 未获能力授权 |
| `E_OS_PERMISSION_DENIED` | Android 权限被拒绝或撤销 |
| `E_USER_CANCELLED` | 用户取消系统 UI |
| `E_NOT_UPDATABLE` | 当前来源无拉取更新能力 |
| `E_CONFLICT` | 操作与当前状态冲突 |
| `E_LIMIT_EXCEEDED` | 大小、速率、配额或结果上限超出 |
| `E_TIMEOUT` | 操作超时 |
| `E_INTERNAL` | 未分类的宿主错误；日志含内部详情 |

## 11. 权限与能力政策

### 11.1 三层模型

```text
Bridge Origin/Frame 校验
→ Haminn 对当前 Web App 的能力授权
→ Android 对 Haminn APK 的系统授权
→ Native Adapter
```

这三层解决不同问题：

- Origin 校验回答“是哪一个页面/Frame 在调用”；
- Web App 授权回答“用户是否允许这个 App Profile 使用该项 Haminn 能力”；
- Android 系统授权回答“操作系统是否允许整个 Haminn APK 调用底层 API”。

Android 权限属于整个 APK。假如 Web App A 让 Haminn 获得麦克风权限，没有 per-App policy 时，Web App B 随后可以绕过任何提示直接录音。这不是抽象的安全偏好，而是“一 APK 多 Web App”模型中的权限语义错误。因此 v1 必须保留轻量的 per-App capability gate。

一项能力的有效授权定义为：

```text
effectiveGrant(webApp, capability)
= capability 已在当前 Haminn 版本实现
  AND Web App 的 Haminn grant 有效
  AND 该 capability 所需的全部 Android 权限当前有效
  AND 所需系统组件/服务当前可用
```

Haminn 维护两类不同记录：

- `system_permission_observations`：记录 Haminn 最近观察到的 Android 权限状态、时间和触发原因；
- `capability_grants`：记录某个 Web App 对某项 Haminn capability 的 `ask/allow/deny` 决策及作用域。

Android 系统始终是系统权限的事实来源。Haminn 的记录用于展示、审计和变化检测，但每次敏感调用前仍调用系统 API 重新核验，不能把历史记录当成永久有效授权。

### 11.2 授权请求流程

当 Web App 调用 `haminn.permissions.request(capability)` 或第一次调用受限能力时，执行：

```text
确认 capability 已实现且当前 Web App 可见
→ 查询该 Web App 的 Haminn grant
→ grant=deny：直接返回 E_CAPABILITY_DENIED
→ grant=ask：显示由 Haminn 控制的可信授权界面
→ 用户同意后检查 Android 系统权限
→ Haminn 已有系统权限：不重复弹系统框，直接把 Haminn grant 发放给当前 Web App
→ Haminn 尚无系统权限：由 Haminn 代当前 Web App 发起 Android 系统权限请求
→ 系统同意：记录最新系统状态，并使当前 Web App 的 grant 生效
→ 系统拒绝：不发放有效能力，返回 E_OS_PERMISSION_DENIED
```

这里的“直接发放”只表示无需再次请求 Android 系统权限，不表示任意 Web App 自动继承其他 Web App 的 Haminn grant。每个 Web App 仍需通过自己的 Haminn 授权。

状态变化规则：

- 用户在 Store 撤销某 Web App 的 grant，不会也不应自动撤销 Haminn APK 的系统权限；只让该 Web App 失去能力；
- 用户在 Android 设置中撤销系统权限，所有依赖它的 Web App 立即变为 `effective=false`；各 Web App 原有 grant 可保留为用户意图，下一次调用只需重新申请系统层；
- 如果 capability 依赖多个系统权限，必须全部满足才有效；
- 不需要 Android runtime permission 的 capability，系统层结果记为 `not-required`；
- 系统授权请求和 Haminn 授权请求都绑定当前 `appId + sessionId + requestId`，切换 Web App 后旧结果不得发给新页面；
- 用户选择“仅本次”时，grant 只存在于当前 Runtime Session；选择“始终允许”才持久化；
- 安装 manifest 中声明的 capabilities 只用于安装前说明和兼容性检查，不自动授权。

### 11.3 能力风险分级与默认规则

| 类别 | 示例 | 默认策略 |
| --- | --- | --- |
| 运行信息 | 版本、capability discovery、当前 appId | 自动允许 |
| App 私有数据 | 自己的 DB、私有 files | 自动允许，受配额限制 |
| 可见输出 | TTS、震动 | 自动允许；后台或高频调用限速 |
| 系统 UI 介入 | 文件选择、分享、Camera Intent | 允许发起，最终由用户在系统 UI 确认 |
| 敏感采集 | 麦克风、定位、传感器、剪贴板读取 | 首次按 Web App 明确授权 |
| 边界绕过 | native fetch、局域网访问 | 首次按 Web App 授权并配置目标范围 |
| 宿主管理 | 安装、删除、Registry、全局审计、Deploy Server | Store Origin 独占 |

授权 UI 由 Haminn 的可信 Native/Store 控制面展示，必须明确写出 Web App 名称、来源和请求的能力，不能由 Web App 自己绘制后冒充授权结果。可保存 `ask`、`allow`、`deny`；敏感能力至少支持“仅本次”和“始终允许”。

HTTP Remote App 的页面代码可能在传输中被替换，因此敏感能力和 native fetch 默认保持 `ask/deny`，用户必须在 Store 中显式开启。Haminn 支持 HTTP 是兼容能力，不等于把不安全传输描述为可信来源。

### 11.4 Manifest 原则

每个 Haminn APK 版本只预声明“这个版本已经实现的全部 Capability 所需权限和组件”，不预埋未来权限词典。

- 普通网络：`INTERNET`、`ACCESS_NETWORK_STATE`；
- 相机/语音：实现对应 Adapter 后声明 `CAMERA`、`RECORD_AUDIO`；
- 定位：`ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION`；
- Android 17 / target 37 的直接局域网访问：`ACCESS_LOCAL_NETWORK` 并运行时请求；
- TTS 服务发现：按目标版本声明对应 `<queries>`；
- 文件分享：配置范围最小的 `FileProvider`；
- 文件访问优先 SAF，不默认申请 all-files；
- VPN、Accessibility、Notification Listener 等组件型高权限能力不在 v1 预埋。

## 12. 公共 Runtime API

### 12.1 v1 API 面

| 命名空间 | v1 方法方向 | 备注 |
| --- | --- | --- |
| `haminn.runtime` | `info()`、`capabilities()`、`on()`、`off()` | 版本、provider、隔离降级信息 |
| `haminn.app` | `info()`、`reload()`、`checkUpdate()`、`update()` | 仅当前 appId |
| `haminn.permissions` | `status()`、`request()` | 同时返回当前 Web App grant、OS 状态和 effective 状态 |
| `haminn.tts` | `voices()`、`speak()`、`stop()`、`synthesize()` | engine 可能联网，不能承诺本地处理 |
| `haminn.speech` | `availability()`、`start()`、`stop()` | partial/final 通过事件返回 |
| `haminn.location` | `getCurrent()`、`watch()`、`clearWatch()` | 前台定位；v1 不做后台定位 |
| `haminn.files` | app-private 文件、SAF picker、导入/导出 | 使用 handle，不暴露绝对路径 |
| `haminn.db` | `open()`、`exec()`、`query()`、`transaction()` | 按 appId 和数据库名隔离 |
| `haminn.network` | `status()`、受限 `fetch()` | 明确绕过 CORS，属于敏感能力 |
| `haminn.camera` | `capturePhoto()` | v1 走系统 Camera Intent |
| `haminn.share` | `text()`、`file()` | 系统 chooser |
| `haminn.clipboard` | `write()`；受限 `read()` | 受 Android 版本和 per-App policy 约束 |
| `haminn.haptics` | `vibrate()`、`impact()` | 限制持续时长和调用频率 |

不在 v1 稳定 ABI 中承诺实时 Camera stream、后台录音、蓝牙、NFC、通知监听、VPN、Accessibility 或任意 Android Intent。

### 12.2 Store Host API

只有固定 Store Origin 可以访问：

```text
haminn.host.apps.list/get/install/reinstall/remove
haminn.host.apps.launch
haminn.host.sources.resolve
haminn.host.shortcuts.pin/update/disable
haminn.host.capabilities.getPolicy/setPolicy
haminn.host.permissions.listSystem/refresh/openSettings
haminn.host.audit.query/clear
haminn.host.deploy.start/stop/status
haminn.host.data.export/import
```

Host API 与 Runtime API 必须使用不同的 Registry 和 Dispatcher allowlist。不能只在 JavaScript 包装层隐藏 host 方法；Native Dispatcher 必须再次校验 Store 的 appId、Profile、top origin 和 `sourceOrigin`。

## 13. Native API 的关键边界

### 13.1 SQLite

每个 App 使用独立目录和独立数据库文件。数据库名仅允许受限字符和长度，Native 根据当前 `appId` 拼接真实路径，网页永远不能传绝对路径或其他 appId。

因为 Android `SQLiteDatabase` 没有直接暴露足以构建完整 SQL 沙箱的 authorizer，v1 的 SQL 接口必须遵守：

- 一次只执行一条 statement；
- 所有值通过绑定参数传入；
- 禁止 `ATTACH`、`DETACH`、`VACUUM INTO` 与加载扩展；
- `PRAGMA` 只开放明确白名单；
- query 返回行数、列数和序列化字节数设上限；
- transaction 具有超时，App 切换时回滚；
- 每个 App 设置 Native DB 总配额；
- 数据库代码、Registry DB 和审计 DB 使用不同目录与连接工厂。

SQLite 是关键持久数据能力；IndexedDB 是 Web 兼容存储。二者不做透明同步。

### 13.2 Files

- App-private API 只返回逻辑路径或 opaque handle；
- path normalization 后仍必须验证 canonical path 位于当前 App 数据根；
- SAF 选择器产生的 URI grant 记录其归属 appId；
- persisted URI 被移动、删除或撤销后返回可恢复错误；
- 导出必须由系统创建/选择目标，不允许网页指定任意宿主路径；
- App 代码目录只读，业务数据写入 `data/files/`。

### 13.3 Native Fetch

`haminn.network.fetch()` 是有意绕过浏览器 CORS 和 mixed-content 限制的高权限出口，必须受控：

- 只支持 `http` 和 `https`；
- 每个 App 保存允许的 host/port 范围；默认仅允许安装来源 Origin；
- 私网、环回、链路本地和公网目标分开授权；
- 每次 redirect 都重新校验 scheme、host、port 和地址类别；
- 默认不共享 WebView Cookie，不自动携带系统凭据；
- 禁止调用 `file:`、`content:`、`intent:` 等 scheme；
- 设置连接/读取超时、并发数、响应头和响应体上限；
- 大响应写入当前 App 临时文件并返回 handle，不在 Bridge 中复制巨量 Base64；
- 审计只记录目标 Origin、方法、状态、字节数和耗时，不记录 token、请求体或响应体。

### 13.4 语音、定位和相机

- TTS 引擎可能由系统或第三方实现，也可能联网，API 只报告已知 engine 信息，不承诺离线或隐私属性；
- STT 启动前同时检查 per-App policy、`RECORD_AUDIO` 和服务可用性，优先暴露 on-device availability，但不伪造“必然离线”；
- 定位 v1 只允许前台会话，Activity 不可见或 App 切换时停止 watch；
- 拍照 v1 通过系统 Camera Intent 与受控临时 URI 完成，不实现常驻原生预览流。

## 14. 数据模型

系统数据库至少包含：

```sql
CREATE TABLE app_profiles (
  app_id TEXT PRIMARY KEY,
  publisher_id TEXT,
  name TEXT NOT NULL,
  short_name TEXT,
  icon_path TEXT,
  source_type TEXT NOT NULL,
  source_spec_json TEXT NOT NULL,
  delivery_mode TEXT NOT NULL,
  start_url TEXT NOT NULL,
  primary_origin TEXT NOT NULL,
  web_profile_name TEXT NOT NULL,
  shortcut_id TEXT NOT NULL UNIQUE,
  active_version_id TEXT,
  active_provenance TEXT,
  developer_deploy_enabled INTEGER NOT NULL DEFAULT 0,
  installed_version_code INTEGER,
  installed_version TEXT,
  installed_revision TEXT,
  state TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE app_versions (
  version_id TEXT PRIMARY KEY,
  app_id TEXT NOT NULL,
  provenance TEXT NOT NULL,
  source_revision TEXT,
  version_code INTEGER,
  version_name TEXT,
  content_sha256 TEXT,
  relative_web_root TEXT NOT NULL,
  state TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE capability_grants (
  app_id TEXT NOT NULL,
  capability TEXT NOT NULL,
  decision TEXT NOT NULL,
  scope TEXT NOT NULL,
  granted_at INTEGER,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (app_id, capability)
);

CREATE TABLE system_permission_observations (
  permission TEXT PRIMARY KEY,
  observed_state TEXT NOT NULL,
  observed_at INTEGER NOT NULL,
  trigger_app_id TEXT,
  trigger_request_id TEXT
);

CREATE TABLE install_operations (
  operation_id TEXT PRIMARY KEY,
  app_id TEXT,
  kind TEXT NOT NULL,
  state TEXT NOT NULL,
  staging_path TEXT,
  error_code TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE audit_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  app_id TEXT NOT NULL,
  session_id TEXT,
  top_origin TEXT,
  source_origin TEXT,
  method TEXT NOT NULL,
  decision TEXT NOT NULL,
  os_permission_state TEXT,
  result_code TEXT,
  duration_ms INTEGER,
  created_at INTEGER NOT NULL
);
```

Registry schema 通过 `SQLiteOpenHelper` 做显式版本迁移。迁移失败时保留原数据库备份并进入只读恢复页，不能重建空库后假装成功。

`system_permission_observations` 只是最近状态快照和审计线索；实际调用前仍以 Android 权限 API 的实时结果为准。`capability_grants` 才表示用户对某个 Web App 的 Haminn 层授权，二者不可合并成一个布尔字段。

### 14.1 审计原则

- 默认记录方法级元数据，不记录语音内容、文本正文、文件内容、数据库参数或网络 body；
- URL 日志移除 userinfo、query 和 fragment，避免 token 泄漏；
- 每 App 和全局都有条数/容量上限；
- Store 可查看全局摘要，普通 App 只能查看自己的摘要；
- 审计用于透明、调试和故障定位，不等同于权限控制。

### 14.2 备份与恢复

Android 清除数据或卸载 APK 会删除 Haminn 私有数据。v1 默认关闭 Android Auto Backup，避免在设备迁移时恢复出代码、Registry、Profile 和快捷方式互相不一致的半状态。

正式发布前至少实现 Store 主动导出：

- App Profile 的可移植元数据；
- Native DB；
- App-private files；
- 可选的 local code 版本；
- 不导出临时 token 和不可迁移系统 URI grant。

## 15. WebView 配置基线

| 设置 | 基线 |
| --- | --- |
| JavaScript | 开启 |
| DOM Storage | 开启 |
| WebView debugging | 仅 debug build |
| file URL access | 关闭 |
| universal access from file URL | 关闭 |
| mixed content | 默认 `NEVER_ALLOW` |
| cleartext | App 网络配置允许；App Profile 明确标记 HTTP |
| Safe Browsing | 设备支持时开启 |
| third-party cookies | 默认关闭；确有兼容需求时按 App 显式开启 |
| media autoplay | 默认需要用户手势 |
| geolocation | 通过 WebChromeClient 严格绑定当前 App 与 Origin |
| SSL error | 永不全局 `proceed()` |
| popup/new window | 不实现多标签；受控外部打开或同 WebView 导航 |

Store 自身应有最严格的导航 allowlist、独立 Profile 和强 CSP。Store 不加载第三方脚本，也不允许远程内容覆盖其 assets。

## 16. Developer Deploy

### 16.1 目标

Developer Deploy 只解决“把已经构建好的 Web 文件推送到一个 App Profile”，不提供 shell、文件浏览器、Git 或后台远程管理。任何 local-delivery Web App——包括最初从本地目录导入、HTTP package 安装或 GitHub 下载的 Web App——都可以在 Store 中开启该能力，并以稳定 `appId` 作为部署目标。

从开发者视角，这是对 Haminn 内该 Web App 专用代码目录的直接更新；从实现视角，它绝不能绕过版本事务直接修改 active 目录。每次部署都生成一个 `provenance=deploy` 的完整新版本，提交成功后一次切换并主动 reload。外部工具不需要知道、也不能获得 Android 私有文件系统的真实路径。

### 16.2 两种连接方式

首选：

```text
设备仅监听 loopback
→ adb forward 把电脑本地端口转到设备端口
→ curl PUT dist.zip
```

可选：

```text
Store 显式开启局域网开发连接
→ 申请所需局域网权限
→ 显示 IP、端口、短期高熵 token
→ 页面关闭、超时或用户停止后关服
```

局域网模式要求：

- 只在 Store 前台显式开启；
- 不使用 Foreground Service 常驻；
- token 至少 128 bit 随机熵、短期有效、只显示一次；
- 单会话限速、上传大小和并发限制；
- 失败多次后立即撤销 token；
- 上传仍走统一 staging 校验与版本切换；
- 日志绝不记录 Authorization header；
- targetSdk 37 时按 Android 17 规则声明并请求 `ACCESS_LOCAL_NETWORK`。

建议接口：

```http
GET  /v1/health
GET  /v1/apps
PUT  /v1/apps/{appId}/deploy
POST /v1/apps/{appId}/reload
```

`PUT deploy` 接受完整 ZIP 快照，响应返回 `operationId`、新 `versionId`、摘要和 reload 结果。Push 只能作用于用户在 Store 中明确开启部署的 App Profile；不允许请求任意文件系统路径，也不提供逐文件覆盖 active 版本的接口。后续若需要增量传输，只能把增量应用到 staging 后重新生成完整版本，不能削弱提交边界。

## 17. 原生工程设计

### 17.1 技术选型

v1 固定采用：

```text
Java
+ Android Framework
+ System WebView
+ androidx.webkit
+ SQLiteDatabase / SQLiteOpenHelper
+ Storage Access Framework
+ Gradle Wrapper
```

Android 工程身份固定为：

```text
applicationId = io.github.zhyuzh3d.haminn
namespace     = io.github.zhyuzh3d.haminn
minSdk        = 26
targetSdk     = 37
```

不采用 Capacitor、Cordova、Flutter、React Native、Compose、Room、Retrofit、OkHttp、DI 框架、NDK 或 Go runtime。Go 可在未来用于独立 `haminnctl`，但不是 Android 宿主依赖。

这里选择 Java 的原因是减少构建层和运行层依赖，不是认为 Kotlin 技术上不可行。若未来 Java 回调和异步状态管理成本显著超过 Kotlin 引入成本，应通过 ADR 重新评估，而不是把语言选择当作产品 ABI。

构建时固定 Gradle Wrapper、Android Gradle Plugin、JDK 与 compileSdk 版本。具体版本写入仓库和 CI，不在产品能力中承诺“固定多少秒完成构建”。

### 17.2 线程模型

- UI 线程：Activity、WebView 初始化、WebView 回调、系统授权和 Activity Result；
- bounded I/O executor：下载、ZIP 校验、文件导入导出、SQLite；
- capability-specific executor：可能阻塞的系统服务；
- 所有回调在返回 JS 前检查 session 是否仍存活；
- Activity 销毁时取消会话任务，但安装/更新事务可由受控 application-scope coordinator 完成或安全回滚；
- 第一版不引入后台常驻服务。

### 17.3 推荐目录

```text
haminnapp/
├─ app/
│  └─ src/main/
│     ├─ java/io/github/zhyuzh3d/haminn/
│     │  ├─ MainActivity.java
│     │  ├─ runtime/
│     │  │  ├─ RuntimeSession.java
│     │  │  ├─ WebViewFactory.java
│     │  │  ├─ WebProfileManager.java
│     │  │  ├─ NavigationPolicy.java
│     │  │  └─ LocalContentGateway.java
│     │  ├─ bridge/
│     │  │  ├─ BridgeHost.java
│     │  │  ├─ RpcDispatcher.java
│     │  │  ├─ RpcRequest.java
│     │  │  ├─ RpcError.java
│     │  │  └─ SessionRegistry.java
│     │  ├─ capability/
│     │  │  ├─ CapabilityRegistry.java
│     │  │  ├─ PermissionBroker.java
│     │  │  ├─ tts/
│     │  │  ├─ speech/
│     │  │  ├─ location/
│     │  │  ├─ files/
│     │  │  ├─ db/
│     │  │  └─ network/
│     │  ├─ install/
│     │  │  ├─ InstallResolver.java
│     │  │  ├─ InstallCoordinator.java
│     │  │  ├─ PackageValidator.java
│     │  │  ├─ UpdateManager.java
│     │  │  └─ source/
│     │  ├─ registry/
│     │  │  ├─ AppRegistry.java
│     │  │  └─ RegistryOpenHelper.java
│     │  ├─ launcher/ShortcutHost.java
│     │  ├─ audit/AuditLog.java
│     │  └─ deploy/DeployServer.java
│     ├─ assets/
│     │  ├─ store/
│     │  ├─ bridge/haminn-v1.js
│     │  └─ haminn-host.json
│     └─ res/xml/network_security_config.xml
├─ api/
│  ├─ haminn-api.d.ts
│  ├─ protocol-v1.md
│  ├─ haminn-install.schema.json
│  └─ haminn-version.schema.json
├─ docs/
├─ examples/
├─ scripts/
├─ gradle/wrapper/
├─ gradlew
├─ LICENSE
└─ README.md
```

### 17.4 构建配置

原研究稿使用 `haminn.yaml`，但 v1 运行时已经依赖 JSON 协议且目标是避免 YAML parser，因此统一改为 APK 内置的 `haminn-host.json`：

```json
{
  "schema": 1,
  "productName": "HaminnApp",
  "storeOrigin": "https://store.haminn.invalid",
  "bridgeApiMajor": 1,
  "defaultRemoteCachePolicy": "browser",
  "auditMaxEventsPerApp": 5000,
  "localVersionRetention": 2
}
```

配置优先级：

```text
编译期 BuildConfig 与 Android Manifest
→ APK assets/haminn-host.json
→ Registry 中的用户设置
```

运行时配置不能修改 Android Manifest、签名、package ID 或 APK 内 Store 权限。

## 18. 可靠性与恢复

### 18.1 必须处理的失败

- WebView provider 缺失、过旧或关键 feature 不支持；
- WebView renderer 崩溃或被系统回收；
- 安装/更新下载中断；
- ZIP 校验失败、空间不足、入口缺失；
- Activity 在系统权限对话框期间重建；
- 用户撤销 Android 权限或 SAF URI grant；
- Shortcut 指向已删除 appId；
- Registry 迁移失败或活动版本目录丢失；
- Remote URL 超时、TLS 错误或 HTTP 离线；
- App 更新后首屏加载失败。

### 18.2 恢复原则

- App Profile 状态机和安装操作状态机都持久化；
- 启动时清理无主 staging，并验证 `activeVersionId` 指向存在目录；
- 更新前保留上一可运行版本，失败后回滚；
- renderer 崩溃时只重建当前 Runtime Session，不删除用户数据；
- 任何自动恢复都要保留可诊断错误，不允许用空数据覆盖损坏数据；
- Store 始终可以离线打开，作为全局恢复入口。

### 18.3 状态机

App Profile 的合法主状态转换：

```text
candidate
  → installing
      → ready
      → failed

ready
  → updating
      → ready(new activeVersionId)
      → ready(previous activeVersionId, update failed)

ready / failed
  → removing
      → removed
```

`install_operations` 单独保存每次操作状态：

```text
created → resolving → transferring → validating → committing → succeeded
                                      └──────────→ failed
```

只有 `committing` 可以修改 `activeVersionId`，并且修改必须和安装版本元数据处于同一 Registry 事务。应用进程重启后，任何停在 `resolving/transferring/validating` 的操作都可安全标记失败并清理 staging；停在 `committing` 的操作则根据 Registry 指针和版本目录是否存在完成提交或回滚。

## 19. 安全与信任边界

Haminn 允许运行第三方代码并向其开放 Native 能力，因此不能使用“不替用户负责”来替代技术边界。v1 至少假设以下威胁真实存在：

- 已安装 Web App 本身恶意；
- Remote HTTP 内容被局域网中间人替换；
- 可信页面存在 XSS；
- 第三方 iframe 试图调用 Bridge；
- ZIP 包包含路径穿越或压缩炸弹；
- native fetch 被用于扫描或访问非预期内网；
- SQL 语句试图附加其他 App 数据库；
- 旧异步回调在切换 App 后把结果发给新页面；
- Store 页面被导航到外部站点后仍持有 host API。

必须保持的系统不变量：

1. 非 Store App 永远不能调用 `haminn.host.*`。
2. iframe 永远不能直接调用 v1 Native API。
3. 一个 appId 永远不能通过路径、数据库名或 handle 访问另一个 appId 的 Native 数据。
4. Bridge 消息只属于创建它的 Runtime Session。
5. 未校验完成的代码版本永远不能成为 active version。
6. WebView Profile 不可用时必须如实报告浏览数据共享降级。
7. HTTP 支持不改变其可被篡改的事实。
8. Android 系统权限共享不等于 Haminn per-App policy 共享。

Haminn 的逻辑隔离降低误用和跨 App 访问，但同一 APK/UID 内的实现缺陷仍可能破坏隔离。只有生成不同 package 的 APK 才能获得真正的 Android UID 与系统权限隔离；这不属于当前产品。

## 20. MVP 与版本范围

P0、P1、P2 是同一次连续开发中的内部实施顺序和质量门禁，不是三个需要等待用户逐次批准的独立项目。进入开发后，执行者应持续完成实现、自动测试、真机验证、修复和回归，直到达到正式 v1 交付标准；只有出现必须由用户提供的外部凭据/设备、不可替代的产品取舍或无法安全继续的真实阻塞时才暂停。

每一阶段通过后立即进入下一阶段。阶段通过只表示局部门禁成立，不能把 P0 骨架、P1 局部能力或 debug APK 对外描述为最终产品。

### 20.1 技术闭环 P0

目标是证明核心架构，而不是堆积能力：

- 单 Activity 和内置 Store；
- Registry 与随机 appId；
- 安装 Remote HTTP(S)/IP URL；
- Pinned Shortcut 与 Store 内启动降级；
- 每次 App 切换重建 WebView；
- WebMessageListener exact-origin 主 frame Bridge；
- `runtime.info()` 与 `runtime.capabilities()`；
- 基础审计和 WebView 兼容性诊断。

### 20.2 可用 MVP P1

- Local ZIP/目录导入与虚拟 HTTPS Origin；
- `haminn-install.json`；
- 不可变版本目录、活动指针和失败回滚；
- per-App capability policy 与 Android PermissionBroker；
- `app`、`permissions`、`tts`、`db`、`files`；
- Remote `browser/no-cache`；
- Store 中的安装、权限、存储、审计和卸载管理。

### 20.3 正式 v1 P2

- `haminn-version.json` 与 package 更新；
- GitHub public repository adapter；
- STT、前台定位、Camera Intent、share、clipboard、haptics；
- 受限 native fetch；
- QR 输入；
- ADB-first Developer Deploy 与显式 LAN 模式；
- export/import；
- Multi-Profile 支持与共享 fallback 验证；
- 发布签名、升级和恢复测试。

### 20.4 v1 之后

- 实时 Camera stream；
- 蓝牙、NFC、通知等新 Adapter；
- 私有仓库凭据；
- 签名 Web package 与 publisher trust；
- 多窗口或独立 Recents task；
- 其他代码托管平台 Adapter；
- 临时干净 Web Profile；
- 独立 `haminnctl`。

### 20.5 最终连续交付物

最终交付不能只有源码或“构建成功”日志，至少包括：

- 使用 `io.github.zhyuzh3d.haminn` 的可安装 release APK；
- 对应的 debug APK，供诊断和 WebView 调试；
- APK 的 SHA-256、versionCode、versionName、minSdk、targetSdk 和签名证书指纹；
- 完整源码、Gradle Wrapper、锁定的依赖版本和一条命令可复现的构建说明；
- Haminn Store、JS SDK、JSON schema 和至少一个 Remote/Local 示例 Web App；
- 自动测试结果、真机矩阵、关键负面测试、安装更新回滚证据和已知限制；
- 从空设备安装、创建 Web App、固定 Shortcut、授权、更新、导出和 APK 覆盖升级的验收记录。

产品级 release APK 必须使用长期保存的正式签名密钥。签名密钥及口令不得进入 Git、文档、日志或 APK 产物目录；如果开发开始时没有现成正式密钥，应在仓库外生成 Haminn 专用密钥、提供离线备份说明，并在第一次对外分发前固定证书指纹。debug 签名不能冒充正式产品签名。

## 21. 测试与验收

### 21.1 单元测试

- URL、GitHub、裸域名、IP 和 manifest Resolver；
- Origin canonicalization，包括默认端口、IPv6 和大小写；
- manifest/version schema、路径与大小限制；
- ZIP Slip、压缩炸弹和重复路径检测；
- App Profile、install operation 和 update 状态机；
- capability policy 与 Android permission 映射；
- native fetch redirect 与地址类别校验；
- SQLite 名称、statement 与结果上限；
- 日志脱敏和轮转。

### 21.2 Instrumentation 与真机测试

- 主图标永远进入 Store，Shortcut 精确进入 appId；
- Launcher 不支持、拒绝或延迟固定 Shortcut 时仍可从 Store 启动；
- A → B 切换后 A 的旧异步响应不能到达 B；
- top frame 可调用，same-origin/cross-origin iframe 都不可调用；
- 跨 Origin 导航后 Bridge 消失，返回允许 Origin 后恢复；
- Multi-Profile 可用时 Cookie/WebStorage 隔离；不可用时正确显示降级；
- Remote HTTP、HTTPS、私网 IP、TLS 错误、离线和重定向；
- Local 相对路径、fetch、WASM、字体、媒体和 404 MIME 行为；
- 权限首次申请、拒绝、永久拒绝、系统设置撤销和 Activity 重建；
- 更新中断、校验失败、磁盘不足、入口损坏和回滚；
- WebView renderer crash 恢复；
- Haminn APK 覆盖升级后 Registry、代码版本和 Shortcut 仍有效。

### 21.3 兼容矩阵

至少覆盖：

- API 26 最低版本；
- 一个不支持 Multi-Profile 的设备/WebView；
- 一个支持 Multi-Profile 和 document-start 的现代设备；
- AOSP/Google WebView 与至少一个主流厂商设备；
- Android 17 / target 37 的局域网权限行为；
- IPv4、IPv6、HTTP、HTTPS、自签名 HTTPS 失败路径；
- 冷启动、热启动、`onNewIntent`、旋转/重建、低内存恢复。

### 21.4 发布门槛

正式 v1 只有同时满足以下条件才可称为完成：

1. P0/P1/P2 范围的自动测试通过；
2. 至少两类 WebView provider 的真机主链验证通过；
3. 安装、更新、失败回滚和数据保留有可复现实证；
4. Origin、iframe、App 切换、host API 的负面测试通过；
5. APK 覆盖升级不丢 App Profile、Native 数据和可用 Shortcut；
6. release build 关闭 WebView debugging，签名与升级链验证通过；
7. 文档明确说明共享 Android 权限、WebView fallback、HTTP 风险和卸载删除数据的边界。

## 22. 发布与维护

- 首发渠道以 GitHub Release 和手动安装为主，不以 Play 上架为首要约束；
- 官方发行长期保持 `io.github.zhyuzh3d.haminn` 和同一签名；签名密钥离线备份；
- 第三方 fork 使用自己的 applicationId、签名和品牌，避免与官方升级链冲突；
- 建议采用 Apache-2.0，以明确包含专利授权；最终许可证在首次公开发布前冻结；
- Bridge API v1 发布后保持向后兼容；废弃能力先标记、至少跨一个主版本保留；
- APK 低频更新只承载新 Native Capability、Android/WebView 兼容变化和宿主修复；
- Store Web App 和 JS SDK 随 APK 发布，因为它们属于可信控制面；普通 Web App 独立更新。

## 23. 冻结的架构决策

| ADR | 决策 |
| --- | --- |
| ADR-01 | Android only；`applicationId/namespace=io.github.zhyuzh3d.haminn`；minSdk 26；targetSdk 37 |
| ADR-02 | 主图标固定进入内置 Store |
| ADR-03 | 一个 APK 管理多个 App Profile 与 Pinned Shortcut |
| ADR-04 | 运行时无 HUD、地址栏或固定管理层 |
| ADR-05 | `appId` 由 Haminn 随机生成；来源 ID 只作元数据 |
| ADR-06 | Source、Delivery、Origin 分离 |
| ADR-07 | Remote URL 默认 remote；package/GitHub/local/push 使用 local |
| ADR-08 | 本地目录 MVP 采用导入快照，不做实时 SAF 挂载 |
| ADR-09 | Local 使用 appId 独立虚拟 HTTPS Origin，禁止正式 `file://` 路径 |
| ADR-10 | App 切换时销毁并重建 WebView |
| ADR-11 | Multi-Profile 可用时按 appId 隔离；不可用时明确降级 |
| ADR-12 | WebMessageListener exact-origin + main-frame Bridge；无不安全兼容回退 |
| ADR-13 | Store host API 与公共 Runtime API 在 Native Dispatcher 层分离 |
| ADR-14 | Android 权限之外保留最小 per-App capability policy |
| ADR-15 | Manifest 只声明当前 APK 已实现 Capability 的权限与组件 |
| ADR-16 | Local 更新使用不可变版本目录 + Registry 活动指针 |
| ADR-17 | GitHub Adapter 只下载静态产物，不做 Git client 或移动端构建 |
| ADR-18 | native fetch 是受授权、受目标范围与资源上限约束的能力 |
| ADR-19 | 所有 Local App 都可按 appId 开启 Developer Deploy；默认 ADB-first，LAN 仅显式临时开启；部署生成新版本，不逐文件改写 active 目录 |
| ADR-20 | Java + System WebView + AndroidX WebKit；单 Android module |
| ADR-21 | 构建期宿主配置统一使用 JSON，不引入 YAML parser |
| ADR-22 | 不后台轮询更新，不常驻部署服务 |
| ADR-23 | P0/P1/P2 连续执行，最终以产品级 release APK 和完整验收证据交付 |

## 24. 仍需用原型验证、不能只靠文档断言的事项

以下问题不阻塞搭建 P0，但必须通过真机原型得出结论：

1. 目标设备上 WebMessageListener、document-start 和 Multi-Profile 的实际覆盖率；
2. 自定义虚拟 HTTPS Origin 下 Service Worker、WASM、模块脚本和媒体 Range 请求行为；
3. 不同 Launcher 对 Pinned Shortcut 创建、更新、禁用和图标显示的差异；
4. WebView renderer 重建后 Profile 与 Bridge 的恢复顺序；
5. Remote `LOAD_NO_CACHE` 与 Service Worker/CacheStorage 的真实交互；
6. 大型 GitHub 目录下载的性能、限额和 archive fallback；
7. Android 17 局域网权限对 WebView Remote URL、Native fetch 和 Deploy Server 三条路径的实际影响；
8. Native SQLite 防御性 SQL 限制是否足够；如不可可靠约束，应把 v1 API 收敛为结构化表/事务操作而不是开放 SQL。

这些是实验项，不应提前写成产品保证。每一项都需要记录设备、Android 版本、WebView provider/version、输入、预期和实际结果。

## 25. 结论

HaminnApp 的长期核心不是 WebView 本身，也不是支持多少 Android API，而是下面这个稳定合同：

```text
一个可信、可恢复的 Store 控制面
+ 一个 App Profile 对应一个桌面入口和运行身份
+ 可验证的来源与本地代码版本
+ exact-origin、main-frame、session-bound Native Bridge
+ per-App 数据和能力边界
+ 可探测、版本化的 Haminn API
```

先把“安装 → 桌面入口 → 正确身份启动 → Bridge → Native 能力 → 更新回滚”这一条链做对，再扩展能力数量。只要这条链稳定，Haminn 才是一个真正可持续升级的 Android Web App Runtime；否则它只会成为一个权限很大的 WebView 示例程序。

## 参考资料

- [Android Developers: Access native APIs with JavaScript bridge](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge)
- [Android Developers: WebView native bridge security risks](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)
- [Android Developers: WebViewCompat API](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
- [Android Developers: ProfileStore API](https://developer.android.com/reference/androidx/webkit/ProfileStore)
- [Android Developers: WebViewAssetLoader InternalStoragePathHandler](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader.InternalStoragePathHandler)
- [Android Developers: Load in-app content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)
- [Android Developers: ShortcutManager API](https://developer.android.com/reference/android/content/pm/ShortcutManager)
- [Android Developers: Android 17 local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Android Developers: Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [GitHub Docs: REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
- [GitHub Docs: Git Trees API](https://docs.github.com/en/rest/git/trees)
