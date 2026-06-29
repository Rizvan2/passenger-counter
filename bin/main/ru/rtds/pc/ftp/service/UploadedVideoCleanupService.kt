package ru.rtds.pc.ftp.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.model.SessionStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Service
class UploadedVideoCleanupService(
    private val properties: FtpProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Вызывается после завершения анализа сессии.
     *
     * Режим определяется флагом [FtpProperties.keepAfterAnalysis]:
     *   true  — тестовый: файл перемещается в processed/<статус>/ и остаётся
     *           доступным для просмотра через интерфейс
     *   false — продовый: файл сразу удаляется, место не занимается
     *
     * Файлы не из FTP-папки (например загруженные вручную через UI) не трогаются.
     */
    fun afterAnalysis(sessionPath: String, status: SessionStatus) {
        val source = Paths.get(sessionPath).toAbsolutePath().normalize()
        val home = Paths.get(properties.home).toAbsolutePath().normalize()

        // Трогаем только файлы из CarCam-структуры (лежат в videos/, но не в processed/)
        if (!source.startsWith(home) || !Files.exists(source)) return

        if (properties.keepAfterAnalysis) {
            moveToProcessed(source, status)
        } else {
            deleteImmediately(source)
        }
    }

    private fun moveToProcessed(source: Path, status: SessionStatus) {
        val targetDir = Paths.get(properties.processedDir, status.name.lowercase())
            .toAbsolutePath().normalize()
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(source.fileName.toString())
        runCatching {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            log.info("FTP file archived → {} (status={})", target, status)
        }.onFailure {
            log.warn("Failed to archive FTP file {}: {}", source, it.message)
        }
    }

    private fun deleteImmediately(source: Path) {
        runCatching {
            Files.deleteIfExists(source)
            log.info("FTP file deleted after analysis: {}", source.fileName)
        }.onFailure {
            log.warn("Failed to delete FTP file {}: {}", source, it.message)
        }
    }
}