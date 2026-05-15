# Passenger Counter — тестовый проект подсчёта пассажиров

Прогон тестовых видео из маршруток через YOLOv8 + IoU/ReID-трекинг.
Видишь в браузере: исходное видео + боксы людей + виртуальную линию + счётчик IN/OUT в реальном времени.

## Стек

- **Backend**: Kotlin + Spring Boot 3.2 + JVM 21
- **CV**: ONNX Runtime (YOLOv8n для детекции + OSNet x0.25 для ReID)
- **Видео**: JavaCV / FFmpeg
- **Frontend**: статичный HTML + JS + Canvas (без сборки)
- **Транспорт**: REST для управления, WebSocket для realtime-кадров

## Запуск через Docker (рекомендуется)

Требования: Docker Desktop с Docker Compose.

```bash
# 1. Из корня проекта
docker compose up --build
```

Первый билд займёт 3-5 минут (Gradle скачивает зависимости, ~600 МБ).
При первом запуске сервер автоматически скачает модели в `./models/`:
- `yolov8n.onnx` ~12 МБ
- `osnet_x0_25.onnx` ~10 МБ (опционально, для re-ID)

Если OSNet скачать не получится — приложение продолжит работу с обычным IoU-трекингом без re-ID.

## Использование

1. Положи тестовые видео в папку `./videos/` (она монтируется в контейнер как `/videos`).
   Например `./videos/bus.mp4`.

2. Открой http://localhost:8080 в браузере.

3. В поле "Путь к видео" укажи **`/videos/bus.mp4`** (это путь внутри контейнера).

4. Подбери позицию виртуальной линии слайдером или мышью прямо по кадру.

5. Жми "Старт". На канвасе появится видео с боксами и счётчиком.

6. Цвета боксов:
   - 🟡 жёлтый — человек в кадре, ещё не определён
   - 🟢 зелёный — засчитан как ВОШЕДШИЙ
   - 🔴 красный — засчитан как ВЫШЕДШИЙ

## Логика подсчёта

- Виртуальная горизонтальная линия делит кадр на "снаружи" (выше) и "внутри" (ниже).
- Если ориентация камеры наоборот — поменяй файл `CrossingLineCounter.kt`, поле `firstSideAbove` и логику.
- Если человек перешёл линию **впервые** в направлении внутрь → засчитывается boarding.
- Если перешёл обратно → откатывается (учли что он вышел пропустить других).
- Если опять перешёл внутрь → снова засчитывается.
- ReID (если включён) восстанавливает trackId если человек уходил из кадра и вернулся (по визуальной похожести одежды).

## Если модели не скачались автоматически

Если в логах видишь `YOLO model could not be downloaded automatically` — значит у Docker нет доступа в интернет или HuggingFace недоступен.

**Скачай вручную:**

1. `yolov8n.onnx` (~12 МБ) — любая из ссылок:
   - https://huggingface.co/Xenova/yolov8n/resolve/main/onnx/model.onnx (переименуй в `yolov8n.onnx`)
   - https://huggingface.co/SpotLab/YOLOv8Detection/resolve/main/yolov8n.onnx
   - Через PowerShell: `Invoke-WebRequest "https://huggingface.co/SpotLab/YOLOv8Detection/resolve/main/yolov8n.onnx" -OutFile "models\yolov8n.onnx"`

2. `osnet_x0_25.onnx` (~1 МБ, опционально для ReID):
   - https://huggingface.co/anriha/osnet_x0_25_msmt17/resolve/main/osnet_x0_25_msmt17.onnx (переименуй в `osnet_x0_25.onnx`)

3. Положи файлы в папку `./models/` рядом с `docker-compose.yml`
4. Перезапусти: `docker compose restart`

## Запуск без Docker (для отладки)

Нужен JDK 21 и Gradle 8.5+. На Windows проще через Docker.

```bash
gradle bootRun
```

Видео указывай по абсолютному пути, например `C:/test/bus.mp4` (с прямыми слешами).

## Параметры в `application.yml`

```yaml
pc:
  yolo-input-size: 416           # меньше = быстрее, но менее точно. 320 / 416 / 640
  confidence-threshold: 0.35     # порог уверенности детекции
  reid-similarity-threshold: 0.65 # порог совпадения для re-ID
  process-every-n-frames: 3      # обрабатывать каждый N-й кадр (3 = ~10 FPS из 30)
  emit-frame-every-ms: 100       # как часто слать кадры на фронт (10 FPS на UI)
  jpeg-quality: 0.55             # качество JPEG для стрима на фронт
```

## Файлы проекта

```
passenger-counter/
├── build.gradle.kts             # зависимости
├── Dockerfile
├── docker-compose.yml
├── src/main/
│   ├── kotlin/ru/rtds/pc/
│   │   ├── PassengerCounterApplication.kt
│   │   ├── config/
│   │   │   ├── AsyncConfig.kt
│   │   │   └── WebSocketConfig.kt
│   │   ├── controller/AnalysisController.kt
│   │   ├── dto/Dtos.kt
│   │   ├── model/
│   │   │   ├── Detection.kt
│   │   │   ├── TrackedPerson.kt
│   │   │   └── AnalysisSession.kt
│   │   ├── service/
│   │   │   ├── ModelDownloadService.kt    # скачивает модели
│   │   │   ├── YoloDetectorService.kt     # детекция людей
│   │   │   ├── ReidService.kt             # эмбеддинги для re-ID
│   │   │   ├── PersonTracker.kt           # трекинг + re-ID
│   │   │   ├── CrossingLineCounter.kt     # логика подсчёта
│   │   │   ├── VideoFrameReader.kt        # чтение видео + препроцессинг
│   │   │   ├── SessionManager.kt
│   │   │   └── AnalysisService.kt         # главный оркестратор
│   │   └── websocket/AnalysisWebSocketHandler.kt
│   └── resources/
│       ├── application.yml
│       └── static/
│           ├── index.html
│           ├── style.css
│           └── app.js
├── videos/                      # сюда кладёшь тестовые видео
└── models/                      # сюда скачиваются модели
```

## Лицензии

- **YOLOv8** (Ultralytics) — AGPL-3.0. Для тестов и личного использования допустимо.
  Для коммерческого использования в продакшене либо открой исходники, либо замени на YOLOX (Apache 2.0).
- **OSNet** (KaiyangZhou/deep-person-reid) — MIT.
- **Всё остальное** (DJL, ONNX Runtime, JavaCV, Spring) — Apache 2.0 / MIT.

## Известные ограничения

- На CPU обработка медленнее реалтайма (~5-10 FPS на i3-N305). Для теста — норм.
- Модели обучены на COCO/MSMT17 — в реальных условиях (зимняя одежда, толпа, плохое освещение)
  точность будет ниже. Нужно дообучать на своих видео.
- Линию во время обработки можно двигать только локально на UI.
  Чтобы синхронизировать с бэкендом — нужен отдельный эндпоинт.
