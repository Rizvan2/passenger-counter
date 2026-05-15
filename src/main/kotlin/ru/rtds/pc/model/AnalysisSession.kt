package ru.rtds.pc.model

import java.util.concurrent.atomic.AtomicInteger

class AnalysisSession(
    val id: String,
    val sourcePath: String,
    val lineYRatio: Float,
    val initialOnboard: Int = 0,
) {
    @Volatile var stopRequested: Boolean = false
    @Volatile var status: SessionStatus = SessionStatus.RUNNING
    @Volatile var errorMessage: String? = null

    val totalBoardings = AtomicInteger(0)
    val totalAlightings = AtomicInteger(0)
    @Volatile var framesProcessed: Int = 0
    @Volatile var currentOnboard: Int = initialOnboard
    val startedAt: Long = System.currentTimeMillis()
    @Volatile var finishedAt: Long? = null

}

enum class SessionStatus { RUNNING, FINISHED, STOPPED, FAILED }
