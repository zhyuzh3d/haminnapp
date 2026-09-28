# HaminnApp 屏幕截取与录制开发计划

## 1. 目标与验收范围

本计划为 happ 增加两项能力:截取整个设备屏幕(含其他应用),以及录制整个设备屏幕并同时收录**系统播放声音与麦克风声音**。两者都以 MediaProjection 会话为前提,由用户对每次会话显式同意。

完成标准:

- 新增 `screen` 命名空间,含 `availability`、`capture`、`startRecording`、`stopRecording`、`cancelRecording` 五个方法,以及两个事件 `screen.recording.ended` 与 `screen.recording.error`。
- 截屏覆盖整个设备显示,产物是当前 happ 文件库里的一张 `HaminnFile`(JPEG 或 PNG),返回尺寸、字节数、抓取时刻;不返回内联 Base64。
- 录屏默认同时收录系统声音与麦克风,产物是当前 happ 文件库里的一段 MP4(H.264 + AAC 单音轨混音);返回时长、分辨率、实际音轨来源与结束原因。
- 用户拒绝系统投屏同意框时,`capture` 与 `startRecording` 返回 `{ cancelled: true }`,不建立任何会话、不启动前台服务、不留下临时文件。
- 录屏在 happ 退到后台、用户切到其他应用期间持续进行,由 `mediaProjection` 类型前台服务维持,通知栏常驻可见并带「停止录制」操作。
- 用户从系统状态栏停止投屏、达到时长或字节上限、当前前台 happ 改变、Haminn 任务销毁时,录制都按同一收尾路径结束并尽力把已采集内容导入文件库;页面仍存活时通过 `screen.recording.ended` 告知。
- 契约三处对齐:`api/capabilities.json`、`sdk/haminn-api.d.ts`、`assets/bridge/haminn-v1.js`、`MainActivity` 分派分支;`apiMinor` 由 13 升到 14。
- 本机验证通过:`node tools/validate-contracts.mjs`、`node --test tools/contracts.test.mjs`、`node tools/sync-agent-assets.mjs --check`、Gradle Kotlin 编译与 JVM 单元测试。
- 本轮**不做真机验证**。真机清单在第 12 节列出,作为后续独立步骤。

## 2. 范围边界(明确不做)

写清楚不做的事,避免本计划在执行中膨胀:

- **不扩展 camera 能力**。`camera` 仍是 `capturePhoto` / `torchStatus` / `setTorch`。内置 CameraX 预览、录像、镜头选择不在本轮。
- **不新增麦克风能力**。`audio.startRecording` 已覆盖原始录音;本轮不新增实时 PCM 流给页面。
- **不放开 `getUserMedia`**。`WebChromeClient.onPermissionRequest` 继续一律 `deny()`。页面永远不直接持有设备采集资源,预览也不通过把设备交回页面实现。
- ~~**不修改 `FileStore` 的 64 MiB 单文件上限**~~ —— **此条已被 2026-09-23 的用户指示推翻**,单文件上限提到 256 MiB 并新增分段录制,见 §14。该上限是全局配额,改动同时影响备份、导出与既有能力,所以只动常量与文案,不动配额结构;单应用总量仍是 256 MiB。
- **不改 HaminnUI 源码**。授权的中文标签 `capabilityLabels` 位于 `haminnweb/public/shell/features/manage.js`,而 `app/src/main/assets/store/` 是它的生成快照(`tools/sync-shell-assets.mjs` 声明 store/ 为 generated-only)。该标签属于 haminnweb 仓库的独立小事,列在第 11 节收尾项,不在本仓库改。
- **不写新的仪器化测试**。`app/src/androidTest/` 需要真机或模拟器运行,与本轮约束冲突;只保证不破坏既有仪器化测试的可编译性。

## 3. 唯一产品流程

### 3.1 能力探测

happ 先读 `haminn.runtime.capabilities()` 看 `screen` 是否 `usable`,再读 `haminn.screen.availability()` 拿具体事实:是否支持投屏、能否采集系统声音、本机有没有麦克风、可选的音轨模式、时长与字节上限、可用缩放档、码率区间、静态图格式。页面据此决定入口是否可见,不做「按 Android 版本或品牌猜测」的分支。

### 3.2 截屏

1. 页面调 `haminn.screen.capture({ maxEdge?, format? })`。
2. 宿主先过逐 happ 授权闸门(`screen.capture`),再弹出系统投屏同意框。同意框在 Android 14 及以上只提供「整个屏幕」,不提供「单个应用窗口」——本能力显式使用 `MediaProjectionConfig.createConfigForDefaultDisplay()` 锁死整屏语义。
3. 用户同意后:启动 `mediaProjection` 前台服务 → `getMediaProjection` → `registerCallback` → 创建 VirtualDisplay,输出到 `ImageReader`。
4. 等到第一帧有效图像(最多约 2 秒),按 `maxEdge` 等比缩放,压成 JPEG(默认,字节更小)或 PNG,写入当前 happ 文件库。
5. 立刻收尾:释放 VirtualDisplay 与 ImageReader、注销回调、停止投屏会话、停止前台服务、删除临时文件。
6. 返回 `HaminnFile` 与 `width`/`height`/`bytes`/`capturedAt`/`cancelled:false`。

### 3.3 录屏

1. 页面调 `haminn.screen.startRecording({ audio, maxDurationMs, maxBytes, scale, frameRate, videoBitRate, audioBitRate, name })`。
2. 宿主过 `screen.record` 授权;若 `audio` 含麦克风或系统声音,再过 `microphone.record` 授权(系统声音采集在 Android 上同样要求 `RECORD_AUDIO`)。
3. 弹出系统投屏同意框;拒绝则返回 `{ cancelled: true }`。
4. 同意后按固定顺序建立会话:**启动前台服务 → getMediaProjection → registerCallback → 启动音频管道(先) → 创建 VirtualDisplay 输出到 MediaRecorder 的 Surface → recorder.start()**。
5. 返回 `{ recordingId, width, height, audio, maxDurationMs, maxBytes, startedAt }`。
6. 页面调 `haminn.screen.stopRecording()`;宿主停止 recorder 与音频管道,编码收尾,先做音视频合流,再把最终 MP4 导入文件库,然后按上面同一顺序反向收尾。
7. 返回到 `durationMs`、`width`、`height`、`audio`、`stoppedBy`。

### 3.4 取消与外部中断

- `cancelRecording`:立即停止采集、丢弃临时文件、不导入、立即释放投屏会话与前台服务,返回 `{ cancelled: true }`。
- 达到 `maxDurationMs` 或 `maxBytes`:按正常收尾路径结束并导入,发出 `screen.recording.ended`(`reason` 为 `duration` 或 `bytes`)。
- 用户在系统状态栏点「停止共享」或系统因锁屏、另一投屏会话、进程被回收而终止投屏:`MediaProjection.Callback.onStop()` 触发,同样按正常收尾路径结束已采集内容并发出 `screen.recording.ended`(`reason: "projection-revoked"`)。
- 当前前台 happ 变为另一个 happ、Haminn 任务被销毁、应用 `onTerminate`:以 `reason: "host-stop"` 结束并导入到**发起录制的那个 happ** 的文件库。
- 页面在录制期间已不可用时不再投递事件,文件仍进入文件库,页面下次可用 `haminn.files.list()` 找到它。

## 4. 平台合同与硬约束

这一节的每一条都是实现必须遵守的,不是可选优化。

**Android 14(API 34)起的投屏顺序是刚性的**。必须先由 Activity 取得用户同意,再启动 `mediaProjection` 类型前台服务,然后才能调用 `getMediaProjection`,否则抛 `SecurityException`。同时:

- 必须声明 `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`,且服务声明 `android:foregroundServiceType="mediaProjection"`;`startForeground` 必须带该类型,否则抛 `MissingForegroundServiceTypeException`。
- 调用 `createVirtualDisplay` 之前必须先 `registerCallback`,否则抛 `IllegalStateException`。
- **一次用户同意只允许一次 `createVirtualDisplay`**,且同一个同意结果不能反复传给 `getMediaProjection`。因此截屏与录屏各自需要一次独立同意,并且**录制进行中不能再截屏**(返回 `E_CONFLICT`),这是平台约束而非产品取舍。
- 投屏会话被终止后,该会话不能再创建 VirtualDisplay,必须重新征求同意。

**系统声音可采集范围有限**。`AudioPlaybackCaptureConfiguration` 只允许 `USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN` 三类;其余 usage 一律采不到。且被采集应用还必须满足:目标 API 29 以上或在其 manifest 里显式 `allowAudioPlaybackCapture="true"`、`AudioManager` 或播放器的 capture policy 为允许、与 Haminn 处于同一用户档案;DRM 保护的音频永远采不到。所以「系统声音」的实际语义是**媒体/游戏类播放在允许时的混音**,不是「所有声音」。`availability().systemAudioUsages` 如实回报这一点。

**单文件 256 MiB 是硬顶**(2026-09-23 起;此前为 64 MiB)。`FileStore` 对单文件强制该上限,超限抛 `E_QUOTA` 且**整段导入作废**。因此在录制阶段就必须自我设限,而不是等导入时才发现:视频走 `MediaRecorder.setMaxFileSize`,音频管道也计入预算,`maxBytes` 是**单个交付文件**的上限,默认 48 MiB 使一次录制的成品稳定落在该值以内。

**单应用总量 256 MiB 与单文件上限同值**。这是这次抬高上限留下的唯一约束:一个写满 256 MiB 的成品会占满该应用的全部逻辑文件配额,所以「提高单文件上限」并不能让一次录制无限长;超过单文件上限的需求走 `segment` 分段(见 §14),而分段实际能录多久最终由这条按应用的总配额决定。

**录屏不必靠单文件长时间覆盖**(2026-09-23 修正)。单文件上限抬到 256 MiB 后,以 720p 级别画面为例,4 Mbps 可录约 8 分钟、默认 2 Mbps 约 17 分钟;再长就用 `segment` 分段,由 §14 的滚动机制把一场录制切成多个各自合规的文件,而不是靠降码率或降分辨率硬挤。`availability()` 与参数文档要如实说明这条换算,不能让 happ 以为「一个文件可以录一小时」。

**方向变化不改采集几何**。录制期间用户切到横屏应用时,系统会把被采集内容等比缩放并居中放到固定尺寸的 VirtualDisplay 上(Android 12L 起的平台行为),结果是加黑边而不是拉伸。本轮不实现 `onCapturedContentResize` 重建编码器。

**采集期间无法同会话截屏**。原因见上:一次同意只给一个 VirtualDisplay。

## 5. Bridge API 合同

### 5.1 `screen.availability()`

无需逐 happ 授权(只回报设备与宿主事实),任何时候可调。

```json
{
  "supported": true,
  "systemAudio": true,
  "systemAudioUsages": ["media", "game", "unknown"],
  "microphone": true,
  "audioModes": ["none", "system", "microphone", "both"],
  "maxDurationMs": 1800000,
  "maxBytes": 50331648,
  "maxFrameRate": 60,
  "scales": [1, 0.75, 0.5],
  "videoBitRateMin": 500000,
  "videoBitRateMax": 8000000,
  "audioBitRateMin": 64000,
  "audioBitRateMax": 192000,
  "stillFormats": ["jpeg", "png"],
  "stillMaxEdge": 2048
}
```

`systemAudio` 与 `microphone` 分别取「平台支持播放采集」与 `FEATURE_MICROPHONE`;`audioModes` 只列出当前设备真正可用的模式(例如没有麦克风时不出现 `microphone` 与 `both`)。

### 5.2 `screen.capture(params?)`

| 参数 | 类型 | 默认 | 约束 |
| --- | --- | --- | --- |
| `maxEdge` | number | 1600 | 720 到 2048 |
| `format` | `"jpeg" \| "png"` | `"jpeg"` | 只接受这两个值 |

返回 `{ cancelled: true }` 或:

```json
{
  "cancelled": false,
  "logicalFileId": "...", "url": "...", "name": "screen-20260923-174500.jpg",
  "mime": "image/jpeg", "size": 412345, "sha256": "...",
  "width": 810, "height": 1800, "capturedAt": 1789000000000
}
```

### 5.3 `screen.startRecording(params?)`

| 参数 | 类型 | 默认 | 约束 |
| --- | --- | --- | --- |
| `audio` | `"none" \| "system" \| "microphone" \| "both"` | `"both"` | 设备不支持的模式返回 `E_UNSUPPORTED` |
| `maxDurationMs` | number | 180000 | 1000 到 1800000(整场上限) |
| `maxBytes` | number | 50331648 | 8388608 到 268435456(**单个交付文件**的上限,不是整场总量) |
| `segment` | boolean | `false` | 打开后到达 `maxBytes` 滚动到下一个文件继续录,见 §14 |
| `scale` | number | 0.5 | 只能取 `scales` 中的值 |
| `frameRate` | number | 30 | 15 到 60;是目标不是硬上限 |
| `videoBitRate` | number | 2000000 | 500000 到 8000000 |
| `audioBitRate` | number | 128000 | 64000 到 192000 |
| `name` | string | `screen-recording.mp4` | 分段时每段在扩展名前加 `-p1`、`-p2`… |

返回 `{ cancelled: true }` 或:

```json
{ "recordingId": "...", "audio": "both", "width": 540, "height": 1200, "segment": false,
  "maxDurationMs": 180000, "maxBytes": 50331648, "startedAt": 1789000000000 }
```

`width`/`height` 是**实际编码尺寸**,已按 `scale` 换算并对齐到偶数、且已校验为当前 H.264 编码器支持。默认值(scale 0.5 + 2 Mbps)下的字节换算是:约 2.1 Mbps 合计码率,48 MiB 约合 3 分钟,与默认时长上限基本对齐,所以默认体验是可预测的「约三分钟」。

`frameRate` 的语义是**目标,不是硬上限**:`MediaRecorder.setVideoFrameRate` 不裁剪真实帧率,画面在变时按显示刷新率(该机约 60 Hz)写帧,画面不动时一帧也不写。所以静态屏幕录出的平均帧率会远低于它,而静止期的时长由最后一帧的 sample duration 承担 —— 成品里那段时间就是「同一幅画面停留」,不丢时间。2026-09-23 真机实测:含 4 秒连续拖动的成品 321 帧、298/320 个帧间隔 < 1/30 s(平均 0.0167 s ≈ 60 fps)、整体平均 30.30 fps,最后一帧 PTS 10.56 s 而容器时长 11.71 s。

### 5.4 `screen.stopRecording(params?)`

`recordingId` 可省略(当前页面会话内只有一段录制)。返回:

```json
{
  "logicalFileId": "...", "url": "...", "name": "screen-recording.mp4",
  "mime": "video/mp4", "size": 41234567, "sha256": "...",
  "durationMs": 181234, "width": 540, "height": 1200,
  "audio": "both", "stoppedBy": "page"
}
```

`stoppedBy` 取 `page`、`duration`、`bytes`、`projection-revoked`、`host-stop`。
若音轨部分或全部未能写入(编码器或合流失败),`audio` 回落为 `"none"` 并在 `message` 字段说明,而不是丢弃整段录制;画面仍然交付。

### 5.5 `screen.cancelRecording(params?)`

返回 `{ cancelled: boolean }`。丢弃已采集内容、删除临时文件、不产生文件对象。

### 5.6 事件

- `screen.recording.ended` — `{ recordingId, reason, durationMs, logicalFileId | null }`。仅在结束原因不是本次 `stopRecording` 请求本身时发出(上限触发、投屏被撤销、宿主停止)。页面应按 `recordingId` 过滤。
- `screen.recording.error` — `{ recordingId, code, message }`。录制中发生不可恢复错误时发出,此后该 `recordingId` 失效。

两个事件只投递给发起录制的那个页面会话;页面已离开则不投递。

### 5.7 错误码

复用既有稳定错误码,不新增:

| 场景 | 错误码 |
| --- | --- |
| 参数越界或取值不在枚举内 | `E_INVALID_ARGUMENT` |
| 设备不支持播放采集、请求了不可用的音轨模式 | `E_UNSUPPORTED` |
| happ 拒绝/未授予 `screen.*` 或 `microphone.record` | `E_CAPABILITY_DENIED` |
| Android 未授予 `RECORD_AUDIO` | `E_OS_PERMISSION_DENIED` |
| 已有投屏会话(录制中再截屏、二次开始录制) | `E_CONFLICT` |
| 已有麦克风录音(`audio.startRecording` 进行中且本机要采麦克风) | `E_CONFLICT` |
| 成品或分段超过单文件上限(256 MiB)且无法继续 | `E_QUOTA` |
| 编码器创建失败、合流失败且画面也无法交付 | `E_INTERNAL` | 

用户取消系统同意框不抛错,返回 `{ cancelled: true }` —— 与 `camera.capturePhoto`、`files.import` 的既有惯例一致。

## 6. 双层授权与资源独占

沿用第 8 节的既有模型,不改造 `PermissionBroker`,也不引入「仅本次」grant:

| 能力 | 默认 Haminn 决策 | 系统层 |
| --- | --- | --- |
| `screen.capture` | 按实例首次询问,之后按 grant 记忆 | 无 Android runtime permission,但**每次调用都必须重新取得系统投屏同意** |
| `screen.record` | 按实例首次询问,之后按 grant 记忆 | 同上 |
| `microphone.record`(仅当 `audio` 含声音) | 复用既有 grant 与文案 | `RECORD_AUDIO` |

关键点:**Haminn 层的 grant 可以记忆,系统层的投屏同意绝不记忆**。用户每次录制都要点一次系统同意框,这与用户明确要求一致,也正是本能力比麦克风更严格的体现。`PermissionBroker` 因此完全不需要改动。

独占资源:

- 同一时刻只允许一个投屏会话(截屏或录制)。第二个请求返回 `E_CONFLICT`。
- 录制期间不接受截屏。
- `AudioController` 新增只读 `isRecording()`;当本机要采麦克风而它正在录音时返回 `E_CONFLICT`,避免两路麦克风采集互相压制导致静音产物。`speech.*` 与录屏的麦克风争用交由平台处理(Android 10+ 允许受限的并发采集),在文档里写明建议调用顺序,不为此增加跨控制器耦合。

## 7. 采集流水线

### 7.1 视频

用 `MediaRecorder` 而不是手写 `MediaCodec`,理由是它自带的编码器选择、尺寸校验、`setMaxFileSize` 与 `setMaxDuration` 回调恰好覆盖本能力最需要的容错,而在无法真机调试的前提下,容错优先于控制力。

- `setVideoSource(SURFACE)` + `setOutputFormat(MPEG_4)` + `setVideoEncoder(H264)`
- `setVideoSize(w, h)`:按 `scale` 算出尺寸后先取偶,再用 `MediaCodecList` 找到 H.264 编码器的 `VideoCapabilities` 校验;不支持时按 0.75 递降,最后回落到 1280x720、960x540、720x480 中第一个可用值。
- `setMaxFileSize(maxBytes / 1.08)`,预留音轨体积。
- `setMaxDuration(maxDurationMs)`。
- `setOrientationHint(0)`。
- `setOnInfoListener` 处理 `MEDIA_RECORDER_INFO_MAX_DURATION_REACHED` 与 `MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED`,与 `audio` 处理 `MAX_DURATION_REACHED` 的方式一致。
- `setOnErrorListener` 转为 `screen.recording.error`。
- 输出到 `cacheDir/screen-recording/<recordingId>.video.mp4`。

### 7.2 音频

不走 `MediaRecorder` 的音轨,因为 `MediaRecorder` 只能有一个音源,无法同时混麦克风与系统声音。改为自建管道:

1. 按 `audio` 模式创建 1 到 2 个 `AudioRecord`:
   - 麦克风:`AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)`
   - 系统声音:`AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)`,其中 config 由 `AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(USAGE_MEDIA).addMatchingUsage(USAGE_GAME).addMatchingUsage(USAGE_UNKNOWN).build()` 构造(三者之外采不到,显式列出比留空更可读也更有据)
   - 编码格式:48000 Hz、16 bit、立体声;不支持的设备回落到 44100 Hz、单声道
2. 单线程混音循环:分别阻塞读两路 PCM,按 `1.0` 增益相加并做 ±32767 饱和截断,得到混音帧。
3. `MediaCodec` AAC 编码器(`AACObjectLC`),PTS 由累计帧数推算:`ptsUs = frames * 1_000_000 / sampleRate`。
4. 编码输出写进第二个 `MediaMuxer`,输出 `cacheDir/screen-recording/<recordingId>.audio.mp4`。
5. 停止时向编码器投 EOS、排空输出、依次 `stop()/release()`。

先起音频管道再起视频,让音频时钟略微领先;合流时两轨各按自己的首帧 PTS 归零,使对齐误差降到毫秒级。

### 7.3 合流

`MediaExtractor` 读 `.video.mp4` 与 `.audio.mp4`,`MediaMuxer` 写最终 `<recordingId>.mp4`:视频轨原样拷贝,音轨拷贝编码后的 AAC 采样,两轨按 PTS 归零后写入。采样缓冲按需增长(`max(1 MiB, 最大采样尺寸)`),不整段载入内存。

合流失败时保留视频中间件,去掉音轨后作为降级成品导入,`audio` 回报为 `"none"`,不把整段录制丢掉。

### 7.4 截屏

VirtualDisplay 输出到 `ImageReader`(`RGBA_8888`,容量 2)。等第一帧时用 `setOnImageAvailableListener` 加约 2 秒超时;拿到 `Image` 后按 `rowStride` 处理行填充转换为 `Bitmap`,再等比缩放到 `maxEdge` 以内,压成 JPEG/PNG,导入文件库。

## 8. 生命周期与归属

这是本能力与既有能力最大的差别:**录屏必须活过页面退到后台**,否则「录制其他应用」在语义上不可能成立。因此:

- `ScreenCaptureController` 挂在 `HaminnApplication`(与 `happShare` 同一形态),不在 `MainActivity` 上。前台服务在录制期间维持进程,控制器不随 Activity 生命周期起落。
- `MainActivity.onStop()` **不**取消 screen 会话(`audio`、`speech`、`location` 等仍按原样取消)。
- `MainActivity.onDestroy()` 调用 `screenCapture.shutdown()`:停止并尽力导入,再释放投屏。
- `showTarget` 切换到另一个 happ 时,若进行中的录制属于别的 appId,以 `host-stop` 结束并导入到发起方。
- 文件归属:**发起录制时捕获 `appId` 与 `dataGeneration`**,结束时按该组合导入;即使页面已经不在,文件也落在正确的实例与数据代里。
- 事件投递:页面会话存活时经 `bridge?.emit()`;`MainActivity.destroyRuntime()` 里 detach,控制器据此停止投递。

## 9. 文件与配额

- 临时件位于 `cacheDir/screen-recording/`,与录音的 `cacheDir/audio-recordings/` 同风格。任何结束路径(成功、取消、异常、`shutdown`)都必须删除临时件。
- 只把最终文件导入 `FileStore`。录制阶段的峰值磁盘占用约为成品的两倍(视频中间件 + 音频中间件 + 成品),按 48 MiB 默认预算即约 100 MiB 缓存,可接受;`maxBytes` 取到 256 MiB 时峰值约 570 MiB,由调用方自己承担。
- 默认参数下成品稳定低于 48 MiB;`maxBytes` 上限钳在 256 MiB(与文件库单文件上限同值),超过该值不可能导入成功。分段录制下每段落定即导入并删除中间件,所以峰值只与**单段**大小相关,与段数无关。
- 截屏产物按 `maxEdge` 与 JPEG 质量压到远小于 4 MiB(与 `captureAgentScreen` 的同级限制思路一致)。

## 10. 失败与恢复

| 失败点 | 处理 |
| --- | --- |
| 用户拒绝投屏同意 | 返回 `{ cancelled: true }`,未创建任何资源 |
| 启动前台服务失败 | 释放投屏会话,抛 `E_INTERNAL`,不留在半启动状态 |
| `getMediaProjection` 或 `createVirtualDisplay` 抛异常 | 依次停止前台服务、释放投屏、删除临时件,抛 `E_INTERNAL` |
| 编码器创建失败(尺寸全不支持) | 抛 `E_UNSUPPORTED`,不留下半个会话 |
| 音频管道部分失败(如播放采集不可用) | 退化为可不带音轨录制,`audio` 回报实际值;若整条音频起不来则直接不带音轨继续 |
| 合流失败 | 降级为视频-only 成品导入,`audio` 回报 `"none"` |
| 达到字节或时长上限 | 正常收尾并导入,发 `ended` 事件,`stoppedBy` 说明原因 |
| 投屏被用户或系统撤销 | 正常收尾已采集内容并导入,`stoppedBy: "projection-revoked"` |
| happ 在录制中切换/任务销毁 | 以 `host-stop` 收尾并导入到发起方 |
| 导入时超过单文件上限 | 触发 `E_QUOTA`;此时该段中间件已删、成品丢弃。`maxBytes` 钳在 256 MiB 使这条路径只在调用方把 `maxBytes` 定为超过库上限时出现,而那条路径已经被钳制掉 |
| 分段落库时单应用总量已满 | 该段 `logicalFileId` 为 `null` 并带 `message`,整场以 `reason: "bytes"` 收尾(§14);继续录只会产出谁也留不下的分段 |

## 11. 落点清单

### 11.1 契约与桥(4 处,缺一 `validate-contracts.mjs` 即失败)

- `api/capabilities.json`:`public.screen` 五个方法;`apiMinor` 13 → 14。
- `app/src/main/assets/bridge/haminn-v1.js`:`screen: namespace("screen")`;`LONG_METHODS` 增加 `screen.(?:capture|startRecording|stopRecording)`(要等系统同意框与收尾合流)。
- `sdk/haminn-api.d.ts`:`HaminnScreenAudioMode` 等类型、`HaminnApi.screen` 段、`HaminnPermissionCapability` 增加 `screen.capture | screen.record`。
- `MainActivity.kt`:`"screen.availability" / "screen.capture" / "screen.startRecording" / "screen.stopRecording" / "screen.cancelRecording"` 五个分支。

### 11.2 Native 实现

新增:

- `app/src/main/java/io/github/zhyuzh3d/haminn/capability/ScreenCapturePlan.kt` — 纯逻辑:请求参数校验与钳制、缩放尺寸取偶、字节预算换算、`audio` 模式到音源组合的映射。**可被 JVM 单元测试直接覆盖**。
- `app/src/main/java/io/github/zhyuzh3d/haminn/capability/ScreenAudioMixer.kt` — 纯逻辑:PCM 相加与饱和截断(按声道数)。**可被 JVM 单元测试直接覆盖**。
- `app/src/main/java/io/github/zhyuzh3d/haminn/capability/ScreenAudioCapture.kt` — `AudioRecord` × N + 混音线程 + AAC 编码 + 中间 muxer。
- `app/src/main/java/io/github/zhyuzh3d/haminn/capability/ScreenMediaMux.kt` — 两段中间件合流为单个 MP4。
- `app/src/main/java/io/github/zhyuzh3d/haminn/capability/ScreenCaptureController.kt` — 会话状态机、截屏、录制、收尾、导入、事件投递。
- `app/src/main/java/io/github/zhyuzh3d/haminn/capability/ScreenCaptureService.kt` — `mediaProjection` 前台服务 + 常驻通知 + 「停止录制」操作,形态对齐 `HappShareService`。

修改:

- `AndroidManifest.xml`:`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限;`ScreenCaptureService` 声明 `foregroundServiceType="mediaProjection"`。
- `HaminnApplication.kt`:`lateinit var screenCapture`,初始化;`onTerminate` 收尾。
- `MainActivity.kt`:投屏同意 launcher(`StartActivityForResult`)与挂起续体;`requestProjection()` 挂起函数;`capabilityPermissions` / `capabilitySupported` / `capabilityRationale` / `authorizationDescriptors` / `capabilityDescriptors` / `GRANT_CAPABILITIES`;`runtime.info` 的 `apiMinor`;`destroyRuntime` / `onDestroy` / `showTarget` 的会话接线。
- `AudioController.kt`:新增只读 `isRecording()`。

### 11.3 测试与文档

- `tools/contracts.test.mjs`:`apiMinor` 断言 13 → 14;新增一条截图/录屏合同测试(方法清单、前台服务类型、权限声明、`GRANT_CAPABILITIES`、`LONG_METHODS`、`E_CONFLICT` 分支存在)。
- `app/src/test/java/io/github/zhyuzh3d/haminn/ScreenCapturePlanTest.kt`、`ScreenAudioMixerTest.kt`:新增 JVM 单元测试。
- `docs/webapp-authoring.md`:在能力叙述段落增加 `screen` 的用法、同意节奏、上限与限制。
- `README.md`「主要能力」段落与「文档导航」:补一句截屏与录屏。
- `docs/haminnapp-product-technical-design.md`:§8 授权表新增一行;§9.3 能力面表新增 `screen` 一行;§9 末段说明录屏的前台与配额约束。
- `docs/validation/known-limitations.md`:新增一条,写清系统声音的 usage 与 opt-out 限制、单文件/单应用配额换算、分段录制、录制中不能截屏、方向变化加黑边。
- 收尾后运行 `node tools/sync-agent-assets.mjs` 同步 `app/src/main/assets/agent/` 两份快照(Gradle `preBuild` 会校验一致性)。

### 11.4 明确留给后续

- `haminnweb/public/shell/features/manage.js` 的 `capabilityLabels` 增加 `screen.capture` / `screen.record` 中文标签(跨仓库,单独处理)。
- 真机验证(第 12 节)。
- 若确需更长的录制,再单独决策「提高单文件上限」或「分段录制」,不在本计划内夹带。

## 12. 验证计划

### 12.1 本机可执行并必须通过

```bash
cd haminnapp
node tools/validate-contracts.mjs
node tools/contracts.test.mjs        # 或 npm test
node tools/sync-agent-assets.mjs --check
node --check app/src/main/assets/bridge/haminn-v1.js
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileDebugKotlin
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest
```

`compileDebugKotlin` 负责证明新增 Kotlin 能编过、manifest 合法、`preBuild` 的文档一致性校验通过;`testDebugUnitTest` 负责证明参数钳制与混音数学正确。这两项是本轮能拿到的最强证据,不假装它们等价于真机验证。

### 12.2 真机清单

- 首次调用是否出现系统投屏同意框;拒绝后是否不启动前台服务、不产生文件。
- Android 14 及以上同意框是否只提供「整个屏幕」。
- 通知栏是否常驻可见、操作按钮能否停止录制并让文件落进文件库。
- 从系统状态栏「停止共享」后是否按 `projection-revoked` 正常收尾。
- 录制期间切到其他应用是否继续,切回后能否 `stopRecording` 拿到完整文件。
- 成品是否同时含系统播放声音与麦克风;被采集应用 opt-out 时是否如文档所述只有麦克风。
- 达到时长/字节上限时是否按 `duration`/`bytes` 收尾。
- 截屏是否包含其他应用内容,分辨率与字节是否符合预期。
- 录制中调用截屏是否稳定返回 `E_CONFLICT`。
- 设备不支持的编码尺寸是否按回落链生效。

### 12.3 真机结果(2026-09-23,HONOR CMA-AN00 / Android 11 / 720x1600)

仪器化用例 `app/src/androidTest/.../ScreenCaptureInstrumentedTest.kt`,四条全通过:

| 用例 | 证明了什么 |
|---|---|
| `availabilityIsUngatedAndMatchesTheDevice` | `apiMinor 14`、`screen` 能力面、`lifecycle: "background-service"`、上限值(1800000 / 256 MiB / `segmenting: true` / 60Hz / 2048 / `[1.0, 0.75, 0.5]`)、`audioModes` 随硬件、`screen.capture`/`screen.record` 免授权可问 |
| `decliningTheProjectionLeavesNothingBehind` | 拒绝投屏 = 正常答案:返回 `{"cancelled":true}`、文件库为空、槽位释放(再次 `availability` 仍 `supported`)。对应清单第 1 条 |
| `anAcceptedScreenshotCoversTheWholeDisplayAndEntersTheLibrary` | 对应清单第 8 条。成品 29349 字节、jpeg、aspect 与显示一致;**画面里状态栏与底部三键导航栏都在**(截的是整块屏幕,含系统 UI 与其它层)。`maxEdge` 语义经双向确认:2048 时**不放大**(取显示自己的最长边 1600),720 时**精确落到 720** |
| `aRecordingSurvivesLeavingHaminnAndDeliversBothTracks` | 对应清单第 5、6 条。切到「设置」5 秒后仍在录,`am start` 之后由后台页面 `stopRecording` 成功收尾(`stoppedBy: "page"`,重复 stop 返回同一个文件);请求 `audio:"both"` 与交付 `"both"` **相等**;成品含 `h264` + `aac (LC) 48000Hz stereo 128kb/s` 双轨 |

音轨的两个来源分别有证据:

- **系统声音**:录屏期间从设备自身播放 440Hz 测试音(`USAGE_MEDIA`,正是播放采集允许的 usage),成品音轨主频实测 **440Hz**,`mean_volume -7.3dB / max_volume -1.6dB`。
- **麦克风**:同一路径在静室(设备无任何播放)那次录制的 `max_volume` 为 `-42.9dB` —— 播放采集此时只能是纯零,这点残差只能来自麦克风。另有结构性证据:`ScreenAudioCapture.open` 从 `BOTH` 起步,两条 `AudioRecord` 任一初始化或 `startRecording` 失败都会降级,而交付值与请求值相等,说明麦克风与播放两条采集都真的起来了。

本轮在这一路径上抓到并修掉一个真实缺陷(见 §12.4)。清单里**尚未覆盖**的:第 2 条(需 Android 14+ 设备)、第 3 条(通知按钮停止)、第 4 条(系统状态栏停止共享)、第 7 条(时长/字节上限收尾)、第 9 条(录制中截屏的 `E_CONFLICT`)、第 10 条(构造不支持尺寸看回落链)。

### 12.4 真机发现的缺陷与修复

**`ScreenMediaMux.copyTrack` 把两个下标当成一个**(`ScreenMediaMux.kt`)。

`combine()` 里视频先 `addTrack`,音频后 `addTrack`,于是 muxer 给音频分配的轨道号是 **1**;而每个中间文件各自只有 **0 号**一条轨。原实现把 muxer 的轨道号直接当作源解复用器的轨道号传给 `copyTrack`,音频那一次就是在单轨文件上读 1 号轨 `getTrackFormat(1)` → `IllegalArgumentException`。视频那次只是**碰巧**两边都是 0 才对上。

后果被 `finalizeRecording` 的降级设计掩盖成「静默无声」:交付一个只有画面的 mp4,附一句「音频轨没有写成功」,画面完全正常,所以不看音轨不会发现。修复是给 `copyTrack` 分别传源轨道号与目标轨道号。同一处补了诊断日志(`HaminnScreenCapture`):静默降级若不留痕,这条路径下次仍然无从查起。

另确认一件事,免得后续误判:`width`/`height` 上报为 `800x360` **不是转置**。该机 `wm size` 为 720x1600,但录制当时屏幕处于横屏,`getRealMetrics` 随当前旋转给出 1600x720,0.5 缩放即 800x360;成品画面是横屏铺满、比例 20:9、文字未被拉伸。

取证通道也有一个 ROM 差异要记住:这台设备上 `/sdcard/Android/data` 对 shell 是 `Permission denied`,所以「把产物拷到 app 外部目录再 `adb pull`」这条路是死的。改用 app 自己的 `filesDir/screen-verification/`,再以 `adb exec-out run-as <包名> cat ...` 取出(debug 包可 `run-as`),并给 `connectedDebugAndroidTest` 加 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`,否则 AGP 跑完会卸载、把证据一起删掉。


### 12.5 开销与帧率实测(CMA-AN00,MT6833 八核 2.0GHz)

录屏整条链路都跑在 app 自己的进程里(仪器化测试与 app 同 pid),所以用 `/proc/self/stat` 的 utime+stime 逐窗口量出的就是功能自身的成本,结果由用例写进 `filesDir/screen-verification/perf.txt` 取回:

| 窗口 | 进程 CPU |
|---|---|
| 基线(应用前台空闲,不录制) | 0.130 cpu-s / 2.00 s = **6% 单核** |
| 录制中,画面静止 | 0.340 cpu-s / 2.00 s = **17% 单核** |
| 录制中,持续拖动 4 s | 0.960 cpu-s / 4.75 s = **20% 单核** |

即录制净成本约 +11~14 个百分点单核,换算到 8 核约 1~2% 整机算力。成品 11.71 s / 2.27 MB(≈1.55 Mbps,请求的 2 Mbps 未触顶)。

**`frameRate` 是目标不是硬上限**(同一份含 4 s 连续拖动的成品):321 帧,298/320 个帧间隔 < 1/30 s(平均 0.0167 s ≈ **60 fps**),17 个间隔 ≥0.06 s(6 个 >0.4 s,最长 0.793 s),整体平均 **30.30 fps**。也就是**画面在变时按显示刷新率(~60 Hz)写帧,画面不动时一帧也不写**;想省字节应降 `scale` / `videoBitRate`,降 `frameRate` 无效。

静止段不丢时间:最后一帧 PTS 10.56 s 而容器时长 11.71 s,差值是该帧保持显示的时长 —— 静止期在成品里就是「同一幅画面停留」。文档里 `frameRate` 的这层语义目前缺失。

## 13. 已知限制

- 系统声音只覆盖 `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`,且被采集应用可以显式拒绝;DRM 与受保护内容永远不出现。通话、闹钟、通知等 usage 采不到。
- 单文件上限 256 MiB 意味着 720p 级别画面在 4 Mbps 下约 8 分钟、默认 2 Mbps 约 17 分钟。要录更长就打开 `segment`(§14),但**单应用逻辑文件总量同样只有 256 MiB**,所以一场分段录制总共能留下的数据仍受这条总配额约束。
- 一次用户同意只允许一个 VirtualDisplay,因此录制进行中不能截屏,截屏与录屏各自都要一次新的系统同意。
- 录制期间被采集内容旋转时按等比缩放居中呈现,会加黑边,不会自动重建编码器。
- 麦克风与 `speech.*` 的并发采集交由平台处理;与 `audio.startRecording` 的冲突被显式拒绝。

## 14. 增量:单文件上限 256 MiB 与分段录制(2026-09-23)

用户指示:「单文件提高到 256M,支持分段录制」。这条推翻 §2 原本的「不动 `FileStore` 上限」,所以这里把改动范围、依据与边界一次写清。

### 14.1 单文件上限 64 MiB → 256 MiB

- `FileStore.MAX_FILE_BYTES` 改为 `256L * 1024 * 1024`,错误文案由常量推导(`单个文件超过 $FILE_LIMIT_MIB MiB`),不再写死数字;`beginWrite`/`appendBytes` 走的是同一个常量,所以分块写入路径一并生效。
- `ScreenCapturePlan.MAX_MAX_BYTES` 同步改为 256 MiB,`DEFAULT_MAX_BYTES` **保持 48 MiB**:默认值保守,上限交给调用方显式要。
- `contracts.test.mjs` 新增一条跨文件不变量:录制的 `MAX_MAX_BYTES` 必须等于 `FileStore.MAX_FILE_BYTES`。两个数字住在不同文件里,靠断言而不是靠人工保持一致。
- **`MAX_APP_FILE_BYTES` 未动,仍是 256 MiB**。这是本次改动的真实约束:一个 256 MiB 的成品会占满该应用全部逻辑文件配额。用户只要求抬单文件上限,所以按原样保留并在文档、known-limitations 与 §13 明写这条相互作用;是否抬高按应用总量是另一个决策,未做。

### 14.2 分段录制

**唯一可用的平台机制是 `MediaRecorder.setNextOutputFile`**(API 26+,本应用 minSdk 29 恒可用)。它有三条硬约束,整个实现是围绕它们的:

1. **只能在 `MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING`(文件到 90% 时触发一次)之后、`MAX_FILESIZE_REACHED` 之前调用**;错过这个窗口,录制就在上限处结束。
2. **同一时刻只能排一个文件**,上一个还没被启用就不能再排。
3. 交出去的文件在 `stop()` 前不能被别的代码碰;切换发生时收到 `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`,那正是上一段已封口的时刻。

因此实现是:`APPROACHING` → `queueNextSegment()` 排下一个文件;`NEXT_OUTPUT_FILE_STARTED` → `rollSegment()` 落定上一段。排队失败不掩盖:记录日志后让录制在字节上限处正常收尾(`reason: "bytes"`),而不是悄悄少录一截。

**音频不随段重启。** 每段重启 `ScreenAudioCapture` 会带来编码器重启的声音空洞,所以整场只有一条连续音频流,切点取 `ScreenAudioCapture.positionUs()`——即混音器自己的采样时钟此刻的位置。用混音器时钟而不是墙钟切窗,段内两轨不需要任何时间换算就对齐,误差也不跨段累积。`ScreenMediaMux.combine()` 增加音轨时间窗参数,窗口内第一个采样即该段音轨的原点;窗口内没有采样时整段落回无声视频而不是交付一个空音轨的坏容器(空轨 muxer 停不掉)。

**交付与配额。** 每段独立导入文件库、每次触发 `screen.recording.segment`;`stopRecording()` 返回值仍指向最后一段并附 `segments` 数组,`screen.recording.ended` 也带同一份数组(页面中途重载不丢前面几段)。某段写不进库(单应用总量满)时该段 `logicalFileId` 为 `null`、带 `message`,整场以 `reason: "bytes"` 收尾。峰值磁盘只与单段大小相关(每段落定即导入并删除中间件),与段数无关。

**并发与收尾。** 段落定走后台协程;`finish()` 先 `join()` 在途任务再收最后一茬,避免结果里少一段。由分段任务自己发起的停止先把 `segmentJob` 引用清掉再请求停止,否则 `finish()` 会等一个永远不会结束的自己。取消录屏与编码器报错都把 `abandoned` 置位,在途分段不再进库。

### 14.3 本轮验证

- `node tools/validate-contracts.mjs` ✓、`node tools/contracts.test.mjs` 30/30 ✓(含新的跨文件上限不变量与分段机制断言)、`sync-agent-assets --check` ✓。
- `:app:compileDebugKotlin` ✓(零告警)、`:app:testDebugUnitTest` **37 项全过**(`ScreenCapturePlanTest` 由 10 增到 12,新增 `segment` 参数校验与 `segmentName` 命名两组用例)。
- **真机分段录制未验证**:`setNextOutputFile` 的实际切换行为、段边界音画同步、每段成品可播,都必须在设备上确认。`ScreenCaptureInstrumentedTest` 里的 `availability` 断言已同步为 256 MiB + `segmenting: true`,但「跑一分段真录制」这条用例还没有。

