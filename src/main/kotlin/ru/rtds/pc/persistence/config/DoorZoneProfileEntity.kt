package ru.rtds.pc.persistence.config

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "door_zone_profiles",
    indexes = [
        Index(name = "idx_door_zone_profiles_name", columnList = "name", unique = true),
    ],
)
class DoorZoneProfileEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "name", nullable = false, length = 128)
    var name: String = "",

    @Column(name = "door_type", nullable = false, length = 32)
    var doorType: String = "CUSTOM",

    @Column(name = "line_y_ratio")
    var lineYRatio: Float? = null,

    @Column(name = "line_ax_ratio")
    var lineAxRatio: Float? = null,

    @Column(name = "line_ay_ratio")
    var lineAyRatio: Float? = null,

    @Column(name = "line_bx_ratio")
    var lineBxRatio: Float? = null,

    @Column(name = "line_by_ratio")
    var lineByRatio: Float? = null,

    @Column(name = "salon_polygon_json", columnDefinition = "text")
    var salonPolygonJson: String? = null,

    @Column(name = "street_polygon_json", columnDefinition = "text")
    var streetPolygonJson: String? = null,

    @Column(name = "door_polygon_json", columnDefinition = "text")
    var doorPolygonJson: String? = null,

    @Column(name = "salon_spawn_polygon_json", columnDefinition = "text")
    var salonSpawnPolygonJson: String? = null,

    @Column(name = "inside_on_top")
    var insideOnTop: Boolean? = null,

    @Column(name = "inside_on_positive_side")
    var insideOnPositiveSide: Boolean? = null,

    @Column(name = "auto_initial_onboard")
    var autoInitialOnboard: Boolean? = null,

    @Column(name = "initial_onboard")
    var initialOnboard: Int? = null,

    @Column(name = "created_at_ms", nullable = false)
    var createdAtMs: Long = 0,

    @Column(name = "updated_at_ms", nullable = false)
    var updatedAtMs: Long = 0,
)
