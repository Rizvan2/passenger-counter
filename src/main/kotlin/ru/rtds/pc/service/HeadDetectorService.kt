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
import kotlin.math.roundToInt

@Service
class HeadDetectorService(
    private val modelDownloadService: ModelDownloadService,
    @Value("\${pc.detector:person}") private val detectorMode: String,
    @Value("\${pc.onnx-provider:cpu}") private val onnxProvider: String,
    @Value("\${pc.head-input-size:640}") private val configuredInputSize: Int,
    @Value("\${pc.head-confidence-threshold:\${pc.confidence-threshold:0.35}}") private val confThreshold: Float,
    @Value("\${pc.nms-iou-threshold:0.5}") private val nmsIouThreshold: Float,
    @Value("\${pc.head-output-format:auto}") private val outputFormat: String,
) : FrameDetector {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var env: OrtEnvironment
    private lateinit var session: OrtSession
    private lateinit var inputName: String

    private var enabled = false
    private var effectiveInputSize = configuredInputSize

    override val id: String = "head"
    override val inputSize: Int get() = effectiveInputSize

    @PostConstruct
    fun init() {
        if (!detectorMode.equals(id, ignoreCase = true)) {
            log.info("Head detector is not selected (pc.detector={}), skipping model load", detectorMode)
            return
        }
        if (!modelDownloadService.isHeadAvailable()) {
            throw IllegalStateException(
                "Head detector selected, but model is not available: ${modelDownloadService.headPath}. " +
                    "Set pc.head-model-path or put the ONNX file into models/head-detector.onnx."
            )
        }

        val modelPath = modelDownloadService.headPath.toAbsolutePath().toString()
        log.info("Loading head detector model from: {}", modelPath)
        env = OrtEnvironment.getEnvironment()
        val options = OrtSessionOptionsFactory.create(onnxProvider, intraOpThreads = 4)
        session = env.createSession(modelPath, options)
        inputName = session.inputNames.first()

        val inputInfo = session.inputInfo[inputName]?.info.toString()
        val outputInfo = session.outputInfo.values.firstOrNull()?.info.toString()
        effectiveInputSize = resolveInputSize(inputInfo)
        enabled = true
        log.info(
            "Head detector loaded. input='{}', inputSize={}, input info: {}, output info: {}, parser={}",
            inputName,
            effectiveInputSize,
            inputInfo,
            outputInfo,
            outputFormat,
        )
    }

    private fun resolveInputSize(inputInfo: String): Int {
        val fixedShape = Regex("""shape=\[1, 3, (\d+), (\d+)]""").find(inputInfo)
        if (fixedShape != null) {
            val h = fixedShape.groupValues[1].toInt()
            val w = fixedShape.groupValues[2].toInt()
            if (h == w) return h
        }
        return configuredInputSize
    }

    @PreDestroy
    fun cleanup() {
        if (::session.isInitialized) session.close()
    }

    override fun detect(letterboxed: FloatArray, origWidth: Int, origHeight: Int): List<Detection> {
        if (!enabled) return emptyList()

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(letterboxed), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return nms(parseOutput(result.get(0).value, origWidth, origHeight), nmsIouThreshold)
            }
        }
    }

    private fun parseOutput(output: Any, origWidth: Int, origHeight: Int): List<Detection> {
        val rowsOrPlanes = outputRowsOrPlanes(output)
        val planes = rowsOrPlanes.takeIf { looksLikeYoloPlanes(it) }
        if (planes != null) {
            return parseYoloPlanes(planes, origWidth, origHeight)
        }
        return rowsOrPlanes.mapNotNull { row -> parseRow(row, origWidth, origHeight) }
    }

    private fun outputRowsOrPlanes(output: Any): List<FloatArray> {
        @Suppress("UNCHECKED_CAST")
        return when (output) {
            is Array<*> -> {
                val first = output.firstOrNull()
                when (first) {
                    is FloatArray -> output.filterIsInstance<FloatArray>()
                    is Array<*> -> first.filterIsInstance<FloatArray>()
                    else -> throw IllegalStateException("Unsupported head detector output element: ${first?.javaClass?.name}")
                }
            }
            is FloatArray -> output.toList().chunked(6).map { it.toFloatArray() }
            else -> throw IllegalStateException("Unsupported head detector output: ${output::class.java.name}")
        }
    }

    private fun looksLikeYoloPlanes(values: List<FloatArray>): Boolean =
        values.size in 5..16 && values.firstOrNull()?.size?.let { it > values.size * 10 } == true

    private fun parseYoloPlanes(planes: List<FloatArray>, origWidth: Int, origHeight: Int): List<Detection> {
        val n = planes[0].size
        val gain = minOf(inputSize.toFloat() / origWidth, inputSize.toFloat() / origHeight)
        val padX = (inputSize - origWidth * gain) / 2f
        val padY = (inputSize - origHeight * gain) / 2f
        val detections = mutableListOf<Detection>()

        for (i in 0 until n) {
            val score = planes[4][i]
            if (score < confThreshold) continue

            val cx = planes[0][i]
            val cy = planes[1][i]
            val w = planes[2][i]
            val h = planes[3][i]
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
                    confidence = score,
                )
            )
        }

        return detections
    }

    private fun parseRow(row: FloatArray, origWidth: Int, origHeight: Int): Detection? {
        if (row.size < 6) return null
        val parsed = when (outputFormat.lowercase()) {
            "batch-class-xyxy" -> rowToDetection(row[2], row[3], row[4], row[5], 1f, origWidth, origHeight)
            "xyxy-score-class" -> rowToDetection(row[0], row[1], row[2], row[3], row[4], origWidth, origHeight)
            else -> parseAuto(row, origWidth, origHeight)
        } ?: return null
        return parsed.takeIf { it.confidence >= confThreshold && it.width >= 2f && it.height >= 2f }
    }

    private fun parseAuto(row: FloatArray, origWidth: Int, origHeight: Int): Detection? {
        val looksBatchClass = row[0].isNearInteger() && row[1].isNearInteger() && row[4] > row[2] && row[5] > row[3]
        return if (looksBatchClass) {
            rowToDetection(row[2], row[3], row[4], row[5], 1f, origWidth, origHeight)
        } else {
            rowToDetection(row[0], row[1], row[2], row[3], row[4], origWidth, origHeight)
        }
    }

    private fun rowToDetection(
        rawX1: Float,
        rawY1: Float,
        rawX2: Float,
        rawY2: Float,
        score: Float,
        origWidth: Int,
        origHeight: Int,
    ): Detection? {
        val normalized = listOf(rawX1, rawY1, rawX2, rawY2).all { it in -0.05f..1.5f }
        val scaleX = if (normalized) origWidth.toFloat() else 1f
        val scaleY = if (normalized) origHeight.toFloat() else 1f

        val x1 = rawX1 * scaleX
        val y1 = rawY1 * scaleY
        val x2 = rawX2 * scaleX
        val y2 = rawY2 * scaleY
        if (x2 <= x1 || y2 <= y1) return null

        return Detection(
            x1 = x1.coerceIn(0f, origWidth.toFloat()),
            y1 = y1.coerceIn(0f, origHeight.toFloat()),
            x2 = x2.coerceIn(0f, origWidth.toFloat()),
            y2 = y2.coerceIn(0f, origHeight.toFloat()),
            confidence = score.coerceIn(0f, 1f),
        )
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

    private fun Float.isNearInteger(): Boolean =
        kotlin.math.abs(this - roundToInt()) < 1e-3f
}
