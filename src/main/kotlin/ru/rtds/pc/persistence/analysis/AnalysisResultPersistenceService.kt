package ru.rtds.pc.persistence.analysis

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.AnalysisSession
import java.nio.file.Paths

@Service
class AnalysisResultPersistenceService(
    private val analysisResultRepository: AnalysisResultRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${analysis-results.persistence.enabled:false}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun save(session: AnalysisSession) {
        if (!enabled) return

        val videoName = runCatching {
            Paths.get(session.sourcePath).fileName.toString()
        }.getOrDefault(session.sourcePath)
        val metadata = session.videoMetadata

        runCatching {
            analysisResultRepository.save(
                AnalysisResultEntity(
                    sessionId       = session.id,
                    sourcePath      = session.sourcePath,
                    originalRelativePath = metadata.originalRelativePath,
                    videoName       = videoName,
                    videoDeviceId   = metadata.videoDeviceId,
                    recordDate      = metadata.recordDateText,
                    channel         = metadata.channel,
                    eventCode       = metadata.eventCode,
                    recordType      = metadata.recordType,
                    clipStartedAtMs = metadata.clipStartedAtMs,
                    clipFinishedAtMs = metadata.clipFinishedAtMs,
                    fileFlag        = metadata.fileFlag,
                    fileUid         = metadata.fileUid,
                    sourceHash      = session.sourceHash,
                    source          = session.source.name,
                    status          = session.status.name,
                    totalBoardings  = session.totalBoardings.get(),
                    totalAlightings = session.totalAlightings.get(),
                    finalOnboard    = session.currentOnboard,
                    initialOnboard  = session.initialOnboard,
                    framesProcessed = session.framesProcessed,
                    durationMs      = session.durationMs(),
                    salonPolygonJson = objectMapper.writeValueAsString(session.salonPolygon),
                    streetPolygonJson = objectMapper.writeValueAsString(session.streetPolygon),
                    doorPolygonJson = objectMapper.writeValueAsString(session.doorPolygon),
                    salonSpawnPolygonJson = objectMapper.writeValueAsString(session.salonSpawnPolygon),
                    lineYRatio      = session.lineYRatio,
                    insideOnTop     = session.insideOnTop,
                    lineAxRatio     = session.lineAxRatio,
                    lineAyRatio     = session.lineAyRatio,
                    lineBxRatio     = session.lineBxRatio,
                    lineByRatio     = session.lineByRatio,
                    insideOnPositiveSide = session.insideOnPositiveSide,
                    startedAtMs     = session.startedAt,
                    finishedAtMs    = session.finishedAt ?: System.currentTimeMillis(),
                    errorMessage    = session.errorMessage,
                )
            )
            log.info(
                "Analysis result saved: session={}, source={}, video={}, videoDeviceId={}",
                session.id, session.source, videoName, metadata.videoDeviceId,
            )
        }.onFailure {
            log.error("Failed to save analysis result for session {}", session.id, it)
        }
    }

    /**
     * Проверяет был ли файл с таким SHA-256 уже обработан и сохранён в БД.
     * Используется для персистентного дедупа FTP-роликов после рестарта приложения.
     * Работает только при persistence.enabled=true, иначе всегда false.
     */
    fun existsByHash(hash: String): Boolean =
        enabled && analysisResultRepository.existsBySourceHash(hash)
}
