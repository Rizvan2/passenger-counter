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
     * Возвращает список видеофайлов из папки videos (рекурсивно, глубина 2).
     */
    @GetMapping
    fun listVideos(): ResponseEntity<List<VideoFileDto>> {
        val root = Paths.get(videosDir).toAbsolutePath().normalize()
        if (!Files.exists(root)) return ResponseEntity.ok(emptyList())

        val videos = Files.walk(root, 2)
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().substringAfterLast('.').lowercase() in videoExtensions }
            // исключаем incoming — это FTP-буфер, там недокачанные файлы
            .filter { !it.toString().contains("incoming") }
            .map { toDto(it) }
            .sorted(compareByDescending { it.modified })
            .toList()

        return ResponseEntity.ok(videos)
    }

    /**
     * GET /api/videos/preview?path=...
     * Возвращает первый кадр указанного видеофайла в виде JPEG base64.
     */
    @GetMapping("/preview")
    fun preview(@RequestParam path: String): ResponseEntity<FramePreviewDto> {
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            return ResponseEntity.notFound().build()
        }

        // Защита от path traversal: файл должен лежать внутри videosDir
        val root = Paths.get(videosDir).toAbsolutePath().normalize()
        val target = file.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(root)) {
            return ResponseEntity.badRequest().build()
        }

        var previewDto: FramePreviewDto? = null

        // Читаем только первый кадр через уже существующий VideoFrameReader
        frameReader.process(path, skipFrames = 0) { _, img, w, h ->
            val jpegBase64 = frameReader.encodeJpegBase64(img, 0.75f)
            previewDto = FramePreviewDto(jpegBase64, w, h)
            false // false = стоп после первого кадра
        }

        return previewDto?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.internalServerError().build()
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