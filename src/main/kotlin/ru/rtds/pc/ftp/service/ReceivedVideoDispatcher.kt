package ru.rtds.pc.ftp.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.model.VideoSource
import ru.rtds.pc.persistence.analysis.AnalysisResultPersistenceService
import ru.rtds.pc.service.AnalysisService
import ru.rtds.pc.service.SessionManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Service
class ReceivedVideoDispatcher(
    private val properties: FtpProperties,
    private val sessionManager: SessionManager,
    private val analysisService: AnalysisService,
    private val persistenceService: AnalysisResultPersistenceService,
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

        log.info(
            "FTP video received: {} ({} bytes), lineYRatio={}, insideOnTop={}, keepAfterAnalysis={}",
            videoFile.fileName,
            runCatching { Files.size(videoFile) }.getOrDefault(-1L),
            properties.analysis.lineYRatio,
            properties.analysis.insideOnTop,
            properties.keepAfterAnalysis,
        )

        val session = sessionManager.create(
            videoPath          = videoFile.toAbsolutePath().toString(),
            lineYRatio         = properties.analysis.lineYRatio,
            insideOnTop        = properties.analysis.insideOnTop,
            autoInitialOnboard = properties.analysis.autoInitialOnboard,
            initialOnboard     = properties.analysis.initialOnboard,
            source             = VideoSource.FTP,
            sourceHash         = fingerprint,
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
}