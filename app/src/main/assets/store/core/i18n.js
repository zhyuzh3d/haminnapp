(() => {
  "use strict";
  const H = window.HaminnShell;
  const STORAGE_KEY = "haminn.language";
  const ATTRIBUTES = ["aria-label", "title", "placeholder", "alt"];
  const EN = Object.freeze({
    "Haminn 应用库":"Haminn App Library",
    "主导航":"Main navigation",
    "跳到主要内容":"Skip to main content",
    "应用库":"Library",
    "你的页面应用，都在这里。":"All your page apps, in one place.",
    "读取中…":"Loading…",
    "在 HaminnApp 中，管理你的应用":"Manage your apps in HaminnApp",
    "这是 HaminnUI 界面。普通浏览器无法读取手机上的应用或调用设备能力。":"This is the HaminnUI interface. A regular browser cannot access apps or device capabilities on your phone.",
    "下载 HaminnApp":"Download HaminnApp",
    "阅读使用指南":"Read the guide",
    "添加一个HAPP应用":"Add a happ",
    "添加新应用":"Add a new app",
    "从压缩包":"From ZIP",
    "从网址":"From URL",
    "扫码添加":"Scan to add",
    "添加后收藏":"Add to favorites",
    "应用列表":"App list",
    "收藏":"Favorites",
    "全部":"All",
    "暂时无法读取应用库":"Unable to load the app library",
    "重新读取":"Retry",
    "正在读取应用库…":"Loading app library…",
    "收藏你的第一个应用":"Favorite your first app",
    "添加你的第一个应用":"Add your first app",
    "使用上方方式添加，或点亮已有应用卡片上的爱心。":"Use an option above, or tap the heart on an existing app.",
    "点亮应用卡片上的爱心，常用工具就会集中在这里。":"Tap the heart on an app card to keep frequently used tools here.",
    "添加在线网址或导入原生 HTML、JavaScript 和 CSS 页面。":"Add an online URL or import a native HTML, JavaScript, and CSS page.",
    "原生数据按应用隔离 · 同源页面数据可共享":"Native data is isolated per app · Same-origin site data can be shared",
    "开发服务":"Development service",
    "智能体开发模式":"Agent development mode",
    "正在读取状态…":"Loading status…",
    "开启智能体开发模式":"Enable agent development mode",
    "关闭智能体开发模式":"Disable agent development mode",
    "请在连接同一局域网的电脑上，将下面的插件地址粘贴给智能体开发软件（如 WorkBuddy、Codex），并让它安装插件。安装完成后，就可以让它进入指定 Happ 的开发模式，修改应用并安装更新。":"On a computer connected to the same local network, paste the plugin address below into agent development software such as WorkBuddy or Codex and ask it to install the plugin. Then ask it to enter development mode for a specific happ to make changes and install updates.",
    "Happ开发插件安装地址":"Happ development plugin install address",
    "刷新插件安装地址":"Refresh plugin install address",
    "刷新":"Refresh",
    "复制插件安装地址":"Copy plugin install address",
    "复制":"Copy",
    "备用安装地址（USB连接）":"Fallback install address (USB)",
    "复制 USB 命令":"Copy USB command",
    "仅 USB 启动":"Start with USB only",
    "开发服务密码":"Development service password",
    "开发服务密码，六位数字":"Development service password, six digits",
    "开发服务密码，只读":"Development service password, read only",
    "编辑":"Edit",
    "编辑开发服务密码":"Edit development service password",
    "保存后旧密码立即失效，已连接的智能体需要使用新密码重新连接。":"After saving, the old password becomes invalid immediately and connected agents must reconnect with the new password.",
    "关闭密码编辑":"Close password editor",
    "六位数字密码":"Six-digit password",
    "新的开发服务密码，六位数字":"New development service password, six digits",
    "随机生成":"Generate randomly",
    "保存":"Save",
    "开关和密码会持久保存，只有你主动关闭时才停止开发模式。仅用于可信 Wi-Fi、手机热点或 USB；普通移动网络不能作为可靠的直连地址。":"The switch and password are saved until you turn development mode off. Use only trusted Wi-Fi, a phone hotspot, or USB; mobile data is not a reliable direct connection.",
    "设置":"Settings",
    "设置分类":"Settings categories",
    "界面":"Interface",
    "更新":"Update",
    "更新（实时在线）":"Update (live online)",
    "朗读":"Text to speech",
    "语音":"Speech",
    "系统":"System",
    "外观":"Appearance",
    "主题":"Theme",
    "跟随系统":"Follow system",
    "浅色":"Light",
    "深色":"Dark",
    "界面语言":"Interface language",
    "自动选择系统语言；中文系统使用中文，其他系统使用英文。":"Automatically uses Chinese on a Chinese system and English on any other system.",
    "中文":"中文",
    "当前 APK 与 UI 版本，连续点击三次使用实时在线界面":"Current APK and UI versions; tap three times to use the live online interface",
    "官方最新版本：未知":"Latest official versions: unknown",
    "恢复使用本地界面":"Use local interface",
    "更新本地版本":"Update local version",
    "检查并更新本地版本":"Check for updates",
    "文字朗读（TTS）":"Text to speech (TTS)",
    "正在检测…":"Checking…",
    "由 Haminn 统一适配设备内置朗读服务，happ 无需区分引擎厂商。":"Haminn adapts the device's installed speech engines so happs do not need vendor-specific logic.",
    "朗读引擎":"Speech engine",
    "跟随系统默认":"Use system default",
    "声音":"Voice",
    "自动选择":"Automatic",
    "优先语言":"Preferred language",
    "自动匹配内容":"Match content automatically",
    "普通话（简体中文）":"Mandarin (Simplified Chinese)",
    "粤语（香港）":"Cantonese (Hong Kong)",
    "中文（台湾）":"Chinese (Taiwan)",
    "英语（美国）":"English (United States)",
    "试听已保存设置":"Test saved settings",
    "系统 TTS 设置":"System TTS settings",
    "语音识别（ASR）":"Speech recognition (ASR)",
    "由 Haminn 统一检测并调用系统可用的语音识别能力。":"Haminn detects and uses the speech recognition capabilities available on the system.",
    "连续识别服务":"Continuous recognition service",
    "一次性识别界面":"One-shot recognition interface",
    "识别语言":"Recognition language",
    "优先离线识别":"Prefer offline recognition",
    "服务支持时减少网络依赖；不支持时由系统正常返回。":"Reduces network use when supported; otherwise the system reports that it is unavailable.",
    "系统语音设置":"System speech settings",
    "保存语音设置":"Save speech settings",
    "工具":"Tools",
    "恢复应用备份":"Restore app backup",
    "从备份创建独立的新应用":"Create a separate app from a backup",
    "运行诊断":"Run diagnostics",
    "检查运行环境并复制报告":"Check the runtime and copy a report",
    "开源许可":"Open-source licenses",
    "查看所使用的开源软件与许可证":"View included open-source software and licenses",
    "智能体时代，我的应用我做主。":"In the agent era, my apps are mine to shape.",
    "版本":"Version",
    "作者":"Author",
    "应用标识":"Application ID",
    "打开 GitHub 仓库":"Open GitHub repository",
    "内置图标":"Built-in icons",
    "返回设置":"Back to settings",
    "页面直接使用，无需外部 CDN":"Use directly in pages, with no external CDN",
    "Font Awesome Free 7.3.1 · 实心 / 常规 / 品牌":"Font Awesome Free 7.3.1 · Solid / Regular / Brands",
    "实心":"Solid",
    "常规":"Regular",
    "品牌":"Brands",
    "搜索图标":"Search icons",
    "搜索图标，如 heart、相机、文件":"Search icons, such as heart, camera, or file",
    "图标样式":"Icon style",
    "正在读取图标…":"Loading icons…",
    "显示更多图标":"Show more icons",
    "帮助与支持":"Help and support",
    "支持":"Support",
    "我的应用，我做主。":"My apps, my choice.",
    "一个真实的使用反馈，就能让 Haminn 更好一点。":"One real piece of feedback can make Haminn better.",
    "项目与问题反馈":"Project and issue feedback",
    "开发":"Develop",
    "开发模式已开启":"Development mode is enabled",
    "关闭提示":"Dismiss message",
    "收藏应用":"Favorite app",
    "取消收藏":"Remove from favorites",
    "添加到手机桌面":"Add to Home screen",
    "已添加到手机桌面":"Added to Home screen",
    "当前桌面不支持固定图标":"The current launcher does not support pinned shortcuts",
    "无法确认桌面图标状态":"Unable to determine Home screen shortcut status",
    "添加到桌面":"Add to Home screen",
    "已添加到桌面":"Added to Home screen",
    "桌面不支持":"Launcher unsupported",
    "分享应用":"Share app",
    "没有可分享的本地安装包":"No local package is available to share",
    "管理":"Manage",
    "应用配置":"App configuration",
    "确认添加应用":"Confirm app",
    "关闭添加":"Close add dialog",
    "选择图标":"Choose icon",
    "应用名称":"App name",
    "给它取个好记的名字":"Give it a memorable name",
    "安装完成后直接显示在收藏列表。":"Show in Favorites immediately after installation.",
    "页面网址":"Page URL",
    "https://example.com、ZIP 或 Git 仓库":"https://example.com, a ZIP, or a Git repository",
    "确认添加":"Add",
    "设备间分享":"Device-to-device sharing",
    "分享 happ":"Share happ",
    "关闭分享弹窗":"Close sharing dialog",
    "happ 分享二维码":"happ sharing QR code",
    "保存安装包":"Save package",
    "分享安装包":"Share package",
    "停止分享":"Stop sharing",
    "来自另一台 Haminn 设备":"From another Haminn device",
    "确认安装 happ":"Confirm happ installation",
    "取消安装":"Cancel installation",
    "未标注":"Not specified",
    "来源":"Source",
    "签名":"Signature",
    "创建桌面图标":"Create Home screen icon",
    "安装完成后立即向手机桌面申请添加快捷图标。":"Request a Home screen shortcut immediately after installation.",
    "下载安装":"Download and install",
    "设置：应用名称":"Settings: App name",
    "关闭设置":"Close settings",
    "更换应用图标":"Change app icon",
    "更换图标":"Change icon",
    "移除图标":"Remove icon",
    "自定义图标将会覆盖默认图标。":"A custom icon overrides the default icon.",
    "运行方式":"Runtime mode",
    "happ 运行方式":"happ runtime mode",
    "本地运行":"Run locally",
    "线上实时运行":"Run live online",
    "地址与更新":"Addresses and updates",
    "本地挂载地址（liveUrl）":"Local mount address (liveUrl)",
    "一键更新地址（updateUrl）":"One-click update address (updateUrl)",
    "检查更新":"Check for updates",
    "当前版本下载地址":"Current version download address",
    "无":"None",
    "从本机文件安装":"Installed from a local file",
    "从链接地址安装":"Installed from a link",
    "二维码链接地址":"QR code link address",
    "应用能力":"App capabilities",
    "接收通知":"Receive notifications",
    "允许接收通知":"Allow notifications",
    "允许此 happ 投递即时、计划和服务器通知。":"Allow this happ to send immediate, scheduled, and server notifications.",
    "允许跨 Origin 网络":"Allow cross-origin network access",
    "放行脚本、接口、页面等主动域外通信，运行时持续显示提示。":"Allow scripts, APIs, and pages to initiate requests outside their origin; a notice remains visible while running.",
    "页面授权":"Page permissions",
    "页面授权是你允许这个 happ 使用某项手机或 Haminn 能力的记录，例如拍照、通知或读取剪贴板。它只对当前应用生效；重置后，下次需要时会再次询问。":"Page permissions record the phone or Haminn capabilities you allow this happ to use, such as the camera, notifications, or clipboard. They apply only to this app; after reset, Haminn asks again when needed.",
    "代码版本":"Code versions",
    "开发工作副本":"Development workspace",
    "正式运行":"Running the stable version",
    "开发副本模式运行":"Running the development workspace",
    "切换到正式版":"Switch to stable",
    "当前运行的是开发工作副本。请选择如何回到正式版。":"This happ is running its development workspace. Choose how to return to the stable version.",
    "当前开发版本":"Current development version",
    "原有正式版本":"Original stable version",
    "直接切换到原有正式版":"Switch to the original stable version",
    "把开发版升级为正式版":"Promote the development version to stable",
    "导出当前运行版本为 Zip 安装包":"Export the running version as a ZIP package",
    "数据与卸载":"Data and uninstall",
    "从备份恢复会覆盖当前 Haminn 数据并重置页面授权。卸载时可选择保留数据，或彻底清除。":"Restoring a backup overwrites current Haminn data and resets page permissions. When uninstalling, you can retain or permanently delete the data.",
    "导出应用备份":"Export app backup",
    "从备份恢复":"Restore from backup",
    "卸载应用":"Uninstall app",
    "调整应用图标":"Adjust app icon",
    "拖动图片调整位置，双指捏合或使用按钮缩放。":"Drag to reposition the image; pinch or use the buttons to zoom.",
    "关闭图标裁切":"Close icon cropper",
    "图标裁切预览":"Icon crop preview",
    "缩小图标":"Zoom out",
    "放大图标":"Zoom in",
    "取消":"Cancel",
    "使用图标":"Use icon",
    "关闭卸载确认":"Close uninstall confirmation",
    "请选择卸载后是否保留这个应用的数据。保留数据后，日后安装同一 happ 可以继续使用；彻底清除不可恢复。":"Choose whether to retain this app's data after uninstalling. Retained data can be reused if the same happ is installed again; permanent deletion cannot be undone.",
    "保留数据卸载":"Uninstall and retain data",
    "彻底清除":"Delete permanently",
    "开发服务地址已变化":"Development service address changed",
    "请重新告知智能体软件新的地址。":"Give the new address to your agent software.",
    "关闭地址变化提示":"Close address change notice",
    "原地址":"Previous address",
    "当前地址":"Current address",
    "我知道了":"Got it",
    "运行环境":"Runtime environment",
    "关闭诊断":"Close diagnostics",
    "报告不含设备序列号、页面数据、开发令牌或文件内容。":"The report excludes device serial numbers, page data, development tokens, and file contents.",
    "复制诊断报告":"Copy diagnostics report",
    "开源组件":"Open-source components",
    "关闭许可":"Close licenses",
    "确认操作":"Confirm action",
    "继续":"Continue",
    "处理中…":"Working…",
    "正在完成操作，请稍候。":"Finishing the current operation. Please wait.",
    "已取消操作。":"Operation cancelled.",
    "操作失败，请重试。":"Operation failed. Try again.",
    "暂不可用":"Unavailable",
    "未连接":"Not connected",
    "未连接 HaminnApp":"Not connected to HaminnApp",
    "请在 HaminnApp 中查看开发状态。":"View development status in HaminnApp.",
    "页面恢复失败，请重试。":"Unable to restore the page. Try again.",
    "应用库读取失败，请重试。":"Unable to load the app library. Try again.",
    "桌面图标状态刷新失败，请重试。":"Unable to refresh Home screen shortcut state. Try again.",
    "开发状态读取失败，进入开发页可重试。":"Unable to read development status. Open Develop to try again.",
    "放弃尚未保存的修改？":"Discard unsaved changes?",
    "已保存的应用数据不受影响。":"Saved app data is not affected.",
    "放弃修改":"Discard changes",
    "原应用已不存在，已返回应用列表。":"The original app no longer exists. Returned to the app list.",
    "原页面已不可用，已返回收藏页。":"The original page is unavailable. Returned to Favorites.",
    "界面状态恢复失败，已保留当前页面。":"Unable to restore interface state. The current page was kept.",
    "让任意电脑的智能体快速更新、刷新和发布 happ。":"Let an agent on any computer quickly update, refresh, and publish happs.",
    "调整外观、界面版本与常用工具。":"Adjust appearance, interface version, and common tools.",
    "支持 Haminn":"Support Haminn",
    "向项目提交反馈，并查看开源仓库。":"Send project feedback and view the open-source repository.",
    "搜索并复制页面可直接使用的图标。":"Search and copy icons for direct use in a page.",
    "图标目录读取失败，请切换页面重试。":"Unable to load the icon catalog. Switch pages and try again.",
    "页面刷新失败，请重试。":"Unable to refresh the page. Try again.",
    "图标图片尺寸无效。":"The icon image dimensions are invalid.",
    "没有取得可用的图标图片。":"No usable icon image was received.",
    "图标图片无法读取。":"Unable to read the icon image.",
    "图标裁切失败，请换一张图片。":"Unable to crop the icon. Try another image.",
    "离线图标目录读取失败。":"Unable to load the offline icon catalog.",
    "没有匹配的图标，试试英文名称或其他关键词。":"No matching icons. Try an English name or another keyword.",
    "无可用地址":"No available address",
    "未连接 Wi-Fi 或手机热点":"Not connected to Wi-Fi or a phone hotspot",
    "开发服务暂不可达":"Development service temporarily unreachable",
    "开发模式仍保持开启。请连接 Wi-Fi 或手机热点，恢复后会自动生成可用地址。":"Development mode remains enabled. Connect to Wi-Fi or a phone hotspot; an available address will be generated automatically.",
    "已开启 · 普通 happ 可开发":"Enabled · happ development available",
    "已开启 · 等待 Wi-Fi 或手机热点":"Enabled · waiting for Wi-Fi or a phone hotspot",
    "已开启 · 服务正在恢复":"Enabled · service recovering",
    "未开启":"Disabled",
    "未连接 Wi-Fi":"Not connected to Wi-Fi",
    "开发模式已关闭。":"Development mode disabled.",
    "当前没有可用的开发服务地址。请打开 Wi-Fi 并连接到局域网后重试，或使用下方“仅 USB 启动”。":"No development service address is available. Turn on Wi-Fi and connect to a local network, then try again, or use “Start with USB only” below.",
    "开启智能体开发模式？":"Enable agent development mode?",
    "持有密码的电脑可修改普通 happ 的开发副本。请仅在可信局域网使用。":"A computer with the password can modify development workspaces for regular happs. Use only on a trusted local network.",
    "开启开发模式":"Enable development mode",
    "开发模式已开启，请仅向可信智能体分享连接信息。":"Development mode enabled. Share connection details only with trusted agents.",
    "仅通过 USB 启动？":"Start through USB only?",
    "电脑需要执行页面显示的 adb forward 命令，接口和开发副本与局域网模式完全相同。":"The computer must run the adb forward command shown on this page. The API and development workspaces are the same as in LAN mode.",
    "启动 USB 模式":"Start USB mode",
    "USB 开发服务已启动，请在电脑执行转发命令。":"USB development service started. Run the forwarding command on the computer.",
    "密码必须是 6 位数字。":"The password must contain six digits.",
    "新密码已生效，旧密码已失效。":"The new password is active and the old one is invalid.",
    "开发服务尚未启动。":"The development service has not started.",
    "插件安装地址已刷新。":"Plugin install address refreshed.",
    "开发模式保持开启，正在等待 Wi-Fi 或手机热点。":"Development mode remains enabled and is waiting for Wi-Fi or a phone hotspot.",
    "USB 命令":"USB command",
    "线上安装包":"Online package",
    "GitHub 仓库":"GitHub repository",
    "GitLab 仓库":"GitLab repository",
    "Gitee 仓库":"Gitee repository",
    "本地 ZIP":"Local ZIP",
    "本地文件夹":"Local folder",
    "本地创建":"Created locally",
    "线上网址":"Online URL",
    "线上 happ":"Online happ",
    "本地 happ":"Local happ",
    "未标注版本":"Version not specified",
    "运行开发副本 · 本机代码":"Development workspace · On-device code",
    "本机代码":"On-device code",
    "已加入收藏。":"Added to Favorites.",
    "已取消收藏。":"Removed from Favorites.",
    "桌面图标仍然存在；如需移除，请在桌面长按图标删除。":"The Home screen icon still exists. Long-press it on the Home screen to remove it.",
    "当前桌面不支持固定图标。":"The current launcher does not support pinned shortcuts.",
    "已请求添加到手机桌面，请确认系统提示。":"Home screen shortcut requested. Confirm the system prompt.",
    "二维码链接":"QR code link",
    "在线网址":"Online URL",
    "解析并确认":"Resolve and confirm",
    "解析地址":"Resolve address",
    "解析应用来源":"Resolve app source",
    "请先解析地址；解析完成后可修改应用名称和图标。普通网页会实时运行；ZIP 会校验后本地安装。":"Resolve the source first. You can edit the app name and icon after resolution. Web pages run live; ZIP packages are verified and installed locally.",
    "解析完成后可修改应用名称和图标":"You can edit the app name and icon after resolving the source.",
    "普通网页将以线上实时方式添加。":"This page will be added as a live online happ.",
    "地址已解析，确认后继续安装。":"Address resolved. Confirm to continue.",
    "已解析二维码地址":"QR address resolved",
    "已解析网址":"Address resolved",
    "继续安装":"Continue installation",
    "地址已解析，确认页中可以修改应用名称和图标。":"Address resolved. You can edit the app name and icon on the confirmation screen.",
    "确认安装":"Install",
    "已解析安装包":"Resolved package",
    "压缩包内没有 haminn.json，将作为普通本地页面安装。":"The ZIP has no haminn.json; it will be installed as a plain local page.",
    "安装包已校验并安装。":"Package verified and installed.",
    "已解析到 happ 信息，确认后开始安装。":"happ details resolved. Confirm to start installing.",
    "压缩包已解压并解析，确认后开始安装。":"ZIP unpacked and parsed. Confirm to start installing.",
    "普通网页会实时运行；ZIP 会下载校验后本地安装；Git 仓库优先读取 haminn-install.json，缺失时再选择仓库中实际存在的发布目录。":"Regular pages run live; ZIPs are downloaded, verified, and installed locally; Git repositories use haminn-install.json when present, otherwise you choose an existing release directory.",
    "压缩包已校验并安装。":"ZIP verified and installed.",
    "请输入完整的 HTTPS 地址。":"Enter a complete HTTPS address.",
    "请输入以 https:// 或 http:// 开头的完整网址。":"Enter a complete URL beginning with https:// or http://.",
    "已取消添加。":"Add cancelled.",
    "添加信息已失效，请重新选择来源。":"The add request expired. Choose the source again.",
    "应用名称不能为空。":"App name cannot be empty.",
    "允许未加密的 HTTP 页面？":"Allow an unencrypted HTTP page?",
    "网页内容和凭据可能被同一网络中的其他人读取或篡改。仅在你信任当前网络和服务时继续。":"Page content and credentials may be read or modified by others on the same network. Continue only if you trust the network and service.",
    "仍然添加":"Add anyway",
    "全新安装":"Install as new",
    "更新原实例":"Update existing app",
    "已安装同一签名的 happ":"Same signed happ already installed",
    "发现相同 happId 的实例":"Existing instance with the same happId",
    "选择网页发布目录":"Choose web release directory",
    "来源内容已校验并安装为本地 happ，可创建开发副本。":"Source verified and installed as a local happ. A development workspace can be created.",
    "普通网页已添加为实时 happ；没有本地代码，不能进入开发模式。":"The regular page was added as a live happ. It has no local code and cannot enter development mode.",
    "保存已取消。":"Save cancelled.",
    "应用地址":"App addresses",
    "名称和图标":"Name and icon",
    "图标":"Icon",
    "名称":"Name",
    "通知设置":"Notification settings",
    "网络设置":"Network settings",
    "已保存":"Saved ",
    "；其余修改未完成。":"; the remaining changes were not completed. ",
    "拍照":"Take photo",
    "麦克风录音":"Microphone recording",
    "语音识别":"Speech recognition",
    "大致位置":"Approximate location",
    "精确位置":"Precise location",
    "读取剪贴板":"Read clipboard",
    "网络请求":"Network requests",
    "发送通知":"Send notifications",
    "开发副本与正式版本一致。":"The development workspace matches the stable version.",
    "开发副本有未发布修改。":"The development workspace has unpublished changes.",
    "正式版本已更新，开发副本仍保留未发布修改。":"The stable version was updated; unpublished development changes were retained.",
    "开发副本状态暂不可用。":"Development workspace status is unavailable.",
    "应用已不存在，请刷新应用库。":"The app no longer exists. Refresh the library.",
    "正在读取授权…":"Loading permissions…",
    "正在读取版本…":"Loading versions…",
    "授权信息读取失败，请重新打开管理面板。":"Unable to load permissions. Reopen the management panel.",
    "尚无持久授权。使用相关功能时会向你申请。":"No persistent permissions. Haminn asks when a capability is used.",
    "已允许":"Allowed",
    "已拒绝":"Denied",
    "重置":"Reset",
    "已重置，下次使用会重新询问。":"Reset. Haminn will ask again next time.",
    "尚无持久授权。":"No persistent permissions.",
    "版本信息读取失败，请重新打开管理面板。":"Unable to load versions. Reopen the management panel.",
    "当前使用":"Active",
    "切换代码版本":"Switch code version",
    "页面代码会切换到此版本，现有数据保留。请确保旧版代码与当前数据兼容。":"Page code will switch to this version while existing data is retained. Make sure the older code is compatible with current data.",
    "切换版本":"Switch version",
    "代码版本已切换。":"Code version switched.",
    "应用设置已失效，请重新打开。":"App settings expired. Reopen them.",
    "允许跨 Origin 网络？":"Allow cross-origin network access?",
    "允许":"Allow",
    "允许后，此 happ 的脚本、接口和页面可以主动连接 liveUrl Origin 之外的地址，运行时会持续显示提示。":"After allowing, this happ's scripts, APIs, and pages can initiate connections outside the liveUrl origin. A notice remains visible while it runs.",
    "以后打开此 happ 将直接加载它的线上地址，页面代码可随服务器变化。":"This happ will load its online address directly, so page code can change with the server.",
    "以后打开此 happ 将优先运行手机内已验证的本地代码。":"This happ will prefer the verified on-device code.",
    "应用已不存在。":"The app no longer exists.",
    "请填写有效的新版本号和版本名称。":"Enter a valid new version code and version name.",
    "已取消导出。":"Export cancelled.",
    "发布 ZIP 已导出；正式版本尚未改变。":"Release ZIP exported; the stable version is unchanged.",
    "已从更新地址下载并安装代码，数据已保留。":"Code downloaded and installed from the update address; data was retained.",
    "重新安装？":"Reinstall?",
    "将替换当前代码，Haminn 数据保留。":"Current code will be replaced and Haminn data retained.",
    "重新安装":"Reinstall",
    "已从原地址重新安装，数据已保留。":"Reinstalled from the original address; data was retained.",
    "将创建独立的新应用，不继承登录状态和页面授权。":"A separate new app will be created without login state or page permissions.",
    "选择备份":"Choose backup",
    "原有 Haminn 记录和附件会被备份内容覆盖，页面授权重置。此操作无法撤销，请先导出当前备份。":"Existing Haminn records and attachments will be overwritten by the backup and page permissions reset. This cannot be undone; export the current backup first.",
    "选择备份并替换":"Choose backup and replace",
    "数据已恢复，页面授权已重置。":"Data restored and page permissions reset.",
    "将删除这个应用及其全部 Haminn 本机数据，无法恢复。":"This app and all of its local Haminn data will be permanently deleted.",
    "应用已卸载并彻底清除数据。":"App uninstalled and data permanently deleted.",
    "应用已卸载，数据仍保留在 HaminnApp 中。":"App uninstalled and data retained in HaminnApp.",
    "包含代码、Haminn 记录和附件，不包含网站登录状态。备份不加密，请保存到可信位置。":"Includes code, Haminn records, and attachments, but not website login state. The backup is not encrypted; save it in a trusted location.",
    "选择保存位置":"Choose save location",
    "备份已导出。":"Backup exported.",
    "诊断报告":"Diagnostics report",
    "正在读取声音…":"Loading voices…",
    "需联网":"Network required",
    "可离线":"Available offline",
    "未知语言":"Unknown language",
    "声音列表不可用":"Voice list unavailable",
    "朗读引擎不可用":"Speech engine unavailable",
    "已自动切换":"Automatically switched",
    "可用":"Available",
    "系统引擎":"System engine",
    "没有可用朗读引擎":"No speech engine is available",
    "连续识别 · 支持离线":"Continuous recognition · Offline supported",
    "连续识别可用":"Continuous recognition available",
    "仅支持一次性识别":"One-shot recognition only",
    "系统未提供语音识别":"No system speech recognition service",
    "系统语音设置已保存。":"System speech settings saved.",
    "实时在线":"Live online",
    "本地":"Local",
    "界面更新暂时不可用，继续使用当前本地界面。":"Interface update is temporarily unavailable. Continuing with the current local interface.",
    "语音能力检测失败":"Speech capability check failed",
    "界面模式暂不可用":"Interface mode unavailable",
    "此 happ 没有可分享的本地安装包。":"This happ has no local package to share.",
    "开发快照":"Development snapshot",
    "正式版本":"Stable version",
    "让朋友使用Haminn应用扫码即可安装同款应用。二维码1小时有效。":"Have your friend scan this with the Haminn app to install the same happ. The QR code is valid for one hour.",
    "未检测到可用局域网，不会启动下载服务。你仍可保存或发送安装包。":"No available local network was detected, so the download service was not started. You can still save or send the package.",
    "应用名称已更新。":"App name updated.",
    "应用图标已更新。":"App icon updated.",
    "已移除自定义图标，恢复 happ 默认图标。":"Custom icon removed; the happ default icon is used again.",
    "已允许接收通知。":"Notifications allowed.",
    "已停止接收通知。":"Notifications stopped.",
    "已允许跨 Origin 网络。":"Cross-origin network access allowed.",
    "已禁止跨 Origin 网络。":"Cross-origin network access blocked.",
    "已切换为线上实时运行。":"Switched to live online running.",
    "已切换为本地运行。":"Switched to local running.",
    "切换运行方式？":"Switch the runtime mode?",
    "切换":"Switch",
    "已切换为运行原有正式版，开发副本仍保留。":"Switched to the original stable version; the development workspace is kept.",
    "当前开发版已安装为正式版并开始运行。":"The development version was installed as the new stable version and is now running.",
    "当前运行版本已导出为 Zip 安装包。":"The running version was exported as a ZIP package.",
    "另一台设备":"Another device",
    "带发布者签名":"Publisher signature present",
    "未签名开发快照":"Unsigned development snapshot",
    "未签名安装包":"Unsigned package",
    "分享会话已结束。":"The sharing session has ended.",
    "已取消保存。":"Save cancelled.",
    "安装包已保存。":"Package saved.",
    "已停止分享。":"Sharing stopped.",
    "分享信息已失效，请重新扫码。":"Sharing details expired. Scan again.",
    "happ 已安装，已请求创建桌面图标。":"happ installed and Home screen icon requested.",
    "happ 已安装，当前桌面没有接受快捷图标请求。":"happ installed, but the current launcher did not accept the shortcut request.",
    "happ 已安装。":"happ installed.",
    "未登记的宿主操作":"Unregistered host operation",
    "请在 HaminnApp 中使用此功能。":"Use this feature in HaminnApp.",
    "当前 APK 不支持此功能，请更新 HaminnApp。":"The current APK does not support this feature. Update HaminnApp.",
    "请在 HaminnApp 中复制内容。":"Copy content in HaminnApp."
  });

  function pattern(value) {
    let match;
    if ((match = /^已收藏【(\d+)】个HAPP应用$/.exec(value))) return match[1] + " favorite happ" + (match[1] === "1" ? "" : "s");
    if ((match = /^已安装【(\d+)】个HAPP应用$/.exec(value))) return match[1] + " installed happ" + (match[1] === "1" ? "" : "s");
    if ((match = /^版本 (.+)$/.exec(value))) return "Version " + match[1];
    if ((match = /^设置：(.+)$/.exec(value))) return "Settings: " + match[1];
    if ((match = /^分享“(.+)”$/.exec(value))) return "Share “" + match[1] + "”";
    if ((match = /^已恢复“(.+)”。$/.exec(value))) return "Restored “" + match[1] + "”.";
    if ((match = /^替换“(.+)”的数据？$/.exec(value))) return "Replace data for “" + match[1] + "”?";
    if ((match = /^彻底清除“(.+)”？$/.exec(value))) return "Permanently delete “" + match[1] + "”?";
    if ((match = /^导出“(.+)”的备份$/.exec(value))) return "Export a backup of “" + match[1] + "”";
    if ((match = /^“(.+)”具有相同 happId 和发布者公钥。更新原实例会保留它的数据、设置和授权；全新安装会创建相互隔离的新实例。$/.exec(value))) return "“" + match[1] + "” has the same happId and publisher key. Updating keeps its data, settings, and permissions; installing as new creates an isolated instance.";
    if ((match = /^“(.+)”使用相同 happId，但发布者公钥与当前包不同或缺失。只有你确认这是同一 happ 时才更新原实例；更新会采用新包的发布者信息并保留数据与授权。$/.exec(value))) return "“" + match[1] + "” uses the same happId, but its publisher key differs or is missing. Update only if you know it is the same happ; the new publisher details will be adopted while data and permissions are retained.";
    if ((match = /^“(.+)”使用相同 happId，但双方都没有可验证的发布者签名。仅在你确认它们是同一 happ 时更新原实例；也可以创建隔离的新实例。$/.exec(value))) return "“" + match[1] + "” uses the same happId, but neither package has a verifiable publisher signature. Update only if you know they are the same happ, or create an isolated new instance.";
    if ((match = /^(.+) 仓库没有提供 haminn-install\.json。请选择要作为 happ 根目录的现有发布目录；Haminn 会先复制到临时区并校验入口与配置，再提交安装。$/.exec(value))) return "The " + match[1] + " repository does not provide haminn-install.json. Choose an existing release directory to use as the happ root; Haminn copies it to a temporary area and validates the entry and configuration before installation.";
    if ((match = /^(\d+) 个图标 · 已显示 (\d+)$/.exec(value))) return match[1] + " icons · " + match[2] + " shown";
    if ((match = /^复制 (.+) (.+) 图标代码$/.exec(value))) return "Copy " + match[1] + " " + match[2] + " icon code";
    if ((match = /^当前版本：APK (.+) · UI (.+)$/.exec(value))) return "Current versions: APK " + match[1] + " · UI " + match[2];
    if ((match = /^官方最新版本：APK (.+) · UI (.+)$/.exec(value))) return "Latest official versions: APK " + match[1] + " · UI " + match[2];
    if ((match = /^本地界面已更新到 (.+)。$/.exec(value))) return "Local interface updated to " + match[1] + ".";
    if ((match = /^Haminn APK (.+) 已下载，正在打开 Android 安装界面。请确认安装。$/.exec(value))) return "Haminn APK " + match[1] + " is downloaded. Confirm installation in Android.";
    if ((match = /^Haminn APK (.+) 已下载。请允许 Haminn 安装应用，返回后会继续。$/.exec(value))) return "Haminn APK " + match[1] + " is downloaded. Allow Haminn to install apps; installation will continue when you return.";
    if ((match = /^HaminnUI 已更新到 (.+)，安装成功。$/.exec(value))) return "HaminnUI updated to " + match[1] + ". Installation succeeded.";
    if (value === "Haminn 和 HaminnUI 已是最新版本。") return "Haminn and HaminnUI are up to date.";
    if ((match = /^(.+)已复制。$/.exec(value))) return (EN[match[1]] || match[1]) + " copied.";
    if ((match = /^已保存(.+)；其余修改未完成。(.*)$/.exec(value))) return "Saved " + match[1] + "; the remaining changes were not completed. " + match[2];
    if ((match = /^未登记的宿主操作：(.+)$/.exec(value))) return "Unregistered host operation: " + match[1];
    const insecure = "\n\n网页内容和凭据可能被同一网络中的其他人读取或篡改。仅在你信任当前网络和服务时继续。";
    if (value.endsWith(insecure)) return value.slice(0, -insecure.length) + "\n\n" + EN[insecure.trim()];
    return null;
  }

  function english(value) {
    if (Object.prototype.hasOwnProperty.call(EN, value)) return EN[value];
    const direct = pattern(value);
    if (direct != null) return direct;
    if (value.includes(" · ")) return value.split(" · ").map(part => EN[part] || pattern(part) || part).join(" · ");
    return value;
  }

  function translate(value) {
    if (current !== "en" || typeof value !== "string") return value;
    const match = /^(\s*)(.*?)(\s*)$/s.exec(value);
    return match[1] + english(match[2]) + match[3];
  }

  function systemChoice(language) { return String(language || "").toLowerCase() === "zh" ? "zh-CN" : "en"; }
  function readPreference() {
    try { const value = localStorage.getItem(STORAGE_KEY); return ["system", "zh-CN", "en"].includes(value) ? value : "system"; }
    catch (_) { return "system"; }
  }
  let preference = readPreference();
  let systemLanguage = systemChoice((navigator.languages && navigator.languages[0] || navigator.language || "en").split("-")[0]);
  let current = preference === "system" ? systemLanguage : preference;
  const sourceText = new WeakMap();
  const sourceAttributes = new WeakMap();
  let observer;

  function ignored(node) {
    const parent = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
    return !!(parent && parent.closest("script,style,code,pre,[data-i18n-ignore]"));
  }
  function textNode(node, capture) {
    if (ignored(node)) return;
    if (capture || !sourceText.has(node)) sourceText.set(node, node.data);
    const next = translate(sourceText.get(node));
    if (node.data !== next) node.data = next;
  }
  function attribute(element, name, capture) {
    if (!element.hasAttribute(name)) return;
    let values = sourceAttributes.get(element);
    if (!values) { values = {}; sourceAttributes.set(element, values); }
    if (capture || !(name in values)) values[name] = element.getAttribute(name);
    const next = translate(values[name]);
    if (element.getAttribute(name) !== next) element.setAttribute(name, next);
  }
  function tree(root, capture) {
    if (root.nodeType === Node.TEXT_NODE) { textNode(root, capture); return; }
    if (root.nodeType !== Node.ELEMENT_NODE && root.nodeType !== Node.DOCUMENT_FRAGMENT_NODE && root.nodeType !== Node.DOCUMENT_NODE) return;
    if (root.nodeType === Node.ELEMENT_NODE) ATTRIBUTES.forEach(name => attribute(root, name, capture));
    const walker = document.createTreeWalker(root, 5);
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (node.nodeType === Node.TEXT_NODE) textNode(node, capture);
      else ATTRIBUTES.forEach(name => attribute(node, name, capture));
    }
  }
  function observe() {
    observer.observe(document.documentElement, { subtree:true, childList:true, characterData:true, attributes:true, attributeFilter:ATTRIBUTES });
  }
  function apply(capture = false) {
    observer.disconnect();
    document.documentElement.lang = current;
    document.documentElement.dir = "ltr";
    tree(document.documentElement, capture);
    observe();
    window.dispatchEvent(new CustomEvent("haminnlanguagechange", { detail:{ language:current, preference, systemLanguage } }));
  }
  observer = new MutationObserver(records => {
    observer.disconnect();
    for (const record of records) {
      if (record.type === "characterData") textNode(record.target, true);
      else if (record.type === "attributes") attribute(record.target, record.attributeName, true);
      else record.addedNodes.forEach(node => tree(node, true));
    }
    observe();
  });
  function setPreference(value) {
    preference = ["system", "zh-CN", "en"].includes(value) ? value : "system";
    try { localStorage.setItem(STORAGE_KEY, preference); } catch (_) {}
    current = preference === "system" ? systemLanguage : preference;
    apply(false);
    return current;
  }
  async function syncSystemLanguage() {
    if (!window.haminn || !window.haminn.isReady) return;
    try {
      const value = await window.haminn.system.language();
      systemLanguage = systemChoice(value && value.language);
      if (preference === "system" && current !== systemLanguage) { current = systemLanguage; apply(false); }
    } catch (_) {}
  }
  H.i18n = { t:translate, current:() => current, preference:() => preference, setPreference, syncSystemLanguage };
  apply(true);
  addEventListener("haminnready", syncSystemLanguage);
  if (window.haminn && window.haminn.isReady) syncSystemLanguage();
})();
