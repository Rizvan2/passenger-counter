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
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import kotlin.math.sqrt

@Service
class ReidService(
    private val modelDownloadService: ModelDownloadService,
    @Value("\${pc.onnx-provider:cpu}") private val onnxProvider: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val width = 128
    private val height = 256

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var available = false
    private var batchSize = 1

    @PostConstruct
    fun init() {
        if (!modelDownloadService.isReidAvailable()) {
            log.warn("ReID model not available. Re-identification disabled.")
            return
        }
        try {
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(
                modelDownloadService.osnetPath.toAbsolutePath().toString(),
                OrtSessionOptionsFactory.create(onnxProvider),
            )
            inputName = session!!.inputNames.first()
            val inputInfo = session!!.inputInfo[inputName]?.info.toString()
            val outputInfo = session!!.outputInfo.values.firstOrNull()?.info.toString()
            batchSize = resolveBatchSize(inputInfo)
            available = true
            log.info(
                "ReID (OSNet) loaded. input='{}', batchSize={}, input info: {}, output info: {}",
                inputName,
                batchSize,
                inputInfo,
                outputInfo,
            )
        } catch (e: Exception) {
            log.warn("Failed to init ReID, disabling. Reason: {}", e.message)
            available = false
        }
    }

    private fun resolveBatchSize(inputInfo: String): Int {
        val shape = Regex("""shape=\[(\d+),\s*3,\s*\d+,\s*\d+]""").find(inputInfo)
        return shape?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    @PreDestroy
    fun cleanup() {
        session?.close()
    }

    fun isAvailable(): Boolean = available

    fun extract(frame: BufferedImage, det: Detection): FloatArray? =
        extractBatch(frame, listOf(det)).firstOrNull()

    fun extractBatch(frame: BufferedImage, boxes: List<Detection>): List<FloatArray?> {
        if (boxes.isEmpty()) return emptyList()
        if (!available) return List(boxes.size) { null }
        val s = session ?: return List(boxes.size) { null }
        val e = env ?: return List(boxes.size) { null }
        val name = inputName ?: return List(boxes.size) { null }

        val prepared = boxes.mapIndexedNotNull { index, det ->
            preprocess(frame, det)?.let { index to it }
        }
        if (prepared.isEmpty()) return List(boxes.size) { null }

        val embeddings = MutableList<FloatArray?>(boxes.size) { null }
        val cropSize = 3 * width * height
        val effectiveBatchSize = batchSize.coerceAtLeast(1)
        val shape = longArrayOf(effectiveBatchSize.toLong(), 3, height.toLong(), width.toLong())

        return try {
            for (chunk in prepared.chunked(effectiveBatchSize)) {
                val input = FloatArray(effectiveBatchSize * cropSize)
                chunk.forEachIndexed { slot, (_, cropInput) ->
                    cropInput.copyInto(input, destinationOffset = slot * cropSize)
                }
                OnnxTensor.createTensor(e, FloatBuffer.wrap(input), shape).use { tensor ->
                    s.run(mapOf(name to tensor)).use { result ->
                        val rows = allEmbeddings(result.get(0).value, effectiveBatchSize)
                        chunk.forEachIndexed { slot, (originalIndex, _) ->
                            rows.getOrNull(slot)?.let { embeddings[originalIndex] = normalize(it) }
                        }
                    }
                }
            }
            embeddings
        } catch (ex: Exception) {
            available = false
            log.warn("ReID extraction failed once, disabling ReID for this run. Reason: {}", ex.message)
            List(boxes.size) { null }
        }
    }

    private fun preprocess(frame: BufferedImage, det: Detection): FloatArray? {
        val padX = det.width * 0.10f
        val padTop = det.height * 0.05f
        val padBottom = det.height * 0.05f
        val rawX1 = det.x1 - padX
        val rawY1 = det.y1 - padTop
        val rawX2 = det.x2 + padX
        val rawY2 = det.y2 + padBottom
        if (rawX2 <= 0f || rawY2 <= 0f || rawX1 >= frame.width || rawY1 >= frame.height) return null

        val x1 = rawX1.toInt().coerceIn(0, frame.width - 1)
        val y1 = rawY1.toInt().coerceIn(0, frame.height - 1)
        val x2 = rawX2.toInt().coerceIn(x1 + 1, frame.width)
        val y2 = rawY2.toInt().coerceIn(y1 + 1, frame.height)
        if (x2 - x1 < 10 || y2 - y1 < 10) return null

        val crop = frame.getSubimage(x1, y1, x2 - x1, y2 - y1)
        val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = resized.createGraphics()
        g.drawImage(crop, 0, 0, width, height, null)
        g.dispose()

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatArray(3 * width * height)
        val plane = width * height
        val pixels = IntArray(plane)
        resized.getRGB(0, 0, width, height, pixels, 0, width)
        for (i in 0 until plane) {
            val rgb = pixels[i]
            val r = ((rgb shr 16) and 0xFF) / 255f
            val gg = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            input[i] = (r - mean[0]) / std[0]
            input[plane + i] = (gg - mean[1]) / std[1]
            input[2 * plane + i] = (b - mean[2]) / std[2]
        }

        return input
    }

    private fun allEmbeddings(output: Any, expectedRows: Int): List<FloatArray> {
        @Suppress("UNCHECKED_CAST")
        return when (output) {
            is Array<*> -> flattenEmbeddingRows(output)
            is FloatArray -> {
                if (expectedRows > 1 && output.size % expectedRows == 0) {
                    val rowSize = output.size / expectedRows
                    List(expectedRows) { row -> output.copyOfRange(row * rowSize, (row + 1) * rowSize) }
                } else {
                    listOf(output)
                }
            }
            else -> throw IllegalStateException("Unsupported ReID output: ${output::class.java.name}")
        }
    }

    private fun flattenEmbeddingRows(output: Array<*>): List<FloatArray> {
        val rows = mutableListOf<FloatArray>()
        for (item in output) {
            when (item) {
                is FloatArray -> rows += item
                is Array<*> -> rows += flattenEmbeddingRows(item)
                null -> Unit
                else -> throw IllegalStateException("Unsupported ReID output element: ${item::class.java.name}")
            }
        }
        if (rows.isEmpty()) throw IllegalStateException("ReID output is empty")
        return rows
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    fun blend(old: FloatArray?, current: FloatArray?, alpha: Float): FloatArray? {
        if (current == null) return old
        if (old == null || old.size != current.size) return current
        val a = alpha.coerceIn(0f, 1f)
        val merged = FloatArray(current.size) { old[it] * (1f - a) + current[it] * a }
        return normalize(merged)
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sumSq = 0f
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq).coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / norm }
    }
}
