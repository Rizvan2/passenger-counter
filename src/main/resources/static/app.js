const $ = (id) => document.getElementById(id);

const elPath = $("videoPath");
const elLine = $("lineRatio");
const elLineLabel = $("lineLabel");
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
const elFps = $("statFps");
const elFrames = $("statFrames");
const elReid = $("reid-status");

let currentSessionId = null;
let currentWs = null;
let currentFrame = { width: 0, height: 0, image: null, detections: [], lineY: 0 };
let lineRatio = 0.7;

// ===== Автоопределение людей в салоне =====
let onboardInitialized = false;
let currentOnboardCount = 0;

// === init ===
fetch("/api/sessions/info").then(r => r.json()).then(info => {
  if (info.reidEnabled) {
    elReid.textContent = "ReID: ✓ включён";
    elReid.className = "badge ok";
  } else {
    elReid.textContent = "ReID: отключён (только IoU-трекинг)";
    elReid.className = "badge off";
  }
}).catch(() => {
  elReid.textContent = "Сервер недоступен";
  elReid.className = "badge off";
});

// Линия слайдером
elLine.addEventListener("input", () => {
  lineRatio = elLine.value / 100;
  elLineLabel.textContent = elLine.value + "%";

  if (currentFrame.width) {
    currentFrame.lineY = currentFrame.width * lineRatio;
    redraw();
  }
});

// Линия мышью
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

function updateLineFromMouse(e) {
  if (!currentFrame.image) return;

  const rect = elCanvas.getBoundingClientRect();
  const xInCanvas = e.clientX - rect.left;

  const ratio = xInCanvas / rect.width;
  const clamped = Math.max(0.05, Math.min(0.95, ratio));

  lineRatio = clamped;

  elLine.value = Math.round(clamped * 100);
  elLineLabel.textContent = elLine.value + "%";

  currentFrame.lineY = currentFrame.width * clamped;

  redraw();
}

elStart.addEventListener("click", startSession);
elStop.addEventListener("click", stopSession);

async function startSession() {
  const videoPath = elPath.value.trim();

  if (!videoPath) {
    alert("Укажи путь к видеофайлу на сервере");
    return;
  }

  // Сброс состояния
  onboardInitialized = false;
  currentOnboardCount = 0;

  elFinal.classList.add("hidden");

  elBoarding.textContent = "0";
  elAlighting.textContent = "0";
  elOnboard.textContent = "0";
  elFrames.textContent = "0";
  elFps.textContent = "—";

  setStatus("Запуск…", "running");
  elStart.disabled = true;

  try {
    const r = await fetch("/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        videoPath,
        lineYRatio: lineRatio,
      })
    });

    if (!r.ok) {
      const text = await r.text();
      throw new Error(text || `HTTP ${r.status}`);
    }

    const data = await r.json();

    currentSessionId = data.sessionId;

    openWebSocket(data.wsUrl);

    elStop.disabled = false;

    setStatus("Обработка…", "running");

  } catch (e) {

    setStatus("Ошибка: " + e.message, "error");
    elStart.disabled = false;
  }
}

async function stopSession() {
  if (!currentSessionId) return;

  try {
    await fetch(`/api/sessions/${currentSessionId}/stop`, {
      method: "POST"
    });
  } catch (e) {
    console.warn(e);
  }
}

function openWebSocket(path) {

  const proto = location.protocol === "https:" ? "wss:" : "ws:";

  const url = `${proto}//${location.host}${path}`;

  currentWs = new WebSocket(url);

  currentWs.onopen = () => console.log("WS open");

  currentWs.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);

    if (msg.type === "FRAME") {
      onFrame(msg);
    } else if (msg.type === "FINISHED") {
      onFinished(msg);
    }
  };

  currentWs.onerror = (e) => console.error("WS error", e);

  currentWs.onclose = () => {
    console.log("WS closed");

    elStart.disabled = false;
    elStop.disabled = true;
  };
}

function onFrame(msg) {

  // JPEG
  const img = new Image();

  img.onload = () => {

    currentFrame.image = img;
    currentFrame.width = msg.width;
    currentFrame.height = msg.height;
    currentFrame.detections = msg.detections;
    currentFrame.lineY = msg.lineY;

    elCanvas.width = msg.width;
    elCanvas.height = msg.height;

    redraw();
  };

  img.src = "data:image/jpeg;base64," + msg.frameJpegBase64;

  // ===== Автоинициализация onboard =====
  if (!onboardInitialized) {

    let insideCount = 0;

    for (const d of msg.detections) {

      const centerX = (d.x1 + d.x2) / 2;

      // Слева от линии = салон
      if (centerX < msg.lineY) {
        insideCount++;
      }
    }

    currentOnboardCount = insideCount;

    onboardInitialized = true;
  }

  // Далее уже прибавляем/убавляем
  const calculatedOnboard =
      currentOnboardCount +
      msg.boardings -
      msg.alightings;

  elBoarding.textContent = msg.boardings;
  elAlighting.textContent = msg.alightings;
  elOnboard.textContent = calculatedOnboard;

  elFps.textContent = msg.fps.toFixed(1);
  elFrames.textContent = msg.frameIndex;
}

function redraw() {

  if (!currentFrame.image) return;

  const {
    image,
    detections,
    lineY,
    width,
    height
  } = currentFrame;

  const lineX = lineY;

  ctx.clearRect(0, 0, elCanvas.width, elCanvas.height);

  ctx.drawImage(image, 0, 0, width, height);

  // Вертикальная линия
  ctx.strokeStyle = "#4f7df3";
  ctx.lineWidth = 3;
  ctx.setLineDash([12, 8]);

  ctx.beginPath();
  ctx.moveTo(lineX, 0);
  ctx.lineTo(lineX, height);
  ctx.stroke();

  ctx.setLineDash([]);

  // Подписи
  ctx.fillStyle = "#4f7df3";
  ctx.font = "bold 22px sans-serif";

  ctx.fillText("← САЛОН", Math.max(10, lineX - 140), 30);
  ctx.fillText("УЛИЦА →", Math.min(width - 130, lineX + 12), 30);

  // Боксы
  ctx.font = "bold 16px sans-serif";

  for (const d of detections) {

    let color = "#fcd34d";

    if (d.isBoarded) color = "#6ee7b7";
    else if (d.isAlighted) color = "#fca5a5";

    ctx.strokeStyle = color;
    ctx.lineWidth = 2;

    ctx.strokeRect(
        d.x1,
        d.y1,
        d.x2 - d.x1,
        d.y2 - d.y1
    );

    const label =
        `#${d.trackId} ${Math.round(d.confidence * 100)}%` +
        `${d.isBoarded ? " ВОШЁЛ" : ""}` +
        `${d.isAlighted ? " ВЫШЕЛ" : ""}`;

    const tw = ctx.measureText(label).width + 12;

    ctx.fillStyle = color;
    ctx.fillRect(d.x1, d.y1 - 24, tw, 22);

    ctx.fillStyle = "#000";
    ctx.fillText(label, d.x1 + 6, d.y1 - 8);
  }
}

function onFinished(msg) {

  setStatus(
      msg.status === "FINISHED"
          ? "Готово"
          : msg.status,

      msg.status === "FINISHED"
          ? ""
          : "error"
  );

  elFinal.classList.remove("hidden");

  if (msg.status === "FAILED") {
    elFinal.classList.add("error");
  } else {
    elFinal.classList.remove("error");
  }

  const sec = (msg.durationMs / 1000).toFixed(1);

  elFinalText.innerHTML =
      msg.status === "FAILED"
          ? `<b>Ошибка:</b> ${msg.errorMessage || "неизвестная"}`
          : `Обработано <b>${msg.framesProcessed}</b> кадров за <b>${sec} сек</b>.
       Вошло: <b>${msg.totalBoardings}</b>,
       вышло: <b>${msg.totalAlightings}</b>,
       финальная загрузка: <b>${elOnboard.textContent}</b>`;

  elStart.disabled = false;
  elStop.disabled = true;

  currentSessionId = null;
}

function setStatus(text, cls = "") {
  elStatus.textContent = text;
  elStatus.className = "status " + cls;
}