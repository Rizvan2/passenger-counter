package ru.rtds.pc.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class VideoMetadataTest {
    @Test
    fun `extracts video device metadata from ftp-style path`() {
        val metadata = VideoMetadata.fromPath("088037000054/2026-06-20/2_EVT_1_1718860000_1718860060_0_42.avi")

        assertEquals("088037000054", metadata.videoDeviceId)
        assertEquals("088037000054/2026-06-20/2_EVT_1_1718860000_1718860060_0_42.avi", metadata.originalRelativePath)
        assertEquals(LocalDate.parse("2026-06-20"), metadata.recordDate)
        assertEquals(2, metadata.channel)
        assertEquals("EVT", metadata.eventCode)
        assertEquals(1, metadata.recordType)
        assertEquals(Instant.ofEpochSecond(1718860000), metadata.clipStartedAt)
        assertEquals(Instant.ofEpochSecond(1718860060), metadata.clipFinishedAt)
        assertEquals(0, metadata.fileFlag)
        assertEquals(42L, metadata.fileUid)
    }

    @Test
    fun `extracts variable-length numeric video device id`() {
        val metadata = VideoMetadata.fromPath(
            "/app/ftp/incoming/4862404199/2026-07-20/0000_00140000_1_1784563451_1784563475_0_2865392_0.avi",
        )

        assertEquals("4862404199", metadata.videoDeviceId)
        assertEquals(
            "4862404199/2026-07-20/0000_00140000_1_1784563451_1784563475_0_2865392_0.avi",
            metadata.originalRelativePath,
        )
        assertEquals(LocalDate.parse("2026-07-20"), metadata.recordDate)
    }

    @Test
    fun `does not treat unrelated numeric parent directory as video device id`() {
        val metadata = VideoMetadata.fromPath(
            "/archive/123/other/4862404199/2026-07-20/0000_EVT_1_1784563451_1784563475_0_42.avi",
        )

        assertEquals("4862404199", metadata.videoDeviceId)
    }

    @Test
    fun `keeps video device empty when path does not contain ftp folder structure`() {
        val metadata = VideoMetadata.fromPath("C:/videos/manual-upload.mp4")

        assertNull(metadata.videoDeviceId)
        assertNull(metadata.originalRelativePath)
    }
}
