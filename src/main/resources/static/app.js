"use strict";
const $ = (id) => document.getElementById(id);

// ═══════════════════════════════════════════════════
// TABS
// ═══════════════════════════════════════════════════
const tabSetup   = $("tabSetup");
const tabMonitor = $("tabMonitor");
const viewSetup  = $("viewSetup");
const viewMonitor= $("viewMonitor");

function showTab(tab) {
  const isSetup = tab === "setup";
  tabSetup.classList.toggle("active", isSetup);
  tabMonitor.classList.toggle("active", !isSetup);
  viewSetup.classList.toggle("hidden", !isSetup);
  viewMonitor.classList.toggle("hidden", isSetup);
}
tabSetup.addEventListener("click",   () => showTab("setup"));
tabMonitor.addEventListener("click", () => showTab("monitor"));
$("btnBackToSetup").addEventListener("click", () => showTab("setup"));

// ═══════════════════════════════════════════════════
// SETUP — выбор видео
// ═══════════════════════════════════════════════════
const elSearch   = $("searchInput");
const elRefresh  = $("refreshBtn");
const elList     = $("videoList");
const elPath     = $("videoPath");

let allVideos    = [];
let selectedPath = "";

loadVideos();
elSearch.addEventListener("input", () => renderList(elSearch.value.trim().toLowerCase()));
elRefresh.addEventListener("click", loadVideos);
elPath.addEventListener("input", () => { selectedPath = elPath.value.trim(); onSelChange(); });

async function loadVideos() {
  elList.innerHTML = '<div class="vlist-empty">Загрузка…</div>';
  try {
    const r = await fetch("/api/videos");
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    allVideos = await r.json();
    renderList(elSearch.value.trim().toLowerCase());
  } catch (e) {
    elList.innerHTML = `<div class="vlist-empty">Ошибка: ${e.message}</div>`;
  }
}

function renderList(filter) {
  const items = filter ? allVideos.filter(v => v.name.toLowerCase().includes(filter)) : allVideos;
  if (!items.length) { elList.innerHTML = '<div class="vlist-empty">Файлы не найдены</div>'; return; }
  elList.innerHTML = "";
  for (const v of items) {
    const el = document.createElement("div");
    el.className = "vitem" + (v.path === selectedPath ? " sel" : "");
    el.dataset.path = v.path;
    el.innerHTML = `<span class="vitem-icon">🎥</span>
      <div style="min-width:0;flex:1">
        <div class="vitem-name" title="${esc(v.path)}">${esc(v.name)}</div>
        <div class="vitem-meta">${fmtSize(v.sizeBytes)} · ${v.modified}</div>
      </div>`;
    el.addEventListener("click", () => selectVideo(v));
    elList.appendChild(el);
  }
}

function selectVideo(v) {
  selectedPath = v.path;
  elPath.value = v.path;
  document.querySelectorAll(".vitem").forEach(el =>
      el.classList.toggle("sel", el.dataset.path === v.path));
  onSelChange();
  loadPreview(v.path);
}

function onSelChange() {
  $("btnStart").disabled = !selectedPath || !!currentSessionId;
  updateSummary();
}

// ═══════════════════════════════════════════════════
// SETUP — превью и линия
// ═══════════════════════════════════════════════════
const elLineRatio  = $("lineRatio");
const elLineLabel  = $("lineLabel");
const elInsideSide = $("insideSide");
const elAutoInit   = $("autoInitial");
const elManField   = $("manualInitialField");
const elInitOnb    = $("initialOnboard");
const previewWrap  = $("previewWrap");
const previewCv    = $("previewCanvas");
const previewCtx   = previewCv.getContext("2d");

let lineRatio = 0.5;
let frameImg  = null;
let dragging  = false;

elLineRatio.addEventListener("input", () => {
  lineRatio = elLineRatio.value / 100;
  elLineLabel.textContent = `${elLineRatio.value}%`;
  updateSummary(); redrawPreview();
});
elInsideSide.addEventListener("change", () => { updateSummary(); redrawPreview(); });
elAutoInit.addEventListener("change", () => {
  elManField.style.display = elAutoInit.checked ? "none" : "";
  updateSummary();
});
elInitOnb.addEventListener("input", updateSummary);

// drag на превью
previewWrap.addEventListener("mousedown", e => { dragging = true; setLineY(e.clientY); e.preventDefault(); });
window.addEventListener("mousemove", e => { if (dragging) setLineY(e.clientY); });
window.addEventListener("mouseup", () => { dragging = false; });
previewWrap.addEventListener("touchstart", e => { dragging = true; setLineY(e.touches[0].clientY); e.preventDefault(); }, { passive: false });
window.addEventListener("touchmove", e => { if (dragging) setLineY(e.touches[0].clientY); }, { passive: false });
window.addEventListener("touchend", () => { dragging = false; });

function setLineY(clientY) {
  const r = previewWrap.getBoundingClientRect();
  lineRatio = +Math.max(0.05, Math.min(0.95, (clientY - r.top) / r.height)).toFixed(2);
  elLineRatio.value = Math.round(lineRatio * 100);
  elLineLabel.textContent = `${elLineRatio.value}%`;
  updateSummary(); redrawPreview();
}

async function loadPreview(path) {
  $("previewPh").classList.add("hidden");
  $("previewSpin").classList.remove("hidden");
  frameImg = null;
  try {
    const r = await fetch(`/api/videos/preview?path=${encodeURIComponent(path)}`);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const data = await r.json();
    const img = new Image();
    img.onload = () => {
      frameImg = { img, width: data.width, height: data.height };
      previewCv.width  = data.width;
      previewCv.height = data.height;
      $("previewSpin").classList.add("hidden");
      redrawPreview();
    };
    img.onerror = () => { $("previewSpin").classList.add("hidden"); $("previewPh").classList.remove("hidden"); };
    img.src = `data:image/jpeg;base64,${data.frameJpegBase64}`;
  } catch (e) {
    $("previewSpin").classList.add("hidden");
    $("previewPh").classList.remove("hidden");
    $("previewPh").textContent = `Превью недоступно: ${e.message}`;
  }
}

function redrawPreview() {
  if (!frameImg) return;
  const { img, width: w, height: h } = frameImg;
  previewCv.width = w; previewCv.height = h;
  previewCtx.drawImage(img, 0, 0, w, h);
  drawPreviewLine(w, h);
}

function drawPreviewLine(w, h) {
  const centerY = lineRatio * h;
  const halfW   = Math.max(10, Math.min(60, h * 0.04));
  const topY    = centerY - halfW;
  const botY    = centerY + halfW;
  const inside  = elInsideSide.value === "top";

  previewCtx.fillStyle = "rgba(79,125,243,.15)";
  previewCtx.fillRect(0, topY, w, botY - topY);

  previewCtx.strokeStyle = "#4f7df3"; previewCtx.lineWidth = 1.5;
  previewCtx.setLineDash([7, 5]);
  previewCtx.beginPath();
  previewCtx.moveTo(0, topY); previewCtx.lineTo(w, topY);
  previewCtx.moveTo(0, botY); previewCtx.lineTo(w, botY);
  previewCtx.stroke(); previewCtx.setLineDash([]);

  previewCtx.strokeStyle = "#f6c85f"; previewCtx.lineWidth = 2.5;
  previewCtx.beginPath();
  previewCtx.moveTo(0, centerY); previewCtx.lineTo(w, centerY);
  previewCtx.stroke();

  previewCtx.font = "bold 12px system-ui,sans-serif";
  const topLbl = inside ? "САЛОН" : "УЛИЦА";
  const botLbl = inside ? "УЛИЦА" : "САЛОН";
  const topClr = inside ? "#79a7ff" : "#ff8b8b";
  const botClr = inside ? "#ff8b8b" : "#79a7ff";

  [{ y: topY - 22, lbl: topLbl, clr: topClr }, { y: botY + 4, lbl: botLbl, clr: botClr }]
      .forEach(({ y, lbl, clr }) => {
        previewCtx.fillStyle = "rgba(5,6,8,.65)";
        previewCtx.fillRect(6, y, 72, 20);
        previewCtx.fillStyle = clr;
        previewCtx.fillText(lbl, 12, y + 14);
      });

  previewCtx.fillStyle = "rgba(5,6,8,.7)";
  previewCtx.fillRect(w - 62, centerY - 11, 56, 18);
  previewCtx.fillStyle = "#f6c85f";
  previewCtx.font = "bold 11px ui-monospace,monospace";
  previewCtx.fillText(`y=${Math.round(lineRatio * 100)}%`, w - 59, centerY + 3);
}

// ═══════════════════════════════════════════════════
// SETUP — сводка и запуск
// ═══════════════════════════════════════════════════
function updateSummary() {
  const name = selectedPath ? selectedPath.split(/[\\/]/).pop() : "не выбран";
  $("sumFile").textContent    = name;
  $("sumLine").textContent    = `${Math.round(lineRatio * 100)}%`;
  $("sumSide").textContent    = elInsideSide.value === "top" ? "сверху" : "снизу";
  $("sumInitial").textContent = elAutoInit.checked ? "авто" : `${parseInt(elInitOnb.value) || 0} чел.`;
}

$("btnStart").addEventListener("click", startSession);
$("btnStop").addEventListener("click",  stopSession);

async function startSession() {
  if (!selectedPath) return;
  $("btnStart").disabled = true;
  $("btnStop").style.display = "block";
  $("btnStop").disabled = false;
  $("launchErr").classList.add("hidden");
  setStatus("запуск", "running");

  try {
    const body = {
      videoPath:         selectedPath,
      lineYRatio:        lineRatio,
      insideOnTop:       elInsideSide.value === "top",
      autoInitialOnboard: elAutoInit.checked,
      initialOnboard:    elAutoInit.checked ? 0 : (parseInt(elInitOnb.value) || 0),
    };
    const r = await fetch("/api/sessions", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(await r.text() || `HTTP ${r.status}`);
    const data = await r.json();
    currentSessionId = data.sessionId;
    openWebSocket(data.wsUrl);
    setStatus("обработка", "running");
    showTab("monitor"); // переключаемся на мониторинг
  } catch (e) {
    $("launchErr").textContent = e.message;
    $("launchErr").classList.remove("hidden");
    $("btnStart").disabled = false;
    $("btnStop").style.display = "none";
    setStatus("ошибка", "error");
  }
}

async function stopSession() {
  if (!currentSessionId) return;
  try { await fetch(`/api/sessions/${currentSessionId}/stop`, { method: "POST" }); } catch (e) {}
}

// ═══════════════════════════════════════════════════
// MONITOR — WebSocket и рендер (оригинальный код)
// ═══════════════════════════════════════════════════
const elCanvas    = $("canvas");
const ctx         = elCanvas.getContext("2d");
const elFinal     = $("final");
const elFinalText = $("finalText");
const elStatus    = $("status");
const elReid      = $("reid-status");
const elEventLog  = $("eventLog");

let currentSessionId = null;
let currentWs        = null;
let currentFrame     = null;
let monLineRatio     = 0.7; // линия из сервера
let eventItems       = [];

// drag на мониторинг-канвасе (корректировка для следующего запуска)
elCanvas.addEventListener("mousedown", e => {
  const onMove = ev => updateLineFromMouse(ev);
  const onUp   = () => { document.removeEventListener("mousemove", onMove); document.removeEventListener("mouseup", onUp); };
  document.addEventListener("mousemove", onMove);
  document.addEventListener("mouseup", onUp);
  updateLineFromMouse(e);
});

function updateLineFromMouse(e) {
  if (!currentFrame?.image) return;
  const rect   = elCanvas.getBoundingClientRect();
  const clamped = Math.max(0.05, Math.min(0.95, (e.clientY - rect.top) / rect.height));
  // синхронизируем ползунок на вкладке настройки
  lineRatio = clamped;
  elLineRatio.value = Math.round(clamped * 100);
  elLineLabel.textContent = `${elLineRatio.value}%`;
  updateSummary();
  // перерисовываем мониторинг с новой локальной линией
  const center = currentFrame.height * clamped;
  const half   = Math.max(24, Math.min(currentFrame.height * 0.06, 90));
  currentFrame.lineY     = center;
  currentFrame.doorTopY  = center - half;
  currentFrame.doorBottomY = center + half;
  redrawMonitor();
}

fetch("/api/sessions/info")
    .then(r => r.json())
    .then(info => {
      elReid.textContent = info.reidEnabled ? "ReID: включен" : "ReID: IoU";
      elReid.className   = info.reidEnabled ? "badge ok" : "badge off";
      if (info.processEveryNFrames) $("pParamEvery").textContent    = info.processEveryNFrames;
      if (info.countAnchorYRatio)   $("pParamAnchor").textContent   = info.countAnchorYRatio;
      if (info.doorZoneHalfWidth)   $("pParamDoor").textContent     = info.doorZoneHalfWidth;
      if (info.minAnchorMovement)   $("pParamMovement").textContent = info.minAnchorMovement;
      if (info.confidenceThreshold) $("pParamConf").textContent     = info.confidenceThreshold;
    })
    .catch(() => { elReid.textContent = "сервер недоступен"; elReid.className = "badge off"; });

function openWebSocket(path) {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  currentWs = new WebSocket(`${proto}//${location.host}${path}`);
  currentWs.onmessage = ev => {
    const msg = JSON.parse(ev.data);
    if (msg.type === "FRAME")    onFrame(msg);
    if (msg.type === "FINISHED") onFinished(msg);
  };
  currentWs.onerror = () => setStatus("ошибка ws", "error");
  currentWs.onclose = () => { $("btnStart").disabled = !selectedPath; $("btnStop").disabled = true; };
}

function onFrame(msg) {
  const img = new Image();
  img.onload = () => {
    currentFrame = {
      image: img, width: msg.width, height: msg.height,
      detections: msg.detections,
      lineY: msg.lineY, doorTopY: msg.doorTopY, doorBottomY: msg.doorBottomY,
      insideOnTop: msg.insideOnTop,
    };
    elCanvas.width  = msg.width;
    elCanvas.height = msg.height;
    $("emptyState").classList.add("hidden");
    redrawMonitor();
  };
  img.src = `data:image/jpeg;base64,${msg.frameJpegBase64}`;

  $("statBoarding").textContent  = msg.boardings;
  $("statAlighting").textContent = msg.alightings;
  $("statOnboard").textContent   = msg.onboard;
  $("statInitial").textContent   = msg.initialOnboard;
  $("statTracks").textContent    = msg.detections.length;
  $("statFps").textContent       = msg.fps.toFixed(1);
  $("statFrames").textContent    = msg.frameIndex;
  $("doorWidthLabel").textContent = `${Math.round((msg.doorBottomY ?? 0) - (msg.doorTopY ?? 0))} px`;
  $("insideSideLabel").textContent  = msg.insideOnTop ? "сверху" : "снизу";
  $("outsideSideLabel").textContent = msg.insideOnTop ? "снизу"  : "сверху";
  $("visibleCount").textContent  = msg.visibleDetections ?? msg.detections.length;
  $("insideCount").textContent   = msg.insideDetections  ?? 0;
  $("doorwayCount").textContent  = msg.doorwayDetections ?? 0;
  $("outsideCount").textContent  = msg.outsideDetections ?? 0;

  for (const ev of msg.events || []) addEvent(ev);
}

function redrawMonitor() {
  if (!currentFrame?.image) return;
  const { image, width, height, detections, lineY, doorTopY, doorBottomY, insideOnTop } = currentFrame;
  ctx.clearRect(0, 0, elCanvas.width, elCanvas.height);
  ctx.drawImage(image, 0, 0, width, height);
  drawDoorZone(width, height, doorTopY, doorBottomY, lineY, insideOnTop);
  drawBoxes(detections);
}

function drawDoorZone(width, height, topY, bottomY, centerY, insideOnTop) {
  ctx.save();
  ctx.fillStyle = "rgba(79,125,243,0.16)";
  ctx.fillRect(0, topY, width, bottomY - topY);
  ctx.strokeStyle = "#4f7df3"; ctx.lineWidth = 2; ctx.setLineDash([10, 8]);
  ctx.beginPath(); ctx.moveTo(0, topY); ctx.lineTo(width, topY);
  ctx.moveTo(0, bottomY); ctx.lineTo(width, bottomY); ctx.stroke();
  ctx.setLineDash([]);
  ctx.strokeStyle = "#f6c85f"; ctx.lineWidth = 3;
  ctx.beginPath(); ctx.moveTo(0, centerY); ctx.lineTo(width, centerY); ctx.stroke();
  ctx.fillStyle = "rgba(15,17,21,.74)"; ctx.fillRect(12, 12, 290, 34);
  ctx.fillStyle = "#e6e8ec"; ctx.font = "600 18px system-ui,sans-serif";
  ctx.fillText(insideOnTop ? "САЛОН ↑ | ПРОЕМ | ↓ УЛИЦА" : "УЛИЦА ↑ | ПРОЕМ | ↓ САЛОН", 24, 35);
  ctx.restore();
}

function drawBoxes(detections) {
  ctx.font = "600 15px system-ui,sans-serif";
  for (const d of detections) {
    const color = colorFor(d);
    const label = `Пассажир-${d.trackId} ${Math.round(d.confidence * 100)}%${trackStatus(d)}`;
    ctx.strokeStyle = color; ctx.lineWidth = 2;
    ctx.strokeRect(d.x1, d.y1, d.x2 - d.x1, d.y2 - d.y1);
    const tw = ctx.measureText(label).width + 12;
    const ly = Math.max(4, d.y1 - 25);
    ctx.fillStyle = color; ctx.fillRect(d.x1, ly, tw, 22);
    ctx.fillStyle = "#0f1115"; ctx.fillText(label, d.x1 + 6, ly + 16);
    ctx.fillStyle = color; ctx.beginPath();
    ctx.arc((d.x1 + d.x2) / 2, d.y2, 4, 0, Math.PI * 2); ctx.fill();
  }
}

function colorFor(d) {
  if (d.isBoarded)       return "#41d69a";
  if (d.isAlighted)      return "#ff7a7a";
  if (d.zone === "DOORWAY") return "#f6c85f";
  if (d.zone === "INSIDE")  return "#79a7ff";
  return "#d1d7e0";
}
function trackStatus(d) {
  if (d.isBoarded)  return " вошел";
  if (d.isAlighted) return " вышел";
  return "";
}

function addEvent(event) {
  eventItems.unshift(event);
  eventItems = eventItems.slice(0, 12);
  elEventLog.innerHTML = "";
  for (const item of eventItems) {
    const isCancel = item.direction.startsWith("CANCEL_");
    const positive = item.direction === "BOARDING" || item.direction === "CANCEL_ALIGHTING";
    const row = document.createElement("div");
    row.className = `event ${positive ? "boarding" : "alighting"}${isCancel ? " cancel" : ""}`;
    row.innerHTML = `<b>${evtLabel(item.direction)}</b><span>#${item.trackId} · кадр ${item.frameIndex}</span>`;
    elEventLog.appendChild(row);
  }
}
function evtLabel(d) {
  const m = { BOARDING: "Вход", ALIGHTING: "Выход", CANCEL_BOARDING: "Отмена входа", CANCEL_ALIGHTING: "Отмена выхода" };
  return m[d] || d;
}

function onFinished(msg) {
  setStatus(msg.status === "FINISHED" ? "готово" : msg.status.toLowerCase(), msg.status === "FINISHED" ? "" : "error");
  elFinal.classList.remove("hidden");
  elFinal.classList.toggle("error", msg.status === "FAILED");
  const sec = (msg.durationMs / 1000).toFixed(1);
  elFinalText.innerHTML = msg.status === "FAILED"
      ? `<b>Ошибка:</b> ${msg.errorMessage || "неизвестная"}`
      : `Кадров: <b>${msg.framesProcessed}</b>, время: <b>${sec} с</b>, вошло: <b>${msg.totalBoardings}</b>, вышло: <b>${msg.totalAlightings}</b>, в салоне: <b>${msg.finalOnboard}</b>`;
  currentSessionId = null;
  $("btnStart").disabled = !selectedPath;
  $("btnStop").style.display = "none";
  $("btnStop").disabled = true;
}

function resetMonitor() {
  eventItems = [];
  elEventLog.innerHTML = '<div class="event empty">Пока нет событий</div>';
  elFinal.classList.add("hidden"); elFinal.classList.remove("error");
  ["statBoarding","statAlighting","statOnboard","statInitial","statTracks","statFrames",
    "visibleCount","insideCount","doorwayCount","outsideCount"].forEach(id => $(id).textContent = "0");
  $("statFps").textContent = "-";
}

function setStatus(text, cls = "") {
  elStatus.textContent = text;
  elStatus.className   = `status ${cls}`;
}

// ═══════════════════════════════════════════════════
// UTILS
// ═══════════════════════════════════════════════════
function fmtSize(b) {
  if (b == null) return "";
  if (b < 1024)        return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(0)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}
function esc(s) {
  return s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");
}

// init
updateSummary();