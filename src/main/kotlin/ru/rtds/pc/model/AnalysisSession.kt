package ru.rtds.pc.model

import java.util.concurrent.atomic.AtomicInteger

class AnalysisSession(
    val id: String,
    val sourcePath: String,
    val videoMetadata: VideoMetadata = VideoMetadata.fromPath(sourcePath),
    val salonPolygon: List<NormalizedPoint>,
    val streetPolygon: List<NormalizedPoint>,
    val doorPolygon: List<NormalizedPoint>,
    val salonSpawnPolygon: List<NormalizedPoint> = emptyList(),
    val lineAxRatio: Float = 0f,
    val lineAyRatio: Float = 0.5f,
    val lineBxRatio: Float = 1f,
    val lineByRatio: Float = 0.5f,
    val insideOnPositiveSide: Boolean = false,
    val autoInitialOnboard: Boolean = true,
    initialOnboard: Int = 0,
    val source: VideoSource = VideoSource.MANUAL,
    val sourceHash: String? = null,
    val zoneProfileId: String? = null,
    val zoneProfileName: String? = null,
    val logicalDoor: String? = null,
) {
    val lineYRatio: Float get() = (lineAyRatio + lineByRatio) / 2f
    val insideOnTop: Boolean get() = !insideOnPositiveSide

    @Volatile var stopRequested: Boolean = false
    @Volatile var status: SessionStatus = SessionStatus.RUNNING
    @Volatile var errorMessage: String? = null

    val totalBoardings = AtomicInteger(0)
    val totalAlightings = AtomicInteger(0)
    @Volatile var initialOnboard: Int = initialOnboard
    @Volatile var initialOnboardDetected: Boolean = !autoInitialOnboard
    @Volatile var initialOnboardFrames: Int = 0
    @Volatile var initialOnboardCandidate: Int = initialOnboard
    @Volatile var countingPrescanDone: Boolean = false
    @Volatile var countingPrescanFrames: Int = 0
    @Volatile var visibleDetections: Int = 0
    @Volatile var insideDetections: Int = 0
    @Volatile var bufferDetections: Int = 0
    @Volatile var doorwayDetections: Int = 0
    @Volatile var outsideDetections: Int = 0
    @Volatile var framesProcessed: Int = 0
    @Volatile var currentOnboard: Int = initialOnboard
    val startedAt: Long = System.currentTimeMillis()
    @Volatile var finishedAt: Long? = null

    fun durationMs(): Long = (finishedAt ?: System.currentTimeMillis()) - startedAt
}

enum class SessionStatus { RUNNING, FINISHED, STOPPED, FAILED }

enum class VideoSource { FTP, MANUAL }
