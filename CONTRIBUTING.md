# 贡献指南

感谢你愿意为 **HaminnApp** 出力。本文件说明提 Issue,提 Pull Request,本地开发与自检,代码风格与红线。动手前请先读一遍。

## 提 Issue

- 到 <https://github.com/zhyuzh3d/haminnapp/issues> 新建 Issue,优先使用仓库提供的模板：`.github/ISSUE_TEMPLATE/bug_report.yml`(缺陷)与 `feature_request.yml`(功能建议)。
- 缺陷请写清：复现步骤,期望结果,实际结果,Haminn 版本与 versionCode(设置页可见),Android 版本,设备型号与 WebView 版本,以及相关日志或截图。
- 一个 Issue 只谈一件事,不确定是不是缺陷时,先描述你观察到的现象和你的判断。

## 提 Pull Request

1. Fork 本仓库并从 `main` 切出特性分支。分支名建议 `fix/短描述`,`feat/短描述`,`doc/短描述`。
2. 只做与目标直接相关的改动,保留仓库既有风格,不要顺手重构无关代码。
3. 提交前跑与改动相称的自检(见下),在 PR 描述里写清：改了什么,为什么,怎么验证的。
4. 向 `main` 发起 PR,描述里关联相关 Issue(如 `Closes #12`)。
5. 本仓库与同级的 HaminnWeb 是彼此独立的仓库,跨仓改动请分仓提交,不要把两个仓库的改动塞进同一个 PR。

## 分支与提交信息风格

- 分支：从 `main` 切出,合并回 `main`,不要把 `main` 当作开发分支直接提交。
- 提交信息参考仓库既有历史,推荐使用一句话主题行(可用半角前缀)：
  - `feat: 一句话说清新能力`
  - `fix: 一句话说清修了什么`
  - `doc: 文档改动` / `chore: 构建与杂项` / `refactor: 不改行为的重构`
  - 也可以用中文直接描述,例如「摆姿：修正膝的弯曲方向」。
- 主题行尽量控制在 72 字符以内,需要时在正文说明动机,影响面与验证方式。
- 不要把多个无关改动混进一个提交,版本化产物发布后不得覆盖。

## 本地跑起来

环境要求：JDK 17,Android SDK 37,Node(>= 22,仅用于合同检查,HaminnUI 快照与图标维护)。

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./scripts/doctor.sh
```

## 自检

按改动范围选择,验证强度与改动相称：

```sh
./scripts/quick-check.sh web       # 合同检查:node --test tools/contracts.test.mjs
./scripts/quick-check.sh android   # 快速编译:./gradlew :app:compileDebugKotlin
./scripts/quick-check.sh release   # 构建正式包(只在确实要发版时)
node tools/validate-contracts.mjs  # 合同校验
node --test tools/contracts.test.mjs
```

- 改了网页资源：做语法与直接合同检查。
- 改了局部 Kotlin：编译受影响变体并跑直接相关测试。
- 只改文档：不需要构建。
- 涉及设备更新时用 `./scripts/update-device.sh`(必要时构建 → `adb install -r` → 启动 → 核对版本),不要手工重复构建同一个产物。

## 代码风格与红线

- **HaminnUI 的语义源码不在本仓库**。它维护在 HaminnWeb(`haminnweb/public/shell/`),`app/src/main/assets/store/` 只是同步工具生成的快照,不要手改,需要更新时运行 `node tools/sync-shell-assets.mjs`。
- 页面合同,Bridge 方法与错误码以 `api/` 与 `sdk/` 的当前合同为准,改动合同要同步更新机器可读定义与测试。
- 核心链路以国内无 GMS 设备为基线：不要引入 Google Play 服务,Firebase,海外 CDN,在线许可校验或运行时下载模块等依赖。
- 不要为一个来源或运行方式引入第二套语义,`source` 与 `runtimeMode` 保持正交。
- 文件内容不要写进数据库：图片,音视频,附件以文件系统对象保存,数据库只放对象 URL,逻辑 ID,摘要与必要元数据。

## 不要提交

- 签名密钥,口令,token,`local.properties` 和任何私有路径配置。
- 构建产物与缓存：`.gradle/`,`.kotlin/`,`**/build/`,`node_modules/`,`artifacts/*`(该目录已在 `.gitignore` 中忽略)。
- 设备开发地址与服务密码。
- 大体积二进制或与改动无关的文件。

## License

提交即表示你同意你的贡献以本仓库的 [MIT License](./LICENSE) 授权。
