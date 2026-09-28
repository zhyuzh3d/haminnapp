(() => {
  const text = document.querySelector("#text");
  const status = document.querySelector("#status");
  const notes = document.querySelector("#notes");
  const record = document.querySelector("#record");
  let pendingAttachment = null;
  let recordingId = null;
  const report = value => status.textContent = value;
  async function refresh() {
    const result = await haminn.data.scan({ collection: "notes", limit: 100 });
    notes.replaceChildren(...result.items.reverse().map(item => {
      const row = document.createElement("article"); row.className = "note";
      const body = document.createElement("p"); body.textContent = item.value.text; row.append(body);
      if (item.value.attachment) {
        const label = document.createElement("small"); label.textContent = "附件：" + item.value.attachment.name; row.append(label);
        if (item.value.attachment.mime?.startsWith("audio/")) {
          const play = document.createElement("button"); play.textContent = "播放";
          play.onclick = async () => {
            try {
              await haminn.audio.stopPlayback({});
              await haminn.audio.play({ logicalFileId: item.value.attachment.logicalFileId });
            } catch (error) { report(error.message); }
          };
          row.append(play);
        }
        const share = document.createElement("button"); share.textContent = "分享";
        share.onclick = () => haminn.files.share({ logicalFileId: item.value.attachment.logicalFileId }).catch(error => report(error.message));
        const save = document.createElement("button"); save.textContent = "导出";
        save.onclick = () => haminn.files.export({ logicalFileId: item.value.attachment.logicalFileId }).catch(error => report(error.message));
        row.append(share, save);
      }
      return row;
    }));
  }
  document.querySelector("#save").onclick = async () => {
    const value = text.value.trim(); if (!value) return;
    await haminn.data.put({ collection: "notes", key: String(Date.now()).padStart(16, "0"),
      value: { text: value, createdAt: Date.now(), attachment: pendingAttachment }, expectedRevision: "absent" });
    text.value = ""; pendingAttachment = null; report("已保存"); await refresh();
  };
  document.querySelector("#speak").onclick = () => haminn.tts.speak({ text: text.value || "请先输入内容", language: "zh-CN" }).catch(error => report(error.message));
  document.querySelector("#attach").onclick = async () => {
    const file = await haminn.files.import({});
    if (!file.cancelled) { pendingAttachment = file; report("附件将在保存笔记时关联：" + file.name); }
  };
  async function finishRecording() {
    if (!recordingId) return;
    const id = recordingId; recordingId = null; record.textContent = "录音附件";
    try {
      pendingAttachment = await haminn.audio.stopRecording({ recordingId: id, name: `recording-${Date.now()}.m4a` });
      report("录音将在保存笔记时关联：" + pendingAttachment.name);
    } catch (error) { report(error.message); }
  }
  record.onclick = async () => {
    if (recordingId) return finishRecording();
    try {
      const result = await haminn.audio.startRecording({ maxDurationMs: 5 * 60_000 });
      recordingId = result.recordingId; record.textContent = "停止录音"; report("正在录音…");
    } catch (error) { report(error.message); }
  };
  haminn.on("audio.recording.limit", value => {
    if (value.recordingId === recordingId) finishRecording();
  });
  document.querySelector("#listen").onclick = async () => {
    const offFinal = haminn.on("speech.final", value => { text.value += (text.value ? "\n" : "") + (value.alternatives[0]?.text || ""); offFinal(); });
    try { await haminn.speech.start({ language: "zh-CN", partial: true }); } catch (error) { offFinal(); report(error.message); }
  };
  async function ready() {
    const result = await haminn.runtime.capabilities();
    const capabilities = new Map(result.capabilities.map(item => [item.name, item]));
    const audio = capabilities.get("audio");
    record.disabled = !audio?.features?.microphoneRecording;
    record.title = record.disabled ? "本机没有可用麦克风" : "";
    document.querySelector("#listen").disabled = !capabilities.get("speech")?.supported;
    document.querySelector("#speak").disabled = !capabilities.get("tts")?.supported;
    await refresh();
  }
  addEventListener("haminnready", () => ready().catch(error => report(error.message)), { once: true });
  if (haminn.isReady) ready().catch(error => report(error.message));
})();
