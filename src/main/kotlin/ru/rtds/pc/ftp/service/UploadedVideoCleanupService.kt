package ru.rtds.pc.ftp.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.SessionStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Service
class UploadedVideoCleanupService(
    @Value("\${ftp.home:./videos/incoming}") private val ftpHome: String,
    @Value("\${ftp.processed-dir:./videos/processed}") private val processedDir: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun afterAnalysis(sessionPath: String, status: SessionStatus) {
        val source = Paths.get(sessionPath).toAbsolutePath().normalize()
        val home = Paths.get(ftpHome).toAbsolutePath().normalize()
        if (!source.startsWith(home) || !Files.exists(source)) {
            return
        }

        val targetDir = Paths.get(processedDir, status.name.lowercase()).toAbsolutePath().normalize()
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(source.fileName.toString())

        runCatching {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            log.info("FTP upload archived to {}", target)
        }.onFailure {
            log.warn("Failed to archive FTP upload {}: {}", source, it.message)
        }
    }
}
