package ru.rtds.pc.model

import java.util.concurrent.atomic.AtomicInteger

class AnalysisSession(
    val id: String,
    val sourcePath: String,
    val lineYRatio: Float,
    val insideOnTop: Boolean = true,
    val autoInitialOnboard: Boolean = true,
    initialOnboard: Int = 0,
) {
    @Volatile var stopRequested: Boolean = false
    @Volatile var status: SessionStatus = SessionStatus.RUNNING
    @Volatile var errorMessage: String? = null

    val totalBoardings = AtomicInteger(0)
    val totalAlightings = AtomicInteger(0)
    @Volatile var initialOnboard: Int = initialOnboard
    @Volatile var initialOnboardDetected: Boolean = !autoInitialOnboard
    @Volatile var initialOnboardFrames: Int = 0
    @Volatile var initialOnboardCandidate: Int = initialOnboard
    @Volatile var visibleDetections: Int = 0
    @Volatile var insideDetections: Int = 0
    @Volatile var doorwayDetections: Int = 0
    @Volatile var outsideDetections: Int = 0
    @Volatile var framesProcessed: Int = 0
    @Volatile var currentOnboard: Int = initialOnboard
    val startedAt: Long = System.currentTimeMillis()
    @Volatile var finishedAt: Long? = null

    fun durationMs(): Long = (finishedAt ?: System.currentTimeMillis()) - startedAt
}

enum class SessionStatus { RUNNING, FINISHED, STOPPED, FAILED }
