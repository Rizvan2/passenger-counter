package ru.rtds.pc.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.rtds.pc.dto.SessionStatusResponse
import ru.rtds.pc.dto.StartSessionRequest
import ru.rtds.pc.dto.StartSessionResponse
import ru.rtds.pc.service.AnalysisService
import ru.rtds.pc.service.SessionManager
import ru.rtds.pc.service.ReidService

@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(originPatterns = ["*"])
class AnalysisController(
    private val sessionManager: SessionManager,
    private val analysisService: AnalysisService,
    private val reidService: ReidService,
) {
    @PostMapping
    fun start(@Valid @RequestBody req: StartSessionRequest): ResponseEntity<StartSessionResponse> {
        val s = sessionManager.create(
            req.videoPath,
            req.lineYRatio,
            req.insideOnTop,
            req.autoInitialOnboard,
            req.initialOnboard
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
    fun info(): Map<String, Any> = mapOf(
        "reidEnabled" to reidService.isAvailable(),
    )
}
