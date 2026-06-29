package ru.rtds.pc.controller

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.rtds.pc.dto.AnalysisResultResponse
import ru.rtds.pc.service.AnalysisResultQueryService

@RestController
@RequestMapping("/api/results")
class AnalysisResultsController(
    private val analysisResultQueryService: AnalysisResultQueryService,
) {
    @GetMapping
    fun getAll(
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) status: String?,
        @PageableDefault(size = 20, sort = ["finishedAtMs"], direction = Sort.Direction.DESC)
        pageable: Pageable,
    ): ResponseEntity<Page<AnalysisResultResponse>> =
        ResponseEntity.ok(
            analysisResultQueryService.findAll(
                from = from,
                to = to,
                deviceId = deviceId,
                source = source,
                status = status,
                pageable = pageable,
            ),
        )

    @GetMapping("/{sessionId}")
    fun getById(@PathVariable sessionId: String): ResponseEntity<AnalysisResultResponse> {
        val result = analysisResultQueryService.findById(sessionId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }

    @GetMapping("/by-device/{videoDeviceId}")
    fun getByDevice(
        @PathVariable videoDeviceId: String,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
    ): ResponseEntity<List<AnalysisResultResponse>> =
        ResponseEntity.ok(analysisResultQueryService.findByDevice(videoDeviceId, from, to))
}
