package ru.rtds.pc.ftp.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import ru.rtds.pc.ftp.config.FtpProperties
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Дедуп по SHA-256 — исключает повторный анализ одного файла при store-and-forward.
    // In-memory: не переживает рестарт. Для прода с гарантией — вынести в БД
    // (добавить поле source_hash в AnalysisResultEntity и проверять через репозиторий).
    private val seenFingerprints = ConcurrentHashMap.newKeySet<String>()

    /**
     * Вызывается из UploadCompletionFtplet в потоке FTP-сервера.
     * Сразу уходим в @Async чтобы не блокировать FTP-соединение пока
     * считается SHA-256 и стартует анализ.
     */
    @Async("analysisExecutor")
    fun onVideoReceived(videoFile: Path) {
        val fingerprint = sha256(videoFile)
        if (!seenFingerprints.add(fingerprint)) {
            log.info("Duplicate FTP upload ignored: {} ({})", videoFile.fileName, fingerprint)
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
            videoPath            = videoFile.toAbsolutePath().toString(),
            lineYRatio           = properties.analysis.lineYRatio,
            insideOnTop          = properties.analysis.insideOnTop,
            autoInitialOnboard   = properties.analysis.autoInitialOnboard,
            initialOnboard       = properties.analysis.initialOnboard,
        )

        // startAsync уже помечен @Async("analysisExecutor") — поставит задачу в очередь,
        // не заблокирует этот поток.
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