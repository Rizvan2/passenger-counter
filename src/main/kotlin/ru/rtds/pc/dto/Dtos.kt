package ru.rtds.pc.dto

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank

data class StartSessionRequest(
    @field:NotBlank(message = "Путь к видео обязателен")
    val videoPath: String,

    @field:DecimalMin("0.05") @field:DecimalMax("0.95")
    val lineYRatio: Float = 0.5f,

    val initialOnboard: Int = 0,
)

data class StartSessionResponse(
    val sessionId: String,
    val wsUrl: String,
)

data class SessionStatusResponse(
    val sessionId: String,
    val status: String,
    val framesProcessed: Int,
    val totalBoardings: Int,
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
    val lineY: Float,
    val boardings: Int,
    val alightings: Int,
    val onboard: Int,
    val fps: Float,
)

data class BoxDto(
    val trackId: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val isBoarded: Boolean,
    val isAlighted: Boolean,
)

data class SessionFinishedDto(
    val type: String = "FINISHED",
    val status: String,
    val totalBoardings: Int,
    val totalAlightings: Int,
    val finalOnboard: Int,
    val framesProcessed: Int,
    val durationMs: Long,
    val errorMessage: String?,
)
