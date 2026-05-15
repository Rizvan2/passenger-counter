package ru.rtds.pc.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.Detection
import java.nio.FloatBuffer

@Service
class YoloDetectorService(
    private val modelDownloadService: ModelDownloadService,
    @Value("\${pc.yolo-input-size}") private val inputSize: Int,
    @Value("\${pc.confidence-threshold}") private val confThreshold: Float,
    @Value("\${pc.nms-iou-threshold}") private val nmsIouThreshold: Float,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var env: OrtEnvironment
    private lateinit var session: OrtSession
    private lateinit var inputName: String

    private val personClassId = 0
    private val numClasses = 80
    private var numBoxes: Int = 0

    @PostConstruct
    fun init() {
        val modelPath = modelDownloadService.yoloPath.toAbsolutePath().toString()
        log.info("Loading YOLO model from: {}", modelPath)
        env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(4)
        session = env.createSession(modelPath, options)
        inputName = session.inputNames.first()

        // Проверка формата выхода. Поддерживаем только стандартный YOLOv8: [1, 84, N]
        val outputInfo = session.outputInfo.values.first().info
        val outStr = outputInfo.toString()
        log.info("YOLO loaded. input='{}', output info: {}", inputName, outStr)

        if (!outStr.contains("shape=[1, 84")) {
            log.error("=".repeat(70))
            log.error("UNSUPPORTED YOLO MODEL FORMAT")
            log.error("Expected output shape: [1, 84, N] (standard YOLOv8 with post-processing)")
            log.error("Got: {}", outStr)
            log.error("")
            log.error("This appears to be a raw-output YOLO (without post-processing) or")
            log.error("a YOLO11/YOLOv8 variant we don't support.")
            log.error("")
            log.error("Fix: delete the wrong model and let the app re-download a correct one:")
            log.error("  docker compose down")
            log.error("  Remove-Item models\\yolov8n.onnx")
            log.error("  docker compose up")
            log.error("=".repeat(70))
            throw IllegalStateException("Unsupported YOLO model format: $outStr")
        }
    }

    @PreDestroy
    fun cleanup() {
        if (::session.isInitialized) session.close()
        if (::env.isInitialized) env.close()
    }

    /**
     * Принимает уже подготовленный (letterboxed, CHW, нормализованный) FloatArray размера 3*inputSize*inputSize.
     * Возвращает детекции в координатах ОРИГИНАЛЬНОГО кадра.
     */
    fun detect(letterboxed: FloatArray, origWidth: Int, origHeight: Int): List<Detection> {
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val buf = FloatBuffer.wrap(letterboxed)
        OnnxTensor.createTensor(env, buf, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result.get(0).value as Array<Array<FloatArray>>
                // output[0] имеет форму [84, N], где N — число anchor-предсказаний (зависит от input size)
                val planes = output[0]
                val n = planes[0].size
                if (numBoxes == 0) {
                    numBoxes = n
                    log.info("YOLO output shape detected: [1, {}, {}]", planes.size, n)
                }
                return parseAndNms(planes, n, origWidth, origHeight)
            }
        }
    }

    private fun parseAndNms(
        planes: Array<FloatArray>,
        n: Int,
        origWidth: Int,
        origHeight: Int,
    ): List<Detection> {
        // YOLOv8: planes[0..3] = cx,cy,w,h; planes[4..83] = class scores (80 классов)
        val detections = mutableListOf<Detection>()
        val gain = minOf(inputSize.toFloat() / origWidth, inputSize.toFloat() / origHeight)
        val padX = (inputSize - origWidth * gain) / 2f
        val padY = (inputSize - origHeight * gain) / 2f

        for (i in 0 until n) {
            val personScore = planes[4 + personClassId][i]
            if (personScore < confThreshold) continue

            // Проверяем что person — наиболее вероятный класс
            var maxScore = personScore
            for (c in 0 until numClasses) {
                val s = planes[4 + c][i]
                if (s > maxScore) { maxScore = s; break }
            }
            if (maxScore > personScore) continue

            val cx = planes[0][i]
            val cy = planes[1][i]
            val w = planes[2][i]
            val h = planes[3][i]

            // Переводим в координаты оригинала (убираем letterbox padding и масштаб)
            val x1 = (cx - w / 2f - padX) / gain
            val y1 = (cy - h / 2f - padY) / gain
            val x2 = (cx + w / 2f - padX) / gain
            val y2 = (cy + h / 2f - padY) / gain

            detections.add(
                Detection(
                    x1 = x1.coerceIn(0f, origWidth.toFloat()),
                    y1 = y1.coerceIn(0f, origHeight.toFloat()),
                    x2 = x2.coerceIn(0f, origWidth.toFloat()),
                    y2 = y2.coerceIn(0f, origHeight.toFloat()),
                    confidence = personScore,
                )
            )
        }
        return nms(detections, nmsIouThreshold)
    }

    private fun nms(dets: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = dets.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { it.iou(best) > iouThreshold }
        }
        return kept
    }
}
