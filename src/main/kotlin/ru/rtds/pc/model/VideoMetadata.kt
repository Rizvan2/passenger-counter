package ru.rtds.pc.model

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

data class VideoMetadata(
    val originalRelativePath: String? = null,
    val videoDeviceId: String? = null,
    val recordDate: LocalDate? = null,
    val channel: Int? = null,
    val cameraCode: String? = null,
    val eventCode: String? = null,
    val recordType: Int? = null,
    val clipStartedAt: Instant? = null,
    val clipFinishedAt: Instant? = null,
    val fileFlag: Int? = null,
    val fileUid: Long? = null,
) {
    companion object {
        private val videoDeviceIdPattern = Regex("\\d{1,64}")
        private val recordDatePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

        fun fromPath(sourcePath: String): VideoMetadata {
            val path = runCatching { Path.of(sourcePath).normalize() }.getOrNull()
            val fileName = path?.fileName?.toString() ?: sourcePath.substringAfterLast('/', sourcePath)
            val pathSegments = path?.map { it.toString() }.orEmpty()

            val deviceIndex = findDeviceIndex(pathSegments)
            val videoDeviceId = deviceIndex?.let(pathSegments::get)
            val recordDate = deviceIndex?.let { pathSegments.getOrNull(it + 1) }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val relativePath = buildRelativePath(pathSegments, fileName, deviceIndex)

            val fileParts = fileName.substringBeforeLast('.').split('_')
            val channel = fileParts.getOrNull(0)?.toIntOrNull()
            val eventCode = fileParts.getOrNull(1)?.takeIf { it.isNotBlank() }
            val cameraCode = eventCode ?: channel?.toString()
            val recordType = fileParts.getOrNull(2)?.toIntOrNull()
            val clipStartedAt = fileParts.getOrNull(3)?.toLongOrNull()?.let(Instant::ofEpochSecond)
            val clipFinishedAt = fileParts.getOrNull(4)?.toLongOrNull()?.let(Instant::ofEpochSecond)
            val fileFlag = fileParts.getOrNull(5)?.toIntOrNull()
            val fileUid = fileParts.getOrNull(6)?.toLongOrNull()

            return VideoMetadata(
                originalRelativePath = relativePath,
                videoDeviceId = videoDeviceId,
                recordDate = recordDate,
                channel = channel,
                cameraCode = cameraCode,
                eventCode = eventCode,
                recordType = recordType,
                clipStartedAt = clipStartedAt,
                clipFinishedAt = clipFinishedAt,
                fileFlag = fileFlag,
                fileUid = fileUid,
            )
        }

        private fun normalizeSeparators(path: String): String = path.replace('\\', '/')

        private fun findDeviceIndex(pathSegments: List<String>): Int? =
            pathSegments.indices.firstOrNull { index ->
                pathSegments[index].matches(videoDeviceIdPattern) &&
                    pathSegments.getOrNull(index + 1)?.matches(recordDatePattern) == true
            }

        private fun buildRelativePath(
            pathSegments: List<String>,
            fileName: String,
            deviceIndex: Int?,
        ): String? {
            if (deviceIndex == null) return null

            val relevantSegments = pathSegments.drop(deviceIndex)
            if (relevantSegments.isEmpty()) return fileName
            return normalizeSeparators(relevantSegments.joinToString("/"))
        }
    }

    val clipStartedAtMs: Long?
        get() = clipStartedAt?.toEpochMilli()

    val clipFinishedAtMs: Long?
        get() = clipFinishedAt?.toEpochMilli()

    val recordDateText: String?
        get() = recordDate?.toString()

    val deviceId: String?
        get() = videoDeviceId
}
