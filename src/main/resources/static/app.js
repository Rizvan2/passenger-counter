const $ = (id) => document.getElementById(id);

const elPath = $("videoPath");
const elLine = $("lineRatio");
const elLineLabel = $("lineLabel");
const elInsideSide = $("insideSide");
const elStart = $("btnStart");
const elStop = $("btnStop");
const elStatus = $("status");
const elCanvas = $("canvas");
const ctx = elCanvas.getContext("2d");
const elFinal = $("final");
const elFinalText = $("finalText");
const elBoarding = $("statBoarding");
const elAlighting = $("statAlighting");
const elOnboard = $("statOnboard");
const elInitial = $("statInitial");
const elTracks = $("statTracks");
const elFps = $("statFps");
const elFrames = $("statFrames");
const elReid = $("reid-status");
const elEventLog = $("eventLog");
const elDoorWidth = $("doorWidthLabel");
const elEmptyState = $("emptyState");
const elVisibleCount = $("visibleCount");
const elInsideCount = $("insideCount");
const elDoorwayCount = $("doorwayCount");
const elOutsideCount = $("outsideCount");
const elInsideSideLabel = $("insideSideLabel");
const elOutsideSideLabel = $("outsideSideLabel");

let currentSessionId = null;
let currentWs = null;
let currentFrame = null;
let lineRatio = 0.7;
let eventItems = [];

fetch("/api/sessions/info")
  .then((r) => r.json())
  .then((info) => {
    elReid.textContent = info.reidEnabled ? "ReID: включен" : "ReID: IoU";
    elReid.className = info.reidEnabled ? "badge ok" : "badge off";
  })
  .catch(() => {
    elReid.textContent = "сервер недоступен";
    elReid.className = "badge off";
  });

elLine.addEventListener("input", () => {
  lineRatio = elLine.value / 100;
  elLineLabel.textContent = `${elLine.value}%`;
  moveLocalDoorZone(lineRatio);
});

elCanvas.addEventListener("mousedown", (e) => {
  const onMove = (ev) => updateLineFromMouse(ev);
  const onUp = () => {
    document.removeEventListener("mousemove", onMove);
    document.removeEventListener("mouseup", onUp);
  };
  document.addEventListener("mousemove", onMove);
  document.addEventListener("mouseup", onUp);
  updateLineFromMouse(e);
});

elStart.addEventListener("click", startSession);
elStop.addEventListener("click", stopSession);

function updateLineFromMouse(e) {
  if (!currentFrame?.image) return;
  const rect = elCanvas.getBoundingClientRect();
  const ratio = (e.clientY - rect.top) / rect.height;
  const clamped = Math.max(0.05, Math.min(0.95, ratio));
  lineRatio = clamped;
  elLine.value = Math.round(clamped * 100);
  elLineLabel.textContent = `${elLine.value}%`;
  moveLocalDoorZone(clamped);
}

function moveLocalDoorZone(ratio) {
  if (!currentFrame) return;
  const center = currentFrame.height * ratio;
  const half = Math.max(24, Math.min(currentFrame.height * 0.06, 90));
  currentFrame.lineY = center;
  currentFrame.doorTopY = center - half;
  currentFrame.doorBottomY = center + half;
  redraw();
}

async function startSession() {
  const videoPath = elPath.value.trim();
  if (!videoPath) {
    alert("Укажи путь к видеофайлу на сервере");
    return;
  }

  resetMonitor();
  setStatus("запуск", "running");
  elStart.disabled = true;

  try {
    const response = await fetch("/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        videoPath,
        lineYRatio: lineRatio,
        insideOnTop: elInsideSide.value === "top",
        autoInitialOnboard: true,
      }),
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `HTTP ${response.status}`);
    }

    const data = await response.json();
    currentSessionId = data.sessionId;
    openWebSocket(data.wsUrl);
    elStop.disabled = false;
    setStatus("обработка", "running");
  } catch (e) {
    setStatus(`ошибка: ${e.message}`, "error");
    elStart.disabled = false;
  }
}

async function stopSession() {
  if (!currentSessionId) return;
  try {
    await fetch(`/api/sessions/${currentSessionId}/stop`, { method: "POST" });
  } catch (e) {
    console.warn(e);
  }
}

function openWebSocket(path) {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  currentWs = new WebSocket(`${proto}//${location.host}${path}`);
  currentWs.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.type === "FRAME") onFrame(msg);
    if (msg.type === "FINISHED") onFinished(msg);
  };
  currentWs.onerror = () => setStatus("ошибка ws", "error");
  currentWs.onclose = () => {
    elStart.disabled = false;
    elStop.disabled = true;
  };
}

function onFrame(msg) {
  const img = new Image();
  img.onload = () => {
    currentFrame = {
      image: img,
      width: msg.width,
      height: msg.height,
      detections: msg.detections,
      lineY: msg.lineY,
      doorTopY: msg.doorTopY,
      doorBottomY: msg.doorBottomY,
      insideOnTop: msg.insideOnTop,
    };
    elCanvas.width = msg.width;
    elCanvas.height = msg.height;
    elEmptyState.classList.add("hidden");
    redraw();
  };
  img.src = `data:image/jpeg;base64,${msg.frameJpegBase64}`;

  elBoarding.textContent = msg.boardings;
  elAlighting.textContent = msg.alightings;
  elOnboard.textContent = msg.onboard;
  elInitial.textContent = msg.initialOnboard;
  elTracks.textContent = msg.detections.length;
  elFps.textContent = msg.fps.toFixed(1);
  elFrames.textContent = msg.frameIndex;
  const doorTopY = msg.doorTopY ?? 0;
  const doorBottomY = msg.doorBottomY ?? 0;
  const insideOnTop = msg.insideOnTop ?? true;
  elDoorWidth.textContent = `${Math.round(doorBottomY - doorTopY)} px`;
  elInsideSideLabel.textContent = insideOnTop ? "сверху" : "снизу";
  elOutsideSideLabel.textContent = insideOnTop ? "снизу" : "сверху";
  elVisibleCount.textContent = msg.visibleDetections ?? msg.detections.length;
  elInsideCount.textContent = msg.insideDetections ?? 0;
  elDoorwayCount.textContent = msg.doorwayDetections ?? 0;
  elOutsideCount.textContent = msg.outsideDetections ?? 0;

  for (const event of msg.events || []) addEvent(event);
}

function redraw() {
  if (!currentFrame?.image) return;
  const { image, width, height, detections, lineY, doorTopY, doorBottomY, insideOnTop } = currentFrame;
  ctx.clearRect(0, 0, elCanvas.width, elCanvas.height);
  ctx.drawImage(image, 0, 0, width, height);
  drawDoorZone(width, height, doorTopY, doorBottomY, lineY, insideOnTop);
  drawBoxes(detections);
}

function drawDoorZone(width, height, topY, bottomY, centerY, insideOnTop) {
  ctx.save();
  ctx.fillStyle = "rgba(79, 125, 243, 0.16)";
  ctx.fillRect(0, topY, width, bottomY - topY);
  ctx.strokeStyle = "#4f7df3";
  ctx.lineWidth = 2;
  ctx.setLineDash([10, 8]);
  ctx.beginPath();
  ctx.moveTo(0, topY);
  ctx.lineTo(width, topY);
  ctx.moveTo(0, bottomY);
  ctx.lineTo(width, bottomY);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.strokeStyle = "#f6c85f";
  ctx.lineWidth = 3;
  ctx.beginPath();
  ctx.moveTo(0, centerY);
  ctx.lineTo(width, centerY);
  ctx.stroke();
  ctx.fillStyle = "rgba(15, 17, 21, 0.74)";
  ctx.fillRect(12, 12, 290, 34);
  ctx.fillStyle = "#e6e8ec";
  ctx.font = "600 18px system-ui, sans-serif";
  ctx.fillText(insideOnTop ? "САЛОН ↑ | ПРОЕМ | ↓ УЛИЦА" : "УЛИЦА ↑ | ПРОЕМ | ↓ САЛОН", 24, 35);
  ctx.restore();
}

function drawBoxes(detections) {
  ctx.font = "600 15px system-ui, sans-serif";
  for (const d of detections) {
    const color = colorForDetection(d);
    const label = `Пассажир-${d.trackId} ${Math.round(d.confidence * 100)}%${trackStatus(d)}`;
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.strokeRect(d.x1, d.y1, d.x2 - d.x1, d.y2 - d.y1);
    const textWidth = ctx.measureText(label).width + 12;
    const labelY = Math.max(4, d.y1 - 25);
    ctx.fillStyle = color;
    ctx.fillRect(d.x1, labelY, textWidth, 22);
    ctx.fillStyle = "#0f1115";
    ctx.fillText(label, d.x1 + 6, labelY + 16);
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc((d.x1 + d.x2) / 2, d.y2, 4, 0, Math.PI * 2);
    ctx.fill();
  }
}

function colorForDetection(d) {
  if (d.isBoarded) return "#41d69a";
  if (d.isAlighted) return "#ff7a7a";
  if (d.zone === "DOORWAY") return "#f6c85f";
  if (d.zone === "INSIDE") return "#79a7ff";
  return "#d1d7e0";
}

function trackStatus(d) {
  if (d.isBoarded) return " вошел";
  if (d.isAlighted) return " вышел";
  return "";
}

function addEvent(event) {
  eventItems.unshift(event);
  eventItems = eventItems.slice(0, 12);
  elEventLog.innerHTML = "";
  for (const item of eventItems) {
    const row = document.createElement("div");
    const isCancel = item.direction.startsWith("CANCEL_");
    const positiveSide = item.direction === "BOARDING" || item.direction === "CANCEL_ALIGHTING";
    row.className = `event ${positiveSide ? "boarding" : "alighting"}${isCancel ? " cancel" : ""}`;
    row.innerHTML = `<b>${eventLabel(item.direction)}</b><span>#${item.trackId} · кадр ${item.frameIndex}</span>`;
    elEventLog.appendChild(row);
  }
}

function eventLabel(direction) {
  if (direction === "BOARDING") return "Вход";
  if (direction === "ALIGHTING") return "Выход";
  if (direction === "CANCEL_BOARDING") return "Отмена входа";
  if (direction === "CANCEL_ALIGHTING") return "Отмена выхода";
  return direction;
}

function onFinished(msg) {
  setStatus(msg.status === "FINISHED" ? "готово" : msg.status.toLowerCase(), msg.status === "FINISHED" ? "" : "error");
  elFinal.classList.remove("hidden");
  elFinal.classList.toggle("error", msg.status === "FAILED");
  const sec = (msg.durationMs / 1000).toFixed(1);
  elFinalText.innerHTML = msg.status === "FAILED"
    ? `<b>Ошибка:</b> ${msg.errorMessage || "неизвестная"}`
    : `Кадров: <b>${msg.framesProcessed}</b>, время: <b>${sec} с</b>, вошло: <b>${msg.totalBoardings}</b>, вышло: <b>${msg.totalAlightings}</b>, в салоне: <b>${msg.finalOnboard}</b>`;
  elStart.disabled = false;
  elStop.disabled = true;
  currentSessionId = null;
}

function resetMonitor() {
  eventItems = [];
  elEventLog.innerHTML = '<div class="event empty">Пока нет событий</div>';
  elFinal.classList.add("hidden");
  elFinal.classList.remove("error");
  elBoarding.textContent = "0";
  elAlighting.textContent = "0";
  elOnboard.textContent = "0";
  elInitial.textContent = "0";
  elTracks.textContent = "0";
  elFps.textContent = "-";
  elFrames.textContent = "0";
  elVisibleCount.textContent = "0";
  elInsideCount.textContent = "0";
  elDoorwayCount.textContent = "0";
  elOutsideCount.textContent = "0";
}

function setStatus(text, cls = "") {
  elStatus.textContent = text;
  elStatus.className = `status ${cls}`;
}
