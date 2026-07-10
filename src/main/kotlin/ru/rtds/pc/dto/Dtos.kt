package ru.rtds.pc.dto

import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import ru.rtds.pc.model.NormalizedPoint

data class StartSessionRequest(
    @field:NotBlank(message = "Путь к видео обязателен")
    val videoPath: String,

    @Deprecated("Use lineAy/lineBy")
    @field:DecimalMin("0.05") @field:DecimalMax("0.95")
    val lineYRatio: Float? = null,

    @Deprecated("Use insideOnPositiveSide")
    @JsonAlias("insideOnLeft")
    val insideOnTop: Boolean? = true,

    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val lineAx: Float? = null,

    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val lineAy: Float? = null,

    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val lineBx: Float? = null,

    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val lineBy: Float? = null,

    val insideOnPositiveSide: Boolean? = null,

    val salonPolygon: List<LinePointDto>? = null,

    val streetPolygon: List<LinePointDto>? = null,

    val doorPolygon: List<LinePointDto>? = null,

    val salonSpawnPolygon: List<LinePointDto>? = null,

    val autoInitialOnboard: Boolean = true,

    val initialOnboard: Int = 0,
) {
    fun resolvedLineAx(): Float = lineAx ?: 0f
    fun resolvedLineAy(): Float = lineAy ?: (lineYRatio ?: 0.5f)
    fun resolvedLineBx(): Float = lineBx ?: 1f
    fun resolvedLineBy(): Float = lineBy ?: (lineYRatio ?: 0.5f)
    fun resolvedInsideOnPositiveSide(): Boolean = insideOnPositiveSide ?: !(insideOnTop ?: true)
    fun resolvedSalonPolygon(): List<NormalizedPoint> = salonPolygon.orEmpty().map { NormalizedPoint(it.x, it.y).clamped() }
    fun resolvedStreetPolygon(): List<NormalizedPoint> = streetPolygon.orEmpty().map { NormalizedPoint(it.x, it.y).clamped() }
    fun resolvedDoorPolygon(): List<NormalizedPoint> = doorPolygon.orEmpty().map { NormalizedPoint(it.x, it.y).clamped() }
    fun resolvedSalonSpawnPolygon(): List<NormalizedPoint> = salonSpawnPolygon.orEmpty().map { NormalizedPoint(it.x, it.y).clamped() }
    fun hasExplicitPolygons(): Boolean = resolvedSalonPolygon().size >= 3 && resolvedStreetPolygon().size >= 3
    fun hasExplicitDoorPolygon(): Boolean = resolvedDoorPolygon().size >= 3
    fun hasExplicitSalonSpawnPolygon(): Boolean = resolvedSalonSpawnPolygon().size >= 3
}

data class StartSessionResponse(
    val sessionId: String,
    val wsUrl: String,
)

data class SessionStatusResponse(
    val sessionId: String,
    val status: String,
    val framesProcessed: Int,
    val totalExited: Int,
    @Deprecated("Exit-only mode always reports zero boardings")
    val totalBoardings: Int,
    @Deprecated("Use totalExited")
    val totalAlightings: Int,
    val currentOnboard: Int,
    val errorMessage: String?,
)

data class FrameUpdateDto(
    val type: String = "FRAME",
    val frameIndex: Int,
    val frameJpegBase64: String,
    val width: Int,
    val height: Int,
    val detections: List<BoxDto>,
    val salonPolygon: List<LinePointDto>,
    val streetPolygon: List<LinePointDto>,
    val doorPolygon: List<LinePointDto>,
    val salonSpawnPolygon: List<LinePointDto>,
    val lineY: Float,
    val doorTopY: Float,
    val doorBottomY: Float,
    val insideOnTop: Boolean,
    val lineAx: Float,
    val lineAy: Float,
    val lineBx: Float,
    val lineBy: Float,
    val lineAxRatio: Float,
    val lineAyRatio: Float,
    val lineBxRatio: Float,
    val lineByRatio: Float,
    val insideOnPositiveSide: Boolean,
    val doorCorridor: List<LinePointDto>,
    val events: List<PassengerEventDto>,
    val exited: Int,
    @Deprecated("Exit-only mode always reports zero boardings")
    val boardings: Int,
    @Deprecated("Use exited")
    val alightings: Int,
    val initialOnboard: Int,
    val initialOnboardLocked: Boolean,
    val onboard: Int,
    val visibleDetections: Int,
    val insideDetections: Int,
    val bufferDetections: Int,
    val doorwayDetections: Int,
    val outsideDetections: Int,
    val fps: Float,
)

data class LinePointDto(
    val x: Float,
    val y: Float,
)

data class BoxDto(
    val trackId: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val anchorX: Float,
    val anchorY: Float,
    val confidence: Float,
    val isBoarded: Boolean,
    val isAlighted: Boolean,
    val inDoor: Boolean,
    val zone: String,
    val state: String,
)

data class PassengerEventDto(
    val trackId: Int,
    val direction: String,
    val frameIndex: Int,
    val from: String,
    val to: String,
)

data class SessionFinishedDto(
    val type: String = "FINISHED",
    val status: String,
    val totalExited: Int,
    @Deprecated("Exit-only mode always reports zero boardings")
    val totalBoardings: Int,
    @Deprecated("Use totalExited")
    val totalAlightings: Int,
    val finalOnboard: Int,
    val framesProcessed: Int,
    val durationMs: Long,
    val errorMessage: String?,
)
