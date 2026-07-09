package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.rtds.pc.model.AnalysisSession
import ru.rtds.pc.model.NormalizedPoint
import ru.rtds.pc.model.VideoSource
import ru.rtds.pc.model.VideoMetadata
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class SessionManager {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, AnalysisSession>()

    fun create(
        videoPath: String,
        salonPolygon: List<NormalizedPoint>,
        streetPolygon: List<NormalizedPoint>,
        doorPolygon: List<NormalizedPoint>,
        salonSpawnPolygon: List<NormalizedPoint>,
        lineAxRatio: Float,
        lineAyRatio: Float,
        lineBxRatio: Float,
        lineByRatio: Float,
        insideOnPositiveSide: Boolean,
        autoInitialOnboard: Boolean,
        initialOnboard: Int,
        source: VideoSource = VideoSource.MANUAL,
        sourceHash: String? = null,
    ): AnalysisSession {
        val id = UUID.randomUUID().toString()
        val videoMetadata = VideoMetadata.fromPath(videoPath)
        val s = AnalysisSession(
            id                 = id,
            sourcePath         = videoPath,
            videoMetadata      = videoMetadata,
            salonPolygon       = salonPolygon,
            streetPolygon      = streetPolygon,
            doorPolygon        = doorPolygon,
            salonSpawnPolygon  = salonSpawnPolygon,
            lineAxRatio        = lineAxRatio,
            lineAyRatio        = lineAyRatio,
            lineBxRatio        = lineBxRatio,
            lineByRatio        = lineByRatio,
            insideOnPositiveSide = insideOnPositiveSide,
            autoInitialOnboard = autoInitialOnboard,
            initialOnboard     = initialOnboard,
            source             = source,
            sourceHash         = sourceHash,
        )
        sessions[id] = s
        log.info("Created session {} for {} (source={})", id, videoPath, source)
        return s
    }

    fun get(id: String): AnalysisSession? = sessions[id]

    fun stop(id: String) {
        sessions[id]?.stopRequested = true
        log.info("Stop requested for session {}", id)
    }
}
