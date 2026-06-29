package ru.rtds.pc.controller

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.rtds.pc.service.VideoFrameReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class VideoFileDto(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modified: String,
)

data class FramePreviewDto(
    val frameJpegBase64: String,
    val width: Int,
    val height: Int,
)

data class VideoInfoDto(
    val path: String,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val lengthInFrames: Long,
    val durationMs: Long,
)

@RestController
@RequestMapping("/api/videos")
@CrossOrigin(originPatterns = ["*"])
class VideoController(
    private val frameReader: VideoFrameReader,
    @Value("\${pc.videos-dir:./videos}") private val videosDir: String,
) {

    private val videoExtensions = setOf("mp4", "avi", "mov", "mkv", "flv", "wmv", "webm", "m4v")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")

    /**
     * GET /api/videos
     * Возвращает список видеофайлов из videos/{deviceId}/{YYYY-MM-DD}/.
     */
    @GetMapping
    fun listVideos(): ResponseEntity<List<VideoFileDto>> {
        val root = Paths.get(videosDir).toAbsolutePath().normalize()
        if (!Files.exists(root)) return ResponseEntity.ok(emptyList())

        val videos = Files.walk(root, 4)
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().substringAfterLast('.').lowercase() in videoExtensions }
            .filter { !isUnderProcessedDir(it, root) }
            .map { toDto(it) }
            .sorted(compareByDescending { it.modified })
            .toList()

        return ResponseEntity.ok(videos)
    }

    /**
     * GET /api/videos/info?path=...
     * Метаданные видео для таймлайна в UI.
     */
    @GetMapping("/info")
    fun info(@RequestParam path: String): ResponseEntity<VideoInfoDto> {
        val file = resolveReadableVideo(path) ?: return ResponseEntity.notFound().build()
        val probe = frameReader.probe(file.absolutePath) ?: return ResponseEntity.internalServerError().build()
        return ResponseEntity.ok(
            VideoInfoDto(
                path = file.absolutePath,
                width = probe.width,
                height = probe.height,
                frameRate = probe.frameRate,
                lengthInFrames = probe.lengthInFrames,
                durationMs = probe.durationMs,
            ),
        )
    }

    /**
     * GET /api/videos/preview?path=...&frameIndex=0
     * Возвращает кадр указанного видеофайла в виде JPEG base64.
     */
    @GetMapping("/preview")
    fun preview(
        @RequestParam path: String,
        @RequestParam(defaultValue = "0") frameIndex: Int,
    ): ResponseEntity<FramePreviewDto> {
        val file = resolveReadableVideo(path) ?: return ResponseEntity.notFound().build()
        val safeFrameIndex = frameIndex.coerceAtLeast(0)
        var previewDto: FramePreviewDto? = null

        frameReader.process(file.absolutePath, skipFrames = 0) { idx, img, w, h ->
            if (idx < safeFrameIndex) {
                return@process true
            }
            val jpegBase64 = frameReader.encodeJpegBase64(img, 0.75f)
            previewDto = FramePreviewDto(jpegBase64, w, h)
            false
        }

        return previewDto?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.internalServerError().build()
    }

    private fun resolveReadableVideo(path: String): File? {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            return null
        }
        val root = Paths.get(videosDir).toAbsolutePath().normalize()
        val target = file.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(root) || isUnderProcessedDir(target, root)) {
            return null
        }
        return file
    }

    private fun isUnderProcessedDir(path: Path, root: Path): Boolean {
        val relative = runCatching { root.relativize(path.toAbsolutePath().normalize()) }.getOrNull() ?: return false
        return relative.nameCount > 0 && relative.getName(0).toString().equals("processed", ignoreCase = true)
    }

    private fun toDto(path: Path): VideoFileDto {
        val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        val modified = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()),
            ZoneId.systemDefault()
        ).format(dateFormatter)
        return VideoFileDto(
            name = path.fileName.toString(),
            path = path.toAbsolutePath().toString(),
            sizeBytes = attrs.size(),
            modified = modified,
        )
    }
}
