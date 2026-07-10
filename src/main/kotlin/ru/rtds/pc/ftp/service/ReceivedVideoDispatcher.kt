package ru.rtds.pc.ftp.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.model.VideoMetadata
import ru.rtds.pc.model.VideoSource
import ru.rtds.pc.persistence.analysis.AnalysisResultPersistenceService
import ru.rtds.pc.service.AnalysisService
import ru.rtds.pc.service.CountingZones
import ru.rtds.pc.service.DoorConfigurationService
import ru.rtds.pc.service.SessionManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value

@Service
class ReceivedVideoDispatcher(
    private val properties: FtpProperties,
    private val sessionManager: SessionManager,
    private val analysisService: AnalysisService,
    private val persistenceService: AnalysisResultPersistenceService,
    private val doorConfigurationService: DoorConfigurationService,
    @Value("\${pc.videos-dir:./videos}") private val videosDir: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // In-memory дедуп — быстрая проверка без обращения к БД.
    // Персистентный дедуп через БД (existsByHash) страхует после рестарта.
    private val seenFingerprints = ConcurrentHashMap.newKeySet<String>()

    @Async("analysisExecutor")
    fun onVideoReceived(videoFile: Path) {
        val fingerprint = sha256(videoFile)

        // 1. Быстрая проверка in-memory
        if (!seenFingerprints.add(fingerprint)) {
            log.info("Duplicate FTP upload ignored (in-memory): {} ({})", videoFile.fileName, fingerprint)
            return
        }

        // 2. Персистентная проверка через БД (работает после рестарта)
        if (persistenceService.existsByHash(fingerprint)) {
            log.info("Duplicate FTP upload ignored (db): {} ({})", videoFile.fileName, fingerprint)
            return
        }

        val metadata = VideoMetadata.fromPath(videoFile.toAbsolutePath().toString())
        val previewFile = archivePreview(videoFile, metadata)
        doorConfigurationService.registerVideoPreview(
            metadata = metadata,
            previewSourcePath = previewFile.toString(),
        )
        val doorConfig = doorConfigurationService.resolveFor(metadata)
        if (doorConfig.classificationNeeded) {
            archiveWaitingForClassification(videoFile)
            log.info(
                "FTP video waits for camera classification: file={}, recorderId={}, cameraCode={}, sample={}",
                videoFile.fileName,
                metadata.videoDeviceId,
                metadata.cameraCode,
                previewFile,
            )
            return
        }
        if (doorConfig.ignored) {
            log.info(
                "FTP video ignored by camera binding: file={}, recorderId={}, cameraCode={}, logicalDoor={}",
                videoFile.fileName,
                metadata.videoDeviceId,
                metadata.cameraCode,
                doorConfig.logicalDoor,
            )
            return
        }

        log.info(
            "FTP video received: {} ({} bytes), recorderId={}, cameraCode={}, profile={} ({}, {}), line=({},{})->({},{}), insidePositive={}, keepAfterAnalysis={}",
            videoFile.fileName,
            runCatching { Files.size(videoFile) }.getOrDefault(-1L),
            metadata.videoDeviceId,
            metadata.cameraCode,
            doorConfig.profileName,
            doorConfig.source,
            doorConfig.logicalDoor,
            doorConfig.resolvedLineAx(),
            doorConfig.resolvedLineAy(),
            doorConfig.resolvedLineBx(),
            doorConfig.resolvedLineBy(),
            doorConfig.resolvedInsideOnPositiveSide(),
            properties.keepAfterAnalysis,
        )

        val legacyLineAx = doorConfig.resolvedLineAx()
        val legacyLineAy = doorConfig.resolvedLineAy()
        val legacyLineBx = doorConfig.resolvedLineBx()
        val legacyLineBy = doorConfig.resolvedLineBy()
        val insidePositive = doorConfig.resolvedInsideOnPositiveSide()
        val legacyPolygons = CountingZones.legacyPolygonsFromLine(
            lineAxRatio = legacyLineAx,
            lineAyRatio = legacyLineAy,
            lineBxRatio = legacyLineBx,
            lineByRatio = legacyLineBy,
            insideOnPositiveSide = insidePositive,
        )
        val (salonPolygon, streetPolygon, fallbackDoorPolygon) = if (doorConfig.hasExplicitPolygons()) {
            Triple(
                doorConfig.salonPolygon,
                doorConfig.streetPolygon,
                legacyPolygons.third,
            )
        } else {
            legacyPolygons
        }
        val doorPolygon = if (doorConfig.hasExplicitDoorPolygon()) {
            doorConfig.doorPolygon
        } else {
            fallbackDoorPolygon
        }
        val salonSpawnPolygon = if (doorConfig.hasExplicitSalonSpawnPolygon()) {
            doorConfig.salonSpawnPolygon
        } else {
            emptyList()
        }

        val session = sessionManager.create(
            videoPath          = videoFile.toAbsolutePath().toString(),
            salonPolygon       = salonPolygon,
            streetPolygon      = streetPolygon,
            doorPolygon        = doorPolygon,
            salonSpawnPolygon  = salonSpawnPolygon,
            lineAxRatio        = legacyLineAx,
            lineAyRatio        = legacyLineAy,
            lineBxRatio        = legacyLineBx,
            lineByRatio        = legacyLineBy,
            insideOnPositiveSide = insidePositive,
            autoInitialOnboard = doorConfig.autoInitialOnboard,
            initialOnboard     = doorConfig.initialOnboard,
            source             = VideoSource.FTP,
            sourceHash         = fingerprint,
            zoneProfileId      = doorConfig.profileId,
            zoneProfileName    = doorConfig.profileName,
            logicalDoor        = doorConfig.logicalDoor,
        )

        analysisService.startAsync(session)
        log.info("Analysis queued for FTP upload: {} → session {}", videoFile.fileName, session.id)
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Keeps one current inspection sample for every recorder channel. */
    private fun archivePreview(source: Path, metadata: VideoMetadata): Path {
        val recorderId = safeSegment(metadata.videoDeviceId ?: "unknown-recorder")
        val cameraCode = safeSegment(metadata.cameraCode ?: "unknown-camera")
        val extension = source.fileName.toString().substringAfterLast('.', "mp4")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "mp4"
        val targetDir = Paths.get(videosDir, "classification", recorderId, cameraCode)
            .toAbsolutePath()
            .normalize()
        Files.createDirectories(targetDir)

        val target = targetDir.resolve("latest.$extension")
        val temporary = targetDir.resolve(".latest-${System.nanoTime()}.$extension")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            runCatching {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private fun safeSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "unknown" }

    private fun archiveWaitingForClassification(source: Path) {
        if (!Files.exists(source)) return
        if (!properties.keepAfterAnalysis) {
            Files.deleteIfExists(source)
            return
        }

        val targetDir = Paths.get(properties.processedDir, "needs-classification")
            .toAbsolutePath()
            .normalize()
        Files.createDirectories(targetDir)
        Files.move(source, targetDir.resolve(source.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
    }
}
