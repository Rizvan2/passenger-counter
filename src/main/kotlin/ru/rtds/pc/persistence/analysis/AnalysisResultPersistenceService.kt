package ru.rtds.pc.persistence.analysis

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.AnalysisSession

@Service
class AnalysisResultPersistenceService(
    private val analysisResultRepository: AnalysisResultRepository,
    @Value("\${analysis-results.persistence.enabled:false}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun save(session: AnalysisSession) {
        if (!enabled) {
            return
        }

        runCatching {
            analysisResultRepository.save(
                AnalysisResultEntity(
                    sessionId = session.id,
                    sourcePath = session.sourcePath,
                    status = session.status.name,
                    totalBoardings = session.totalBoardings.get(),
                    totalAlightings = session.totalAlightings.get(),
                    finalOnboard = session.currentOnboard,
                    initialOnboard = session.initialOnboard,
                    framesProcessed = session.framesProcessed,
                    durationMs = session.durationMs(),
                    lineYRatio = session.lineYRatio,
                    insideOnTop = session.insideOnTop,
                    startedAtMs = session.startedAt,
                    finishedAtMs = session.finishedAt ?: System.currentTimeMillis(),
                    errorMessage = session.errorMessage,
                )
            )
            log.info("Analysis result saved: session={}", session.id)
        }.onFailure {
            log.error("Failed to save analysis result for session {}", session.id, it)
        }
    }
}
