package ru.rtds.pc.persistence.config

import org.springframework.data.jpa.repository.JpaRepository

interface DoorZoneProfileRepository : JpaRepository<DoorZoneProfileEntity, String> {
    fun findByNameIgnoreCase(name: String): DoorZoneProfileEntity?
}
