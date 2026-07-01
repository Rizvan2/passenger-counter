package ru.rtds.pc.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.rtds.pc.dto.AnalysisResultResponse
import ru.rtds.pc.dto.LinePointDto
import ru.rtds.pc.model.NormalizedPoint
import ru.rtds.pc.persistence.analysis.AnalysisResultEntity
import ru.rtds.pc.persistence.analysis.AnalysisResultRepository

@Service
class AnalysisResultQueryService(
    private val analysisResultRepository: AnalysisResultRepository,
    private val objectMapper: ObjectMapper,
) {
    fun findAll(
        from: Long?,
        to: Long?,
        deviceId: String?,
        source: String?,
        status: String?,
        pageable: Pageable,
    ): Page<AnalysisResultResponse> =
        analysisResultRepository.search(
            from = from,
            to = to,
            deviceId = deviceId?.trim()?.takeIf { it.isNotBlank() },
            source = source?.trim()?.takeIf { it.isNotBlank() },
            status = status?.trim()?.takeIf { it.isNotBlank() },
            pageable = pageable,
        ).map(::toResponse)

    fun findById(sessionId: String): AnalysisResultResponse? =
        analysisResultRepository.findById(sessionId).map(::toResponse).orElse(null)

    fun findByDevice(videoDeviceId: String, from: Long?, to: Long?): List<AnalysisResultResponse> =
        analysisResultRepository.findByVideoDeviceIdAndPeriod(videoDeviceId, from, to)
            .map(::toResponse)

    private fun toResponse(entity: AnalysisResultEntity): AnalysisResultResponse =
        AnalysisResultResponse(
            sessionId = entity.sessionId,
            sourcePath = entity.sourcePath,
            originalRelativePath = entity.originalRelativePath,
            videoName = entity.videoName,
            videoDeviceId = entity.videoDeviceId,
            recordDate = entity.recordDate,
            channel = entity.channel,
            eventCode = entity.eventCode,
            recordType = entity.recordType,
            clipStartedAtMs = entity.clipStartedAtMs,
            clipFinishedAtMs = entity.clipFinishedAtMs,
            fileFlag = entity.fileFlag,
            fileUid = entity.fileUid,
            sourceHash = entity.sourceHash,
            source = entity.source,
            status = entity.status,
            totalBoardings = entity.totalBoardings,
            totalAlightings = entity.totalAlightings,
            finalOnboard = entity.finalOnboard,
            initialOnboard = entity.initialOnboard,
            framesProcessed = entity.framesProcessed,
            durationMs = entity.durationMs,
            salonPolygon = parsePolygon(entity.salonPolygonJson),
            streetPolygon = parsePolygon(entity.streetPolygonJson),
            doorPolygon = parsePolygon(entity.doorPolygonJson),
            lineYRatio = entity.lineYRatio,
            insideOnTop = entity.insideOnTop,
            lineAxRatio = entity.lineAxRatio ?: 0f,
            lineAyRatio = entity.lineAyRatio ?: entity.lineYRatio,
            lineBxRatio = entity.lineBxRatio ?: 1f,
            lineByRatio = entity.lineByRatio ?: entity.lineYRatio,
            insideOnPositiveSide = entity.insideOnPositiveSide ?: !entity.insideOnTop,
            startedAtMs = entity.startedAtMs,
            finishedAtMs = entity.finishedAtMs,
            errorMessage = entity.errorMessage,
        )

    private fun parsePolygon(json: String?): List<LinePointDto> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(json, object : TypeReference<List<NormalizedPoint>>() {})
                .map { LinePointDto(it.x, it.y) }
        }.getOrElse { emptyList() }
    }
}
