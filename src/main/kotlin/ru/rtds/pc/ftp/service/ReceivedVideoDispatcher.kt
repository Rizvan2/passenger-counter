package ru.rtds.pc.ftp.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.service.AnalysisService
import ru.rtds.pc.service.SessionManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Service
class ReceivedVideoDispatcher(
    private val sessionManager: SessionManager,
    private val analysisService: AnalysisService,
    @Value("\${ftp.analysis.line-y-ratio:0.5}") private val defaultLineYRatio: Float,
    @Value("\${ftp.analysis.inside-on-top:true}") private val defaultInsideOnTop: Boolean,
    @Value("\${ftp.analysis.auto-initial-onboard:true}") private val defaultAutoInitialOnboard: Boolean,
    @Value("\${ftp.analysis.initial-onboard:0}") private val defaultInitialOnboard: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val seenFingerprints = ConcurrentHashMap.newKeySet<String>()

    fun onVideoReceived(videoFile: Path) {
        val fingerprint = sha256(videoFile)
        if (!seenFingerprints.add(fingerprint)) {
            log.info("Duplicate FTP upload ignored: {} ({})", videoFile.fileName, fingerprint)
            return
        }

        val session = sessionManager.create(
            videoFile.toAbsolutePath().toString(),
            defaultLineYRatio,
            defaultInsideOnTop,
            defaultAutoInitialOnboard,
            defaultInitialOnboard,
        )

        analysisService.startAsync(session)
        log.info("Analysis started for FTP upload: {}", videoFile.fileName)
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
