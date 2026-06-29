package ru.rtds.pc.persistence.analysis

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AnalysisResultRepository : JpaRepository<AnalysisResultEntity, String> {
    fun existsBySourceHash(sourceHash: String): Boolean

    fun existsBySourceHashAndStatus(sourceHash: String, status: String): Boolean

    fun existsByOriginalRelativePathAndStatus(originalRelativePath: String, status: String): Boolean

    fun existsByVideoDeviceIdAndFileUidAndStatus(
        videoDeviceId: String,
        fileUid: Long,
        status: String,
    ): Boolean

    @Query(
        """
        SELECT r.sourceHash FROM AnalysisResultEntity r
        WHERE r.status = :status AND r.sourceHash IS NOT NULL
        """,
    )
    fun findSourceHashesByStatus(@Param("status") status: String): List<String>

    @Query(
        """
        SELECT r FROM AnalysisResultEntity r
        WHERE (:from IS NULL OR COALESCE(r.clipStartedAtMs, r.finishedAtMs) >= :from)
          AND (:to IS NULL OR COALESCE(r.clipStartedAtMs, r.finishedAtMs) <= :to)
          AND (:deviceId IS NULL OR r.videoDeviceId = :deviceId)
          AND (:source IS NULL OR r.source = :source)
          AND (:status IS NULL OR r.status = :status)
        """,
    )
    fun search(
        @Param("from") from: Long?,
        @Param("to") to: Long?,
        @Param("deviceId") deviceId: String?,
        @Param("source") source: String?,
        @Param("status") status: String?,
        pageable: Pageable,
    ): Page<AnalysisResultEntity>

    @Query(
        """
        SELECT r FROM AnalysisResultEntity r
        WHERE r.videoDeviceId = :videoDeviceId
          AND (:from IS NULL OR COALESCE(r.clipStartedAtMs, r.finishedAtMs) >= :from)
          AND (:to IS NULL OR COALESCE(r.clipStartedAtMs, r.finishedAtMs) <= :to)
        ORDER BY COALESCE(r.clipStartedAtMs, r.finishedAtMs) DESC
        """,
    )
    fun findByVideoDeviceIdAndPeriod(
        @Param("videoDeviceId") videoDeviceId: String,
        @Param("from") from: Long?,
        @Param("to") to: Long?,
    ): List<AnalysisResultEntity>
}
