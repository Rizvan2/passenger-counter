package ru.rtds.pc.persistence.config

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "camera_profile_bindings",
    indexes = [
        Index(name = "idx_camera_profile_binding_lookup", columnList = "recorder_id,camera_code,active"),
    ],
)
class CameraProfileBindingEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "recorder_id", nullable = false, length = 64)
    var recorderId: String = "",

    @Column(name = "camera_code", nullable = false, length = 64)
    var cameraCode: String = "",

    @Column(name = "logical_door", nullable = false, length = 32)
    var logicalDoor: String = "CUSTOM",

    @Column(name = "profile_id", length = 64)
    var profileId: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "confirmed_by", length = 128)
    var confirmedBy: String? = null,

    @Column(name = "confirmed_at_ms")
    var confirmedAtMs: Long? = null,

    @Column(name = "preview_source_path", columnDefinition = "text")
    var previewSourcePath: String? = null,

    @Column(name = "preview_frame_index")
    var previewFrameIndex: Int? = null,

    @Column(name = "created_at_ms", nullable = false)
    var createdAtMs: Long = 0,

    @Column(name = "updated_at_ms", nullable = false)
    var updatedAtMs: Long = 0,
)
