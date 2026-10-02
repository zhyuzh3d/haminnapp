# HaminnApp

**Haminn** 是一个面向 Android 的 happ 容器：它直接运行 HTML,CSS,JavaScript 页面,并为这些页面提供安装,版本,数据,文件,权限和系统能力。它不把网页重新包装成一个个独立 APK,而是在一个开放,轻量,可离线工作的宿主里同时管理多个页面应用。

> 官网：<https://haminn.airen.life/> · [下载 Android 安装包](https://haminn.airen.life/pages/download.html) · [使用指南](https://haminn.airen.life/pages/guide.html) · [应用广场](https://haminn.airen.life/pages/happs.html) · [GitHub Releases](https://github.com/zhyuzh3d/haminnapp/releases) · [MIT License](./LICENSE)

- 包名：`life.airen.haminn`
- 当前源码版本：`1.12.15`(versionCode `97`),由 `app/build.gradle.kts` 决定
- 运行要求：Android 10 及以上(minSdk 29),无需 Google Play 服务
- 形态：Kotlin + Gradle(Android 宿主 APK),页面侧为原生 HTML / CSS / JavaScript

> 版本号以 `app/build.gradle.kts` 的 `versionName` / `versionCode` 为准,正式构建时由 `scripts/build-release.sh` 通过 `-PhaminnVersionName` / `-PhaminnVersionCode` 注入。标准 APK 命名为 `haminn-v<version>-release.apk`；官网另提供同版本号的官方集成版 OIV，命名为 `haminn-v<version>-oiv-release.apk`。GitHub Releases 只发布标准 APK。OIV 选择清单在 `tools/oiv-happs.json`；正式构建时从 HaminnWeb 当前已发布的 `haminn-install.json` 和 ZIP 生成集成包，happ 单独发布不会改动已发布 OIV。

---

## 这是什么

Haminn 的目标不是又一个浏览器,而是一个**应用容器**：每个 happ 就是一份可以直接运行的静态页面目录,导入后由 Haminn 负责安装,运行,版本切换,数据隔离和系统能力授权。因为页面源码就是最终产物,任何装了 Haminn 的人都能读懂,修改并重新运行它。

打开 Haminn 看到的是 **HaminnUI** —— 宿主默认加载,并拥有宿主管理权限的官方 happ。安装,运行,权限,数据,备份和智能体开发入口都在这里,用户不需要额外安装任何界面。

## 功能演示

<p align="center">
  <a href="https://www.youtube.com/watch?v=RkRiN4SVqiA">
    <img src="docs/images/haminn-feature-tour-cover.png" alt="Watch the Haminn feature tour on YouTube" width="260">
  </a>
</p>

[在 YouTube 上观看 Haminn 功能演示](https://www.youtube.com/watch?v=RkRiN4SVqiA)

## 特性亮点

- **一个宿主,多个页面应用**：应用库支持本地目录 / ZIP,HTTP(S) 页面或安装包,局域网地址和公开 Git 仓库来源,提供收藏,搜索,扫码预填,桌面快捷方式,重装,更新,代码回退,备份恢复和卸载保留数据。
- **来源与运行方式分离**：`source` 记录代码来自哪里,`runtimeMode` 只表示当前用本地 release 还是直接访问 `liveUrl`,下载到手机不会改变来源。
- **不可变 release 与原子切换**：本地代码以不可变 release 保存,更新先生成新 release 再原子切换,当前版与上一版可用于回退,页面数据不会随代码切换被覆盖。
- **按实例隔离的数据与授权**：每个安装实例有唯一的 `instanceId`,业务数据,文件,授权,通知和代码版本都按实例管理,Cookie,localStorage 与 IndexedDB 仍遵守浏览器标准同源规则。
- **稳定的 Bridge 能力**：页面通过 `window.haminn` 调用数据,文件,录音与播放,系统 TTS,语音识别,定位,运动 / 方向 / 环境传感器,拍照与闪光灯,截屏与录屏,Wi-Fi,BLE,红外,网络与电池状态,分享,剪贴板,震动及通知等能力。敏感能力需要逐 happ 授权,涉及 Android runtime permission 时还需同时获得系统授权。
- **为国内无 GMS 设备设计**：二维码使用随 APK 打包的 CameraX 与 ZXing 离线识别,图标与 Font Awesome Free 全部内置,本地运行,数据,备份和局域网开发不依赖 Google Play 服务,海外 CDN 或运行时下载。
- **智能体开发模式**：可信电脑用局域网地址和密码连接 MCP 服务,即可增量创建,修改,移动或删除 happ 文件,并在同一 WebView 快速刷新查看结果。

### 界面示意

HaminnUI 底部导航固定为「收藏,开发,设置,支持」四个入口：

- **收藏(应用库)**：应用卡片显示图标,名称与版本,支持收藏筛选,搜索与桌面快捷方式,「从压缩包 / 从网址 / 扫码添加」三条添加入口,解析出安装包后先确认再安装。
- **开发**：智能体开发模式开关与状态,Happ 开发插件安装地址,备用 USB 地址(`adb forward tcp:8766 tcp:8766`)和开发服务密码编辑。
- **设置**：界面,朗读(TTS),语音(ASR)和系统四个分区,含中英文双语,浅色 / 深色主题,备份与恢复。
- **支持**：项目与问题反馈入口。

## 安装使用

1. 打开[下载页](https://haminn.airen.life/pages/download.html)下载最新正式版 APK,或到 [GitHub Releases](https://github.com/zhyuzh3d/haminnapp/releases) 取 `haminn-v<version>-release.apk` 与校验文件。
2. 在 Android 10 及以上手机上安装该 APK(无需任何 Google 服务)。
3. 打开 Haminn,在「收藏」页点「扫码添加」「从网址」或「从压缩包」添加你的第一个 happ。
4. 需要自己写应用时,到「开发」页开启智能体开发模式,按页面给出的局域网地址连接。

## 快速上手：编写一个 happ

推荐目录就是可直接运行的原生静态页面,不需要 React,Vue,Vite,Webpack,Node/npm 或转译步骤：

```text
my-happ/
  haminn.json
  index.html
  app.js
  style.css
```

`haminn.json` schema 2 的最小正式示例：

```json
{
  "schema": 2,
  "happId": "com.example.notes",
  "name": "示例笔记",
  "version": { "code": 1, "name": "1.0.0" },
  "entry": "index.html",
  "routing": "hash"
}
```

`liveUrl` 与 `updateUrl` 都是可选字段,缺少 `liveUrl` 表示纯本地 happ。完整作者合同,Bridge 示例与兼容要求见 [WebApp 编写指南](docs/webapp-authoring.md),机器可读合同位于 [api](api/) 与 [sdk](sdk/),可运行的示例在 [examples](examples/)。

## 核心模型

Haminn 把 happ 的来源与运行方式分开描述：

- `source` 记录代码来自本地文件,HTTP(S),Git 等何处。
- `runtimeMode` 只表示当前采用本地 release,还是直接访问 `liveUrl`。
- 有本地 release 才能本地运行,有 `liveUrl` 才能线上实时运行,有 `updateUrl` 才能一键更新,有 `downloadUrl` 才能从原地址重装。

对于带 `liveUrl` 的本地 release,Haminn 会在该真实 URL 空间内优先提供包内静态文件,包内不存在的资源和动态请求继续访问网络,因此相对 URL,Cookie 和请求头仍按正常网页规则工作。没有 `liveUrl` 的纯本地 happ 只访问包内资源和已授权的 Bridge 能力。

## HaminnUI

HaminnUI 是宿主自带的界面,也是它自己的 happ。它随 APK 一起分发,快照保存在本仓库 `app/src/main/assets/store/`,`app/src/main/assets/shared/` 内是随包图标资源。界面支持中英文双语和浅色 / 深色主题,并可在「实时在线界面」与「本地界面」之间切换：在线模式直接加载官网发布的 HaminnUI,本地模式使用 APK 内置快照,APK 替换会恢复内置 UI。

> HaminnUI 的语义源码不在本仓库。它维护在 HaminnWeb(`haminnweb/public/shell/`),本仓库只通过 `node tools/sync-shell-assets.mjs` 接收生成快照,不要直接改 `app/src/main/assets/store/`。

## 项目结构

```text
haminnapp/
  app/                      Android 应用源码(Kotlin)与资源
    src/main/assets/
      bridge/               页面 Bridge 合同(haminn-v1.js 等)
      store/                随包发布的 HaminnUI 快照(由工具同步)
      shared/               随包内置资源(如 Font Awesome Free)
      agent/                智能体开发插件说明
  api/                      Bridge 与安装清单的机器可读合同(JSON Schema / OpenAPI)
  sdk/                      TypeScript 合同声明
  docs/                     产品与技术文档,验证清单
  examples/                 可直接导入的示例 happ
  scripts/                  构建,打包,设备更新与校验脚本
  tools/                    合同校验,快照同步,图标维护(Node)
  plans/                    开发计划与执行记录
```

## 开发与验证

下面的工具仅供 HaminnApp 维护者使用,不是 happ 作者的依赖。项目锁定 JDK 17,AGP 9.1.1,Gradle 9.3.1 与 Android SDK 37,Node(>= 22)只用于合同检查,HaminnUI 快照和第三方图标资源维护,不参与页面运行时构建。

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./scripts/doctor.sh              # 环境自检
./scripts/quick-check.sh web     # 合同检查:node --test tools/contracts.test.mjs
./scripts/quick-check.sh android # 快速编译:./gradlew :app:compileDebugKotlin
./scripts/quick-check.sh release # 直接构建正式包
```

合同校验与单测也可以单独运行：

```sh
node tools/validate-contracts.mjs
node --test tools/contracts.test.mjs
```

HaminnUI 快照同步(上游 HaminnWeb 发布后再执行,不要手改快照)：

```sh
node tools/sync-shell-assets.mjs
```

正式签名信息从仓库外读取。构建,打包,校验与设备状态分别使用：

```sh
./scripts/build-release.sh     # 构建正式签名 APK
./scripts/package-release.sh   # 生成 artifacts/v<version>/ 交付物与 SBOM
./scripts/verify-release.sh    # 校验 APK 签名,包名,版本与 minSdk/targetSdk
./scripts/verify-device.sh     # 校验设备状态
./scripts/update-device.sh     # 必要时构建 -> adb install -r -> 启动 -> 核对版本
```

版本化交付物位于 `artifacts/v<version>/`。签名密钥,口令,token,`local.properties` 和构建目录不得进入 Git。

仓库仍保留面向单个实例的旧式 ADB / LAN 部署脚本,适合明确取得短期 token 的兼容流程：

```sh
./scripts/pack.sh examples/voice-notes /tmp/voice-notes.zip
export HAMINN_TOKEN='从目标 happ 的开发连接面板复制'
./scripts/deploy.sh '<instanceId>' /tmp/voice-notes.zip
```

## 文档导航

- [产品与技术设计](docs/haminnapp-product-technical-design.md)
- [WebApp 编写指南](docs/webapp-authoring.md)
- [隐私与数据边界](docs/privacy-and-data.md)
- [实体设备验收清单](docs/validation/physical-device-checklist.md)
- [开发计划](plans/haminnapp-v1-development-plan.md)
- [截屏与录屏开发计划](plans/haminnapp-screen-capture-recording-plan.md)
- [执行记录](plans/haminnapp-v1-execution-log.md)

## Haminn 家族

**Haminn 家族 —— 一个安卓宿主 + 若干可自由改造的应用**

- **Haminn**(宿主,先装这个)：<https://haminn.airen.life/> · <https://github.com/zhyuzh3d/haminnapp> —— **本仓库**
- **Chataxi**(多角色 AI 群聊)：<https://chataxi.airen.life/> · <https://github.com/zhyuzh3d/chataxi>
- **HamDraw**(实时 AI 绘图)：<https://hamdraw.airen.life/> · <https://github.com/zhyuzh3d/hamdraw>
- **PoseGi**(3D 摆姿生图)：<https://posegi.airen.life/> · <https://github.com/zhyuzh3d/PoseGi>

Chataxi,HamDraw,PoseGi 都是**装在 Haminn 里的应用**：它们必须先有 Haminn 宿主才能运行,在 Haminn 的[应用广场](https://haminn.airen.life/pages/happs.html)扫码或复制官方安装清单地址即可添加。它们共用 Haminn 的数据,文件,语音与网络能力,又各自独立,互不依赖,可以按需安装任意一个。

## 贡献

欢迎提交 Issue 与 Pull Request。请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)：其中说明了提 Issue / 提 PR 的流程,分支与提交信息风格,如何在本地跑起来与自检,以及不要提交哪些内容(签名,密钥,token,`local.properties`,构建目录等)。

## License

本项目以 [MIT License](./LICENSE) 发布,Copyright (c) 2026 zhyuzh。

随 APK 打包的第三方组件(如 Font Awesome Free,CameraX,ZXing 等)保留各自的原始许可,清单见 `app/src/main/assets/third-party-notices.txt`。

## 免责与支持

- Haminn 是 happ 的容器与 Native Bridge：安装包校验,解压,URL,文件路径,大小,摘要和事务边界由 Native 把关,但每个 happ 对自己的代码,服务器,账号,令牌和业务内容负责。
- 智能体开发模式使用可信局域网内的明文 HTTP,不能暴露到公网。
- 遇到问题请到 <https://github.com/zhyuzh3d/haminnapp/issues> 反馈,或从应用内「支持」页进入反馈入口。
