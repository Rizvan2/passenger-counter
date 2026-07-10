package ru.rtds.pc.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.rtds.pc.dto.RnisDoorAssignmentRequest
import ru.rtds.pc.dto.RnisDoorAssignmentResponse
import ru.rtds.pc.dto.RnisDoorChannelDto
import ru.rtds.pc.dto.RnisDoorChannelsResponse
import ru.rtds.pc.dto.RnisDoorFrameResponse
import ru.rtds.pc.service.RnisDoorClassificationService

@RestController
@RequestMapping("/api/rnis/door-classification")
@CrossOrigin(originPatterns = ["*"])
class RnisDoorClassificationController(
    private val classificationService: RnisDoorClassificationService,
) {
    @GetMapping("/pending")
    fun pending(
        @RequestParam(required = false) recorderId: String?,
    ): ResponseEntity<List<RnisDoorChannelDto>> =
        ResponseEntity.ok(classificationService.pendingChannels(recorderId))

    @GetMapping("/recorders/{recorderId}/channels", "/registrars/{recorderId}/channels")
    fun channels(
        @PathVariable recorderId: String,
    ): ResponseEntity<RnisDoorChannelsResponse> =
        ResponseEntity.ok(classificationService.channels(recorderId))

    @GetMapping(
        "/recorders/{recorderId}/channels/{cameraCode}/frame",
        "/registrars/{recorderId}/channels/{cameraCode}/frame",
    )
    fun frame(
        @PathVariable recorderId: String,
        @PathVariable cameraCode: String,
        @RequestParam(defaultValue = "0") frameIndex: Int,
    ): ResponseEntity<RnisDoorFrameResponse> =
        ResponseEntity.ok(classificationService.frame(recorderId, cameraCode, frameIndex))

    @PostMapping(
        "/recorders/{recorderId}/channels/{cameraCode}/assignment",
        "/registrars/{recorderId}/channels/{cameraCode}/assignment",
    )
    fun assign(
        @PathVariable recorderId: String,
        @PathVariable cameraCode: String,
        @RequestBody request: RnisDoorAssignmentRequest,
    ): ResponseEntity<RnisDoorAssignmentResponse> =
        ResponseEntity.ok(classificationService.assign(recorderId, cameraCode, request))
}
