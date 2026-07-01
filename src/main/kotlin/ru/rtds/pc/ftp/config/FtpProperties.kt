package ru.rtds.pc.ftp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import ru.rtds.pc.model.NormalizedPoint

@ConfigurationProperties(prefix = "ftp")
data class FtpProperties(
    val port: Int = 2021,
    val user: String = "admin",
    val password: String = "admin",
    val home: String = "./videos/incoming",
    val processedDir: String = "./videos/processed",
    val passive: Passive = Passive(),
    val analysis: Analysis = Analysis(),
    val keepAfterAnalysis: Boolean = true,
) {
    data class Passive(
        val ports: String = "30000-30010",
        val externalAddress: String = "",
    )

    data class Analysis(
        val lineYRatio: Float = 0.5f,
        val lineAxRatio: Float? = null,
        val lineAyRatio: Float? = null,
        val lineBxRatio: Float? = null,
        val lineByRatio: Float? = null,
        val salonPolygon: List<NormalizedPoint> = emptyList(),
        val streetPolygon: List<NormalizedPoint> = emptyList(),
        val doorPolygon: List<NormalizedPoint> = emptyList(),
        val insideOnTop: Boolean = false,
        val insideOnPositiveSide: Boolean? = null,
        val autoInitialOnboard: Boolean = true,
        val initialOnboard: Int = 0,
    ) {
        fun resolvedLineAx(): Float = lineAxRatio ?: 0f
        fun resolvedLineAy(): Float = lineAyRatio ?: lineYRatio
        fun resolvedLineBx(): Float = lineBxRatio ?: 1f
        fun resolvedLineBy(): Float = lineByRatio ?: lineYRatio
        fun resolvedInsideOnPositiveSide(): Boolean = insideOnPositiveSide ?: !insideOnTop
        fun resolvedSalonPolygon(): List<NormalizedPoint> = salonPolygon.map { it.clamped() }
        fun resolvedStreetPolygon(): List<NormalizedPoint> = streetPolygon.map { it.clamped() }
        fun resolvedDoorPolygon(): List<NormalizedPoint> = doorPolygon.map { it.clamped() }
        fun hasExplicitPolygons(): Boolean = resolvedSalonPolygon().size >= 3 && resolvedStreetPolygon().size >= 3
        fun hasExplicitDoorPolygon(): Boolean = resolvedDoorPolygon().size >= 3
    }
}
