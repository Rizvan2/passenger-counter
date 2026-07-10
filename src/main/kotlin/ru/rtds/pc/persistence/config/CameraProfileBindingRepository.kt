package ru.rtds.pc.persistence.config

import org.springframework.data.jpa.repository.JpaRepository

interface CameraProfileBindingRepository : JpaRepository<CameraProfileBindingEntity, String> {
    fun findByRecorderIdAndCameraCodeAndActiveTrue(
        recorderId: String,
        cameraCode: String,
    ): CameraProfileBindingEntity?

    fun findByRecorderIdAndCameraCodeOrderByCreatedAtMsDesc(
        recorderId: String,
        cameraCode: String,
    ): List<CameraProfileBindingEntity>

    fun findByRecorderIdAndActiveTrueOrderByCameraCodeAscCreatedAtMsDesc(
        recorderId: String,
    ): List<CameraProfileBindingEntity>

    fun findByLogicalDoorAndActiveTrueOrderByUpdatedAtMsDesc(
        logicalDoor: String,
    ): List<CameraProfileBindingEntity>
}
