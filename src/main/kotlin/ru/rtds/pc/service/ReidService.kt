package ru.rtds.pc.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.rtds.pc.model.Detection
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import kotlin.math.sqrt

@Service
class ReidService(
    private val modelDownloadService: ModelDownloadService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val width = 128
    private val height = 256

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var available = false

    @PostConstruct
    fun init() {
        if (!modelDownloadService.isReidAvailable()) {
            log.warn("ReID model not available. Re-identification disabled.")
            return
        }
        try {
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(modelDownloadService.osnetPath.toAbsolutePath().toString())
            inputName = session!!.inputNames.first()
            available = true
            log.info("ReID (OSNet) loaded. input='{}'", inputName)
        } catch (e: Exception) {
            log.warn("Failed to init ReID, disabling. Reason: {}", e.message)
            available = false
        }
    }

    @PreDestroy
    fun cleanup() {
        session?.close()
    }

    fun isAvailable(): Boolean = available

    /**
     * Извлечь эмбеддинг для bbox из исходного кадра.
     * Возвращает нормализованный вектор (L2 = 1) или null если ReID недоступен.
     */
    fun extract(frame: BufferedImage, det: Detection): FloatArray? {
        if (!available) return null
        val s = session ?: return null
        val e = env ?: return null
        val name = inputName ?: return null

        // Вырезаем bbox с padding
        val x1 = det.x1.toInt().coerceIn(0, frame.width - 1)
        val y1 = det.y1.toInt().coerceIn(0, frame.height - 1)
        val x2 = det.x2.toInt().coerceIn(x1 + 1, frame.width)
        val y2 = det.y2.toInt().coerceIn(y1 + 1, frame.height)
        if (x2 - x1 < 10 || y2 - y1 < 10) return null

        val crop = frame.getSubimage(x1, y1, x2 - x1, y2 - y1)

        // Resize 128x256
        val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = resized.createGraphics()
        g.drawImage(crop, 0, 0, width, height, null)
        g.dispose()

        // CHW нормализация ImageNet
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

        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        return try {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(input), shape).use { tensor ->
                s.run(mapOf(name to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result.get(0).value as Array<FloatArray>
                    normalize(out[0])
                }
            }
        } catch (ex: Exception) {
            log.debug("ReID extraction failed: {}", ex.message)
            null
        }
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // оба вектора уже нормализованы → косинус = dot
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sumSq = 0f
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq).coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / norm }
    }
}
