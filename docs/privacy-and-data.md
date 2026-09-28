# Haminn 隐私与数据边界

Haminn v1 不包含账号、云同步、广告 SDK、宿主遥测服务或 Google Play 服务依赖。实例注册信息、Haminn 记录、逻辑文件、本地代码、页面授权与短期开发配置默认只保存在设备上。二维码图像只在设备内通过随 APK 打包的解码器分析，不上传、不保存，也不下载识别模型。Android 自动备份和设备迁移备份已关闭；需要迁移时由用户在应用库显式导出备份。

这不表示寄居页面不会联网。在线应用本身从其地址加载，本地应用也可以使用浏览器网络 API；用户明确授权后还可以使用 Haminn Native HTTP。网页、远端 API、系统 WebView、TTS 引擎和语音识别服务各自可能处理或发送数据，具体行为取决于页面代码、目标服务和设备提供商。Haminn 不把 WebView Cookie、WebStorage、系统权限、逐应用授权或开发 token 放入导出备份。

敏感能力先经过当前 Web App 的 Haminn grant；麦克风、定位和 Android 17 局域网访问等能力还需满足系统权限。应用库自己的二维码入口会请求 Haminn 相机权限，但不会因此把相机能力授权给任何 Web App；页面拍照仍走各自的 Haminn 能力授权和系统相机。一个 Web App 促使 Haminn 获得系统权限，不会自动使其他 Web App 获得对应 Haminn grant。修改在线应用 Origin 或用备份替换数据会提升信任版本并使旧授权失效。网页站点数据使用共享 WebView 资料空间并遵守标准同源规则。

备份默认不加密，可能包含用户笔记与附件，应保存到可信位置。分享与导出通过 Android URI grant 或系统文件选择器完成，接收方取得的数据副本脱离 Haminn 后不再受 Haminn 控制。删除实例会删除 Haminn 管理的代码与数据并安排 WebView Profile 清理，桌面 Launcher 中的残留固定图标可能仍需用户手动移除。
