package ru.rtds.pc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import ru.rtds.pc.controller.VideoController
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.service.VideoFrameReader
import java.nio.file.Files
import java.nio.file.Path

class VideoControllerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `lists all video folders once and marks files that are not ready`() {
        val videos = tempDir.resolve("videos")
        val incoming = videos.resolve("incoming")
        val processed = videos.resolve("processed")

        writeVideo(videos.resolve("manual.avi"))
        writeVideo(videos.resolve("classification/4862404199/0/latest.avi"))
        writeVideo(incoming.resolve("4862404199/2026-07-22/uploading.avi"))
        writeVideo(processed.resolve("needs-classification/waiting.avi"))
        writeVideo(processed.resolve("finished/done.avi"))
        writeVideo(processed.resolve("failed/empty.avi"), byteArrayOf())

        val controller = VideoController(
            frameReader = VideoFrameReader(1),
            ftpProperties = FtpProperties(
                home = incoming.toString(),
                processedDir = processed.toString(),
            ),
            videosDir = videos.toString(),
        )

        val files = controller.listVideos().body.orEmpty()
        val byName = files.associateBy { it.name }

        assertEquals(6, files.size)
        assertEquals("MANUAL", byName.getValue("manual.avi").location)
        assertEquals("CHANNEL_PREVIEW", byName.getValue("latest.avi").location)
        assertEquals("FTP_INCOMING", byName.getValue("uploading.avi").location)
        assertEquals("FTP_NEEDS_CLASSIFICATION", byName.getValue("waiting.avi").location)
        assertEquals("FTP_FINISHED", byName.getValue("done.avi").location)
        assertEquals("FTP_FAILED", byName.getValue("empty.avi").location)

        assertFalse(byName.getValue("uploading.avi").previewAvailable)
        assertFalse(byName.getValue("uploading.avi").analysisAvailable)
        assertEquals("Загрузка ещё не завершена", byName.getValue("uploading.avi").issue)

        assertFalse(byName.getValue("empty.avi").previewAvailable)
        assertFalse(byName.getValue("empty.avi").analysisAvailable)
        assertEquals("Пустой файл", byName.getValue("empty.avi").issue)

        assertTrue(byName.getValue("done.avi").previewAvailable)
        assertTrue(byName.getValue("done.avi").analysisAvailable)
        assertTrue(byName.getValue("waiting.avi").previewAvailable)
        assertFalse(byName.getValue("waiting.avi").analysisAvailable)

        val emptyPreviewError = assertThrows(ResponseStatusException::class.java) {
            controller.preview(byName.getValue("empty.avi").path)
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, emptyPreviewError.statusCode)

        val incomingPreviewError = assertThrows(ResponseStatusException::class.java) {
            controller.preview(byName.getValue("uploading.avi").path)
        }
        assertEquals(HttpStatus.CONFLICT, incomingPreviewError.statusCode)
    }

    private fun writeVideo(path: Path, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
}
