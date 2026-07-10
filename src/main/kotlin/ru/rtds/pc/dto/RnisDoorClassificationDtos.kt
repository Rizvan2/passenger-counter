package ru.rtds.pc.dto

data class RnisDoorChannelDto(
    val recorderId: String,
    val cameraCode: String,
    val logicalDoor: String,
    val classificationNeeded: Boolean,
    val profileId: String?,
    val profileName: String?,
    val previewAvailable: Boolean,
    val sampleVideoName: String?,
    val previewFrameIndex: Int?,
    val updatedAtMs: Long?,
)

data class RnisDoorChannelsResponse(
    val recorderId: String,
    val channels: List<RnisDoorChannelDto>,
)

data class RnisDoorFrameResponse(
    val recorderId: String,
    val cameraCode: String,
    val requestedFrameIndex: Int,
    val frameIndex: Int,
    val previousFrameIndex: Int?,
    val nextFrameIndex: Int,
    val frameJpegBase64: String,
    val width: Int,
    val height: Int,
    val sampleVideoName: String,
)

data class RnisDoorAssignmentRequest(
    val logicalDoor: String = "FRONT",
    val confirmedBy: String? = null,
    val frameIndex: Int? = null,
    val profileId: String? = null,
)

data class RnisDoorAssignmentResponse(
    val binding: CameraProfileBindingResponse,
    val resolved: ResolvedDoorConfigResponse,
)
