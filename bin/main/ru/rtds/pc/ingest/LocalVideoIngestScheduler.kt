package ru.rtds.pc.ingest

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.rtds.pc.ftp.service.ReceivedVideoDispatcher
import ru.rtds.pc.model.VideoMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Dev: подхватывает ролики, положенные вручную в
 * videos/{deviceId}/{YYYY-MM-DD}/ — та же структура, что у CarCam по FTP.
 */
@Component
class LocalVideoIngestScheduler(
    private val dispatcher: ReceivedVideoDispatcher,
    @Value("\${pc.videos-dir:./videos}") private val videosDir: String,
    @Value("\${pc.local-ingest.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val videoExtensions = setOf("avi", "mp4", "mov", "mkv", "m4v")
    private val deviceIdPattern = Regex("\\d{12}")
    private val recordDatePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

    @Scheduled(fixedDelayString = "\${pc.local-ingest.scan-interval-ms:15000}", initialDelayString = "5000")
    fun scanVideosDirectory() {
        if (!enabled) return

        val root = Paths.get(videosDir).toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return

        runCatching {
            Files.walk(root)
                .filter { Files.isRegularFile(it) }
                .filter { isVideoFile(it) }
                .filter { isCarCamLayout(it, root) }
                .forEach { dispatcher.onVideoReceived(it) }
        }.onFailure {
            log.warn("Local video ingest scan failed: {}", it.message)
        }
    }

    private fun isVideoFile(path: Path): Boolean {
        val ext = path.fileName.toString().substringAfterLast('.').lowercase()
        return ext in videoExtensions
    }

    private fun isCarCamLayout(file: Path, root: Path): Boolean {
        val relative = root.relativize(file.toAbsolutePath().normalize())
        if (relative.nameCount < 3) return false

        val segments = (0 until relative.nameCount - 1).map { relative.getName(it).toString() }
        if (!segments[0].matches(deviceIdPattern)) return false
        if (!segments[1].matches(recordDatePattern)) return false
        if (segments.any { it.equals("processed", ignoreCase = true) }) return false

        val metadata = VideoMetadata.fromPath(file.toAbsolutePath().toString())
        return metadata.videoDeviceId != null && metadata.recordDate != null
    }
}
