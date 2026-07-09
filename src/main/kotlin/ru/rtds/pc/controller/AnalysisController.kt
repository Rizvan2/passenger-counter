package ru.rtds.pc.controller

import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.rtds.pc.dto.SessionStatusResponse
import ru.rtds.pc.dto.StartSessionRequest
import ru.rtds.pc.dto.StartSessionResponse
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.service.AnalysisService
import ru.rtds.pc.service.CountingZones
import ru.rtds.pc.service.SessionManager
import ru.rtds.pc.service.ReidService

@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(originPatterns = ["*"])
class AnalysisController(
    private val sessionManager: SessionManager,
    private val analysisService: AnalysisService,
    private val reidService: ReidService,
    private val ftpProperties: FtpProperties,
    @Value("\${pc.process-every-n-frames}") private val processEveryNFrames: Int,
    @Value("\${pc.count-anchor-y-ratio:0.5}") private val countAnchorYRatio: Float,
    @Value("\${pc.count-min-anchor-movement-px:40}") private val minAnchorMovement: Float,
    @Value("\${pc.confidence-threshold:0.35}") private val confidenceThreshold: Float,
) {
    @PostMapping
    fun start(@Valid @RequestBody req: StartSessionRequest): ResponseEntity<StartSessionResponse> {
        val legacyLineAx = req.resolvedLineAx()
        val legacyLineAy = req.resolvedLineAy()
        val legacyLineBx = req.resolvedLineBx()
        val legacyLineBy = req.resolvedLineBy()
        val insidePositive = req.resolvedInsideOnPositiveSide()
        val legacyPolygons = CountingZones.legacyPolygonsFromLine(
            lineAxRatio = legacyLineAx,
            lineAyRatio = legacyLineAy,
            lineBxRatio = legacyLineBx,
            lineByRatio = legacyLineBy,
            insideOnPositiveSide = insidePositive,
        )
        val (salonPolygon, streetPolygon, fallbackDoorPolygon) = if (req.hasExplicitPolygons()) {
            Triple(req.resolvedSalonPolygon(), req.resolvedStreetPolygon(), legacyPolygons.third)
        } else if (ftpProperties.analysis.hasExplicitPolygons()) {
            Triple(
                ftpProperties.analysis.resolvedSalonPolygon(),
                ftpProperties.analysis.resolvedStreetPolygon(),
                legacyPolygons.third,
            )
        } else {
            legacyPolygons
        }
        val doorPolygon = when {
            req.hasExplicitDoorPolygon() -> req.resolvedDoorPolygon()
            ftpProperties.analysis.hasExplicitDoorPolygon() -> ftpProperties.analysis.resolvedDoorPolygon()
            else -> fallbackDoorPolygon
        }
        val salonSpawnPolygon = when {
            req.hasExplicitSalonSpawnPolygon() -> req.resolvedSalonSpawnPolygon()
            ftpProperties.analysis.hasExplicitSalonSpawnPolygon() -> ftpProperties.analysis.resolvedSalonSpawnPolygon()
            else -> emptyList()
        }
        val s = sessionManager.create(
            videoPath = req.videoPath,
            salonPolygon = salonPolygon,
            streetPolygon = streetPolygon,
            doorPolygon = doorPolygon,
            salonSpawnPolygon = salonSpawnPolygon,
            lineAxRatio = legacyLineAx,
            lineAyRatio = legacyLineAy,
            lineBxRatio = legacyLineBx,
            lineByRatio = legacyLineBy,
            insideOnPositiveSide = insidePositive,
            autoInitialOnboard = req.autoInitialOnboard,
            initialOnboard = req.initialOnboard,
        )
        analysisService.startAsync(s)
        return ResponseEntity.ok(
            StartSessionResponse(
                sessionId = s.id,
                wsUrl = "/ws/analysis/${s.id}",
            )
        )
    }

    @GetMapping("/{id}")
    fun status(@PathVariable id: String): ResponseEntity<SessionStatusResponse> {
        val s = sessionManager.get(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            SessionStatusResponse(
                sessionId = s.id,
                status = s.status.name,
                framesProcessed = s.framesProcessed,
                totalBoardings = s.totalBoardings.get(),
                totalAlightings = s.totalAlightings.get(),
                currentOnboard = s.currentOnboard,
                errorMessage = s.errorMessage,
            )
        )
    }

    @PostMapping("/{id}/stop")
    fun stop(@PathVariable id: String): ResponseEntity<Void> {
        sessionManager.stop(id)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/info")
    fun info(): Map<String, Any> {
        val legacyPolygons = CountingZones.legacyPolygonsFromLine(
            lineAxRatio = ftpProperties.analysis.resolvedLineAx(),
            lineAyRatio = ftpProperties.analysis.resolvedLineAy(),
            lineBxRatio = ftpProperties.analysis.resolvedLineBx(),
            lineByRatio = ftpProperties.analysis.resolvedLineBy(),
            insideOnPositiveSide = ftpProperties.analysis.resolvedInsideOnPositiveSide(),
        )
        val doorPolygon = ftpProperties.analysis.resolvedDoorPolygon().takeIf { it.size >= 3 } ?: legacyPolygons.third
        return mapOf(
            "reidEnabled" to reidService.isAvailable(),
            "processEveryNFrames" to processEveryNFrames,
            "countAnchorYRatio" to countAnchorYRatio,
            "minAnchorMovement" to minAnchorMovement,
            "confidenceThreshold" to confidenceThreshold,
            "defaultLineAx" to ftpProperties.analysis.resolvedLineAx(),
            "defaultLineAy" to ftpProperties.analysis.resolvedLineAy(),
            "defaultLineBx" to ftpProperties.analysis.resolvedLineBx(),
            "defaultLineBy" to ftpProperties.analysis.resolvedLineBy(),
            "defaultInsideOnPositiveSide" to ftpProperties.analysis.resolvedInsideOnPositiveSide(),
            "defaultSalonPolygon" to ftpProperties.analysis.resolvedSalonPolygon().map { mapOf("x" to it.x, "y" to it.y) },
            "defaultStreetPolygon" to ftpProperties.analysis.resolvedStreetPolygon().map { mapOf("x" to it.x, "y" to it.y) },
            "defaultDoorPolygon" to doorPolygon.map { mapOf("x" to it.x, "y" to it.y) },
            "defaultSalonSpawnPolygon" to ftpProperties.analysis.resolvedSalonSpawnPolygon().map { mapOf("x" to it.x, "y" to it.y) },
        )
    }
}
