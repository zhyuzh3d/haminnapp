export type HaminnErrorCode =
  | "E_INVALID_ARGUMENT" | "E_UNSUPPORTED" | "E_ORIGIN_DENIED" | "E_CAPABILITY_DENIED"
  | "E_OS_PERMISSION_DENIED" | "E_SESSION_EXPIRED" | "E_CANCELLED" | "E_TIMEOUT"
  | "E_CONFLICT" | "E_QUOTA" | "E_STORAGE" | "E_NETWORK" | "E_INTERNAL";

export interface HaminnRecord<T = unknown> { collection: string; key: string; value: T; revision: string }
export interface HaminnFile { logicalFileId: string; url: string; name: string; mime: string; size: number; sha256: string }
export interface HaminnCapability { name: string; implemented: boolean; supported: boolean; usable: boolean; lifecycle: string; features?: Record<string, unknown>; authorization?: Record<string, { implemented: boolean; supported: boolean; grant: "ask" | "allow" | "deny" | "host"; system: "granted" | "missing" | "not-required"; missingPermissions?: string[]; usable: boolean }> }
export type HaminnNotificationRecurrence = "once" | "daily" | "weekly" | "monthly" | "yearly";
export interface HaminnNotification { id: string; title: string; body?: string; data?: Record<string, unknown> }
export type HaminnPermissionCapability = "speech" | "microphone.record" | "tts.speak" | "location.approximate" | "location.precise" | "sensors.read" | "sensors.steps" | "wifi.scan" | "wifi.connect" | "bluetooth.scan" | "bluetooth.connect" | "infrared.transmit" | "camera.capture" | "camera.torch" | "screen.capture" | "screen.record" | "clipboard.read" | "network" | "notifications";
export type HaminnScreenAudioMode = "none" | "system" | "microphone" | "both";
export type HaminnScreenStopReason = "page" | "duration" | "bytes" | "projection-revoked" | "host-stop";
export interface HaminnScreenAvailability { supported: boolean; systemAudio: boolean; systemAudioUsages: Array<"media" | "game" | "unknown">; microphone: boolean; audioModes: HaminnScreenAudioMode[]; segmenting: boolean; maxDurationMs: number; maxBytes: number; maxFrameRate: number; scales: number[]; videoBitRateMin: number; videoBitRateMax: number; audioBitRateMin: number; audioBitRateMax: number; stillFormats: Array<"jpeg" | "png">; stillMaxEdge: number }
export interface HaminnScreenSegment { recordingId: string; index: number; logicalFileId: string | null; url?: string; name?: string; mime: string; size?: number; sha256?: string; durationMs: number; width: number; height: number; audio: HaminnScreenAudioMode }
export interface HaminnScreenRecording { logicalFileId: string | null; url?: string; name?: string; mime: string; size?: number; sha256?: string; index: number; durationMs: number; width: number; height: number; audio: HaminnScreenAudioMode; stoppedBy: HaminnScreenStopReason; segments: HaminnScreenSegment[]; message?: string }
export type HaminnSensorType = "accelerometer" | "gyroscope" | "magneticField" | "orientation" | "rotationVector" | "gravity" | "linearAcceleration" | "light" | "proximity" | "pressure" | "ambientTemperature" | "relativeHumidity" | "stepCounter" | "stepDetector";
export interface HaminnNetworkState { connected: boolean; validated: boolean; internet: boolean; captivePortal: boolean; metered: boolean; downstreamKbps: number | null; upstreamKbps: number | null; transports: Array<"wifi" | "cellular" | "ethernet" | "vpn"> }
export type HaminnMultipartPart = { name: string; text: string } | { name: string; logicalFileId: string; filename?: string; contentType?: string };
export interface HaminnNetworkRequest { url: string; method?: "GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE"; headers?: Record<string, string>; contentType?: string; bodyText?: string; bodyBase64?: string; bodyLogicalFileId?: string; multipart?: HaminnMultipartPart[]; timeoutMs?: number }
export interface HaminnNetworkResponse { status: number; headers: Record<string, string>; url: string; body?: null; bodyText?: string; bodyBase64?: string; file?: HaminnFile }
export interface HaminnNetworkStream extends HaminnNetworkResponse { streamId: string; contentType: string; contentLength: number | null }
export interface HaminnNetworkSocket extends HaminnNetworkResponse { socketId: string }
export interface HaminnSystemLanguage { languageTag: string; language: string; script: string | null; region: string | null; preferredLanguages: string[] }
export interface HaminnApi {
  icons: HaminnIcons;
  call<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T>;
  runtime: {
    info(): Promise<{ apiMajor: 1; apiMinor: number; sessionId: string; appId: string; role: "store" | "web_app"; webViewPackage: string | null; runtimeMode: "local" | "live"; launchChannel: "stable" | "dev"; devRevision: number | null; bridgeMode: "shared-web-message" | "shared-legacy-bridge"; isolatedProfiles: boolean }>;
    capabilities(): Promise<{ capabilities: HaminnCapability[] }>;
  };
  app: { info(): Promise<Record<string, unknown>>; reload(): Promise<{ reloading: true }>; ready(): Promise<{ recorded: true }>; checkUpdate(): Promise<{ canCheck: boolean }>; setRuntimeMode(params: { runtimeMode: "local" | "live" }): Promise<Record<string, unknown>>; backup(): Promise<{ cancelled: true } | { cancelled: false; exported: true; appId: string; bytes: number; fileName: string }> };
  appearance: { reportTheme(params: { theme: "light" | "dark" }): Promise<{ theme: "light" | "dark"; applied: true }> };
  permissions: {
    status(params: { capability: HaminnPermissionCapability; scope?: string }): Promise<Record<string, unknown>>;
    request(params: { capability: HaminnPermissionCapability; scope?: string }): Promise<{ capability: string; scope: string; addressClass?: "public" | "private" | null; usable: true }>;
  };
  data: {
    get<T = unknown>(params: { collection: string; key: string }): Promise<HaminnRecord<T> | null>;
    put<T = unknown>(params: { collection: string; key: string; value: T; expectedRevision?: string }): Promise<Pick<HaminnRecord, "collection" | "key" | "revision">>;
    delete(params: { collection: string; key: string; expectedRevision?: string }): Promise<{ deleted: boolean }>;
    scan<T = unknown>(params: { collection: string; prefix?: string; afterKey?: string; limit?: number }): Promise<{ items: HaminnRecord<T>[]; nextAfterKey: string | null }>;
    batch(params: { operations: Array<Record<string, unknown>> }): Promise<{ results: Array<Record<string, unknown>> }>;
  };
  files: {
    import(params?: { accept?: string }): Promise<HaminnFile & { cancelled?: boolean }>;
    pickImage(params?: { maxDimension?: number; maxBytes?: number }): Promise<{ cancelled: true } | (HaminnFile & { cancelled: false })>;
    pickInline(params: { accept: string; maxBytes?: number }): Promise<{ cancelled: true } | { cancelled: false; name: string; mime: string; size: number; dataUrl: string }>;
    writeText(params: { name: string; text: string }): Promise<HaminnFile>;
    beginWrite(params: { name: string; mime?: string }): Promise<{ writeId: string; maxChunkBytes: number }>;
    appendBytes(params: { writeId: string; chunkBase64: string }): Promise<{ writeId: string; receivedBytes: number; maxChunkBytes: number }>;
    finishWrite(params: { writeId: string }): Promise<HaminnFile>;
    abortWrite(params: { writeId: string }): Promise<{ writeId: string; aborted: true }>;
    readText(params: { logicalFileId: string; maxBytes?: number }): Promise<HaminnFile & { text: string }>;
    list(): Promise<{ files: HaminnFile[] }>;
    export(params: { logicalFileId: string }): Promise<{ exported?: true; cancelled: boolean }>;
    delete(params: { logicalFileId: string }): Promise<{ deleted: boolean }>;
    share(params: { logicalFileId: string; title?: string }): Promise<{ chooserOpened: true }>;
  };
  audio: {
    startRecording(params?: { maxDurationMs?: number }): Promise<{ recordingId: string; maxDurationMs: number }>;
    stopRecording(params?: { recordingId?: string; name?: string }): Promise<HaminnFile & { durationMs: number }>;
    cancelRecording(params?: { recordingId?: string }): Promise<{ cancelled: boolean }>;
    play(params: { logicalFileId: string; loop?: boolean; volume?: number }): Promise<{ playbackId: string }>;
    stopPlayback(params?: { playbackId?: string }): Promise<{ stopped: boolean }>;
  };
  tts: {
    availability(): Promise<{ available: boolean; operational: boolean; state: "ready" | "fallback" | "unavailable"; reasonCode: string | null; message: string | null; probeRequired: boolean; voiceSelectionSupported: boolean; languageSelectionSupported: boolean; fileSynthesisSupported: boolean; networkMayBeRequired: boolean; settingsAvailable: boolean }>;
    preferences(): Promise<{ language: string | null; rate: number; pitch: number }>;
    voices(): Promise<{ currentVoice: string | null; selectedVoice: string | null; languageSelectionSupported: boolean; voiceSelectionSupported: boolean; languages: string[]; voices: Array<{ id: string; locale: string; networkRequired: boolean; quality: number; latency: number; features: string[] }> }>;
    languageAvailability(params: { language: string }): Promise<{ language: string; support: "language" | "country" | "variant" | "missing-data" | "unsupported" | "unknown"; available: boolean }>;
    speak(params: { text: string; language?: string; voiceId?: string; rate?: number; pitch?: number; volume?: number; pan?: number; queue?: "flush" | "add" }): Promise<{ utteranceId: string }>;
    stop(): Promise<{ stopped: true }>;
    export(params: { text: string; name?: string; language?: string; voiceId?: string; rate?: number; pitch?: number }): Promise<HaminnFile>;
  };
  speech: {
    availability(): Promise<{ available: boolean; state: "ready" | "activity-only" | "unavailable"; streamingAvailable: boolean; oneShotAvailable: boolean; onDeviceAvailable: boolean; partialResultsSupported: boolean; rmsEventsSupported: boolean; languageDetectionSupported: boolean; settingsAvailable: boolean; reasonCode: string | null; message: string | null }>;
    preferences(): Promise<{ language: string | null; preferOffline: boolean }>;
    languages(): Promise<{ languageSelectionSupported: boolean; languages: string[]; preferredLanguage: string | null; source: "system-provider"; message: string | null }>;
    start(params?: { language?: string; languageModel?: "freeForm" | "webSearch"; prompt?: string; partial?: boolean; maxResults?: number; onDevice?: boolean; preferOffline?: boolean; rmsEvents?: boolean; detectLanguage?: boolean; completeSilenceMs?: number; possiblyCompleteSilenceMs?: number }): Promise<{ subscriptionId: string; onDevice: boolean; mode: "streaming" }>;
    recognizeOnce(params?: { language?: string; languageModel?: "freeForm" | "webSearch"; prompt?: string; maxResults?: number; preferOffline?: boolean }): Promise<{ cancelled: true; mode: "one-shot" } | { cancelled: false; mode: "one-shot"; alternatives: Array<{ text: string; confidence: number | null }> }>;
    stop(params?: { subscriptionId?: string }): Promise<Record<string, unknown>>;
    cancel(): Promise<Record<string, unknown>>;
  };
  location: { availability(): Promise<{ supported: boolean; enabled: boolean; providers: Array<{ name: string; enabled: boolean }> }>; getCurrent(params?: { precise?: boolean; timeoutMs?: number; maxAgeMs?: number }): Promise<Record<string, number | boolean | null>>; watch(params?: Record<string, unknown>): Promise<{ subscriptionId: string }>; clearWatch(params: { subscriptionId: string }): Promise<{ cleared: boolean }> };
  sensors: { availability(): Promise<{ available: boolean; maxRateHz: number; sensors: Array<Record<string, unknown>> }>; watch(params: { type: HaminnSensorType; rateHz?: number }): Promise<{ subscriptionId: string; type: HaminnSensorType; rateHz: number }>; clearWatch(params: { subscriptionId: string }): Promise<{ cleared: boolean }> };
  camera: { capturePhoto(): Promise<(HaminnFile & { cancelled: false }) | { cancelled: true }>; torchStatus(): Promise<{ supported: boolean; cameras: Array<Record<string, unknown>> }>; setTorch(params: { enabled: boolean }): Promise<{ cameraId: string; enabled: boolean }> };
  screen: {
    availability(): Promise<HaminnScreenAvailability>;
    capture(params?: { maxEdge?: number; format?: "jpeg" | "png" }): Promise<{ cancelled: true } | (HaminnFile & { cancelled: false; width: number; height: number; capturedAt: number })>;
    startRecording(params?: { audio?: HaminnScreenAudioMode; maxDurationMs?: number; maxBytes?: number; segment?: boolean; scale?: number; frameRate?: number; videoBitRate?: number; audioBitRate?: number; name?: string }): Promise<{ cancelled: true } | { recordingId: string; audio: HaminnScreenAudioMode; width: number; height: number; segment: boolean; maxDurationMs: number; maxBytes: number; startedAt: number }>;
    stopRecording(params?: { recordingId?: string }): Promise<HaminnScreenRecording>;
    cancelRecording(params?: { recordingId?: string }): Promise<{ cancelled: boolean }>;
  };
  share: { text(params: { text: string; title?: string }): Promise<{ chooserOpened: true }>; file(params: { logicalFileId: string; title?: string }): Promise<{ chooserOpened: true }> };
  clipboard: { write(params: { text: string; label?: string }): Promise<{ written: true }>; read(): Promise<{ text: string | null }> };
  haptics: { vibrate(params?: { durationMs?: number }): Promise<{ started: true }>; impact(params?: { style?: "light" | "medium" | "heavy" }): Promise<{ started: true }> };
  network: {
    status(): Promise<HaminnNetworkState>;
    watch(): Promise<{ subscriptionId: string; current: HaminnNetworkState }>;
    clearWatch(params: { subscriptionId: string }): Promise<{ cleared: boolean }>;
    request(params: HaminnNetworkRequest): Promise<HaminnNetworkResponse>;
    openStream(params: HaminnNetworkRequest): Promise<HaminnNetworkStream>;
    readStream(params: { streamId: string; maxBytes?: number; timeoutMs?: number }): Promise<{ streamId: string; chunkBase64: string; bytes: number; receivedBytes: number; done: boolean }>;
    closeStream(params: { streamId: string }): Promise<{ streamId: string; closed: true }>;
    openSocket(params: { url: string; headers?: Record<string, string>; timeoutMs?: number }): Promise<HaminnNetworkSocket>;
    readSocket(params: { socketId: string; timeoutMs?: number }): Promise<{ socketId: string; type: "timeout" | "text" | "binary" | "closing" | "closed" | "error"; text?: string; dataBase64?: string; code?: number }>;
    sendSocket(params: { socketId: string; text: string } | { socketId: string; dataBase64: string }): Promise<{ socketId: string; accepted: true; bytes: number }>;
    closeSocket(params: { socketId: string; reason?: string }): Promise<{ socketId: string; closed: true }>;
  };
  wifi: {
    status(): Promise<Record<string, unknown>>;
    scan(): Promise<{ fresh: boolean; throttled: boolean; networks: Array<Record<string, unknown>> }>;
    requestNetwork(params: { ssid: string; security?: "open" | "wpa2" | "wpa3"; passphrase?: string; hidden?: boolean; bindProcess?: boolean; timeoutMs?: number }): Promise<{ connectionId: string; pendingSystemApproval: true; processBound: boolean }>;
    releaseNetwork(params: { connectionId: string }): Promise<{ released: boolean }>;
    openSettings(): Promise<{ opened: true; page: "wifi" }>;
  };
  bluetooth: {
    status(): Promise<Record<string, unknown>>;
    paired(): Promise<{ devices: Array<Record<string, unknown>> }>;
    scan(params?: { mode?: "lowPower" | "balanced" | "lowLatency"; serviceUuid?: string }): Promise<{ subscriptionId: string; mode: string }>;
    stopScan(params: { subscriptionId: string }): Promise<{ stopped: boolean }>;
    connect(params: { address: string; autoConnect?: boolean }): Promise<{ connectionId: string; address: string; connecting: true }>;
    disconnect(params: { connectionId: string }): Promise<{ disconnected: boolean }>;
    services(params: { connectionId: string }): Promise<{ connectionId: string; services: Array<Record<string, unknown>> }>;
    read(params: { connectionId: string; serviceUuid: string; characteristicUuid: string }): Promise<{ accepted: true }>;
    write(params: { connectionId: string; serviceUuid: string; characteristicUuid: string; valueBase64: string; withoutResponse?: boolean }): Promise<{ accepted: true }>;
    subscribe(params: { connectionId: string; serviceUuid: string; characteristicUuid: string; enabled?: boolean }): Promise<{ accepted: true; enabled: boolean }>;
    openSettings(): Promise<{ opened: true; page: "bluetooth" }>;
  };
  infrared: { status(): Promise<{ supported: boolean; transmitOnly: true; receiveSupported: false; frequencyRanges: Array<{ minHz: number; maxHz: number }> }>; transmit(params: { carrierFrequencyHz: number; patternUs: number[] }): Promise<{ transmitted: true; carrierFrequencyHz: number; segments: number; durationUs: number }> };
  battery: { status(): Promise<Record<string, unknown>>; watch(): Promise<{ subscriptionId: string; current: Record<string, unknown> }>; clearWatch(params: { subscriptionId: string }): Promise<{ cleared: boolean }> };
  system: {
    language(): Promise<HaminnSystemLanguage>;
    openSettings(params: { page: "wifi" | "bluetooth" | "location" | "voiceInput" | "tts" | "app" | "notifications" }): Promise<{ opened: true; page: string }>;
  };
  notifications: {
    notify(params: HaminnNotification): Promise<{ posted: boolean; id: string }>;
    schedule(params: { notification: HaminnNotification; triggerAt: number; recurrence?: HaminnNotificationRecurrence }): Promise<{ scheduled: true; id: string; triggerAt: number; recurrence: HaminnNotificationRecurrence }>;
    cancel(params: { id: string }): Promise<{ cancelled: boolean }>;
    cancelAll(): Promise<{ cancelled: number }>;
    getScheduled(): Promise<{ items: Array<HaminnNotification & { triggerAt: number; nextTriggerAt: number; recurrence: HaminnNotificationRecurrence; enabled: boolean }> }>;
    setEndpoint(params: { endpoint: string }): Promise<{ endpoint: string; origin: string }>;
    getStatus(): Promise<{ enabled: boolean; systemPermission: boolean; exactAlarm: boolean; endpoint: string | null }>;
  };
  on(event: string, listener: (data: unknown) => void): () => void;
  readonly isReady: boolean;
}

export interface HaminnIcons {
  readonly version: string;
  readonly stylesheet: string;
  load(): Promise<void>;
  create(name: string, options?: { style?: "solid" | "regular" | "brands"; label?: string }): HTMLElement;
}
declare global { interface Window { readonly haminn: HaminnApi; readonly HaminnIcons: HaminnIcons } const haminn: HaminnApi }
export {};
