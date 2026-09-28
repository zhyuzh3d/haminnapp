(() => {
  "use strict";
  const H = window.HaminnShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const copy = (text, label) => H.copy(text, label);
  let catalogPromise;
  function loadCatalog() {
    if (!catalogPromise) catalogPromise = new Promise((resolve, reject) => {
      const script = document.createElement("script"); script.src = "icon-catalog.js";
      script.onload = resolve; script.onerror = () => { catalogPromise = null; script.remove(); reject(new Error("离线图标目录读取失败。")); };
      document.head.append(script);
    });
    return catalogPromise.then(renderIcons);
  }
  const synonyms = { "相机":"camera", "拍照":"camera", "文件":"file", "文件夹":"folder", "搜索":"search", "设置":"gear", "用户":"user", "心":"heart", "收藏":"star", "删除":"trash", "添加":"plus", "音乐":"music", "声音":"volume", "下载":"download", "上传":"upload", "主页":"home", "房子":"house", "位置":"location", "时间":"clock", "日历":"calendar", "锁":"lock", "安全":"shield", "图片":"image", "邮件":"envelope", "消息":"message", "通知":"bell", "手机":"mobile", "电脑":"computer", "返回":"arrow-left", "分享":"share", "编辑":"pen", "电话":"phone", "视频":"video", "网络":"wifi", "云":"cloud", "工具":"tool", "列表":"list", "购物":"cart", "书":"book", "地球":"globe", "代码":"code", "麦克风":"microphone" };
  function renderIcons() {
    if (!window.haminnIconCatalog) return;
    const raw = $("#searchIcons").value.trim().toLowerCase(), query = synonyms[raw] || raw;
    const items = window.haminnIconCatalog.icons.filter(icon => (state.iconStyle === "all" || icon.style === state.iconStyle) && (!query || [icon.name,icon.label,...icon.terms].join(" ").toLowerCase().includes(query)));
    const root = $("#iconGrid"); root.replaceChildren();
    for (const icon of items.slice(0,state.iconLimit)) {
      const button = document.createElement("button"); button.className = "catalog-icon";
      button.setAttribute("aria-label", "复制 " + icon.style + " " + icon.name + " 图标代码");
      const mark = document.createElement("i"); mark.className = "fa-" + icon.style + " fa-" + icon.name; mark.setAttribute("aria-hidden", "true");
      const label = document.createElement("span"); label.textContent = icon.name;
      const style = document.createElement("small"); style.textContent = { solid:"实心",regular:"常规",brands:"品牌" }[icon.style];
      button.append(mark,label,style);
      button.onclick = () => busy(button, () => copy('<i class="fa-' + icon.style + ' fa-' + icon.name + '" aria-hidden="true"></i>', "图标代码"));
      root.append(button);
    }
    $("#iconCount").textContent = items.length ? items.length + " 个图标 · 已显示 " + Math.min(items.length,state.iconLimit) : "没有匹配的图标，试试英文名称或其他关键词。";
    $("#moreIcons").classList.toggle("hidden", state.iconLimit >= items.length);
  }
  $("#searchIcons").oninput = () => { state.iconLimit = 60; renderIcons(); };
  $$("[data-style]").forEach(button => { button.onclick = () => { state.iconStyle = button.dataset.style; state.iconLimit = 60; selected("#iconFilters","style",state.iconStyle); renderIcons(); }; });
  $("#moreIcons").onclick = () => { state.iconLimit += 60; renderIcons(); };
  H.features.icons = { loadCatalog };
})();
