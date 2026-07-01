package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.Detection
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.TrackedPerson
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max

@Service
class PersonTracker(
    private val reidService: ReidService,
    @Value("\${pc.reid-similarity-threshold}") private val reidThreshold: Float,
    @Value("\${pc.track-active-lost-frames:120}") private val maxFramesLostBeforeArchive: Int,
    @Value("\${pc.side-fallback-max-distance-px:1200}") private val sideFallbackMaxDistancePx: Float,
    @Value("\${pc.side-fallback-max-lost-frames:8}") private val sideFallbackMaxLostFrames: Int,
    @Value("\${pc.track-center-distance-threshold-px:90}") private val centerMatchThresholdPx: Float,
    @Value("\${pc.track-center-size-multiplier:1.8}") private val centerMatchSizeMultiplier: Float,
    @Value("\${pc.track-counted-iou-match-threshold:0.12}") private val countedIouMatchThreshold: Float,
    @Value("\${pc.track-counted-continuation-iou-threshold:0.25}") private val countedContinuationIouThreshold: Float,
    @Value("\${pc.track-counted-continuation-distance-px:70}") private val countedContinuationDistancePx: Float,
    @Value("\${pc.track-counted-continuation-lost-frames:2}") private val countedContinuationLostFrames: Int,
    @Value("\${pc.track-counted-reid-continuation-lost-frames:20}") private val countedReidContinuationLostFrames: Int,
    @Value("\${pc.track-counted-reid-continuation-distance-px:260}") private val countedReidContinuationDistancePx: Float,
    @Value("\${pc.track-counted-reid-similarity-threshold:0.78}") private val countedReidThreshold: Float,
    @Value("\${pc.count-anchor-x-ratio:0.5}") private val countAnchorXRatio: Float,
    @Value("\${pc.count-anchor-y-ratio:0.95}") private val countAnchorYRatio: Float,
    @Value("\${pc.reid-refresh-every-frames:4}") private val reidRefreshEveryFrames: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val iouMatchThreshold = 0.3f
    private val states = ConcurrentHashMap<String, TrackerState>()

    private data class TrackerState(
        val activeTracks: MutableList<TrackedPerson> = mutableListOf(),
        val lostTracks: MutableList<TrackedPerson> = mutableListOf(),
        var nextTrackId: Int = 1,
        var frameCounter: Int = 0,
    )

    private data class ActiveMatch(
        val track: TrackedPerson,
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
        val zone = lineY?.let {
            CountingZones.fromLegacyLine(
                lineAxRatio = 0f,
                lineAyRatio = (it / frame.height).coerceIn(0f, 1f),
                lineBxRatio = 1f,
                lineByRatio = (it / frame.height).coerceIn(0f, 1f),
                insideOnPositiveSide = !insideOnTop,
                frameWidth = frame.width,
                frameHeight = frame.height,
            )
        }
        return update(sessionId, detections, frame, zone)
    }

    fun update(
        sessionId: String,
        detections: List<Detection>,
        frame: BufferedImage,
        doorZone: CountingZones?,
    ): List<TrackedPerson> {
        val state = states.computeIfAbsent(sessionId) { TrackerState() }
        return synchronized(state) {
            updateState(sessionId, state, detections, frame, doorZone)
        }
    }

    private fun updateState(
        sessionId: String,
        state: TrackerState,
        detections: List<Detection>,
        frame: BufferedImage,
        doorZone: CountingZones?,
    ): List<TrackedPerson> {
        state.frameCounter++
        val unmatchedDets = detections.toMutableList()
        val activeMatches = findActiveMatches(state.activeTracks, detections)
        val matchedTracks = mutableListOf<TrackedPerson>()

        for (track in state.activeTracks) {
            val best = activeMatches[track]

            if (best != null) {
                matchedTracks += track
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
        val unmatchedEmbeddings = if (reidEnabled && unmatchedDets.isNotEmpty()) {
            reidService.extractBatch(frame, unmatchedDets.map { it.bodyOrSelf })
        } else {
            List(unmatchedDets.size) { null }
        }
        for ((detIndex, det) in unmatchedDets.withIndex()) {
            val embedding = unmatchedEmbeddings.getOrNull(detIndex)
            var resurrected = findCountedContinuation(state, embedding, det, doorZone)
                ?: findByReid(state, embedding, det, doorZone)

            // Position-only resurrection is a last resort: it re-attaches a lost track purely by
            // where the detection is, which is exactly what hands one person's id to the next
            // person who stops in the same spot at the door. Only allow it when we have no
            // appearance embedding to judge by. If ReID was available but disagreed (returned
            // null), we intentionally start a new id instead of inheriting a stranger's track.
            if (resurrected == null && doorZone != null && embedding == null) {
                resurrected = findByLineSideFallback(state, det, doorZone)
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
            val doRefresh = reidRefreshEveryFrames > 0 && state.frameCounter % reidRefreshEveryFrames == 0
            val tracksToRefresh = matchedTracks.filter { track ->
                track.framesSinceUpdate == 0 && (track.embedding == null || doRefresh)
            }
            if (tracksToRefresh.isNotEmpty()) {
                val freshEmbeddings = reidService.extractBatch(frame, tracksToRefresh.map { it.detection.bodyOrSelf })
                for ((trackIndex, track) in tracksToRefresh.withIndex()) {
                    val fresh = freshEmbeddings.getOrNull(trackIndex)
                    track.embedding = if (track.embedding == null) {
                        fresh
                    } else {
                        reidService.blend(track.embedding, fresh, 0.2f)
                    }
                }
            }
        }

        return state.activeTracks.toList()
    }

    private fun findActiveMatches(
        tracks: List<TrackedPerson>,
        detections: List<Detection>,
    ): Map<TrackedPerson, ActiveMatch> {
        val reusableTracks = tracks.filterNot { it.isAlighted }
        val countedVisibleTracks = tracks.filter { it.isAlighted && it.framesSinceUpdate == 0 }
        val countedCoastingTracks = tracks.filter {
            it.isAlighted && it.framesSinceUpdate in 1..countedContinuationLostFrames.coerceAtLeast(0)
        }
        val assignedTracks = mutableSetOf<TrackedPerson>()
        val assignedDets = mutableSetOf<Detection>()
        val matches = mutableMapOf<TrackedPerson, ActiveMatch>()

        val regularIouCandidates = reusableTracks.flatMap { track ->
            detections.mapNotNull { det ->
                val iou = det.bodyOrSelf.iou(track.detection.bodyOrSelf)
                if (iou >= iouMatchThreshold) {
                    ActiveMatch(track, det, iou, det.bodyOrSelf.centerDistanceTo(track.detection.bodyOrSelf), "iou")
                } else {
                    null
                }
            }
        }
        val countedIouCandidates = countedVisibleTracks.flatMap { track ->
            detections.mapNotNull { det ->
                val iou = det.bodyOrSelf.iou(track.detection.bodyOrSelf)
                if (iou >= countedIouMatchThreshold) {
                    ActiveMatch(track, det, iou, det.bodyOrSelf.centerDistanceTo(track.detection.bodyOrSelf), "counted-iou")
                } else {
                    null
                }
            }
        }
        val countedCoastingCandidates = countedCoastingTracks.flatMap { track ->
            detections.mapNotNull { det ->
                val iou = det.bodyOrSelf.iou(track.detection.bodyOrSelf)
                val distance = det.bodyOrSelf.centerDistanceTo(track.detection.bodyOrSelf)
                if (iou >= countedContinuationIouThreshold && distance <= countedContinuationDistanceLimit(track, det)) {
                    ActiveMatch(track, det, iou, distance, "counted-continuation-iou")
                } else {
                    null
                }
            }
        }
        val iouCandidates = (regularIouCandidates + countedIouCandidates + countedCoastingCandidates).sortedWith(
            compareByDescending<ActiveMatch> { it.iou }
                .thenBy { it.distance }
        )
        assignCandidates(iouCandidates, assignedTracks, assignedDets, matches)

        val centerCandidates = reusableTracks
            .asSequence()
            .filterNot { it in assignedTracks }
            .flatMap { track ->
                detections.asSequence()
                    .filterNot { it in assignedDets }
                    .map { det -> ActiveMatch(track, det, det.bodyOrSelf.iou(track.detection.bodyOrSelf), det.bodyOrSelf.centerDistanceTo(track.detection.bodyOrSelf), "center") }
                    .filter { it.distance <= centerThresholdFor(it.track, it.detection) }
            }
            .sortedWith(compareBy<ActiveMatch> { it.distance }.thenByDescending { it.iou })
            .toList()
        assignCandidates(centerCandidates, assignedTracks, assignedDets, matches)

        return matches
    }

    private fun assignCandidates(
        candidates: List<ActiveMatch>,
        assignedTracks: MutableSet<TrackedPerson>,
        assignedDets: MutableSet<Detection>,
        matches: MutableMap<TrackedPerson, ActiveMatch>,
    ) {
        for (candidate in candidates) {
            if (candidate.track in assignedTracks || candidate.detection in assignedDets) continue
            matches[candidate.track] = candidate
            assignedTracks += candidate.track
            assignedDets += candidate.detection
        }
    }

    private fun centerThresholdFor(track: TrackedPerson, det: Detection): Float {
        val trackSize = max(track.detection.bodyOrSelf.width, track.detection.bodyOrSelf.height)
        val detSize = max(det.bodyOrSelf.width, det.bodyOrSelf.height)
        val sizeBasedThreshold = max(trackSize, detSize) * centerMatchSizeMultiplier.coerceAtLeast(0.5f)
        return minOf(centerMatchThresholdPx, sizeBasedThreshold)
    }

    private fun findCountedContinuation(
        state: TrackerState,
        embedding: FloatArray?,
        det: Detection,
        doorZone: CountingZones?,
    ): TrackedPerson? {
        if (!isAtExitArea(det, doorZone)) return null

        val candidates = (state.lostTracks.asSequence() +
            state.activeTracks.asSequence().filter { it.framesSinceUpdate > 0 })
            .filter { it.isAlighted }
            .filter { it.framesSinceUpdate <= countedReidContinuationLostFrames.coerceAtLeast(countedContinuationLostFrames) }

        var best: TrackedPerson? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (track in candidates) {
            val distance = det.bodyOrSelf.centerDistanceTo(track.detection.bodyOrSelf)
            val distanceLimit = countedContinuationDistanceLimit(track, det)
            val iou = det.bodyOrSelf.iou(track.detection.bodyOrSelf)
            val spatialOk = track.framesSinceUpdate <= countedContinuationLostFrames.coerceAtLeast(0) &&
                iou >= countedContinuationIouThreshold &&
                distance <= distanceLimit
            val appearance = if (embedding != null && track.embedding != null) {
                reidService.similarity(embedding, track.embedding!!)
            } else {
                null
            }
            val appearanceOk = appearance != null &&
                track.framesSinceUpdate <= countedReidContinuationLostFrames.coerceAtLeast(0) &&
                appearance >= countedReidThreshold &&
                distance <= countedReidContinuationDistanceLimit()

            if (!spatialOk && !appearanceOk) continue

            val score = (appearance ?: 0f) + iou - distance / distanceLimit.coerceAtLeast(1f)
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }

        if (best != null) {
            log.debug(
                "Counted continuation: re-attached alighted track id={} (lostFrames={}, score={})",
                best.id,
                best.framesSinceUpdate,
                "%.3f".format(bestScore),
            )
        }
        return best
    }

    private fun findByReid(
        state: TrackerState,
        embedding: FloatArray?,
        det: Detection,
        doorZone: CountingZones?,
    ): TrackedPerson? {
        if (embedding == null) return null

        var bestSim = reidThreshold
        var bestLost: TrackedPerson? = null
        // Search both archived (lost) tracks AND active tracks that were not matched this frame
        // (coasting during a short occlusion). Previously only archived tracks were searched, so a
        // person occluded for a few frames and reappearing shifted got a brand-new id because ReID
        // never looked at their still-coasting track.
        val candidates = state.lostTracks.asSequence() +
            state.activeTracks.asSequence().filter { it.framesSinceUpdate > 0 }
        for (lost in candidates) {
            if (lost.isAlighted) continue
            if (!canResurrectBySide(lost, det, doorZone)) continue
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

    private fun canResurrectBySide(
        lost: TrackedPerson,
        det: Detection,
        doorZone: CountingZones?,
    ): Boolean {
        if (doorZone == null) return true
        val detSide = doorZone.zoneFor(det, countAnchorXRatio, countAnchorYRatio)
        val detAtDoor = detSide == DoorZoneSide.BUFFER || doorZone.inDoor(det, countAnchorXRatio, countAnchorYRatio)
        return when {
            lost.isBoarded && detSide == DoorZoneSide.OUTSIDE -> detAtDoor
            else -> true
        }
    }

    private fun isAtExitArea(det: Detection, doorZone: CountingZones?): Boolean {
        if (doorZone == null) return true
        val detSide = doorZone.zoneFor(det, countAnchorXRatio, countAnchorYRatio)
        return detSide == DoorZoneSide.OUTSIDE ||
            detSide == DoorZoneSide.BUFFER ||
            doorZone.inDoor(det, countAnchorXRatio, countAnchorYRatio)
    }

    private fun findByLineSideFallback(
        state: TrackerState,
        det: Detection,
        doorZone: CountingZones,
    ): TrackedPerson? {
        val detSide = doorZone.zoneFor(det, countAnchorXRatio, countAnchorYRatio)
        val detIsInside = detSide != DoorZoneSide.OUTSIDE
        val detAtDoor = detSide == DoorZoneSide.BUFFER || doorZone.inDoor(det, countAnchorXRatio, countAnchorYRatio)
        val detAnchorX = det.anchorX(countAnchorXRatio)
        val detAnchorY = det.anchorY(countAnchorYRatio)
        val candidatesPool = state.lostTracks + state.activeTracks.filter { it.framesSinceUpdate > 0 }
        val candidates = candidatesPool.filterNot { it.isAlighted }.filter { lost ->
            if (lost.framesSinceUpdate > sideFallbackMaxLostFrames.coerceAtLeast(1)) {
                return@filter false
            }
            when {
                lost.isBoarded && !detIsInside && detAtDoor -> true
                detAtDoor && lost.lastSeenZone == DoorZoneSide.BUFFER -> true
                else -> false
            }
        }

        val best = candidates
            .map { lost ->
                val dx = lost.detection.anchorX(countAnchorXRatio) - detAnchorX
                val dy = lost.detection.anchorY(countAnchorYRatio) - detAnchorY
                lost to hypot(dx, dy)
            }
            .filter { (lost, distance) -> distance <= sideFallbackDistanceLimit(lost, det) }
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

    private fun sideFallbackDistanceLimit(track: TrackedPerson, det: Detection): Float =
        minOf(sideFallbackMaxDistancePx, centerThresholdFor(track, det) * 2f)

    private fun countedContinuationDistanceLimit(track: TrackedPerson, det: Detection): Float =
        minOf(countedContinuationDistancePx, centerThresholdFor(track, det) * 1.5f)

    private fun countedReidContinuationDistanceLimit(): Float =
        countedReidContinuationDistancePx.coerceAtLeast(countedContinuationDistancePx)

    fun getAllTracks(): List<TrackedPerson> =
        states.values.flatMap { state -> synchronized(state) { state.activeTracks + state.lostTracks } }

    companion object {
        private const val DEFAULT_SESSION_ID = "__default__"
    }
}
