# Haminn 智能体开发模式实施计划

版本 1.2.0，2026-09-11。本文是已交付的智能体开发基础协议基线，不再作为后续开发执行计划。极速热更新、常驻连接、`update-dir` 和稳定更新的唯一实施依据是 [统一开发计划](haminnapp-unified-dev-workspace-plan.md)。用户把手机显示的局域网地址和当前六位密码交给智能体，即可发现当前能力、直接认证、开发原生页面并实时发布和运行。WebApp 继续纯 HTML/JS/CSS、无需框架或前端构建；SDK/协议适配属于宿主开发工具。

## 产品与边界

设置新增“智能体开发模式”。用户开启后，密码持有者可操作所有已开放的开发功能和全部应用，无需选择应用范围。入口为 LAN HTTP，明确仅适用于可信局域网；它不提供传输加密，不替代不可信网络中的 HTTPS/SSH 隧道。保留现有单应用 ADB/TLS 部署作为兼容路径，共用安装事务和注册库。

两个开发入口互斥：在手机上开启智能体模式会停止旧单应用部署，反之亦然。修改智能体密码也关闭旧部署入口，避免遗留 token 成为仍在监听的另一条授权路径。导出备份或替换应用数据时停止智能体服务；密码保留。

公开入口只提供版本、连接方法、当前工具描述、指南和固定客户端脚本；读取应用、代码和执行动作均需当前六位数字密码。密码首次用 SecureRandom 随机生成，在应用私有偏好中持久保存，停止/重启/升级不改变；提供“修改密码”按钮重新生成不同值。Haminn 同时只接受一个密码，多台电脑可共享，既不配对也不登记客户端身份，不发放独立令牌。每次请求校验 Authorization: Bearer <password>；改密后旧密码无法发起新动作。代码提交和排队 UI 动作再次校验，改密先完成则旧请求不能再提交；已提交的事务不撤销。密码不放 URL、日志、诊断报告或 Skill。

六位数字是可信 LAN 的便捷凭据，不是强认证；HTTP 可被同网窃听，不应公网暴露。错误密码按来源限速（60 秒内五次失败后返回 429），来源仅用于防暴力尝试，不绑定授权。Host/Origin 检查及请求/文件/并发限额继续执行。不能只撤销一台电脑；需要撤销时修改全局密码或停服，此限制是共享凭据的明确取舍。

开发模式使用持久化总开关：切入后台、锁屏、空闲或 Wi-Fi 临时断开都不会自动关闭，应用进程启动后恢复监听；只有用户明确关闭或切换到互斥的旧部署模式时停用。它仍是应用进程内服务，不通过前台常驻通知规避 Android 强制停止或进程回收。系统权限与每个 WebApp 的能力授权仍由原授权系统处理，MCP 不授予摄像头/麦克风/定位权限，不开放任意 Android Shell、JS 执行、业务数据或登录态读取。

## 统一接口

采用 MCP Streamable HTTP 的 JSON 请求/响应形式，固定 `/mcp`，支持 2025-11-25、2025-06-18、2025-03-26 协商；基础协议不依赖服务器主动 SSE、OAuth、订阅或 listChanged 推送。开发助手可以复用 HTTP keep-alive 连接，连接复用属于传输性能优化，不改变请求/响应合同。GET/DELETE `/mcp` 返回 405，通知返回 202。实现 initialize、ping、tools/list/call、resources/list/read、prompts/list/get。

`/.well-known/haminn-agent` 描述 endpoint、密码认证方式、serverVersion、guidanceVersion、schemaDigest、当前工具目录；`/skills/haminn-device/SKILL.md` 为手机当前动态指南；MCP initialization.instructions 提供简短入口，guide 工具与 resources/read 随时获取最新版。新 APK 重启连接关闭，但密码保留；重新连接后重新发现能力。MCP 配置与 Skill 安装由客户端完成，不能宣称任何客户端只收到 URL 就会自动注册；提供 Python 标准库连接助手、Codex 配置命令、通用 HTTP/stdio MCP 配置和普通 HTTP 调用说明。

工具覆盖运行状态、全部应用列表/详情、新建本地页面、文件列表/读取、原生文件增量更新、打开/刷新、版本列表与回退、动态指南。整包上传走同一服务的受认证 HTTP PUT，供客户端助手归档目录后发布，避免模型传输大段 Base64。所有写代码动作复用 InstallCoordinator 的 staging → 校验 → 不可变 release → CAS 激活链，要求 expectedReleaseId 和幂等键；从不直接改正在运行的目录。新建和补丁重试采用会话内幂等回执；进程终止后的不确定创建不得盲重试，应重新列举确认。

## 执行阶段与验收

2026-09-11 执行结果：以下开发阶段均已完成，1.2.0/code 4 已同签名覆盖安装手机。完整回归 31 项通过，外部 MCP 夹具另行通过 Wi-Fi 双客户端、ZIP 发布及改密联调；视觉与具体客户端产品配置留给用户验收。证据、制品摘要与明确限制见 [交付报告](../docs/validation/v1.2.0-agent-development-report.md)。

1. 固化协议、工具描述、动态指南与连接入口，确保官方文档支持所选择的客户端接入方式。
2. 实现单密码认证、MCP 路由、改密提交保护、安装/补丁/运行统一调度，保持原接口兼容。
3. 完成原生 Store 控制面板、密码展示与修改、连接信息复制与停止；制作无第三方 Python 依赖的助手和轻量 Skill 引导。
4. 在 Android instrumentation 中覆盖未认证/错误来源/共享密码/改密失效/限速/坏包/CAS/重试/后台保持与进程重建恢复，以及完整创建→更新→读取→打开→刷新链；用独立 MCP SDK 客户端验证协议互通。客户端脚本测试密码持久化、凭证文件权限、动态指南读取、上传与本地 Skill 安装，不改写无关配置。
5. 构建签名 1.2.0 APK，同签名覆盖安装目标手机；保存报告。真实 WorkBuddy 界面配置若未实际运行，明确记录为待用户客户端验收。

参考：[MCP HTTP 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[工具](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[资源](https://modelcontextprotocol.io/specification/2025-11-25/server/resources)、[Codex MCP](https://developers.openai.com/codex/mcp/)、[Codex Skills](https://developers.openai.com/codex/skills/)。
