package ru.rtds.pc.service

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Service
class ModelDownloadService(
    @Value("\${pc.models-dir}") private val modelsDir: String,
    @Value("\${pc.detector:person}") private val detectorMode: String,
    @Value("\${pc.head-model-path:}") private val configuredHeadModelPath: String,
    @Value("\${pc.head-model-urls:}") private val configuredHeadModelUrls: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val yoloPath: Path get() = Paths.get(modelsDir, "yolov8n.onnx")
    val osnetPath: Path get() = Paths.get(modelsDir, "osnet_x0_25.onnx")
    val headPath: Path
        get() = configuredHeadModelPath
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: Paths.get(modelsDir, "head-detector.onnx")

    // Несколько источников на случай если основной недоступен.
    // SpotLab/Kalray отдают стандартный YOLOv8 output [1, 84, 8400].
    // Xenova отдаёт сырой тензор [1, 144, 80, 80] — не поддерживается нашим парсером.
    private val yoloUrls = listOf(
        "https://huggingface.co/SpotLab/YOLOv8Detection/resolve/main/yolov8n.onnx",
        "https://huggingface.co/Kalray/yolov8n/resolve/main/yolov8n.optimized.onnx",
    )

    private val osnetUrls = listOf(
        "https://huggingface.co/anriha/osnet_x0_25_msmt17/resolve/main/osnet_x0_25_msmt17.onnx",
    )

    private val headUrls: List<String>
        get() = configuredHeadModelUrls
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    @PostConstruct
    fun ensureModels() {
        val dir = Paths.get(modelsDir).toAbsolutePath()
        Files.createDirectories(dir)
        log.info("Models directory: {}", dir)
        val needsPersonDetector = detectorMode.equals("person", ignoreCase = true)
        val needsHeadDetector = detectorMode.equals("head", ignoreCase = true)

        // YOLO критичен. Если его нет и не скачается — даём понятное сообщение.
        if (needsPersonDetector && !fileExistsAndValid(yoloPath)) {
            val ok = tryDownload(yoloPath, yoloUrls, "YOLOv8n")
            if (!ok) {
                log.error("=".repeat(70))
                log.error("YOLO model could not be downloaded automatically.")
                log.error("Please download manually and put it to: {}", yoloPath)
                log.error("Source options:")
                yoloUrls.forEach { log.error("  - {}", it) }
                log.error("Then restart the container.")
                log.error("=".repeat(70))
                throw IllegalStateException(
                    "YOLO model not available. See logs above for manual download instructions."
                )
            }
        } else if (fileExistsAndValid(yoloPath)) {
            log.info("YOLOv8n already present: {} ({} bytes)", yoloPath, Files.size(yoloPath))
        }

        if (needsHeadDetector && !fileExistsAndValid(headPath)) {
            val ok = if (headUrls.isNotEmpty()) tryDownload(headPath, headUrls, "Head detector") else false
            if (!ok) {
                log.error("=".repeat(70))
                log.error("Head detector selected, but model is not available.")
                log.error("Put ONNX model to: {}", headPath)
                log.error("Or configure pc.head-model-path / pc.head-model-urls.")
                log.error("=".repeat(70))
            }
        } else if (fileExistsAndValid(headPath)) {
            log.info("Head detector already present: {} ({} bytes)", headPath, Files.size(headPath))
        }

        // OSNet — опциональный. Если не скачается, ReID просто отключается.
        if (!fileExistsAndValid(osnetPath)) {
            val ok = tryDownload(osnetPath, osnetUrls, "OSNet ReID")
            if (!ok) {
                log.warn("OSNet model not available. ReID will be disabled (IoU-only tracking).")
            }
        } else {
            log.info("OSNet ReID already present: {} ({} bytes)", osnetPath, Files.size(osnetPath))
        }
    }

    fun isReidAvailable(): Boolean = fileExistsAndValid(osnetPath)
    fun isHeadAvailable(): Boolean = fileExistsAndValid(headPath)

    private fun fileExistsAndValid(path: Path): Boolean =
        Files.exists(path) && Files.size(path) > 100_000

    private fun tryDownload(target: Path, urls: List<String>, name: String): Boolean {
        for ((idx, url) in urls.withIndex()) {
            log.info("[{}/{}] Downloading {} from {}", idx + 1, urls.size, name, url)
            try {
                downloadWithRedirects(url, target)
                val size = Files.size(target)
                if (size < 100_000) {
                    log.warn("Downloaded file too small ({} b), trying next source", size)
                    Files.deleteIfExists(target)
                    continue
                }
                log.info("{} downloaded successfully: {} bytes", name, size)
                return true
            } catch (e: Exception) {
                log.warn("Failed from {}: {}", url, e.message)
                runCatching { Files.deleteIfExists(target) }
            }
        }
        return false
    }

    /**
     * URL.openStream() не следует за HTTPS->HTTPS редиректами на другие хосты.
     * HuggingFace редиректит на S3 (cdn-lfs.hf.co) → ручной обход.
     */
    private fun downloadWithRedirects(url: String, target: Path) {
        var currentUrl = URI(url).toURL()
        var redirects = 0
        val maxRedirects = 10

        while (redirects < maxRedirects) {
            val conn = currentUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "passenger-counter/1.0")
            conn.setRequestProperty("Accept", "*/*")
            conn.requestMethod = "GET"

            val code = conn.responseCode
            when (code) {
                in 200..299 -> {
                    conn.inputStream.use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    conn.disconnect()
                    return
                }
                in 300..399 -> {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect $code without Location header")
                    conn.disconnect()
                    log.debug("Redirect {} -> {}", code, location)
                    currentUrl = resolveRedirect(currentUrl, location)
                    redirects++
                }
                else -> {
                    val errText = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code: ${errText.take(200)}")
                }
            }
        }
        throw IllegalStateException("Too many redirects ($maxRedirects)")
    }

    private fun resolveRedirect(base: URL, location: String): URL {
        return if (location.startsWith("http://") || location.startsWith("https://")) {
            URI(location).toURL()
        } else {
            URI(base.toString()).resolve(location).toURL()
        }
    }
}
