"use strict";

const $ = (id) => document.getElementById(id);

const tabSetup = $("tabSetup");
const tabMonitor = $("tabMonitor");
const viewSetup = $("viewSetup");
const viewMonitor = $("viewMonitor");

function showTab(tab) {
  const isSetup = tab === "setup";
  tabSetup.classList.toggle("active", isSetup);
  tabMonitor.classList.toggle("active", !isSetup);
  viewSetup.classList.toggle("hidden", !isSetup);
  viewMonitor.classList.toggle("hidden", isSetup);
}

tabSetup.addEventListener("click", () => showTab("setup"));
tabMonitor.addEventListener("click", () => showTab("monitor"));
$("btnBackToSetup").addEventListener("click", () => showTab("setup"));

const elSearch = $("searchInput");
const elRefresh = $("refreshBtn");
const elList = $("videoList");
const elPath = $("videoPath");

let allVideos = [];
let selectedPath = "";
let selectedVideo = null;

loadVideos();
elSearch.addEventListener("input", () => renderList(elSearch.value.trim().toLowerCase()));
elRefresh.addEventListener("click", loadVideos);
elPath.addEventListener("input", () => {
  selectedPath = elPath.value.trim();
  selectedVideo = allVideos.find((v) => v.path === selectedPath) || null;
  onSelChange();
});

async function loadVideos() {
  elList.innerHTML = '<div class="vlist-empty">Загрузка...</div>';
  try {
    const r = await fetch("/api/videos");
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    allVideos = await r.json();
    selectedVideo = allVideos.find((v) => v.path === selectedPath) || null;
    renderList(elSearch.value.trim().toLowerCase());
  } catch (e) {
    elList.innerHTML = `<div class="vlist-empty">Ошибка: ${e.message}</div>`;
  }
}

function renderList(filter) {
  const items = filter
    ? allVideos.filter((v) => [v.name, v.folder, v.relativePath, v.videoDeviceId, v.cameraCode]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(filter)))
    : allVideos;
  if (!items.length) {
    elList.innerHTML = '<div class="vlist-empty">Файлы не найдены</div>';
    return;
  }
  elList.innerHTML = "";
  for (const v of items) {
    const el = document.createElement("div");
    el.className = "vitem" + (v.path === selectedPath ? " sel" : "") + (!v.previewAvailable ? " unavailable" : "");
    el.dataset.path = v.path;
    el.innerHTML = `<span class="vitem-icon">🎥</span>
      <div style="min-width:0;flex:1">
        <div class="vitem-head">
          <div class="vitem-name" title="${esc(v.path)}">${esc(v.name)}</div>
          <span class="vitem-folder" title="${esc(v.relativePath || v.path)}">${esc(v.folder || "Видео")}</span>
        </div>
        <div class="vitem-meta">${esc(videoMetaLabel(v))}</div>
      </div>`;
    el.addEventListener("click", () => selectVideo(v));
    elList.appendChild(el);
  }
}

function selectVideo(v) {
  selectedPath = v.path;
  selectedVideo = v;
  elPath.value = v.path;
  fillCameraBindingFromVideo(v);
  document.querySelectorAll(".vitem").forEach((el) => el.classList.toggle("sel", el.dataset.path === v.path));
  onSelChange();
  if (v.previewAvailable) {
    loadPreview(v.path);
  } else {
    showPreviewUnavailable(v.issue || "Превью для этого файла недоступно");
  }
}

function videoMetaLabel(v) {
  const parts = [fmtSize(v.sizeBytes), v.modified];
  if (v.videoDeviceId || v.cameraCode) {
    parts.push(`${v.videoDeviceId || "-"} / ${v.cameraCode || "-"}`);
  }
  if (v.issue) parts.push(v.issue);
  return parts.filter(Boolean).join(" · ");
}

function fillCameraBindingFromVideo(v) {
  if (elBindRecorderId && v.videoDeviceId) elBindRecorderId.value = v.videoDeviceId;
  if (elBindCameraCode && v.cameraCode) elBindCameraCode.value = v.cameraCode;
  if (v.videoDeviceId && v.cameraCode) {
    resolveCurrentBinding();
  }
}

function onSelChange() {
  const analysisBlocked = selectedVideo && !selectedVideo.analysisAvailable;
  $("btnStart").disabled = !selectedPath || !!currentSessionId || analysisBlocked;
  $("btnStart").title = analysisBlocked
    ? (selectedVideo.issue || "Этот служебный файл нельзя запускать повторно")
    : "";
  updateSummary();
}

const elAutoInit = $("autoInitial");
const elManField = $("manualInitialField");
const elInitOnb = $("initialOnboard");
const elSmartStop = $("smartStopEnabled");
const elEditTarget = $("editTarget");
const previewWrap = $("previewWrap");
const previewCv = $("previewCanvas");
const previewCtx = previewCv.getContext("2d");

let lineSegment = { ax: 0.28, ay: 0.36, bx: 0.68, by: 0.68 };
let insideOnPositiveSide = true;
let zonePolygons = fallbackPolygonsFromLine();
let selectedVertex = { zone: "salon", index: 0 };
let dragState = null;
let frameImg = null;
let doorProfiles = [];
let currentProfileLabel = "application-default";

const elProfileSelect = $("profileSelect");
const elProfileName = $("profileName");
const elProfileDoorType = $("profileDoorType");
const elBindRecorderId = $("bindRecorderId");
const elBindCameraCode = $("bindCameraCode");
const elBindDoor = $("bindDoor");
const elBindProfileSelect = $("bindProfileSelect");
const elBindingList = $("bindingList");

if (![...elEditTarget.options].some((option) => option.value === "door")) {
  const doorOption = new Option("DOOR polygon", "door");
  elEditTarget.appendChild(doorOption);
}
if (![...elEditTarget.options].some((option) => option.value === "salonSpawn")) {
  const spawnOption = new Option("SALON SPAWN polygon", "salonSpawn");
  elEditTarget.appendChild(spawnOption);
}
if ($("doorWidthLabel")?.previousElementSibling) $("doorWidthLabel").previousElementSibling.textContent = "DOOR";
if ($("doorwayCount")?.previousElementSibling) $("doorwayCount").previousElementSibling.textContent = "В DOOR";

function applyDefaultSetup(info) {
  if (typeof info.defaultLineAx === "number") lineSegment.ax = info.defaultLineAx;
  if (typeof info.defaultLineAy === "number") lineSegment.ay = info.defaultLineAy;
  if (typeof info.defaultLineBx === "number") lineSegment.bx = info.defaultLineBx;
  if (typeof info.defaultLineBy === "number") lineSegment.by = info.defaultLineBy;
  if (typeof info.defaultInsideOnPositiveSide === "boolean") {
    insideOnPositiveSide = info.defaultInsideOnPositiveSide;
  }
  const salon = Array.isArray(info.defaultSalonPolygon) ? info.defaultSalonPolygon.map(clampPoint) : [];
  const street = Array.isArray(info.defaultStreetPolygon) ? info.defaultStreetPolygon.map(clampPoint) : [];
  const door = Array.isArray(info.defaultDoorPolygon) ? info.defaultDoorPolygon.map(clampPoint) : [];
  const salonSpawn = Array.isArray(info.defaultSalonSpawnPolygon) ? info.defaultSalonSpawnPolygon.map(clampPoint) : [];
  if (salon.length >= 3 && street.length >= 3 && door.length >= 3) {
    zonePolygons = { salon, street, door, salonSpawn: salonSpawn.length >= 3 ? salonSpawn : defaultSalonSpawnPolygon(salon) };
  } else {
    zonePolygons = fallbackPolygonsFromLine();
  }
  updateSummary();
  redrawPreview();
}

function clampPoint(p) {
  return {
    x: +Math.max(0, Math.min(1, p.x)).toFixed(3),
    y: +Math.max(0, Math.min(1, p.y)).toFixed(3),
  };
}

elAutoInit.addEventListener("change", () => {
  elManField.style.display = elAutoInit.checked ? "none" : "";
  updateSummary();
});
elInitOnb.addEventListener("input", updateSummary);
elSmartStop.addEventListener("change", updateSummary);
$("addPoint").addEventListener("click", addPointToActivePolygon);
$("deletePoint").addEventListener("click", deleteSelectedPoint);
$("reloadProfiles")?.addEventListener("click", () => loadDoorProfiles());
$("saveProfile")?.addEventListener("click", () => saveCurrentProfile(false));
$("updateProfile")?.addEventListener("click", () => saveCurrentProfile(true));
$("bindProfile")?.addEventListener("click", bindSelectedProfile);
$("resolveBinding")?.addEventListener("click", resolveCurrentBinding);
elProfileSelect?.addEventListener("change", () => {
  const profile = selectedDoorProfile();
  if (!profile) return;
  elProfileName.value = profile.name;
  elProfileDoorType.value = profile.doorType || "CUSTOM";
  applyDoorProfile(profile);
  setProfileStatus(`Применен профиль ${profile.name}`, "ok");
});

previewWrap.addEventListener("mousedown", (e) => {
  beginDrag(e.clientX, e.clientY);
  e.preventDefault();
});
window.addEventListener("mousemove", (e) => {
  if (dragState) dragActive(e.clientX, e.clientY);
});
window.addEventListener("mouseup", () => { dragState = null; });
previewWrap.addEventListener("touchstart", (e) => {
  beginDrag(e.touches[0].clientX, e.touches[0].clientY);
  e.preventDefault();
}, { passive: false });
window.addEventListener("touchmove", (e) => {
  if (dragState) dragActive(e.touches[0].clientX, e.touches[0].clientY);
}, { passive: false });
window.addEventListener("touchend", () => { dragState = null; });

function beginDrag(clientX, clientY) {
  const target = elEditTarget.value;
  const nearest = nearestPolygonVertex(target, clientX, clientY);
  if (!nearest) return;
  selectedVertex = { zone: target, index: nearest.index };
  dragState = { type: "polygon", zone: target, index: nearest.index };
  dragActive(clientX, clientY);
}

function dragActive(clientX, clientY) {
  const r = previewWrap.getBoundingClientRect();
  const x = +Math.max(0.02, Math.min(0.98, (clientX - r.left) / r.width)).toFixed(3);
  const y = +Math.max(0.02, Math.min(0.98, (clientY - r.top) / r.height)).toFixed(3);
  zonePolygons[dragState.zone][dragState.index] = { x, y };
  updateSummary();
  redrawPreview();
}

function nearestPolygonVertex(zone, clientX, clientY) {
  const r = previewWrap.getBoundingClientRect();
  let best = null;
  zonePolygons[zone].forEach((p, index) => {
    const px = r.left + p.x * r.width;
    const py = r.top + p.y * r.height;
    const distance = Math.hypot(clientX - px, clientY - py);
    if (!best || distance < best.distance) best = { index, distance };
  });
  return best && best.distance <= 28 ? best : null;
}

function addPointToActivePolygon() {
  const zone = elEditTarget.value;
  const polygon = zonePolygons[zone];
  const last = polygon[polygon.length - 1];
  const first = polygon[0];
  const point = {
    x: +(((last.x + first.x) / 2).toFixed(3)),
    y: +(((last.y + first.y) / 2).toFixed(3)),
  };
  polygon.push(point);
  selectedVertex = { zone, index: polygon.length - 1 };
  updateSummary();
  redrawPreview();
}

function deleteSelectedPoint() {
  const zone = elEditTarget.value;
  const polygon = zonePolygons[zone];
  if (polygon.length <= 3) return;
  const index = selectedVertex.zone === zone ? selectedVertex.index : polygon.length - 1;
  polygon.splice(index, 1);
  selectedVertex = { zone, index: Math.max(0, index - 1) };
  updateSummary();
  redrawPreview();
}

function showPreviewUnavailable(message) {
  frameImg = null;
  previewCtx.clearRect(0, 0, previewCv.width, previewCv.height);
  $("previewSpin").classList.add("hidden");
  $("previewPh").textContent = message;
  $("previewPh").classList.remove("hidden");
}

async function loadPreview(path) {
  $("previewPh").textContent = "Превью недоступно";
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
      previewCv.width = data.width;
      previewCv.height = data.height;
      $("previewSpin").classList.add("hidden");
      redrawPreview();
    };
    img.onerror = () => {
      $("previewSpin").classList.add("hidden");
      $("previewPh").classList.remove("hidden");
    };
    img.src = `data:image/jpeg;base64,${data.frameJpegBase64}`;
  } catch (e) {
    showPreviewUnavailable(`Превью недоступно: ${e.message}`);
  }
}

function redrawPreview() {
  if (!frameImg) return;
  const { img, width, height } = frameImg;
  previewCv.width = width;
  previewCv.height = height;
  previewCtx.drawImage(img, 0, 0, width, height);
  drawZones(previewCtx, zonePolygons.salon, zonePolygons.street, zonePolygons.door, zonePolygons.salonSpawn, width, height, true);
}

function fallbackPolygonsFromLine() {
  const frame = [
    { x: 0, y: 0 },
    { x: 1, y: 0 },
    { x: 1, y: 1 },
    { x: 0, y: 1 },
  ];
  return {
    salon: clipHalfPlane(frame, lineSegment, insideOnPositiveSide),
    street: clipHalfPlane(frame, lineSegment, !insideOnPositiveSide),
    door: doorPortalPolygon(lineSegment),
    salonSpawn: defaultSalonSpawnPolygon(clipHalfPlane(frame, lineSegment, insideOnPositiveSide)),
  };
}

function defaultSalonSpawnPolygon(salon) {
  if (!salon?.length) {
    return [
      { x: 0.90, y: 0.03 },
      { x: 0.98, y: 0.03 },
      { x: 0.98, y: 0.98 },
      { x: 0.90, y: 0.98 },
    ];
  }
  const minX = Math.min(...salon.map((p) => p.x));
  const maxX = Math.max(...salon.map((p) => p.x));
  const minY = Math.min(...salon.map((p) => p.y));
  const maxY = Math.max(...salon.map((p) => p.y));
  const width = maxX - minX;
  const height = maxY - minY;
  return [
    clampPoint({ x: maxX - width * 0.10, y: minY + height * 0.02 }),
    clampPoint({ x: maxX - width * 0.02, y: minY + height * 0.02 }),
    clampPoint({ x: maxX - width * 0.02, y: maxY - height * 0.02 }),
    clampPoint({ x: maxX - width * 0.10, y: maxY - height * 0.02 }),
  ];
}

function doorPortalPolygon(line) {
  const midX = (line.ax + line.bx) / 2;
  const midY = (line.ay + line.by) / 2;
  const length = Math.hypot(line.bx - line.ax, line.by - line.ay);
  const halfWidth = Math.max(0.045, Math.min(0.16, length * 0.18));
  const halfHeight = Math.max(0.08, Math.min(0.22, 0.11 + length * 0.12));
  return [
    clampPoint({ x: midX - halfWidth, y: midY - halfHeight }),
    clampPoint({ x: midX + halfWidth, y: midY - halfHeight }),
    clampPoint({ x: midX + halfWidth, y: midY + halfHeight }),
    clampPoint({ x: midX - halfWidth, y: midY + halfHeight }),
  ];
}

function clipHalfPlane(polygon, line, keepPositive) {
  const result = [];
  let prev = polygon[polygon.length - 1];
  let prevInside = isInsideHalfPlane(prev, line, keepPositive);
  for (const current of polygon) {
    const currentInside = isInsideHalfPlane(current, line, keepPositive);
    if (currentInside !== prevInside) {
      result.push(intersection(prev, current, line));
    }
    if (currentInside) result.push({ x: current.x, y: current.y });
    prev = current;
    prevInside = currentInside;
  }
  return result;
}

function isInsideHalfPlane(point, line, keepPositive) {
  const cross = signedCross(line.ax, line.ay, line.bx, line.by, point.x, point.y);
  return keepPositive ? cross >= -1e-5 : cross <= 1e-5;
}

function intersection(start, end, line) {
  const startCross = signedCross(line.ax, line.ay, line.bx, line.by, start.x, start.y);
  const endCross = signedCross(line.ax, line.ay, line.bx, line.by, end.x, end.y);
  const denom = startCross - endCross;
  const t = Math.abs(denom) < 1e-6 ? 0 : startCross / denom;
  return {
    x: +(start.x + (end.x - start.x) * t).toFixed(3),
    y: +(start.y + (end.y - start.y) * t).toFixed(3),
  };
}

function signedCross(ax, ay, bx, by, px, py) {
  return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
}

function drawZones(canvasCtx, salon, street, door, salonSpawn, width, height, showHandles) {
  drawPolygon(canvasCtx, salon, width, height, "rgba(79,125,243,.18)", "#79a7ff", "SALON", showHandles ? "salon" : null);
  drawPolygon(canvasCtx, street, width, height, "rgba(190,197,207,.14)", "#d1d7e0", "STREET", showHandles ? "street" : null);
  drawPolygon(canvasCtx, door, width, height, "rgba(246,200,95,.12)", "#f6c85f", "DOOR", showHandles ? "door" : null);
  drawPolygon(canvasCtx, salonSpawn, width, height, "rgba(65,214,154,.12)", "#41d69a", "SPAWN", showHandles ? "salonSpawn" : null);
}

function drawPolygon(canvasCtx, polygon, width, height, fill, stroke, label, handleZone) {
  if (!polygon?.length) return;
  canvasCtx.save();
  canvasCtx.fillStyle = fill;
  canvasCtx.strokeStyle = stroke;
  canvasCtx.lineWidth = 2;
  canvasCtx.beginPath();
  polygon.forEach((p, i) => {
    const x = p.x * width;
    const y = p.y * height;
    if (i) canvasCtx.lineTo(x, y);
    else canvasCtx.moveTo(x, y);
  });
  canvasCtx.closePath();
  canvasCtx.fill();
  canvasCtx.stroke();

  const cx = polygon.reduce((sum, p) => sum + p.x, 0) / polygon.length * width;
  const cy = polygon.reduce((sum, p) => sum + p.y, 0) / polygon.length * height;
  canvasCtx.fillStyle = "rgba(15,17,21,.74)";
  canvasCtx.fillRect(cx - 34, cy - 11, 68, 20);
  canvasCtx.fillStyle = stroke;
  canvasCtx.font = "bold 12px system-ui,sans-serif";
  canvasCtx.fillText(label, cx - 24, cy + 4);

  if (handleZone) {
    polygon.forEach((p, index) => drawHandle(canvasCtx, p.x * width, p.y * height, handleZone === selectedVertex.zone && index === selectedVertex.index));
  }
  canvasCtx.restore();
}

function drawHandle(canvasCtx, x, y, active) {
  canvasCtx.fillStyle = "#050608";
  canvasCtx.strokeStyle = active ? "#f6c85f" : "#c4cad6";
  canvasCtx.lineWidth = 2;
  canvasCtx.beginPath();
  canvasCtx.arc(x, y, 7, 0, Math.PI * 2);
  canvasCtx.fill();
  canvasCtx.stroke();
}

function updateSummary() {
  const name = selectedPath ? selectedPath.split(/[\\/]/).pop() : "не выбран";
  $("sumFile").textContent = name;
  $("sumLine").textContent = `SALON ${zonePolygons.salon.length} / STREET ${zonePolygons.street.length} / DOOR ${zonePolygons.door.length} / SPAWN ${zonePolygons.salonSpawn.length}`;
  $("sumProfile").textContent = currentProfileLabel;
  $("sumInitial").textContent = elAutoInit.checked ? "авто" : `${parseInt(elInitOnb.value, 10) || 0} чел.`;
}

async function apiJson(url, options = {}) {
  const r = await fetch(url, options);
  if (!r.ok) throw new Error(await r.text() || `HTTP ${r.status}`);
  return r.status === 204 ? null : r.json();
}

function setProfileStatus(text, kind = "") {
  const el = $("profileStatus");
  if (!el) return;
  el.textContent = text;
  el.className = `config-note ${kind}`;
}

function setBindingStatus(text, kind = "") {
  const el = $("bindingStatus");
  if (!el) return;
  el.textContent = text;
  el.className = `config-note ${kind}`;
}

async function loadDoorProfiles(selectedId = null) {
  if (!elProfileSelect || !elBindProfileSelect) return;
  try {
    doorProfiles = await apiJson("/api/door-config/profiles");
    renderProfileSelects(selectedId);
    await loadCameraBindings();
    setProfileStatus(doorProfiles.length ? `Загружено профилей: ${doorProfiles.length}` : "Профилей пока нет");
  } catch (e) {
    setProfileStatus(`Ошибка загрузки профилей: ${e.message}`, "err");
  }
}

function renderProfileSelects(selectedId = null) {
  const previous = selectedId || elProfileSelect.value || elBindProfileSelect.value;
  elProfileSelect.innerHTML = "";
  elBindProfileSelect.innerHTML = "";
  const empty = new Option("Нет сохраненных профилей", "");
  empty.disabled = doorProfiles.length > 0;
  elProfileSelect.appendChild(empty);
  elBindProfileSelect.appendChild(new Option("Без профиля / fallback", ""));
  for (const profile of doorProfiles) {
    const label = `${profile.name} (${profile.doorType || "CUSTOM"})`;
    elProfileSelect.appendChild(new Option(label, profile.id));
    elBindProfileSelect.appendChild(new Option(label, profile.id));
  }
  if (previous && doorProfiles.some((p) => p.id === previous)) {
    elProfileSelect.value = previous;
    elBindProfileSelect.value = previous;
  }
  const profile = selectedDoorProfile();
  if (profile) {
    elProfileName.value = profile.name;
    elProfileDoorType.value = profile.doorType || "CUSTOM";
  }
}

function selectedDoorProfile() {
  const id = elProfileSelect?.value;
  return doorProfiles.find((p) => p.id === id) || null;
}

function currentProfilePayload() {
  const name = (elProfileName?.value || "").trim();
  if (!name) throw new Error("Введите имя профиля");
  return {
    name,
    doorType: elProfileDoorType?.value || "CUSTOM",
    lineYRatio: +(((lineSegment.ay + lineSegment.by) / 2).toFixed(3)),
    lineAxRatio: lineSegment.ax,
    lineAyRatio: lineSegment.ay,
    lineBxRatio: lineSegment.bx,
    lineByRatio: lineSegment.by,
    salonPolygon: zonePolygons.salon.map(clampPoint),
    streetPolygon: zonePolygons.street.map(clampPoint),
    doorPolygon: zonePolygons.door.map(clampPoint),
    salonSpawnPolygon: zonePolygons.salonSpawn.map(clampPoint),
    insideOnTop: !insideOnPositiveSide,
    insideOnPositiveSide,
    autoInitialOnboard: elAutoInit.checked,
    initialOnboard: elAutoInit.checked ? 0 : (parseInt(elInitOnb.value, 10) || 0),
  };
}

async function saveCurrentProfile(updateExisting) {
  try {
    const body = currentProfilePayload();
    const selectedId = elProfileSelect?.value;
    const url = updateExisting && selectedId
      ? `/api/door-config/profiles/${encodeURIComponent(selectedId)}`
      : "/api/door-config/profiles";
    const method = updateExisting && selectedId ? "PUT" : "POST";
    const saved = await apiJson(url, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    currentProfileLabel = saved.name;
    await loadDoorProfiles(saved.id);
    updateSummary();
    setProfileStatus(updateExisting && selectedId ? "Профиль перезаписан" : "Профиль сохранен", "ok");
  } catch (e) {
    setProfileStatus(e.message, "err");
  }
}

function applyDoorProfile(profile) {
  if (typeof profile.lineAxRatio === "number") lineSegment.ax = profile.lineAxRatio;
  if (typeof profile.lineAyRatio === "number") lineSegment.ay = profile.lineAyRatio;
  if (typeof profile.lineBxRatio === "number") lineSegment.bx = profile.lineBxRatio;
  if (typeof profile.lineByRatio === "number") lineSegment.by = profile.lineByRatio;
  if (typeof profile.lineYRatio === "number") {
    if (typeof profile.lineAyRatio !== "number") lineSegment.ay = profile.lineYRatio;
    if (typeof profile.lineByRatio !== "number") lineSegment.by = profile.lineYRatio;
  }
  if (typeof profile.insideOnPositiveSide === "boolean") {
    insideOnPositiveSide = profile.insideOnPositiveSide;
  } else if (typeof profile.insideOnTop === "boolean") {
    insideOnPositiveSide = !profile.insideOnTop;
  }

  const salon = sanitizeProfilePolygon(profile.salonPolygon);
  const street = sanitizeProfilePolygon(profile.streetPolygon);
  const door = sanitizeProfilePolygon(profile.doorPolygon);
  const salonSpawn = sanitizeProfilePolygon(profile.salonSpawnPolygon);
  if (salon.length >= 3 && street.length >= 3) {
    zonePolygons = {
      salon,
      street,
      door: door.length >= 3 ? door : doorPortalPolygon(lineSegment),
      salonSpawn: salonSpawn.length >= 3 ? salonSpawn : defaultSalonSpawnPolygon(salon),
    };
  }
  elAutoInit.checked = profile.autoInitialOnboard !== false;
  elManField.style.display = elAutoInit.checked ? "none" : "";
  elInitOnb.value = profile.initialOnboard ?? 0;
  currentProfileLabel = profile.name;
  updateSummary();
  redrawPreview();
}

function sanitizeProfilePolygon(points) {
  return Array.isArray(points) ? points.map(clampPoint) : [];
}

async function loadCameraBindings() {
  if (!elBindingList) return;
  try {
    const bindings = await apiJson("/api/door-config/bindings");
    renderCameraBindings(bindings);
  } catch (e) {
    elBindingList.innerHTML = `<div class="vlist-empty">${esc(e.message)}</div>`;
  }
}

function renderCameraBindings(bindings) {
  const active = bindings.filter((b) => b.active).slice(0, 12);
  if (!active.length) {
    elBindingList.innerHTML = '<div class="vlist-empty">Привязок пока нет</div>';
    return;
  }
  elBindingList.innerHTML = "";
  for (const b of active) {
    const row = document.createElement("div");
    row.className = "binding-row";
    const waiting = b.logicalDoor === "NEEDS_CLASSIFICATION";
    const profileLabel = waiting ? "ожидает ручного выбора" : (b.profileName || "fallback");
    row.innerHTML = `<div><b>${esc(b.recorderId)} / ${esc(b.cameraCode)}</b><span>${esc(profileLabel)} · ${esc(b.logicalDoor)}</span></div><em>${waiting ? "WAIT" : esc(b.logicalDoor)}</em>`;
    row.addEventListener("click", () => {
      elBindRecorderId.value = b.recorderId;
      elBindCameraCode.value = b.cameraCode;
      elBindDoor.value = b.logicalDoor === "NEEDS_CLASSIFICATION" ? "FRONT" : b.logicalDoor;
      elBindProfileSelect.value = b.profileId || "";
      setBindingStatus(waiting ? "Канал ожидает классификации: выбери FRONT/REAR/IGNORE и назначь профиль" : "", waiting ? "" : "ok");
    });
    elBindingList.appendChild(row);
  }
}

async function bindSelectedProfile() {
  try {
    const recorderId = (elBindRecorderId?.value || "").trim();
    const cameraCode = (elBindCameraCode?.value || "").trim();
    if (!recorderId || !cameraCode) throw new Error("Введите регистратор и код камеры");
    const logicalDoor = elBindDoor?.value || "CUSTOM";
    const profileId = (logicalDoor === "IGNORE" || logicalDoor === "NEEDS_CLASSIFICATION") ? null : (elBindProfileSelect?.value || null);
    const saved = await apiJson("/api/door-config/bindings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        recorderId,
        cameraCode,
        logicalDoor,
        profileId,
        confirmedBy: "ui",
        previewSourcePath: selectedPath || null,
      }),
    });
    await loadCameraBindings();
    setBindingStatus(`Назначено: ${saved.recorderId}/${saved.cameraCode} -> ${saved.profileName || saved.logicalDoor}`, "ok");
  } catch (e) {
    setBindingStatus(e.message, "err");
  }
}

async function resolveCurrentBinding() {
  try {
    const recorderId = (elBindRecorderId?.value || "").trim();
    const cameraCode = (elBindCameraCode?.value || "").trim();
    if (!recorderId || !cameraCode) throw new Error("Введите регистратор и код камеры");
    const resolved = await apiJson(`/api/door-config/resolve?recorderId=${encodeURIComponent(recorderId)}&cameraCode=${encodeURIComponent(cameraCode)}`);
    const label = resolved.classificationNeeded ? "NEEDS_CLASSIFICATION" : (resolved.ignored ? "IGNORE" : resolved.profileName);
    setBindingStatus(`Будет применен: ${label} (${resolved.source})`, resolved.classificationNeeded ? "" : (resolved.ignored ? "" : "ok"));
  } catch (e) {
    setBindingStatus(e.message, "err");
  }
}

$("btnStart").addEventListener("click", startSession);
$("btnStop").addEventListener("click", stopSession);

async function startSession() {
  if (!selectedPath) return;
  if (selectedVideo && !selectedVideo.analysisAvailable) {
    $("launchErr").textContent = selectedVideo.issue || "Этот файл нельзя запустить на анализ";
    $("launchErr").classList.remove("hidden");
    return;
  }
  $("btnStart").disabled = true;
  $("btnStop").style.display = "block";
  $("btnStop").disabled = false;
  $("launchErr").classList.add("hidden");
  resetMonitor();
  setStatus("запуск", "running");

  try {
    const body = {
      videoPath: selectedPath,
      lineAx: lineSegment.ax,
      lineAy: lineSegment.ay,
      lineBx: lineSegment.bx,
      lineBy: lineSegment.by,
      insideOnPositiveSide,
      salonPolygon: zonePolygons.salon,
      streetPolygon: zonePolygons.street,
      doorPolygon: zonePolygons.door,
      salonSpawnPolygon: zonePolygons.salonSpawn,
      autoInitialOnboard: elAutoInit.checked,
      initialOnboard: elAutoInit.checked ? 0 : (parseInt(elInitOnb.value, 10) || 0),
      smartStopEnabled: elSmartStop.checked,
    };
    const r = await fetch("/api/sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(await r.text() || `HTTP ${r.status}`);
    const data = await r.json();
    currentSessionId = data.sessionId;
    openWebSocket(data.wsUrl);
    setStatus("обработка", "running");
    showTab("monitor");
  } catch (e) {
    $("launchErr").textContent = e.message;
    $("launchErr").classList.remove("hidden");
    onSelChange();
    $("btnStop").style.display = "none";
    setStatus("ошибка", "error");
  }
}

async function stopSession() {
  if (!currentSessionId) return;
  try {
    await fetch(`/api/sessions/${currentSessionId}/stop`, { method: "POST" });
  } catch (e) {}
}

const elCanvas = $("canvas");
const ctx = elCanvas.getContext("2d");
const elFinal = $("final");
const elFinalText = $("finalText");
const elStatus = $("status");
const elReid = $("reid-status");
const elEventLog = $("eventLog");
const elTimeline = $("timelineSlider");
const elTimelineLabel = $("timelineLabel");
const elTimelineTime = $("timelineTime");
const elBtnFollowLive = $("btnFollowLive");
const elBtnStepBack = $("btnStepBack");
const elBtnStepForward = $("btnStepForward");

let currentSessionId = null;
let currentWs = null;
let currentFrame = null;
let eventItems = [];
const frameBuffer = new Map();
let frameTimeline = [];
let timelinePos = 0;
let followLive = true;
let displayToken = 0;
let processedFrames = 0;
let sourceFramesPerSecond = 25;

elTimeline.addEventListener("input", () => {
  followLive = false;
  syncFollowLiveButton();
  showTimelinePosition(parseInt(elTimeline.value, 10) || 0);
});

elTimeline.addEventListener("change", () => {
  showTimelinePosition(parseInt(elTimeline.value, 10) || 0);
});

elBtnFollowLive.addEventListener("click", () => {
  if (!frameTimeline.length) return;
  followLive = true;
  syncFollowLiveButton();
  showTimelinePosition(frameTimeline.length - 1);
});

elBtnStepBack.addEventListener("click", () => {
  if (!frameTimeline.length) return;
  followLive = false;
  syncFollowLiveButton();
  showTimelinePosition(Math.max(0, timelinePos - 1));
});

elBtnStepForward.addEventListener("click", () => {
  if (!frameTimeline.length) return;
  followLive = false;
  syncFollowLiveButton();
  showTimelinePosition(Math.min(frameTimeline.length - 1, timelinePos + 1));
});

function syncFollowLiveButton() {
  elBtnFollowLive.classList.toggle("active", followLive);
}

function frameIndexAt(pos) {
  return frameTimeline[pos] ?? 0;
}

function storeFrame(msg) {
  if (!frameBuffer.has(msg.frameIndex)) {
    frameTimeline.push(msg.frameIndex);
    frameTimeline.sort((a, b) => a - b);
  }
  frameBuffer.set(msg.frameIndex, msg);
  processedFrames = Math.max(processedFrames, msg.frameIndex + 1);
  if (Number.isFinite(msg.sourceFps) && msg.sourceFps > 0) {
    sourceFramesPerSecond = msg.sourceFps;
  }
  updateTimelineUi();
}

function updateTimelineUi() {
  const maxPos = Math.max(0, frameTimeline.length - 1);
  elTimeline.disabled = frameTimeline.length === 0;
  elTimeline.max = String(maxPos);
  elBtnStepBack.disabled = frameTimeline.length === 0;
  elBtnStepForward.disabled = frameTimeline.length === 0;
  elBtnFollowLive.disabled = frameTimeline.length === 0;

  if (followLive) {
    timelinePos = maxPos;
    elTimeline.value = String(timelinePos);
  }
  updateTimelineMeta();
}

function updateTimelineMeta() {
  if (!frameTimeline.length) {
    elTimelineLabel.textContent = "кадр -";
    elTimelineTime.textContent = "00:00 / --:--";
    return;
  }

  const frameIdx = frameIndexAt(timelinePos);
  const totalEstimate = processedFrames || frameTimeline[frameTimeline.length - 1] + 1;
  elTimelineLabel.textContent = `кадр ${frameIdx} · ${timelinePos + 1}/${frameTimeline.length}`;
  elTimelineTime.textContent = `${formatClock(frameIdx)} / ${formatClock(totalEstimate)}`;
}

function formatClock(frameIndex) {
  const fps = sourceFramesPerSecond || 25;
  const totalSec = Math.max(0, Math.floor(frameIndex / fps));
  const mm = String(Math.floor(totalSec / 60)).padStart(2, "0");
  const ss = String(totalSec % 60).padStart(2, "0");
  return `${mm}:${ss}`;
}

function showTimelinePosition(pos) {
  if (!frameTimeline.length) return;
  timelinePos = Math.max(0, Math.min(frameTimeline.length - 1, pos));
  elTimeline.value = String(timelinePos);
  const msg = frameBuffer.get(frameIndexAt(timelinePos));
  if (msg) void renderFrameMessage(msg, false);
  updateTimelineMeta();
}

function loadFrameImage(jpegBase64) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("decode failed"));
    img.src = `data:image/jpeg;base64,${jpegBase64}`;
  });
}

fetch("/api/sessions/info")
  .then((r) => r.json())
  .then((info) => {
    elReid.textContent = info.reidEnabled ? "ReID: включен" : "ReID: IoU";
    elReid.className = info.reidEnabled ? "badge ok" : "badge off";
    if (info.processEveryNFrames) $("pParamEvery").textContent = info.processEveryNFrames;
    if (info.countAnchorYRatio) $("pParamAnchor").textContent = info.countAnchorYRatio;
    if (info.minAnchorMovement) $("pParamMovement").textContent = info.minAnchorMovement;
    if (info.confidenceThreshold) $("pParamConf").textContent = info.confidenceThreshold;
    if (typeof info.smartStopEnabled === "boolean") elSmartStop.checked = info.smartStopEnabled;
    if (info.smartStopDoorCloseConfirmSeconds != null) $("pParamDoorClose").textContent = info.smartStopDoorCloseConfirmSeconds;
    if (info.smartStopInactivitySeconds != null) $("pParamInactivity").textContent = info.smartStopInactivitySeconds;
    if (info.smartStopVehicleMotionConfirmSeconds != null) $("pParamVehicleMotion").textContent = info.smartStopVehicleMotionConfirmSeconds;
    if (info.smartStopPostRollSeconds != null) $("pParamPostRoll").textContent = info.smartStopPostRollSeconds;
    applyDefaultSetup(info);
  })
  .catch(() => {
    elReid.textContent = "сервер недоступен";
    elReid.className = "badge off";
  });

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
    onSelChange();
    $("btnStop").disabled = true;
  };
}

function onFrame(msg) {
  storeFrame(msg);
  if (followLive) {
    timelinePos = frameTimeline.length - 1;
    void renderFrameMessage(msg, true);
    updateTimelineUi();
  }
}

async function renderFrameMessage(msg, appendEvents) {
  const token = ++displayToken;
  try {
    const img = await loadFrameImage(msg.frameJpegBase64);
    if (token !== displayToken) return;

    currentFrame = {
      image: img,
      width: msg.width,
      height: msg.height,
      detections: msg.detections,
      salonPolygon: msg.salonPolygon || [],
      streetPolygon: msg.streetPolygon || [],
      doorPolygon: msg.doorPolygon || [],
      salonSpawnPolygon: msg.salonSpawnPolygon || [],
      smartStop: msg.smartStop || null,
    };
    elCanvas.width = msg.width;
    elCanvas.height = msg.height;
    $("emptyState").classList.add("hidden");
    redrawMonitor();
    updateFrameStats(msg);
    if (appendEvents) {
      for (const ev of msg.events || []) addEvent(ev);
    }
  } catch (e) {
    setStatus("ошибка кадра", "error");
  }
}

function updateFrameStats(msg) {
  const exited = msg.exited ?? msg.alightings ?? 0;
  $("statAlighting").textContent = exited;
  $("statOnboard").textContent = msg.onboard;
  $("statInitial").textContent = msg.initialOnboard;
  $("statTracks").textContent = msg.detections.length;
  $("statFps").textContent = msg.fps.toFixed(1);
  $("statFrames").textContent = msg.frameIndex;
  $("doorWidthLabel").textContent = "polygon";
  $("insideSideLabel").textContent = "SALON";
  $("outsideSideLabel").textContent = "STREET";
  $("visibleCount").textContent = msg.visibleDetections ?? msg.detections.length;
  $("insideCount").textContent = msg.insideDetections ?? 0;
  $("doorwayCount").textContent = msg.doorwayDetections ?? 0;
  $("outsideCount").textContent = msg.outsideDetections ?? 0;
  updateSmartStopMetrics(msg.smartStop);
}

function updateSmartStopMetrics(metrics) {
  const m = metrics || {
    enabled: false,
    phase: "DISABLED",
    doorState: "UNKNOWN",
    doorConfidence: 0,
    calibrationProgress: 0,
    doorReferenceDifference: 0,
    doorMotionScore: 0,
    passengerActivity: false,
    passengerActivitySeen: false,
    secondsSincePassengerActivity: 0,
    sceneMotionScore: 0,
    vehicleMoving: false,
    vehicleMovingSeconds: 0,
    closedStableSeconds: 0,
    postRollRemainingSeconds: 0,
  };
  const stateLabel = {
    CALIBRATING: "КАЛИБРОВКА",
    OPEN: "ОТКРЫТА",
    CLOSED: "ЗАКРЫТА",
    UNKNOWN: "НЕИЗВЕСТНО",
  }[m.doorState] || m.doorState || "НЕИЗВЕСТНО";
  const phaseLabel = {
    DISABLED: "ВЫКЛЮЧЕНО",
    CALIBRATING: "КАЛИБРОВКА",
    MONITORING: "НАБЛЮДЕНИЕ",
    CLOSING: "ПРОВЕРКА",
    POST_ROLL: "ПОСТЗАПИСЬ",
    FINISHED: "ЗАВЕРШЕНО",
  }[m.phase] || m.phase || "—";

  const stateEl = $("doorVisualState");
  stateEl.textContent = stateLabel;
  stateEl.className = `door-state ${(m.doorState || "UNKNOWN").toLowerCase()}`;
  $("smartStopPhase").textContent = phaseLabel;
  $("doorConfidence").textContent = `${Math.round((m.doorConfidence || 0) * 100)}%`;
  $("doorCalibration").textContent = `${Math.round((m.calibrationProgress || 0) * 100)}%`;
  $("doorDifference").textContent = metricNumber(m.doorReferenceDifference);
  $("doorMotion").textContent = metricNumber(m.doorMotionScore);
  $("doorClosedStable").textContent = `${metricSeconds(m.closedStableSeconds)} с`;
  setBooleanMetric("passengerActivity", !!m.passengerActivity, "да", "нет", true);
  $("passengerIdle").textContent = m.passengerActivitySeen
    ? `${metricSeconds(m.secondsSincePassengerActivity)} с`
    : "не наблюдалась";
  $("sceneMotion").textContent = metricNumber(m.sceneMotionScore);
  setBooleanMetric(
    "vehicleMoving",
    !!m.vehicleMoving,
    `да · ${metricSeconds(m.vehicleMovingSeconds)} с`,
    `нет · ${metricSeconds(m.vehicleMovingSeconds)} с`,
    false,
  );

  const bar = $("doorConfidenceBar");
  bar.style.width = `${Math.round((m.doorConfidence || 0) * 100)}%`;
  bar.style.background = m.doorState === "OPEN"
    ? "#75e3ae"
    : (m.doorState === "CLOSED" ? "#ff8b8b" : "#f6c85f");
  $("smartStopHint").textContent = smartStopHint(m);
}

function metricNumber(value) {
  return Number.isFinite(value) ? value.toFixed(3) : "0.000";
}

function metricSeconds(value) {
  return Number.isFinite(value) ? value.toFixed(1) : "0.0";
}

function setBooleanMetric(id, active, yesText, noText, dangerWhenActive) {
  const el = $(id);
  el.textContent = active ? yesText : noText;
  el.className = active ? (dangerWhenActive ? "alert" : "yes") : "";
}

function smartStopHint(m) {
  if (!m.enabled) return "Умное завершение отключено для этой сессии.";
  if (m.phase === "FINISHED") return `Анализ завершен: ${finishReasonLabel(m.finishReason)}.`;
  if (m.phase === "POST_ROLL") {
    return `${finishReasonLabel(m.finishReason)}. Контрольная постзапись: ${metricSeconds(m.postRollRemainingSeconds)} с.`;
  }
  if (m.phase === "CALIBRATING" && !m.passengerActivitySeen) {
    return "Ожидаем пассажирскую активность: без нее эталон открытой двери не фиксируется.";
  }
  if (m.phase === "CALIBRATING") {
    return `Собираем чистые кадры открытой двери: ${Math.round((m.calibrationProgress || 0) * 100)}%.`;
  }
  if (m.passengerActivity) {
    return "Пассажир находится в дверной зоне — состояние двери считается неизвестным.";
  }
  if (m.doorState === "CLOSED") {
    return `Закрытие подтверждается ${metricSeconds(m.closedStableSeconds)} с; до решения также проверяется отсутствие пассажиров.`;
  }
  if (m.vehicleMoving) {
    return "Обнаружено устойчивое движение фона; резервное завершение сработает только после периода без пассажиров.";
  }
  return "Система наблюдает дверь, пассажирские треки и движение фона.";
}

function finishReasonLabel(reason) {
  const labels = {
    DOOR_CLOSED: "дверь закрыта и пассажиров в проеме нет",
    INACTIVITY_AND_MOVEMENT: "пассажирской активности нет, автобус движется",
    MAX_DURATION: "достигнут максимальный лимит анализа",
    END_OF_FILE: "достигнут конец видеофайла",
    USER_STOP: "анализ остановлен пользователем",
    ANALYSIS_ERROR: "ошибка анализа",
  };
  return labels[reason] || reason || "условие завершения подтверждено";
}

function redrawMonitor() {
  if (!currentFrame?.image) return;
  ctx.clearRect(0, 0, elCanvas.width, elCanvas.height);
  ctx.drawImage(currentFrame.image, 0, 0, currentFrame.width, currentFrame.height);
  drawMonitorZones(currentFrame);
  drawSmartStopOverlay(currentFrame.smartStop);
  drawBoxes(currentFrame.detections);
}

function drawSmartStopOverlay(metrics) {
  if (!metrics?.enabled) return;
  const label = {
    CALIBRATING: "ДВЕРЬ: КАЛИБРОВКА",
    OPEN: `ДВЕРЬ: ОТКРЫТА ${Math.round((metrics.doorConfidence || 0) * 100)}%`,
    CLOSED: `ДВЕРЬ: ЗАКРЫТА ${Math.round((metrics.doorConfidence || 0) * 100)}%`,
    UNKNOWN: "ДВЕРЬ: НЕИЗВЕСТНО",
  }[metrics.doorState] || "ДВЕРЬ: НЕИЗВЕСТНО";
  const color = metrics.doorState === "OPEN"
    ? "#75e3ae"
    : (metrics.doorState === "CLOSED" ? "#ff8b8b" : "#f6c85f");
  ctx.save();
  ctx.font = "800 15px system-ui,sans-serif";
  const width = ctx.measureText(label).width + 22;
  ctx.fillStyle = "rgba(10,12,16,.82)";
  ctx.fillRect(12, 12, width, 32);
  ctx.strokeStyle = color;
  ctx.lineWidth = 2;
  ctx.strokeRect(12, 12, width, 32);
  ctx.fillStyle = color;
  ctx.fillText(label, 23, 34);
  ctx.restore();
}

function drawMonitorZones(frame) {
  const salon = normalizePolygonForCanvas(frame.salonPolygon, frame.width, frame.height);
  const street = normalizePolygonForCanvas(frame.streetPolygon, frame.width, frame.height);
  const door = normalizePolygonForCanvas(frame.doorPolygon, frame.width, frame.height);
  const salonSpawn = normalizePolygonForCanvas(frame.salonSpawnPolygon, frame.width, frame.height);
  drawZones(ctx, salon, street, door, salonSpawn, frame.width, frame.height, false);
}

function normalizePolygonForCanvas(points, width, height) {
  return (points || []).map((p) => ({ x: p.x / width, y: p.y / height }));
}

function drawBoxes(detections) {
  ctx.font = "600 15px system-ui,sans-serif";
  for (const d of detections) {
    const color = colorFor(d);
    const label = `Пассажир-${d.trackId} ${Math.round(d.confidence * 100)}%${trackStatus(d)}`;
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.strokeRect(d.x1, d.y1, d.x2 - d.x1, d.y2 - d.y1);
    const tw = ctx.measureText(label).width + 12;
    const ly = Math.max(4, d.y1 - 25);
    ctx.fillStyle = color;
    ctx.fillRect(d.x1, ly, tw, 22);
    ctx.fillStyle = "#0f1115";
    ctx.fillText(label, d.x1 + 6, ly + 16);

    const ax = d.anchorX ?? ((d.x1 + d.x2) / 2);
    const ay = d.anchorY ?? d.y2;
    ctx.fillStyle = "#0f1115";
    ctx.beginPath();
    ctx.arc(ax, ay, 5, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.stroke();
  }
}

function colorFor(d) {
  if (d.isBoarded) return "#41d69a";
  if (d.isAlighted) return "#ff7a7a";
  if (d.inDoor) return "#f6c85f";
  if (d.zone === "BUFFER") return "#f6c85f";
  if (d.zone === "INSIDE") return "#79a7ff";
  if (d.zone === "OUTSIDE") return "#d1d7e0";
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
    const isCancel = item.direction.startsWith("CANCEL_");
    const positive = item.direction === "BOARDING" || item.direction === "CANCEL_ALIGHTING";
    const row = document.createElement("div");
    row.className = `event ${positive ? "boarding" : "alighting"}${isCancel ? " cancel" : ""}`;
    row.innerHTML = `<b>${evtLabel(item.direction)}</b><span>#${item.trackId} · кадр ${item.frameIndex}</span>`;
    elEventLog.appendChild(row);
  }
}

function evtLabel(direction) {
  const map = {
    BOARDING: "Вход",
    ALIGHTING: "Выход",
    CANCEL_BOARDING: "Отмена входа",
    CANCEL_ALIGHTING: "Отмена выхода",
  };
  return map[direction] || direction;
}

function onFinished(msg) {
  setStatus(msg.status === "FINISHED" ? "готово" : msg.status.toLowerCase(), msg.status === "FINISHED" ? "" : "error");
  processedFrames = Math.max(processedFrames, msg.framesProcessed || 0);
  followLive = false;
  syncFollowLiveButton();
  updateTimelineUi();
  if (frameTimeline.length) {
    showTimelinePosition(frameTimeline.length - 1);
  }
  elFinal.classList.remove("hidden");
  elFinal.classList.toggle("error", msg.status === "FAILED");
  const sec = (msg.durationMs / 1000).toFixed(1);
  const exited = msg.totalExited ?? msg.totalAlightings ?? 0;
  elFinalText.innerHTML = msg.status === "FAILED"
    ? `<b>Ошибка:</b> ${msg.errorMessage || "неизвестная"}`
    : `Кадров: <b>${msg.framesProcessed}</b>, время: <b>${sec} с</b>, вышло: <b>${exited}</b>, в салоне: <b>${msg.finalOnboard}</b><br>Завершение: <b>${finishReasonLabel(msg.finishReason)}</b>`;
  if (msg.smartStop) updateSmartStopMetrics(msg.smartStop);
  currentSessionId = null;
  onSelChange();
  $("btnStop").style.display = "none";
  $("btnStop").disabled = true;
}

function resetMonitor() {
  frameBuffer.clear();
  frameTimeline = [];
  timelinePos = 0;
  followLive = true;
  displayToken = 0;
  processedFrames = 0;
  sourceFramesPerSecond = 25;
  currentFrame = null;
  syncFollowLiveButton();
  elTimeline.value = "0";
  elTimeline.max = "0";
  elTimeline.disabled = true;
  elBtnStepBack.disabled = true;
  elBtnStepForward.disabled = true;
  elBtnFollowLive.disabled = true;
  updateTimelineMeta();
  eventItems = [];
  elEventLog.innerHTML = '<div class="event empty">Пока нет событий</div>';
  elFinal.classList.add("hidden");
  elFinal.classList.remove("error");
  [
    "statAlighting",
    "statOnboard",
    "statInitial",
    "statTracks",
    "statFrames",
    "visibleCount",
    "insideCount",
    "doorwayCount",
    "outsideCount",
  ].forEach((id) => { $(id).textContent = "0"; });
  $("statFps").textContent = "-";
  updateSmartStopMetrics(null);
  $("emptyState").classList.remove("hidden");
}

function setStatus(text, cls = "") {
  elStatus.textContent = text;
  elStatus.className = `status ${cls}`;
}

function fmtSize(b) {
  if (b == null) return "";
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(0)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
}

function esc(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

updateSummary();
loadDoorProfiles();
resetMonitor();
