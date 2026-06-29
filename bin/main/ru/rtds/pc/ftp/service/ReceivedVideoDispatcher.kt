package ru.rtds.pc.ftp.service

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.model.VideoMetadata
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

    // Уже обработанные или поставленные в очередь в этом процессе.
    private val processedHashes = ConcurrentHashMap.newKeySet<String>()
    // Файлы, для которых анализ сейчас выполняется.
    private val activePaths = ConcurrentHashMap.newKeySet<String>()

    @PostConstruct
    fun warmProcessedCache() {
        val known = persistenceService.loadFinishedSourceHashes()
        if (known.isNotEmpty()) {
            processedHashes.addAll(known)
            log.info("Loaded {} finished analysis hashes into dedup cache", known.size)
        }
    }

    fun onVideoReceived(videoFile: Path) {
        if (!Files.isRegularFile(videoFile)) return

        val absolute = videoFile.toAbsolutePath().normalize()
        val pathKey = absolute.toString()

        if (activePaths.contains(pathKey)) {
            log.debug("Skip in-flight video: {}", absolute.fileName)
            return
        }

        val metadata = VideoMetadata.fromPath(pathKey)
        val fingerprint = sha256(absolute)

        if (processedHashes.contains(fingerprint)) {
            log.debug("Skip already queued/processed video: {}", absolute.fileName)
            return
        }

        if (persistenceService.isAlreadyAnalyzed(fingerprint, metadata)) {
            processedHashes.add(fingerprint)
            log.info("Skip already analyzed video: {} ({})", absolute.fileName, fingerprint.take(12))
            return
        }

        if (!processedHashes.add(fingerprint)) return
        if (!activePaths.add(pathKey)) {
            processedHashes.remove(fingerprint)
            return
        }

        log.info(
            "Video received for analysis: {} ({} bytes), lineYRatio={}, insideOnTop={}, keepAfterAnalysis={}",
            absolute.fileName,
            runCatching { Files.size(absolute) }.getOrDefault(-1L),
            properties.analysis.lineYRatio,
            properties.analysis.insideOnTop,
            properties.keepAfterAnalysis,
        )

        try {
            val session = sessionManager.create(
                videoPath = pathKey,
                lineYRatio = properties.analysis.lineYRatio,
                insideOnTop = properties.analysis.insideOnTop,
                autoInitialOnboard = properties.analysis.autoInitialOnboard,
                initialOnboard = properties.analysis.initialOnboard,
                source = VideoSource.FTP,
                sourceHash = fingerprint,
            )
            analysisService.startAsync(session)
            log.info("Analysis queued: {} → session {}", absolute.fileName, session.id)
        } catch (e: Exception) {
            activePaths.remove(pathKey)
            processedHashes.remove(fingerprint)
            log.error("Failed to queue analysis for {}: {}", absolute.fileName, e.message, e)
        }
    }

    fun onAnalysisFinished(sourcePath: String, sourceHash: String?) {
        val pathKey = runCatching { Path.of(sourcePath).toAbsolutePath().normalize().toString() }
            .getOrDefault(sourcePath)
        activePaths.remove(pathKey)
        sourceHash?.let { processedHashes.add(it) }
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
