package ru.rtds.pc.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.rtds.pc.dto.CameraProfileBindingRequest
import ru.rtds.pc.dto.CameraProfileBindingResponse
import ru.rtds.pc.dto.DoorZoneProfileRequest
import ru.rtds.pc.dto.DoorZoneProfileResponse
import ru.rtds.pc.dto.LinePointDto
import ru.rtds.pc.dto.ResolvedDoorConfigResponse
import ru.rtds.pc.ftp.config.FtpProperties
import ru.rtds.pc.model.NormalizedPoint
import ru.rtds.pc.model.VideoMetadata
import ru.rtds.pc.persistence.config.CameraProfileBindingEntity
import ru.rtds.pc.persistence.config.CameraProfileBindingRepository
import ru.rtds.pc.persistence.config.DoorZoneProfileEntity
import ru.rtds.pc.persistence.config.DoorZoneProfileRepository
import java.util.UUID

@Service
class DoorConfigurationService(
    private val ftpProperties: FtpProperties,
    private val profileRepository: DoorZoneProfileRepository,
    private val bindingRepository: CameraProfileBindingRepository,
    private val objectMapper: ObjectMapper,
) {
    data class ResolvedDoorConfiguration(
        val recorderId: String?,
        val cameraCode: String?,
        val logicalDoor: String?,
        val ignored: Boolean,
        val classificationNeeded: Boolean,
        val profileId: String?,
        val profileName: String,
        val source: String,
        val lineYRatio: Float,
        val lineAxRatio: Float?,
        val lineAyRatio: Float?,
        val lineBxRatio: Float?,
        val lineByRatio: Float?,
        val salonPolygon: List<NormalizedPoint>,
        val streetPolygon: List<NormalizedPoint>,
        val doorPolygon: List<NormalizedPoint>,
        val salonSpawnPolygon: List<NormalizedPoint>,
        val insideOnTop: Boolean,
        val insideOnPositiveSide: Boolean?,
        val autoInitialOnboard: Boolean,
        val initialOnboard: Int,
    ) {
        fun resolvedLineAx(): Float = lineAxRatio ?: 0f
        fun resolvedLineAy(): Float = lineAyRatio ?: lineYRatio
        fun resolvedLineBx(): Float = lineBxRatio ?: 1f
        fun resolvedLineBy(): Float = lineByRatio ?: lineYRatio
        fun resolvedInsideOnPositiveSide(): Boolean = insideOnPositiveSide ?: !insideOnTop
        fun hasExplicitPolygons(): Boolean = salonPolygon.size >= 3 && streetPolygon.size >= 3
        fun hasExplicitDoorPolygon(): Boolean = doorPolygon.size >= 3
        fun hasExplicitSalonSpawnPolygon(): Boolean = salonSpawnPolygon.size >= 3
    }

    fun listProfiles(): List<DoorZoneProfileResponse> =
        profileRepository.findAll().sortedBy { it.name.lowercase() }.map(::toProfileResponse)

    fun getProfile(id: String): DoorZoneProfileResponse =
        toProfileResponse(profile(id))

    @Transactional
    fun createProfile(request: DoorZoneProfileRequest): DoorZoneProfileResponse {
        val now = System.currentTimeMillis()
        val name = cleanedName(request.name)
        if (profileRepository.findByNameIgnoreCase(name) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Door zone profile with this name already exists")
        }
        return toProfileResponse(
            profileRepository.save(
                DoorZoneProfileEntity(
                    id = UUID.randomUUID().toString(),
                    createdAtMs = now,
                    updatedAtMs = now,
                ).applyRequest(request, name, now),
            ),
        )
    }

    @Transactional
    fun updateProfile(id: String, request: DoorZoneProfileRequest): DoorZoneProfileResponse {
        val entity = profile(id)
        val name = cleanedName(request.name)
        val sameName = profileRepository.findByNameIgnoreCase(name)?.id == id
        if (!sameName && profileRepository.findByNameIgnoreCase(name) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Door zone profile with this name already exists")
        }
        return toProfileResponse(profileRepository.save(entity.applyRequest(request, name, System.currentTimeMillis())))
    }

    @Transactional
    fun deleteProfile(id: String) {
        if (!profileRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Door zone profile not found")
        }
        profileRepository.deleteById(id)
    }

    fun listBindings(): List<CameraProfileBindingResponse> =
        bindingRepository.findAll()
            .sortedWith(
                compareByDescending<CameraProfileBindingEntity> { it.logicalDoor == DoorLogicalState.NEEDS_CLASSIFICATION.value }
                    .thenBy { it.recorderId }
                    .thenBy { it.cameraCode }
                    .thenByDescending { it.createdAtMs },
            )
            .map(::toBindingResponse)

    @Transactional
    fun bindCamera(request: CameraProfileBindingRequest): CameraProfileBindingResponse {
        val now = System.currentTimeMillis()
        val recorderId = cleanedRequired(request.recorderId, "recorderId")
        val cameraCode = cleanedRequired(request.cameraCode, "cameraCode")
        val logicalDoor = normalizedDoor(request.logicalDoor)
        val profileId = request.profileId?.trim()?.takeIf { it.isNotBlank() }
        if (logicalDoor !in setOf(DoorLogicalState.IGNORE.value, DoorLogicalState.NEEDS_CLASSIFICATION.value) &&
            profileId != null && !profileRepository.existsById(profileId)
        ) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Door zone profile not found")
        }

        bindingRepository.findByRecorderIdAndCameraCodeAndActiveTrue(recorderId, cameraCode)?.let {
            it.active = false
            it.updatedAtMs = now
            bindingRepository.save(it)
        }

        return toBindingResponse(
            bindingRepository.save(
                CameraProfileBindingEntity(
                    id = UUID.randomUUID().toString(),
                    recorderId = recorderId,
                    cameraCode = cameraCode,
                    logicalDoor = logicalDoor,
                    profileId = profileId,
                    active = true,
                    confirmedBy = request.confirmedBy?.trim()?.takeIf { it.isNotBlank() },
                    confirmedAtMs = now,
                    previewSourcePath = request.previewSourcePath?.trim()?.takeIf { it.isNotBlank() },
                    previewFrameIndex = request.previewFrameIndex,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            ),
        )
    }

    @Transactional
    fun deactivateBinding(id: String): CameraProfileBindingResponse {
        val entity = bindingRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Camera profile binding not found")
        }
        entity.active = false
        entity.updatedAtMs = System.currentTimeMillis()
        return toBindingResponse(bindingRepository.save(entity))
    }

    fun resolveFor(metadata: VideoMetadata): ResolvedDoorConfiguration {
        val recorderId = metadata.videoDeviceId
        val cameraCode = metadata.cameraCode
        val binding = if (!recorderId.isNullOrBlank() && !cameraCode.isNullOrBlank()) {
            bindingRepository.findByRecorderIdAndCameraCodeAndActiveTrue(recorderId, cameraCode)
        } else {
            null
        }
        if (binding?.logicalDoor == DoorLogicalState.NEEDS_CLASSIFICATION.value) {
            return defaultResolved(metadata, binding, ignored = false, classificationNeeded = true)
        }
        if (binding?.logicalDoor == DoorLogicalState.IGNORE.value) {
            return defaultResolved(metadata, binding, ignored = true)
        }

        val profile = binding?.profileId?.let { profileRepository.findById(it).orElse(null) }
        return if (profile != null) {
            resolvedFromProfile(metadata, binding, profile)
        } else {
            defaultResolved(metadata, binding, ignored = false)
        }
    }

    @Transactional
    fun registerUnclassifiedChannel(
        metadata: VideoMetadata,
        previewSourcePath: String,
        previewFrameIndex: Int = 0,
    ): CameraProfileBindingResponse? = registerVideoPreview(metadata, previewSourcePath, previewFrameIndex)

    @Transactional
    fun registerVideoPreview(
        metadata: VideoMetadata,
        previewSourcePath: String,
        previewFrameIndex: Int = 0,
    ): CameraProfileBindingResponse? {
        val recorderId = metadata.videoDeviceId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cameraCode = metadata.cameraCode?.trim()?.takeIf { it.isNotBlank() } ?: return null
        bindingRepository.findByRecorderIdAndCameraCodeAndActiveTrue(recorderId, cameraCode)?.let {
            it.previewSourcePath = previewSourcePath
            it.previewFrameIndex = previewFrameIndex
            it.updatedAtMs = System.currentTimeMillis()
            return toBindingResponse(bindingRepository.save(it))
        }

        val now = System.currentTimeMillis()
        return toBindingResponse(
            bindingRepository.save(
                CameraProfileBindingEntity(
                    id = UUID.randomUUID().toString(),
                    recorderId = recorderId,
                    cameraCode = cameraCode,
                    logicalDoor = DoorLogicalState.NEEDS_CLASSIFICATION.value,
                    profileId = null,
                    active = true,
                    confirmedBy = null,
                    confirmedAtMs = null,
                    previewSourcePath = previewSourcePath,
                    previewFrameIndex = previewFrameIndex,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            ),
        )
    }

    fun resolveResponse(recorderId: String?, cameraCode: String?): ResolvedDoorConfigResponse =
        resolveFor(VideoMetadata(videoDeviceId = recorderId, cameraCode = cameraCode)).toResponse()

    private fun resolvedFromProfile(
        metadata: VideoMetadata,
        binding: CameraProfileBindingEntity,
        profile: DoorZoneProfileEntity,
    ): ResolvedDoorConfiguration {
        val defaults = ftpProperties.analysis
        val insideOnTop = profile.insideOnTop ?: defaults.insideOnTop
        return ResolvedDoorConfiguration(
            recorderId = metadata.videoDeviceId,
            cameraCode = metadata.cameraCode,
            logicalDoor = binding.logicalDoor,
            ignored = false,
            classificationNeeded = false,
            profileId = profile.id,
            profileName = profile.name,
            source = "DATABASE",
            lineYRatio = profile.lineYRatio ?: defaults.lineYRatio,
            lineAxRatio = profile.lineAxRatio ?: defaults.lineAxRatio,
            lineAyRatio = profile.lineAyRatio ?: defaults.lineAyRatio,
            lineBxRatio = profile.lineBxRatio ?: defaults.lineBxRatio,
            lineByRatio = profile.lineByRatio ?: defaults.lineByRatio,
            salonPolygon = parsePolygon(profile.salonPolygonJson).ifEmpty { defaults.resolvedSalonPolygon() },
            streetPolygon = parsePolygon(profile.streetPolygonJson).ifEmpty { defaults.resolvedStreetPolygon() },
            doorPolygon = parsePolygon(profile.doorPolygonJson).ifEmpty { defaults.resolvedDoorPolygon() },
            salonSpawnPolygon = parsePolygon(profile.salonSpawnPolygonJson).ifEmpty { defaults.resolvedSalonSpawnPolygon() },
            insideOnTop = insideOnTop,
            insideOnPositiveSide = profile.insideOnPositiveSide ?: defaults.insideOnPositiveSide,
            autoInitialOnboard = profile.autoInitialOnboard ?: defaults.autoInitialOnboard,
            initialOnboard = profile.initialOnboard ?: defaults.initialOnboard,
        )
    }

    private fun defaultResolved(
        metadata: VideoMetadata,
        binding: CameraProfileBindingEntity?,
        ignored: Boolean,
        classificationNeeded: Boolean = false,
    ): ResolvedDoorConfiguration {
        val defaults = ftpProperties.analysis
        return ResolvedDoorConfiguration(
            recorderId = metadata.videoDeviceId,
            cameraCode = metadata.cameraCode,
            logicalDoor = binding?.logicalDoor,
            ignored = ignored,
            classificationNeeded = classificationNeeded,
            profileId = null,
            profileName = "application-default",
            source = if (binding == null) "APPLICATION_DEFAULT" else "BINDING_DEFAULT",
            lineYRatio = defaults.lineYRatio,
            lineAxRatio = defaults.lineAxRatio,
            lineAyRatio = defaults.lineAyRatio,
            lineBxRatio = defaults.lineBxRatio,
            lineByRatio = defaults.lineByRatio,
            salonPolygon = defaults.resolvedSalonPolygon(),
            streetPolygon = defaults.resolvedStreetPolygon(),
            doorPolygon = defaults.resolvedDoorPolygon(),
            salonSpawnPolygon = defaults.resolvedSalonSpawnPolygon(),
            insideOnTop = defaults.insideOnTop,
            insideOnPositiveSide = defaults.insideOnPositiveSide,
            autoInitialOnboard = defaults.autoInitialOnboard,
            initialOnboard = defaults.initialOnboard,
        )
    }

    private fun profile(id: String): DoorZoneProfileEntity =
        profileRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Door zone profile not found")
        }

    private fun DoorZoneProfileEntity.applyRequest(
        request: DoorZoneProfileRequest,
        cleanedName: String,
        now: Long,
    ): DoorZoneProfileEntity {
        name = cleanedName
        doorType = normalizedDoor(request.doorType)
        lineYRatio = request.lineYRatio
        lineAxRatio = request.lineAxRatio
        lineAyRatio = request.lineAyRatio
        lineBxRatio = request.lineBxRatio
        lineByRatio = request.lineByRatio
        salonPolygonJson = writePolygon(request.salonPolygon)
        streetPolygonJson = writePolygon(request.streetPolygon)
        doorPolygonJson = writePolygon(request.doorPolygon)
        salonSpawnPolygonJson = writePolygon(request.salonSpawnPolygon)
        insideOnTop = request.insideOnTop
        insideOnPositiveSide = request.insideOnPositiveSide
        autoInitialOnboard = request.autoInitialOnboard
        initialOnboard = request.initialOnboard
        updatedAtMs = now
        return this
    }

    private fun toProfileResponse(entity: DoorZoneProfileEntity): DoorZoneProfileResponse =
        DoorZoneProfileResponse(
            id = entity.id,
            name = entity.name,
            doorType = entity.doorType,
            lineYRatio = entity.lineYRatio,
            lineAxRatio = entity.lineAxRatio,
            lineAyRatio = entity.lineAyRatio,
            lineBxRatio = entity.lineBxRatio,
            lineByRatio = entity.lineByRatio,
            salonPolygon = parsePolygon(entity.salonPolygonJson).map { LinePointDto(it.x, it.y) },
            streetPolygon = parsePolygon(entity.streetPolygonJson).map { LinePointDto(it.x, it.y) },
            doorPolygon = parsePolygon(entity.doorPolygonJson).map { LinePointDto(it.x, it.y) },
            salonSpawnPolygon = parsePolygon(entity.salonSpawnPolygonJson).map { LinePointDto(it.x, it.y) },
            insideOnTop = entity.insideOnTop,
            insideOnPositiveSide = entity.insideOnPositiveSide,
            autoInitialOnboard = entity.autoInitialOnboard,
            initialOnboard = entity.initialOnboard,
            createdAtMs = entity.createdAtMs,
            updatedAtMs = entity.updatedAtMs,
        )

    private fun toBindingResponse(entity: CameraProfileBindingEntity): CameraProfileBindingResponse =
        CameraProfileBindingResponse(
            id = entity.id,
            recorderId = entity.recorderId,
            cameraCode = entity.cameraCode,
            logicalDoor = entity.logicalDoor,
            profileId = entity.profileId,
            profileName = entity.profileId?.let { profileRepository.findById(it).orElse(null)?.name },
            active = entity.active,
            confirmedBy = entity.confirmedBy,
            confirmedAtMs = entity.confirmedAtMs,
            previewSourcePath = entity.previewSourcePath,
            previewFrameIndex = entity.previewFrameIndex,
            createdAtMs = entity.createdAtMs,
            updatedAtMs = entity.updatedAtMs,
        )

    private fun ResolvedDoorConfiguration.toResponse(): ResolvedDoorConfigResponse =
        ResolvedDoorConfigResponse(
            recorderId = recorderId,
            cameraCode = cameraCode,
            logicalDoor = logicalDoor,
            ignored = ignored,
            classificationNeeded = classificationNeeded,
            profileId = profileId,
            profileName = profileName,
            source = source,
            lineYRatio = lineYRatio,
            lineAxRatio = lineAxRatio,
            lineAyRatio = lineAyRatio,
            lineBxRatio = lineBxRatio,
            lineByRatio = lineByRatio,
            salonPolygon = salonPolygon.map { LinePointDto(it.x, it.y) },
            streetPolygon = streetPolygon.map { LinePointDto(it.x, it.y) },
            doorPolygon = doorPolygon.map { LinePointDto(it.x, it.y) },
            salonSpawnPolygon = salonSpawnPolygon.map { LinePointDto(it.x, it.y) },
            insideOnTop = insideOnTop,
            insideOnPositiveSide = insideOnPositiveSide,
            autoInitialOnboard = autoInitialOnboard,
            initialOnboard = initialOnboard,
        )

    private fun writePolygon(points: List<LinePointDto>): String =
        objectMapper.writeValueAsString(points.map { NormalizedPoint(it.x, it.y).clamped() })

    private fun parsePolygon(json: String?): List<NormalizedPoint> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(json, object : TypeReference<List<NormalizedPoint>>() {})
                .map { it.clamped() }
        }.getOrDefault(emptyList())
    }

    private fun cleanedName(name: String): String =
        cleanedRequired(name, "name")

    private fun cleanedRequired(value: String, field: String): String =
        value.trim().takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must not be blank")

    private fun normalizedDoor(value: String): String {
        val normalized = value.trim().uppercase()
        return when (normalized) {
            "FRONT", "REAR", "CUSTOM", "IGNORE", "NEEDS_CLASSIFICATION" -> normalized
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported logical door: $value")
        }
    }

    private enum class DoorLogicalState(val value: String) {
        NEEDS_CLASSIFICATION("NEEDS_CLASSIFICATION"),
        IGNORE("IGNORE"),
    }
}
