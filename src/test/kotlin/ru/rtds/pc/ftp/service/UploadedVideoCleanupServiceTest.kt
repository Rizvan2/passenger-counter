package ru.rtds.pc.ftp.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.model.SessionStatus
import java.nio.file.Files
import java.nio.file.Path

class UploadedVideoCleanupServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `video outside ftp home is not moved after analysis`() {
        val ftpHome = Files.createDirectory(tempDir.resolve("incoming"))
        val processedDir = tempDir.resolve("processed")
        val manualDir = Files.createDirectory(tempDir.resolve("manual"))
        val source = Files.writeString(manualDir.resolve("manual.mp4"), "video")
        val service = cleanupService(ftpHome, processedDir, keepAfterAnalysis = true)

        service.afterAnalysis(source.toString(), SessionStatus.FINISHED)

        assertTrue(Files.exists(source))
        assertFalse(Files.exists(processedDir.resolve("finished").resolve("manual.mp4")))
    }

    @Test
    fun `ftp video under videos directory is moved to processed status directory`() {
        val videosDir = Files.createDirectory(tempDir.resolve("videos"))
        val processedDir = videosDir.resolve("processed")
        val source = Files.writeString(videosDir.resolve("ftp.mp4"), "video")
        val service = cleanupService(videosDir, processedDir, keepAfterAnalysis = true)

        service.afterAnalysis(source.toString(), SessionStatus.FINISHED)

        assertFalse(Files.exists(source))
        assertTrue(Files.exists(processedDir.resolve("finished").resolve("ftp.mp4")))
    }

    private fun cleanupService(
        videosDir: Path,
        processedDir: Path,
        keepAfterAnalysis: Boolean,
    ): UploadedVideoCleanupService =
        UploadedVideoCleanupService(
            FtpProperties(
                home = videosDir.toString(),
                processedDir = processedDir.toString(),
                keepAfterAnalysis = keepAfterAnalysis,
            )
        )
}
