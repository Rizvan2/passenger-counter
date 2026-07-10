package ru.rtds.pc.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.rtds.pc.dto.CameraProfileBindingRequest
import ru.rtds.pc.dto.CameraProfileBindingResponse
import ru.rtds.pc.dto.DoorZoneProfileRequest
import ru.rtds.pc.dto.DoorZoneProfileResponse
import ru.rtds.pc.dto.ResolvedDoorConfigResponse
import ru.rtds.pc.service.DoorConfigurationService

@RestController
@RequestMapping("/api/door-config")
class DoorConfigurationController(
    private val doorConfigurationService: DoorConfigurationService,
) {
    @GetMapping("/profiles")
    fun listProfiles(): ResponseEntity<List<DoorZoneProfileResponse>> =
        ResponseEntity.ok(doorConfigurationService.listProfiles())

    @GetMapping("/profiles/{id}")
    fun getProfile(@PathVariable id: String): ResponseEntity<DoorZoneProfileResponse> =
        ResponseEntity.ok(doorConfigurationService.getProfile(id))

    @PostMapping("/profiles")
    fun createProfile(@RequestBody request: DoorZoneProfileRequest): ResponseEntity<DoorZoneProfileResponse> =
        ResponseEntity.ok(doorConfigurationService.createProfile(request))

    @PutMapping("/profiles/{id}")
    fun updateProfile(
        @PathVariable id: String,
        @RequestBody request: DoorZoneProfileRequest,
    ): ResponseEntity<DoorZoneProfileResponse> =
        ResponseEntity.ok(doorConfigurationService.updateProfile(id, request))

    @DeleteMapping("/profiles/{id}")
    fun deleteProfile(@PathVariable id: String): ResponseEntity<Void> {
        doorConfigurationService.deleteProfile(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/bindings")
    fun listBindings(): ResponseEntity<List<CameraProfileBindingResponse>> =
        ResponseEntity.ok(doorConfigurationService.listBindings())

    @PostMapping("/bindings")
    fun bindCamera(@RequestBody request: CameraProfileBindingRequest): ResponseEntity<CameraProfileBindingResponse> =
        ResponseEntity.ok(doorConfigurationService.bindCamera(request))

    @DeleteMapping("/bindings/{id}")
    fun deactivateBinding(@PathVariable id: String): ResponseEntity<CameraProfileBindingResponse> =
        ResponseEntity.ok(doorConfigurationService.deactivateBinding(id))

    @GetMapping("/resolve")
    fun resolve(
        @RequestParam(required = false) recorderId: String?,
        @RequestParam(required = false) cameraCode: String?,
    ): ResponseEntity<ResolvedDoorConfigResponse> =
        ResponseEntity.ok(doorConfigurationService.resolveResponse(recorderId, cameraCode))
}
