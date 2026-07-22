package ru.rtds.pc.controller

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.model.VideoMetadata
import ru.rtds.pc.service.VideoFrameReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class VideoFileDto(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modified: String,
    val modifiedAtMs: Long,
    val videoDeviceId: String?,
    val recordDate: String?,
    val channel: Int?,
    val cameraCode: String?,
    val location: String,
    val folder: String,
    val relativePath: String,
    val previewAvailable: Boolean,
    val analysisAvailable: Boolean,
    val issue: String?,
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
    private val ftpProperties: FtpProperties,
    @Value("\${pc.videos-dir:./videos}") private val videosDir: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val videoExtensions = setOf("mp4", "avi", "mov", "mkv", "flv", "wmv", "webm", "m4v")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")

    /**
     * Returns videos from the manual folder, generated channel previews, the FTP upload area,
     * and every folder below ftp.processed-dir. The physical path is de-duplicated when roots overlap.
     */
    @GetMapping
    fun listVideos(): ResponseEntity<List<VideoFileDto>> {
        val storages = videoStorages()
        val videosByPath = linkedMapOf<Path, VideoFileDto>()

        storages.forEach { storage ->
            if (!Files.isDirectory(storage.root)) return@forEach
            runCatching {
                Files.walk(storage.root, MAX_SCAN_DEPTH).use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) }
                        .filter { isSupportedVideo(it) }
                        .forEach { rawPath ->
                            val path = rawPath.toAbsolutePath().normalize()
                            // A more specific nested storage owns the file, so the broader root must skip it.
                            if (findStorage(path, storages) == storage) {
                                videosByPath[path] = toDto(path, storage)
                            }
                        }
                }
            }.onFailure { error ->
                log.warn("Unable to scan video folder {}: {}", storage.root, error.message)
            }
        }

        return ResponseEntity.ok(videosByPath.values.sortedByDescending { it.modifiedAtMs })
    }

    /** Returns the first decodable frame only for complete, non-empty files in configured roots. */
    @GetMapping("/preview")
    fun preview(@RequestParam path: String): ResponseEntity<FramePreviewDto> {
        val target = runCatching { Paths.get(path).toAbsolutePath().normalize() }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid video path") }
        val storage = findStorage(target, videoStorages())
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Video is outside configured folders")

        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Video file not found")
        }
        if (!isSupportedVideo(target)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported video format")
        }
        if (Files.size(target) <= 0L) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Video file is empty")
        }

        val location = locationFor(storage, target)
        if (!location.previewAllowed) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "FTP upload is not complete yet")
        }

        val frame = runCatching { frameReader.readFrame(target.toString(), 0) }
            .getOrElse { error ->
                log.info("Unable to decode preview for {}: {}", target, error.message)
                throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Video cannot be decoded")
            }
            ?: throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Video has no decodable frames")

        return ResponseEntity.ok(
            FramePreviewDto(
                frameJpegBase64 = frameReader.encodeJpegBase64(frame.image, 0.75f),
                width = frame.width,
                height = frame.height,
            )
        )
    }

    private fun toDto(path: Path, storage: VideoStorage): VideoFileDto {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        val absolutePath = path.toAbsolutePath().normalize()
        val metadata = VideoMetadata.fromPath(absolutePath.toString())
        val location = locationFor(storage, absolutePath)
        val size = attrs.size()
        val modifiedAtMs = attrs.lastModifiedTime().toMillis()
        val relativePath = storage.root.relativize(absolutePath).toString().replace('\\', '/')
        val issue = when {
            size <= 0L -> "Пустой файл"
            !location.previewAllowed -> "Загрузка ещё не завершена"
            else -> null
        }

        return VideoFileDto(
            name = path.fileName.toString(),
            path = absolutePath.toString(),
            sizeBytes = size,
            modified = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(modifiedAtMs),
                ZoneId.systemDefault()
            ).format(dateFormatter),
            modifiedAtMs = modifiedAtMs,
            videoDeviceId = metadata.videoDeviceId,
            recordDate = metadata.recordDateText,
            channel = metadata.channel,
            cameraCode = metadata.cameraCode,
            location = location.code,
            folder = location.label,
            relativePath = relativePath,
            previewAvailable = size > 0L && location.previewAllowed,
            analysisAvailable = size > 0L && location.analysisAllowed,
            issue = issue,
        )
    }

    private fun videoStorages(): List<VideoStorage> {
        val candidates = listOf(
            VideoStorage(normalize(ftpProperties.home), StorageKind.FTP_INCOMING),
            VideoStorage(normalize(ftpProperties.processedDir), StorageKind.FTP_PROCESSED),
            VideoStorage(normalize(videosDir), StorageKind.VIDEOS),
        )

        // If two settings point to one folder, prefer FTP semantics over the generic videos root.
        return candidates.distinctBy { it.root }
    }

    private fun findStorage(path: Path, storages: List<VideoStorage>): VideoStorage? =
        storages
            .filter { path.startsWith(it.root) }
            .maxWithOrNull(compareBy<VideoStorage> { it.root.nameCount }.thenByDescending { it.kind.priority })

    private fun locationFor(storage: VideoStorage, path: Path): VideoLocation {
        val relative = storage.root.relativize(path)
        val firstFolder = relative.firstOrNull()?.toString()?.lowercase()
        return when (storage.kind) {
            StorageKind.FTP_INCOMING -> VideoLocation.FTP_INCOMING
            StorageKind.VIDEOS -> if (firstFolder == "classification") {
                VideoLocation.CHANNEL_PREVIEW
            } else {
                VideoLocation.MANUAL
            }
            StorageKind.FTP_PROCESSED -> when (firstFolder) {
                "needs-classification" -> VideoLocation.FTP_NEEDS_CLASSIFICATION
                "finished" -> VideoLocation.FTP_FINISHED
                "failed" -> VideoLocation.FTP_FAILED
                "stopped" -> VideoLocation.FTP_STOPPED
                else -> VideoLocation.FTP_PROCESSED
            }
        }
    }

    private fun normalize(value: String): Path = Paths.get(value).toAbsolutePath().normalize()

    private fun isSupportedVideo(path: Path): Boolean =
        path.fileName.toString().substringAfterLast('.', "").lowercase() in videoExtensions

    private data class VideoStorage(val root: Path, val kind: StorageKind)

    private enum class StorageKind(val priority: Int) {
        VIDEOS(0),
        FTP_PROCESSED(1),
        FTP_INCOMING(2),
    }

    private enum class VideoLocation(
        val code: String,
        val label: String,
        val previewAllowed: Boolean,
        val analysisAllowed: Boolean,
    ) {
        MANUAL("MANUAL", "Видео", true, true),
        CHANNEL_PREVIEW("CHANNEL_PREVIEW", "Превью канала", true, false),
        FTP_INCOMING("FTP_INCOMING", "FTP / входящие", false, false),
        FTP_NEEDS_CLASSIFICATION("FTP_NEEDS_CLASSIFICATION", "FTP / ожидает дверь", true, false),
        FTP_FINISHED("FTP_FINISHED", "FTP / обработано", true, true),
        FTP_FAILED("FTP_FAILED", "FTP / ошибка", true, true),
        FTP_STOPPED("FTP_STOPPED", "FTP / остановлено", true, true),
        FTP_PROCESSED("FTP_PROCESSED", "FTP / processed", true, true),
    }

    private companion object {
        const val MAX_SCAN_DEPTH = 8
    }
}
