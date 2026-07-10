package ru.rtds.pc.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import ru.rtds.pc.dto.CameraProfileBindingRequest
import ru.rtds.pc.dto.RnisDoorAssignmentRequest
import ru.rtds.pc.dto.RnisDoorAssignmentResponse
import ru.rtds.pc.dto.RnisDoorChannelDto
import ru.rtds.pc.dto.RnisDoorChannelsResponse
import ru.rtds.pc.dto.RnisDoorFrameResponse
import ru.rtds.pc.model.VideoMetadata
import ru.rtds.pc.persistence.config.CameraProfileBindingEntity
import ru.rtds.pc.persistence.config.CameraProfileBindingRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Service
class RnisDoorClassificationService(
    private val bindingRepository: CameraProfileBindingRepository,
    private val doorConfigurationService: DoorConfigurationService,
    private val frameReader: VideoFrameReader,
    @Value("\${pc.videos-dir:./videos}") private val videosDir: String,
) {
    private val videoExtensions = setOf("mp4", "avi", "mov", "mkv", "flv", "wmv", "webm", "m4v")
    private val assignableDoors = setOf("FRONT", "REAR", "CUSTOM", "IGNORE")

    fun pendingChannels(recorderId: String?): List<RnisDoorChannelDto> {
        val recorderFilter = recorderId?.trim()?.takeIf { it.isNotBlank() }
        return bindingRepository
            .findByLogicalDoorAndActiveTrueOrderByUpdatedAtMsDesc(NEEDS_CLASSIFICATION)
            .asSequence()
            .filter { recorderFilter == null || it.recorderId == recorderFilter }
            .map { toChannelDto(it.recorderId, it.cameraCode, it, sampleVideo(it.recorderId, it.cameraCode)) }
            .toList()
    }

    fun channels(recorderId: String): RnisDoorChannelsResponse {
        val cleanedRecorderId = cleanedRequired(recorderId, "recorderId")
        val bindings = bindingRepository.findByRecorderIdAndActiveTrueOrderByCameraCodeAscCreatedAtMsDesc(cleanedRecorderId)
        val bindingByCamera = bindings.associateBy { it.cameraCode }
        val samplesByCamera = sampleVideos(cleanedRecorderId).associateBy { it.metadata.cameraCode.orEmpty() }
        val cameraCodes = (bindingByCamera.keys + samplesByCamera.keys)
            .filter { it.isNotBlank() }
            .sorted()

        return RnisDoorChannelsResponse(
            recorderId = cleanedRecorderId,
            channels = cameraCodes.map { cameraCode ->
                toChannelDto(
                    recorderId = cleanedRecorderId,
                    cameraCode = cameraCode,
                    binding = bindingByCamera[cameraCode],
                    sample = bindingByCamera[cameraCode]?.let { sampleVideo(it.recorderId, it.cameraCode) } ?: samplesByCamera[cameraCode],
                )
            },
        )
    }

    fun frame(recorderId: String, cameraCode: String, frameIndex: Int): RnisDoorFrameResponse {
        val cleanedRecorderId = cleanedRequired(recorderId, "recorderId")
        val cleanedCameraCode = cleanedRequired(cameraCode, "cameraCode")
        val sample = sampleVideo(cleanedRecorderId, cleanedCameraCode)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Sample video for recorder/camera was not found")
        val requestedFrameIndex = frameIndex.coerceAtLeast(0)
        val snapshot = frameReader.readFrame(sample.path.toString(), requestedFrameIndex)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Frame was not found")

        return RnisDoorFrameResponse(
            recorderId = cleanedRecorderId,
            cameraCode = cleanedCameraCode,
            requestedFrameIndex = requestedFrameIndex,
            frameIndex = snapshot.frameIndex,
            previousFrameIndex = if (snapshot.frameIndex > 0) snapshot.frameIndex - 1 else null,
            nextFrameIndex = snapshot.frameIndex + 1,
            frameJpegBase64 = frameReader.encodeJpegBase64(snapshot.image, 0.75f),
            width = snapshot.width,
            height = snapshot.height,
            sampleVideoName = sample.path.fileName.toString(),
        )
    }

    fun assign(
        recorderId: String,
        cameraCode: String,
        request: RnisDoorAssignmentRequest,
    ): RnisDoorAssignmentResponse {
        val cleanedRecorderId = cleanedRequired(recorderId, "recorderId")
        val cleanedCameraCode = cleanedRequired(cameraCode, "cameraCode")
        val logicalDoor = request.logicalDoor.trim().uppercase()
        if (logicalDoor !in assignableDoors) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "logicalDoor must be one of: ${assignableDoors.joinToString()}")
        }

        val currentBinding = bindingRepository.findByRecorderIdAndCameraCodeAndActiveTrue(cleanedRecorderId, cleanedCameraCode)
        val sample = sampleVideo(cleanedRecorderId, cleanedCameraCode)
        val profileId = if (logicalDoor == "IGNORE") null else request.profileId?.trim()?.takeIf { it.isNotBlank() }
        val frameIndex = request.frameIndex ?: currentBinding?.previewFrameIndex ?: 0
        val binding = doorConfigurationService.bindCamera(
            CameraProfileBindingRequest(
                recorderId = cleanedRecorderId,
                cameraCode = cleanedCameraCode,
                logicalDoor = logicalDoor,
                profileId = profileId,
                confirmedBy = request.confirmedBy,
                previewSourcePath = sample?.path?.toString() ?: currentBinding?.previewSourcePath,
                previewFrameIndex = frameIndex,
            ),
        )
        return RnisDoorAssignmentResponse(
            binding = binding,
            resolved = doorConfigurationService.resolveResponse(cleanedRecorderId, cleanedCameraCode),
        )
    }

    private fun toChannelDto(
        recorderId: String,
        cameraCode: String,
        binding: CameraProfileBindingEntity?,
        sample: SampleVideo?,
    ): RnisDoorChannelDto {
        val resolved = doorConfigurationService.resolveResponse(recorderId, cameraCode)
        val logicalDoor = binding?.logicalDoor ?: NEEDS_CLASSIFICATION
        return RnisDoorChannelDto(
            recorderId = recorderId,
            cameraCode = cameraCode,
            logicalDoor = logicalDoor,
            classificationNeeded = logicalDoor == NEEDS_CLASSIFICATION || resolved.classificationNeeded,
            profileId = binding?.profileId,
            profileName = if (binding?.profileId == null) null else resolved.profileName,
            previewAvailable = sample != null,
            sampleVideoName = sample?.path?.fileName?.toString(),
            previewFrameIndex = binding?.previewFrameIndex ?: 0,
            updatedAtMs = binding?.updatedAtMs ?: sample?.modifiedAtMs,
        )
    }

    private fun sampleVideo(recorderId: String, cameraCode: String): SampleVideo? {
        bindingRepository.findByRecorderIdAndCameraCodeAndActiveTrue(recorderId, cameraCode)
            ?.previewSourcePath
            ?.let(::validatedVideoPath)
            ?.let { path ->
                return SampleVideo(path, VideoMetadata.fromPath(path.toString()), Files.getLastModifiedTime(path).toMillis())
            }
        return sampleVideos(recorderId, cameraCode).firstOrNull()
    }

    private fun sampleVideos(recorderId: String, cameraCode: String? = null): List<SampleVideo> {
        val root = Paths.get(videosDir).toAbsolutePath().normalize()
        if (!Files.exists(root)) return emptyList()

        val stream = Files.walk(root, 6)
        return try {
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().substringAfterLast('.', "").lowercase() in videoExtensions }
                .filter { !it.toString().contains("incoming") }
                .map { it.toAbsolutePath().normalize() }
                .map { path -> SampleVideo(path, VideoMetadata.fromPath(path.toString()), Files.getLastModifiedTime(path).toMillis()) }
                .filter { it.metadata.videoDeviceId == recorderId }
                .filter { cameraCode == null || it.metadata.cameraCode == cameraCode }
                .sorted { left, right -> right.modifiedAtMs.compareTo(left.modifiedAtMs) }
                .toList()
        } finally {
            stream.close()
        }
    }

    private fun validatedVideoPath(rawPath: String): Path? {
        val root = Paths.get(videosDir).toAbsolutePath().normalize()
        val path = runCatching { Paths.get(rawPath).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (!path.startsWith(root)) return null
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return null
        if (path.fileName.toString().substringAfterLast('.', "").lowercase() !in videoExtensions) return null
        return path
    }

    private fun cleanedRequired(value: String, field: String): String =
        value.trim().takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must not be blank")

    private data class SampleVideo(
        val path: Path,
        val metadata: VideoMetadata,
        val modifiedAtMs: Long,
    )

    private companion object {
        const val NEEDS_CLASSIFICATION = "NEEDS_CLASSIFICATION"
    }
}
