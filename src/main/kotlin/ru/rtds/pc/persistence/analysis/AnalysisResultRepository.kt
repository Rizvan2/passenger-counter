package ru.rtds.pc.persistence.analysis

import org.springframework.data.jpa.repository.JpaRepository

interface AnalysisResultRepository : JpaRepository<AnalysisResultEntity, String> {
    fun existsBySourceHash(sourceHash: String): Boolean
}