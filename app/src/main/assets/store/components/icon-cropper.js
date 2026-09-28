(() => {
  "use strict";
  const H = window.HaminnShell;
  const { $, state } = H;
  const { open, close, say } = H.ui;

  function geometry(imageWidth, imageHeight, frameSize, zoom, offsetX, offsetY) {
    const width = Number(imageWidth), height = Number(imageHeight), size = Math.max(1, Number(frameSize || 320));
    if (!(width > 0) || !(height > 0)) throw new Error("图标图片尺寸无效。");
    const safeZoom = Math.max(1, Math.min(4, Number(zoom || 1)));
    const baseScale = size / Math.min(width, height);
    const renderedWidth = width * baseScale * safeZoom, renderedHeight = height * baseScale * safeZoom;
    const limitX = Math.max(0, (renderedWidth - size) / 2), limitY = Math.max(0, (renderedHeight - size) / 2);
    const x = Math.max(-limitX, Math.min(limitX, Number(offsetX || 0)));
    const y = Math.max(-limitY, Math.min(limitY, Number(offsetY || 0)));
    const scale = baseScale * safeZoom, sourceSize = Math.min(width, height, size / scale);
    return {
      zoom:safeZoom, x, y, baseWidth:width * baseScale, baseHeight:height * baseScale,
      sourceX:Math.max(0, Math.min(width - sourceSize, width / 2 - x / scale - sourceSize / 2)),
      sourceY:Math.max(0, Math.min(height - sourceSize, height / 2 - y / scale - sourceSize / 2)), sourceSize
    };
  }

  function cropIcon(dataUrl) {
    if (typeof dataUrl !== "string" || !/^data:image\/(png|jpeg|webp);base64,/i.test(dataUrl)) {
      return Promise.reject(new Error("没有取得可用的图标图片。"));
    }
    return new Promise((resolve, reject) => {
      const panel = $("#iconCropPanel"), stage = $("#iconCropStage"), image = $("#iconCropImage");
      const draft = { zoom:1, x:0, y:0 }, pointers = {};
      let gesture = null, settled = false;
      const cleanup = [];
      function release() { while (cleanup.length) cleanup.pop()(); image.removeAttribute("src"); image.classList.remove("ready"); }
      function finish(value, closePanel = true) {
        if (settled) return; settled = true; release();
        if (closePanel) close("#iconCropPanel");
        resolve(value);
      }
      function fail(error) {
        if (settled) return; settled = true; release(); close("#iconCropPanel"); reject(error);
      }
      function stageSize() {
        let width = stage.getBoundingClientRect().width;
        if (!(width > 0)) width = Math.min(420, Math.max(240, (document.documentElement.clientWidth || 364) - 48));
        stage.style.height = Math.round(width) + "px"; return width;
      }
      function paint() {
        const next = geometry(image.naturalWidth, image.naturalHeight, stageSize(), draft.zoom, draft.x, draft.y);
        draft.zoom = next.zoom; draft.x = next.x; draft.y = next.y;
        image.style.width = next.baseWidth + "px"; image.style.height = next.baseHeight + "px";
        image.style.transform = "translate(-50%, -50%) translate(" + draft.x + "px, " + draft.y + "px) scale(" + draft.zoom + ")";
        image.classList.add("ready"); $("#iconCropZoom").textContent = Math.round(draft.zoom * 100) + "%";
      }
      function pointList() { return Object.keys(pointers).map(key => pointers[key]); }
      function startGesture() {
        const points = pointList();
        if (points.length >= 2) {
          const a = points[0], b = points[1], dx = b.x - a.x, dy = b.y - a.y;
          gesture = { type:"pinch", distance:Math.max(1, Math.sqrt(dx * dx + dy * dy)), centerX:(a.x + b.x) / 2, centerY:(a.y + b.y) / 2, zoom:draft.zoom, x:draft.x, y:draft.y };
        } else if (points.length === 1) gesture = { type:"drag", id:points[0].id, startX:points[0].x, startY:points[0].y, x:draft.x, y:draft.y };
        else gesture = null;
      }
      function moveGesture(activeId) {
        const points = pointList();
        if (gesture && gesture.type === "pinch" && points.length >= 2) {
          const a = points[0], b = points[1], dx = b.x - a.x, dy = b.y - a.y, centerX = (a.x + b.x) / 2, centerY = (a.y + b.y) / 2;
          draft.zoom = gesture.zoom * Math.sqrt(dx * dx + dy * dy) / gesture.distance; draft.x = gesture.x + centerX - gesture.centerX; draft.y = gesture.y + centerY - gesture.centerY; paint();
        } else if (gesture && gesture.type === "drag" && gesture.id === activeId && pointers[activeId]) {
          draft.x = gesture.x + pointers[activeId].x - gesture.startX; draft.y = gesture.y + pointers[activeId].y - gesture.startY; paint();
        }
      }
      function endPointer(event) { delete pointers[event.pointerId]; startGesture(); }
      function bind(target, type, listener, options) { target.addEventListener(type, listener, options); cleanup.push(() => target.removeEventListener(type, listener, options)); }
      function onClosed() { finish(null, false); }
      bind(panel, "haminnmodalclose", onClosed);
      image.onload = () => { try { paint(); } catch (error) { fail(error); } };
      image.onerror = () => fail(new Error("图标图片无法读取。"));
      if ("PointerEvent" in window) {
        bind(stage, "pointerdown", event => { pointers[event.pointerId] = { id:event.pointerId, x:event.clientX, y:event.clientY }; try { stage.setPointerCapture(event.pointerId); } catch (_) {} startGesture(); });
        bind(stage, "pointermove", event => { if (!pointers[event.pointerId]) return; pointers[event.pointerId].x = event.clientX; pointers[event.pointerId].y = event.clientY; moveGesture(event.pointerId); });
        bind(stage, "pointerup", endPointer); bind(stage, "pointercancel", endPointer);
      } else {
        const readTouches = event => { Object.keys(pointers).forEach(key => { if (key.indexOf("touch-") === 0) delete pointers[key]; }); Array.prototype.forEach.call(event.touches || [], touch => { pointers["touch-" + touch.identifier] = { id:"touch-" + touch.identifier, x:touch.clientX, y:touch.clientY }; }); };
        bind(stage, "touchstart", event => { readTouches(event); startGesture(); event.preventDefault(); }, { passive:false });
        bind(stage, "touchmove", event => { readTouches(event); const point = pointList()[0]; if (point) moveGesture(point.id); event.preventDefault(); }, { passive:false });
        bind(stage, "touchend", event => { readTouches(event); startGesture(); event.preventDefault(); }, { passive:false });
        let mouseDown = false;
        bind(stage, "mousedown", event => { mouseDown = true; pointers.mouse = { id:"mouse", x:event.clientX, y:event.clientY }; startGesture(); event.preventDefault(); });
        bind(document, "mousemove", event => { if (!mouseDown) return; pointers.mouse.x = event.clientX; pointers.mouse.y = event.clientY; moveGesture("mouse"); });
        bind(document, "mouseup", () => { mouseDown = false; delete pointers.mouse; startGesture(); });
      }
      bind(stage, "wheel", event => { event.preventDefault(); draft.zoom += event.deltaY < 0 ? .12 : -.12; paint(); }, { passive:false });
      $("#iconCropZoomOut").onclick = () => { draft.zoom -= .15; paint(); };
      $("#iconCropZoomIn").onclick = () => { draft.zoom += .15; paint(); };
      $("#cancelIconCrop").onclick = () => finish(null);
      $("#applyIconCrop").onclick = () => {
        try {
          const next = geometry(image.naturalWidth, image.naturalHeight, stageSize(), draft.zoom, draft.x, draft.y);
          const canvas = document.createElement("canvas"); canvas.width = 192; canvas.height = 192;
          canvas.getContext("2d").drawImage(image, next.sourceX, next.sourceY, next.sourceSize, next.sourceSize, 0, 0, 192, 192);
          finish(canvas.toDataURL("image/png"));
        } catch (error) { say(error.message || "图标裁切失败，请换一张图片。", true); }
      };
      const repaint = () => { if (!settled && stage.isConnected && image.naturalWidth) paint(); };
      bind(window, "resize", repaint); image.src = dataUrl; open("#iconCropPanel");
      if (window.requestAnimationFrame) window.requestAnimationFrame(repaint);
    });
  }

  H.ui.cropIcon = cropIcon;
  H.ui.iconCropGeometry = geometry;
})();
