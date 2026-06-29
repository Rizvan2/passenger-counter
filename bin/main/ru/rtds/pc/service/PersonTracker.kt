package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.Detection
import ru.rtds.pc.model.TrackedPerson
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Service
class PersonTracker(
    private val reidService: ReidService,
    @Value("\${pc.reid-similarity-threshold}") private val reidThreshold: Float,
    @Value("\${pc.track-active-lost-frames:120}") private val maxFramesLostBeforeArchive: Int,
    @Value("\${pc.side-fallback-max-distance-px:1200}") private val sideFallbackMaxDistancePx: Float,
    @Value("\${pc.track-center-distance-threshold-px:90}") private val centerMatchThresholdPx: Float,
    @Value("\${pc.count-anchor-y-ratio:0.55}") private val countAnchorYRatio: Float,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val iouMatchThreshold = 0.3f
    private val states = ConcurrentHashMap<String, TrackerState>()

    private data class TrackerState(
        val activeTracks: MutableList<TrackedPerson> = mutableListOf(),
        val lostTracks: MutableList<TrackedPerson> = mutableListOf(),
        var nextTrackId: Int = 1,
    )

    private data class ActiveMatch(
        val detection: Detection,
        val iou: Float,
        val distance: Float,
        val method: String,
    )

    fun reset() {
        states.clear()
    }

    fun reset(sessionId: String) {
        states[sessionId] = TrackerState()
    }

    fun clear(sessionId: String) {
        states.remove(sessionId)
    }

    fun update(detections: List<Detection>, frame: BufferedImage): List<TrackedPerson> {
        return update(DEFAULT_SESSION_ID, detections, frame, lineY = null)
    }

    fun update(
        detections: List<Detection>,
        frame: BufferedImage,
        lineY: Float?,
        insideOnTop: Boolean = true,
    ): List<TrackedPerson> {
        return update(DEFAULT_SESSION_ID, detections, frame, lineY, insideOnTop)
    }

    fun update(
        sessionId: String,
        detections: List<Detection>,
        frame: BufferedImage,
        lineY: Float?,
        insideOnTop: Boolean = true,
    ): List<TrackedPerson> {
        val state = states.computeIfAbsent(sessionId) { TrackerState() }
        return synchronized(state) {
            updateState(sessionId, state, detections, frame, lineY, insideOnTop)
        }
    }

    private fun updateState(
        sessionId: String,
        state: TrackerState,
        detections: List<Detection>,
        frame: BufferedImage,
        lineY: Float?,
        insideOnTop: Boolean,
    ): List<TrackedPerson> {
        val unmatchedDets = detections.toMutableList()

        for (track in state.activeTracks) {
            val best = findActiveMatch(track, unmatchedDets)

            if (best != null) {
                if (log.isDebugEnabled) {
                    log.debug(
                        "Tracker active match: session={}, track={}, method={}, iou={}, distance={}, detCenterY={}, previousCenterY={}",
                        sessionId,
                        track.id,
                        best.method,
                        "%.3f".format(best.iou),
                        "%.1f".format(best.distance),
                        "%.1f".format(best.detection.centerY),
                        "%.1f".format(track.detection.centerY),
                    )
                }
                track.detection = best.detection
                track.framesSinceUpdate = 0
                unmatchedDets.remove(best.detection)
            } else {
                track.framesSinceUpdate++
            }
        }

        val toArchive = state.activeTracks.filter { it.framesSinceUpdate > maxFramesLostBeforeArchive }
        if (toArchive.isNotEmpty()) {
            state.activeTracks.removeAll(toArchive)
            state.lostTracks.addAll(toArchive)
            log.debug("Archived {} tracks for session {} (now lost: {})", toArchive.size, sessionId, state.lostTracks.size)
        }

        val reidEnabled = reidService.isAvailable()
        for (det in unmatchedDets) {
            val embedding = if (reidEnabled) reidService.extract(frame, det) else null
            var resurrected = findByReid(state, embedding)

            if (resurrected == null && lineY != null) {
                resurrected = findByLineSideFallback(state, det, lineY, insideOnTop)
            }

            if (resurrected != null) {
                state.lostTracks.remove(resurrected)
                state.activeTracks.remove(resurrected)
                resurrected.detection = det
                resurrected.framesSinceUpdate = 0
                resurrected.embedding = embedding ?: resurrected.embedding
                state.activeTracks.add(resurrected)
                log.debug(
                    "Tracker resurrected: session={}, track={}, centerY={}, active={}, lost={}",
                    sessionId,
                    resurrected.id,
                    "%.1f".format(det.centerY),
                    state.activeTracks.size,
                    state.lostTracks.size,
                )
            } else {
                val track = TrackedPerson(
                    id = state.nextTrackId++,
                    detection = det,
                    embedding = embedding,
                )
                state.activeTracks.add(track)
                log.debug(
                    "Tracker created: session={}, track={}, conf={}, box=[{},{},{},{}], centerY={}, active={}",
                    sessionId,
                    track.id,
                    "%.3f".format(det.confidence),
                    "%.1f".format(det.x1),
                    "%.1f".format(det.y1),
                    "%.1f".format(det.x2),
                    "%.1f".format(det.y2),
                    "%.1f".format(det.centerY),
                    state.activeTracks.size,
                )
            }
        }

        if (reidEnabled) {
            for (track in state.activeTracks) {
                if (track.embedding == null) {
                    track.embedding = reidService.extract(frame, track.detection)
                }
            }
        }

        return state.activeTracks.toList()
    }

    private fun findActiveMatch(track: TrackedPerson, unmatchedDets: List<Detection>): ActiveMatch? {
        val byIou = unmatchedDets
            .map { det -> ActiveMatch(det, det.iou(track.detection), det.centerDistanceTo(track.detection), "iou") }
            .filter { it.iou >= iouMatchThreshold }
            .maxByOrNull { it.iou }

        if (byIou != null) return byIou

        return unmatchedDets
            .map { det -> ActiveMatch(det, det.iou(track.detection), det.centerDistanceTo(track.detection), "center") }
            .filter { it.distance <= centerMatchThresholdPx }
            .minByOrNull { it.distance }
    }

    private fun findByReid(state: TrackerState, embedding: FloatArray?): TrackedPerson? {
        if (embedding == null) return null

        var bestSim = reidThreshold
        var bestLost: TrackedPerson? = null
        for (lost in state.lostTracks) {
            val lostEmb = lost.embedding ?: continue
            val sim = reidService.similarity(embedding, lostEmb)
            if (sim > bestSim) {
                bestSim = sim
                bestLost = lost
            }
        }

        if (bestLost != null) {
            log.debug("ReID: resurrected track id={} (sim={})", bestLost.id, bestSim)
        }
        return bestLost
    }

    private fun findByLineSideFallback(
        state: TrackerState,
        det: Detection,
        lineY: Float,
        insideOnTop: Boolean,
    ): TrackedPerson? {
        val detAnchorY = det.anchorY(countAnchorYRatio)
        val detIsInside = if (insideOnTop) detAnchorY < lineY else detAnchorY > lineY
        val candidatesPool = state.lostTracks + state.activeTracks.filter { it.framesSinceUpdate > 0 }
        val candidates = candidatesPool.filter { lost ->
            when {
                lost.isAlighted && detIsInside -> true
                lost.isBoarded && !detIsInside -> true
                else -> false
            }
        }

        val best = candidates
            .map { lost -> lost to abs(lost.detection.anchorY(countAnchorYRatio) - detAnchorY) }
            .filter { it.second <= sideFallbackMaxDistancePx }
            .minByOrNull { it.second }
            ?.first

        if (best != null) {
            log.debug(
                "Side fallback: resurrected track id={} (detSide={}, wasBoarded={}, wasAlighted={})",
                best.id,
                if (detIsInside) "INSIDE" else "OUTSIDE",
                best.isBoarded,
                best.isAlighted,
            )
        }

        return best
    }

    fun getAllTracks(): List<TrackedPerson> =
        states.values.flatMap { state -> synchronized(state) { state.activeTracks + state.lostTracks } }

    companion object {
        private const val DEFAULT_SESSION_ID = "__default__"
    }
}
