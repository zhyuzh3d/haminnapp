# HaminnApp 智能体开发插件接入升级计划

版本 1.0，2026-09-21。本文件基于当前代码实现编写，是对陌生智能体接入体验的专项升级计划。它新增在 `plans/` 中，不修改既有的 `haminnapp-unified-dev-workspace-plan.md`；两者的边界是：既有计划描述开发工作区和工具语义，本计划描述“陌生智能体拿到一个服务地址后如何安装插件、恢复连接并进入这些能力”。

本计划已落地为当前开发服务的唯一 Bootstrap/插件接入路径；实现和验证记录以代码、合同测试及真机验收为准。

## 一、当前代码基线

当前 `AgentDevelopmentServer` 已经实现了一个可用的、认证后的 MCP 开发控制面，主要事实如下：

- `/mcp` 接受 Streamable HTTP MCP JSON-RPC；所有普通工具请求需要 `Authorization: Bearer <password>`；支持现代和旧 MCP 协议版本。
- `/.well-known/haminn-agent` 返回 discovery，包含 `mcpUrl`、Skill URL、helper URL、`clientBootstrap`、`intentIndex`、`toolIndex`、`guidanceVersion`、`schemaDigest`、资源摘要和传输限制。
- `/` 已按 `Accept` 内容协商返回机器 Bootstrap 或人工安装页；`/.well-known/haminn-agent` 返回同一份清单。
- `/plugin/haminn-device` 提供确定性 ZIP，包含 Haminn bundle manifest、Codex `.codex-plugin/plugin.json`、`.mcp.json`、Skill 和 helper。
- `clientBootstrap.prompt` 当前为 `develop-webapp` 符号值，`preferred` 和 `fallback` 能说明 MCP/helper 连接方式，但不能告诉陌生智能体如何安装插件。
- 未认证请求会收到 401 JSON，包含 `discoveryUrl`、`instructionsUrl` 和认证说明；错误密码按来源 IP 计数，五次后一分钟返回 429。当前 401/429 还没有统一的 `nextAction` 和“打开 Haminn 开发配置”的机器可读恢复指令。
- 密码默认是六位数字，手工密码允许 `[0-9A-Za-z]{6}`；密码持久化在设备端，修改后旧密码立即失效。
- 设备端持续监控局域网地址变化，状态中有当前地址、USB 地址、`endpointChange` 和事件记录；HaminnUI 的开发页面已经显示并可复制地址、USB 命令和密码，也有刷新地址和编辑密码能力。
- MCP 工具已经覆盖全局 session、任意已安装 happ、DEV 工作区、原子热更新、开发树下载、页面刷新、阶段性发布、ZIP 正式安装和运行模式切换。高层入口包括 `haminn_open_agent_session`、`haminn_resume_agent_session`、`haminn_list_installed_happs`、`haminn_get_happ_dev_status`、`haminn_prepare_happ_development`、`haminn_hot_update_happ`、`haminn_download_dev_tree`、`haminn_promote_dev_release`、`haminn_install_happ_release` 和 `haminn_switch_happ_runtime_mode`。
- 全局 session 已按设备范围实现，返回 `sessionId`、`scope=global`，每次操作仍通过 `happId` 或 `appId` 定位目标。
- Python helper 已支持 `connect`、`prepare-dir`、`develop-dir`、`update-dir`、直接同步和 stdio 适配。凭据文件按服务地址哈希保存，`connect` 会私下读取并保存密码。
- helper 的 `install-plugin` 只请求一次根 Bootstrap，再按绝对或相对同源 `packageUrl` 下载并校验 ZIP；默认原子安装到 `~/plugins/haminn-device`，并幂等维护 Codex 个人 marketplace 条目。密码不会写入插件包或 MCP 配置。
- `develop-dir` 会读取本地 `haminn.json`、按 `happId` 匹配实例、调用 `haminn_prepare_happ_development`、比较设备开发版本并询问整体同步策略，然后持续发送热更新。
- Android 测试已经覆盖根 Bootstrap、discovery、插件 ZIP 摘要和 Codex 文件、MCP 协议、认证、密码替换、五次失败锁定、全局 session、DEV 工作区、热更新、构建和发布；helper/合同测试覆盖绝对同源 URL、重定向拒绝、ZIP 幂等安装和 marketplace 条目。

因此当前系统的主要缺口不是 MCP 开发能力不足，而是：**服务地址还没有成为陌生智能体可以直接理解的安装入口，插件安装语义不存在，地址和密码失效也没有收敛成智能体可以执行的恢复合同。**

## 二、目标和设计立场

用户只把当前手机开发服务地址告诉陌生智能体，智能体就应该能完成：

```text
读取地址 → 安装或更新 Haminn 插件 → 读取插件开发说明
→ 连接当前设备 MCP → 识别本地 happ → 准备 DEV → 开始开发
```

开发服务地址同时承担两种角色：

1. **插件引导和更新入口**：根路径必须能说明“这是 Haminn 插件、应该安装什么、如何覆盖更新”；
2. **当前设备能力入口**：安装后使用同一地址的 `/mcp`。

根路径不能通过普通 GET 静默修改智能体本地文件，因为不同智能体的插件目录和持久化机制不同，HTTP GET 也不应隐式产生本地写操作。正确实现是由根路径返回一份带明确 `install` 动作的 bootstrap 清单；用户主动把地址交给智能体即构成安装授权，智能体再调用自身的插件安装机制执行原子安装。

不引入二维码、电脑端扫码、局域网扫描、地址猜测、设备配对、per-client token、TLS 改造或新的密码体系。旧地址不可达时只能请求用户从手机 Haminn 的“开发配置”查看当前地址；密码错误时只能请求用户从同一位置查看当前密码。

## 三、升级后的陌生智能体主路径

插件本身必须把以下行为作为唯一推荐路径传递给智能体：

1. 用户提供一个 HTTP 或 ADB 转发地址；插件请求根路径。
2. 根路径返回 `haminn-agent-bootstrap` 清单；智能体检查插件 ID、版本、包摘要和安装动作。
3. 本地没有 `haminn-device` 时安装；已有同 ID 插件但版本或摘要不同则覆盖更新；用户明确要求重装则即使摘要相同也重新安装。
4. 安装完成后读取 Skill 和 `intentIndex`；只在选定意图后读取对应工具 schema。
5. 尝试本地已保存的地址和密码。旧地址失败时停止重试，向用户说明如何在手机开发配置中获取最新地址。
6. 连接 `/mcp`。没有密码或第一次 401 时停止重试，向用户说明如何获取当前开发密码；429 时遵守 `Retry-After`，不继续试错。
7. 建立全局 session。session 不绑定 happ；目标由每次调用的 `happId` 或 `appId` 决定。
8. 读取本地项目 `haminn.json`，按 `happId` 匹配设备已安装实例；多个实例才询问 `appId`。
9. 读取开发状态。本地版本与设备开发状态不一致时，仅询问一次整体方向：以开发端为准、下载设备树，或暂不同步；不在服务端做三方合并和版本优先裁决。
10. 准备或恢复 DEV，后续优先使用 `haminn_hot_update_happ`。阶段性安装、正式 ZIP 安装和切回 stable 仍由用户明确触发。

## 四、Bootstrap 合同升级

### 4.1 根路径和 discovery

将 `AgentDevelopmentServer` 当前的 `/`、`/connect`、`/.well-known/haminn-agent` 重新分工：

- `/`：Bootstrap 主入口。浏览器请求返回简洁中文页面；JSON 或非浏览器请求返回机器清单。
- `/connect`：保留为人工阅读的连接帮助页，并链接回根 Bootstrap；不再作为唯一说明来源。
- `/.well-known/haminn-agent`：返回与根路径完全相同的机器清单，作为标准 discovery 别名。
- `/mcp`：继续只处理 MCP JSON-RPC。
- `/skills/haminn-device/SKILL.md`、`/haminn-agent.py`：继续保留为独立 fallback 资源。
- 新增 `/plugin/haminn-device`：提供自包含插件包，或在客户端不支持 ZIP 插件时返回清单中声明的独立资源组合。

根路径内容协商规则：

- `Accept: text/html` 返回人工页面；
- `Accept: application/json` 或 `application/vnd.haminn.agent-bootstrap+json` 返回 JSON；
- 没有明确 `Accept` 时默认返回 JSON，确保陌生智能体直接请求地址就能理解下一步。

根响应增加 `Link` 头，指向 `service-desc`、`mcp` 和 `plugin`。根路径与 discovery 使用同一个 `bootstrap()` 生成函数，禁止维护两份协议说明。

### 4.2 机器清单的最小内容

清单必须明确“安装或更新插件”，不能只提供 URL：

```json
{
  "kind": "haminn-agent-bootstrap",
  "protocol": 1,
  "product": "Haminn",
  "serverVersion": "1.10.29",
  "runId": "...",
  "plugin": {
    "id": "haminn-device",
    "version": "1.10.29",
    "displayName": "Haminn happ development"
  },
  "packageFormat": "codex-plugin-archive-v1",
  "codexIntegration": "mcp-stdio",
  "nativeCodexPlugin": true,
  "install": {
    "action": "install_or_update",
    "requiresUserConfirmation": true,
    "packageUrl": "http://PHONE:8766/plugin/haminn-device",
    "packageFormat": "codex-plugin-archive-v1",
    "packageSha256": "...",
    "replaceScope": "haminn-device-only",
    "target": "~/plugins/haminn-device",
    "strategy": "atomic_replace_if_hash_differs",
    "existingSameVersion": "no_op",
    "afterInstall": "register_mcp_then_authenticate",
    "mcpRegistration": {
      "name": "haminn-device",
      "transport": "stdio",
      "credentialMode": "helper-managed",
      "passwordInConfig": false,
      "registrationMode": "codex-plugin",
      "registerCommand": ["codex", "plugin", "add", "haminn-device@personal"]
    },
    "fallback": {
      "skillUrl": "http://PHONE:8766/skills/haminn-device/SKILL.md",
      "helperUrl": "http://PHONE:8766/haminn-agent.py",
      "helperSha256": "..."
    }
  },
  "connection": {
    "mcpUrl": "http://PHONE:8766/mcp",
    "transport": "streamable-http",
    "authorization": "Bearer <current-six-character-password>"
  },
  "resources": {
    "guidanceVersion": "...",
    "schemaDigest": "...",
    "intentIndex": "haminn://intent-index",
    "toolIndex": "haminn://tool-index"
  },
  "recovery": {
    "addressUnavailable": {
      "nextAction": "ask_user_for_current_address",
      "message": "请在手机打开 Haminn 应用，在开发配置中查看并提供当前开发服务地址。"
    },
    "passwordInvalid": {
      "nextAction": "ask_user_for_current_password",
      "message": "请在手机打开 Haminn 应用，在开发配置中查看并提供当前开发密码。"
    }
  },
  "bootstrapInstruction": "安装或更新 haminn-device 插件；安装完成后连接 mcpUrl，并按插件说明准备本地 happ 开发。"
}
```

`clientBootstrap.prompt` 应删除或改为真正的 `bootstrapInstruction`，不能继续返回 `develop-webapp` 这种只对已经了解内部合同的客户端有意义的符号。

### 4.3 插件包和更新

插件包由现有 `SKILL.md`、`haminn-agent.py`、插件 manifest 和安装说明组成。Android 端可以在运行时从 APK asset 生成确定性 ZIP 并缓存其字节和 SHA-256，也可以按资源清单提供等价的独立下载；对客户端暴露的合同必须保持一致。

插件 manifest 至少包含插件 ID、版本、包内文件、摘要、协议版本、替换范围和 fallback 资源。安装器必须：

- 只覆盖 `haminn-device` 自身命名空间；
- 先下载到临时位置并校验摘要、manifest 和协议；
- 原子替换，失败保留旧插件；
- 同版本同摘要默认跳过写入；
- 用户明确要求重装时允许同版本强制覆盖；
- 不把密码、地址中的凭据或项目文件写入插件包。

插件包现在是可被 Codex 标准 marketplace 接受的原生插件，同时保留 Skill/helper 和 stdio fallback。支持远程 MCP 但不支持本地插件的客户端可以直接连接 `/mcp`，并按需读取 Skill；这属于兼容降级，不是另一个开发流程。

## 五、地址和密码错误合同

### 5.1 旧地址失败

当前 helper 的凭据文件按地址哈希保存，适合隔离不同服务，但地址变化时只能新建 profile，helper 也只会抛出普通连接错误。升级为：

- profile 仍按服务地址保存，插件安装状态独立于地址 profile；
- 新对话最多短暂尝试旧地址一次，不扫描局域网、不猜测地址；
- 连接失败输出稳定错误码 `E_ADDRESS_UNAVAILABLE` 和 `nextAction=ask_user_for_current_address`；
- CLI、stdio 和直接 MCP Skill 使用同一段中文提示：

```text
无法连接旧的 Haminn 开发服务地址。
请在手机打开 Haminn 应用，进入“开发配置”，查看当前开发服务地址并提供给我。
拿到新地址后，我会重新读取插件清单并继续连接。
```

用户输入新地址后，插件重新读取根 Bootstrap；插件 ID、版本和摘要没有变化时不重复安装，有更新时执行覆盖更新。

服务端已经在设备端监控网络并记录 `endpointChange`，本计划不要求它向电脑主动推送新地址。该状态继续供 HaminnUI 和后续诊断使用；智能体获取新地址的唯一可靠方式是用户从手机“开发配置”提供。

### 5.2 密码缺失、错误和锁定

当前 401 响应已有 `discoveryUrl`、`instructionsUrl` 和认证说明，但 message 过于泛化；当前 429 主要是文本响应。升级为统一 JSON 错误：

```json
{
  "error": "authentication_required",
  "reason": "invalid_credentials",
  "nextAction": "ask_user_for_current_password",
  "message": "请在手机打开 Haminn 应用，在开发配置中查看并提供当前开发密码。",
  "instructionsUrl": "http://PHONE:8766/connect"
}
```

`429` 增加 `reason=ip_locked`、`nextAction=wait` 和 `retryAfter`。智能体规则固定为：

- 没有本地密码：询问用户当前密码；
- 第一次 401：停止重试，询问密码；
- 429：等待服务端指定时间，不再继续试错；
- 新密码成功认证后，覆盖本地安全凭据；
- 不将密码写入项目文件、Skill、日志、错误回显或模型长期上下文。

UI 已有开发配置页面和密码编辑能力，计划只补齐用户可复制的固定文案，不添加二维码和新的认证流程。

## 六、插件内的开发指导

插件 Skill 必须基于当前已经实现的工具，而不是引用尚未存在的名称。推荐主路径使用：

```text
haminn_open_agent_session / haminn_resume_agent_session
→ haminn_list_installed_happs
→ haminn_get_happ_dev_status
→ haminn_prepare_happ_development
→ haminn_hot_update_happ
→ haminn_promote_dev_release（阶段性）
→ haminn_install_happ_release + haminn_switch_happ_runtime_mode（正式交付）
```

页面状态、设备开发树、单文件和底层诊断接口只按需读取 schema。插件必须继续说明：

- 本地项目中的 `haminn.json` 是 `happId` 来源；不要求用户重复输入；
- 全局 session 可操作任意已安装 happ；多实例才需要显式 `appId`；
- 设备端不做三方合并，整体同步方向由用户选择；
- `hot_update_happ` 默认完成原子提交、自动刷新和页面状态恢复；
- 开发修改不自动构建稳定版本；阶段性安装和正式安装需要用户明确要求；
- `runtimeMode` 与来源、版本、实例、数据和授权保持正交。

有持久进程能力的客户端仍优先使用现有 helper：

```sh
python3 haminn-agent.py --address http://PHONE:8766 develop-dir /path/to/happ --sync-policy ask --quiet
```

helper 不具备持久运行条件时，使用 `prepare-dir` 后由智能体直接调用 MCP 热更新。插件不应要求陌生智能体默认执行完整测试、重新打包或截图。

## 七、代码实施分期

### 阶段 A：服务端 Bootstrap

修改 `AgentDevelopmentServer.kt`：

- 抽出统一 `bootstrap()` 清单生成器；
- 实现 `/` 的 JSON/HTML 内容协商和 `/connect` 兼容页面；
- 让 `/.well-known/haminn-agent` 返回同一清单；
- 增加插件 manifest、插件包响应和摘要；
- 将 `clientBootstrap.prompt` 改为真实安装指令；
- 为 401、429 增加 `nextAction`、恢复文案和稳定错误字段；
- 保持现有 MCP、上传、开发工作区和五次失败锁定行为不变。

### 阶段 B：插件资源和 helper 安装器

修改 `app/src/main/assets/agent/` 与 `haminn-agent.py`：

- 将当前 Skill、helper 和连接说明整理为插件包内容；
- 新增 `install-or-update`，读取根清单并校验插件包；
- 使用插件 ID 和摘要判断跳过、更新或强制重装；
- 保持现有凭据文件权限和“不打印密码”约束；
- 将旧地址、401、429 转换为可供智能体理解的结构化错误和统一中文提示；
- 将现有 `install-skill` 改成从当前开发服务获取并安装对应版本，而不是只写 helper 内嵌文本；
- `client-config` 继续作为不支持自动安装客户端的兼容输出。

### 阶段 C：文档和工具路由收口

- 更新 `SKILL.md`，只保留当前真实存在的工具名和主路径；
- discovery 的 `intentIndex` 继续作为意图路由，schema 按需读取并按摘要缓存；
- 让根 Bootstrap、Skill、MCP `initialize.instructions` 和 `/connect` 使用一致的地址/密码恢复文案；
- 不新增一个把安装、连接、happ 选择和同步强行绑定的巨大 MCP 工具。

### 阶段 D：定向测试和设备验收

在 `AgentDevelopmentTest.kt`、`tools/agent_helper_test.py` 和必要的互操作脚本中补充：

- 根路径 JSON/HTML 内容协商、默认 JSON、Link 头和 discovery 同构；
- Bootstrap 字段完整性、绝不返回密码；
- 插件首次安装、同摘要跳过、版本更新覆盖、同版本强制重装；
- 摘要或 manifest 错误时旧插件保留；
- 旧地址连接失败时输出 `E_ADDRESS_UNAVAILABLE` 和指定中文提示；
- 一次 401 后请求当前密码，429 后遵守 `Retry-After`；
- 新地址重新读取 Bootstrap 且不重复安装同版本插件；
- 现有全局 session、多 happ、整体同步、热更新、阶段性安装和正式安装回归。

## 八、验收标准

1. 陌生智能体只获得一个当前开发服务地址，就能从根路径判断这是 Haminn 插件安装入口，并知道安装完成后连接 `/mcp`。
2. 插件不存在、版本过旧、摘要相同、用户强制重装四种情况分别得到正确行为；覆盖只作用于 Haminn 插件自身。
3. `/` 与 `/.well-known/haminn-agent` 不产生两份相互矛盾的 discovery 合同；人类页面和机器 JSON 都能说明安装、连接和密码位置。
4. 旧地址不可达时，智能体不会扫描或盲猜，而是明确要求用户打开手机 Haminn 的“开发配置”查看当前地址。
5. 密码缺失或第一次 401 时，智能体明确要求用户在“开发配置”查看开发密码；429 时不会继续尝试。
6. 新地址重新接入后可以复用已有插件，并按摘要变化更新 Skill/helper/schema 缓存。
7. 安装后的插件能指导智能体使用当前真实的高层 MCP 工具，完成任意已安装 happ 的 DEV 准备和热更新。
8. 既有 MCP 能力、全局 session、实例、数据、授权、版本和五次失败锁定行为不回归。
9. 全流程不依赖二维码、局域网扫描、额外配对或新密码机制。

## 九、明确不做

不把普通 GET 设计成静默安装本地文件；不假设所有智能体有统一插件目录；不把手机地址变化伪装成可自动发现；不扫描局域网和猜测新 IP；不把密码放进 URL、插件或项目；不新增第二套认证；不重做已经实现的 MCP 开发能力；不因为插件安装而绑定单一 happ 或改变稳定版本发布语义。

完成后，陌生智能体的认知路径应当收敛为：**打开手机开发服务地址，按清单安装或更新 Haminn 插件；插件负责告诉它如何连接 MCP、地址或密码失效时如何询问开发者，以及如何进入任意已安装 happ 的 DEV 开发和部署流程。**
