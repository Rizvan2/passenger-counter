package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.rtds.pc.model.AnalysisSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class SessionManager {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, AnalysisSession>()

    fun create(
        videoPath: String,
        lineYRatio: Float,
        insideOnTop: Boolean,
        autoInitialOnboard: Boolean,
        initialOnboard: Int,
    ): AnalysisSession {
        val id = UUID.randomUUID().toString()
        val s = AnalysisSession(
            id = id,
            sourcePath = videoPath,
            lineYRatio = lineYRatio,
            insideOnTop = insideOnTop,
            autoInitialOnboard = autoInitialOnboard,
            initialOnboard = initialOnboard
        )
        sessions[id] = s
        log.info("Created session {} for {}", id, videoPath)
        return s
    }
    fun get(id: String): AnalysisSession? = sessions[id]

    fun stop(id: String) {
        sessions[id]?.stopRequested = true
        log.info("Stop requested for session {}", id)
    }
}
