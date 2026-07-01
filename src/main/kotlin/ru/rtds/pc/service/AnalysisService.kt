package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import ru.rtds.pc.dto.BoxDto
import ru.rtds.pc.dto.FrameUpdateDto
import ru.rtds.pc.dto.PassengerEventDto
import ru.rtds.pc.dto.SessionFinishedDto
import ru.rtds.pc.ftp.service.UploadedVideoCleanupService
import ru.rtds.pc.model.AnalysisSession
import ru.rtds.pc.model.Detection
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.SessionStatus
import ru.rtds.pc.model.TrackCountState
import ru.rtds.pc.model.TrackTrajectoryStore
import ru.rtds.pc.model.TrackedPerson
import ru.rtds.pc.model.TrajectorySample
import kotlin.math.hypot
import ru.rtds.pc.persistence.analysis.AnalysisResultPersistenceService
import ru.rtds.pc.websocket.AnalysisWebSocketHandler
import java.io.File

@Service
class AnalysisService(
    private val frameReader: VideoFrameReader,
    private val detectors: List<FrameDetector>,
    private val tracker: PersonTracker,
    private val crossingCounter: CrossingLineCounter,
    private val trackFateClassifier: TrackFateClassifier,
    private val wsHandler: AnalysisWebSocketHandler,
    private val analysisResultPersistenceService: AnalysisResultPersistenceService,
    private val uploadedVideoCleanupService: UploadedVideoCleanupService,
    @Value("\${pc.process-every-n-frames}") private val processEveryN: Int,
    @Value("\${pc.detector:person}") private val detectorMode: String,
    @Value("\${pc.emit-frame-every-ms}") private val emitEveryMs: Long,
    @Value("\${pc.jpeg-quality}") private val jpegQuality: Float,
    @Value("\${pc.analysis-log-every-n-frames:30}") private val analysisLogEveryNFrames: Int,
    @Value("\${pc.count-anchor-x-ratio:0.5}") private val countAnchorXRatio: Float,
    @Value("\${pc.count-anchor-y-ratio:0.95}") private val countAnchorYRatio: Float,
    @Value("\${pc.capacity:120}") private val capacity: Int,
    @Value("\${pc.count-mode:offline}") private val countMode: String,
    @Value("\${pc.count-duplicate-alighting-hold-frames:18}") private val duplicateAlightingHoldFrames: Int,
    @Value("\${pc.count-duplicate-alighting-distance-px:85}") private val duplicateAlightingDistancePx: Float,
    @Value("\${pc.count-duplicate-alighting-reid-hold-frames:60}") private val duplicateAlightingReidHoldFrames: Int,
    @Value("\${pc.count-duplicate-alighting-reid-similarity-threshold:0.82}") private val duplicateAlightingReidSimilarityThreshold: Float,
    @Value("\${pc.head-tracking.enabled:true}") private val headTrackingEnabled: Boolean,
    @Value("\${pc.head-tracking.height-ratio:0.28}") private val headTrackingHeightRatio: Float,
    @Value("\${pc.head-tracking.width-ratio:0.65}") private val headTrackingWidthRatio: Float,
    @Value("\${pc.head-tracking.min-size-px:12}") private val headTrackingMinSizePx: Float,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val initialOnboardWarmupFrames = 25

    private data class AlightingLock(
        val frameIndex: Int,
        val anchorX: Float,
        val anchorY: Float,
        val headSize: Float,
        val embedding: FloatArray?,
    )

    @Async("analysisExecutor")
    fun startAsync(session: AnalysisSession) {
        try {
            runInternal(session)
        } catch (e: Exception) {
            log.error("Analysis failed for session {}: {}", session.id, e.message, e)
            session.status = SessionStatus.FAILED
            session.errorMessage = e.message
        } finally {
            session.finishedAt = System.currentTimeMillis()
            sendFinished(session)
            analysisResultPersistenceService.save(session)
            uploadedVideoCleanupService.afterAnalysis(session.sourcePath, session.status)
            wsHandler.close(session.id)
            tracker.clear(session.id)
        }
    }

    private fun runInternal(session: AnalysisSession) {
        val file = File(session.sourcePath)
        if (!file.exists() || !file.canRead()) {
            throw IllegalArgumentException("Video file not found or not readable: ${session.sourcePath}")
        }

        tracker.reset(session.id)
        val detector = selectedDetector()
        var lastEmittedMs = 0L
        val pendingEvents = mutableListOf<PassengerCountEvent>()
        val trajectories = TrackTrajectoryStore()
        val alightingLocks = mutableListOf<AlightingLock>()
        val suppressedAlightingTrackIds = mutableSetOf<Int>()
        log.info(
            "Analysis started: session={}, file='{}', processEveryN={}, salonPoints={}, streetPoints={}, doorPoints={}",
            session.id,
            session.sourcePath,
            processEveryN,
            session.salonPolygon.size,
            session.streetPolygon.size,
            session.doorPolygon.size,
        )

        val ok = frameReader.process(session.sourcePath, skipFrames = processEveryN - 1) { frameIdx, img, w, h ->
            if (session.stopRequested) {
                session.status = SessionStatus.STOPPED
                return@process false
            }

            val input = frameReader.preprocess(img, detector.inputSize)
            val detections = trackingDetections(detector.detect(input, w, h), detector, w, h)
            val countingZones = buildZones(session, w, h)
            updateInitialOnboardEstimate(session, detections, countingZones)
            val tracks = tracker.update(session.id, detections, img, countingZones)
            expireAlightingLocks(alightingLocks, frameIdx)
            logFrameDiagnostics(session, frameIdx, w, h, detections, tracks, countingZones)

            // Pass 1: record the trajectory of every visible track. Coasting tracks carry stale
            // boxes, so only genuinely-seen samples are kept — the offline classifier reconciles
            // the final counts once the whole clip is known.
            for (track in tracks) {
                if (track.framesSinceUpdate != 0) continue
                val ax = track.detection.anchorX(countAnchorXRatio)
                val ay = track.detection.anchorY(countAnchorYRatio)
                trajectories.record(
                    track.id,
                    TrajectorySample(
                        frameIndex = frameIdx,
                        zone = countingZones.zoneFor(ax, ay),
                        inDoor = countingZones.inDoor(ax, ay),
                        anchorX = ax,
                        anchorY = ay,
                        headSize = hypot(track.detection.width, track.detection.height),
                    ),
                )
            }

            for (track in tracks) {
                val delta = crossingCounter.updateTrackState(
                    track,
                    countingZones,
                    frameIdx,
                    allowPreboardedExit = true,
                )
                if (delta.boardings != 0) session.totalBoardings.addAndGet(delta.boardings)
                val alightingDelta = reconcileAlightingDelta(
                    track = track,
                    delta = delta,
                    frameIdx = frameIdx,
                    alightingLocks = alightingLocks,
                    suppressedTrackIds = suppressedAlightingTrackIds,
                )
                if (alightingDelta != 0) session.totalAlightings.addAndGet(alightingDelta)
                delta.event?.let {
                    if (delta.alightings != alightingDelta) {
                        return@let
                    }
                    log.info(
                        "Passenger event: session={}, frame={}, track={}, direction={}, from={}, to={}, totals(boardings={}, alightings={})",
                        session.id,
                        frameIdx,
                        it.trackId,
                        it.direction,
                        it.from,
                        it.to,
                        session.totalBoardings.get(),
                        session.totalAlightings.get(),
                    )
                    pendingEvents.add(it)
                }
            }
            val rawOnboard = session.initialOnboard + session.totalBoardings.get() - session.totalAlightings.get()
            session.currentOnboard = rawOnboard.coerceIn(0, capacity)
            if (session.currentOnboard != rawOnboard) {
                log.warn(
                    "Onboard balance clamped for session {}: raw={}, clamped={}, capacity={}",
                    session.id,
                    rawOnboard,
                    session.currentOnboard,
                    capacity,
                )
            }

            session.framesProcessed++

            val now = System.currentTimeMillis()
            if (now - lastEmittedMs >= emitEveryMs) {
                emit(session, frameIdx, img, w, h, tracks, countingZones, pendingEvents.toList())
                pendingEvents.clear()
                lastEmittedMs = now
            }
            true
        }

        if (session.status == SessionStatus.RUNNING) {
            session.status = if (ok) SessionStatus.FINISHED else SessionStatus.FAILED
        }

        // Pass 2: offline classification keeps full-trajectory boardings, while alightings prefer
        // the streaming counter because it suppresses short-lived duplicate track fragments.
        if (countMode.equals("offline", ignoreCase = true)) {
            val streamingBoardings = session.totalBoardings.get()
            val streamingAlightings = session.totalAlightings.get()
            val offline = trackFateClassifier.classify(trajectories.trajectories())
            val reconciledBoardings = offline.boardings
            val reconciledAlightings = if (streamingAlightings > 0) streamingAlightings else offline.alightings
            session.totalBoardings.set(reconciledBoardings)
            session.totalAlightings.set(reconciledAlightings)
            val rawOnboard = session.initialOnboard + reconciledBoardings - reconciledAlightings
            session.currentOnboard = rawOnboard.coerceIn(0, capacity)
            log.info(
                "Offline reconciliation: session={}, tracks={}, streaming(b={}, a={}) -> offline(b={}, a={}) -> final(b={}, a={}), fates={}",
                session.id,
                trajectories.size,
                streamingBoardings,
                streamingAlightings,
                offline.boardings,
                offline.alightings,
                reconciledBoardings,
                reconciledAlightings,
                offline.fateCounts,
            )
        }

        log.info(
            "Analysis finished: session={}, status={}, framesProcessed={}, boardings={}, alightings={}, onboard={}",
            session.id,
            session.status,
            session.framesProcessed,
            session.totalBoardings.get(),
            session.totalAlightings.get(),
            session.currentOnboard,
        )
    }

    private fun reconcileAlightingDelta(
        track: TrackedPerson,
        delta: CrossingLineCounter.CountDelta,
        frameIdx: Int,
        alightingLocks: MutableList<AlightingLock>,
        suppressedTrackIds: MutableSet<Int>,
    ): Int {
        if (delta.alightings < 0 && suppressedTrackIds.remove(track.id)) {
            log.debug("Ignored suppressed alighting cancellation: track={}", track.id)
            return 0
        }
        if (delta.alightings <= 0) return delta.alightings

        val anchorX = track.detection.anchorX(countAnchorXRatio)
        val anchorY = track.detection.anchorY(countAnchorYRatio)
        val headSize = hypot(track.detection.width, track.detection.height)
        expireAlightingLocks(alightingLocks, frameIdx)

        val duplicate = alightingLocks.any {
            isDuplicateAlighting(anchorX, anchorY, headSize, track.embedding, frameIdx, it)
        }
        if (duplicate) {
            suppressedTrackIds += track.id
            suppressDuplicateAlightingState(track)
            log.debug("Suppressed duplicate alighting fragment: track={}, frame={}", track.id, frameIdx)
            return 0
        }

        alightingLocks += AlightingLock(frameIdx, anchorX, anchorY, headSize, track.embedding?.copyOf())
        return delta.alightings
    }

    private fun suppressDuplicateAlightingState(track: TrackedPerson) {
        track.isAlighted = false
        track.countedDirection = null
        track.countState = TrackCountState.OUTSIDE
        if (track.crossingCount > 0) {
            track.crossingCount--
        }
    }

    private fun expireAlightingLocks(alightingLocks: MutableList<AlightingLock>, frameIdx: Int) {
        val holdFrames = maxOf(
            duplicateAlightingHoldFrames,
            duplicateAlightingReidHoldFrames,
            processEveryN.coerceAtLeast(1),
        )
        alightingLocks.removeAll { frameIdx - it.frameIndex > holdFrames }
    }

    private fun isDuplicateAlighting(
        anchorX: Float,
        anchorY: Float,
        headSize: Float,
        embedding: FloatArray?,
        frameIdx: Int,
        lock: AlightingLock,
    ): Boolean {
        val distance = hypot(anchorX - lock.anchorX, anchorY - lock.anchorY)
        val age = frameIdx - lock.frameIndex
        val sizeLimit = maxOf(headSize, lock.headSize) * 1.5f
        val geometryHold = duplicateAlightingHoldFrames.coerceAtLeast(processEveryN.coerceAtLeast(1))
        if (age <= geometryHold && distance <= minOf(duplicateAlightingDistancePx, sizeLimit)) {
            return true
        }

        if (age > duplicateAlightingReidHoldFrames.coerceAtLeast(0)) return false
        val lockEmbedding = lock.embedding ?: return false
        val currentEmbedding = embedding ?: return false
        val similarity = embeddingSimilarity(currentEmbedding, lockEmbedding)
        return similarity >= duplicateAlightingReidSimilarityThreshold
    }

    private fun embeddingSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun selectedDetector(): FrameDetector =
        detectors.firstOrNull { it.id.equals(detectorMode, ignoreCase = true) }
            ?: throw IllegalStateException(
                "Unknown detector '$detectorMode'. Available detectors: ${detectors.joinToString { it.id }}"
            )

    private fun trackingDetections(
        detections: List<Detection>,
        detector: FrameDetector,
        frameWidth: Int,
        frameHeight: Int,
    ): List<Detection> {
        if (!headTrackingEnabled || detector.id.equals("head", ignoreCase = true)) {
            return detections
        }
        return detections.map { person ->
            person.headRegion(
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                heightRatio = headTrackingHeightRatio,
                widthRatio = headTrackingWidthRatio,
                minSizePx = headTrackingMinSizePx,
            ).copy(body = person)
        }
    }

    private fun buildZones(session: AnalysisSession, frameWidth: Int, frameHeight: Int): CountingZones =
        CountingZones.fromNormalizedPolygons(
            salonPolygonRatio = session.salonPolygon,
            streetPolygonRatio = session.streetPolygon,
            doorPolygonRatio = session.doorPolygon,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )

    private fun logFrameDiagnostics(
        session: AnalysisSession,
        frameIdx: Int,
        width: Int,
        height: Int,
        detections: List<ru.rtds.pc.model.Detection>,
        tracks: List<ru.rtds.pc.model.TrackedPerson>,
        countingZones: CountingZones,
    ) {
        if (!log.isDebugEnabled) return
        val every = analysisLogEveryNFrames.coerceAtLeast(1)
        if (frameIdx % every != 0) return

        val detectionZones = detections
            .map { countingZones.zoneFor(it, countAnchorXRatio, countAnchorYRatio) }
            .groupingBy { it }
            .eachCount()
        val detectionsInDoor = detections.count { countingZones.inDoor(it, countAnchorXRatio, countAnchorYRatio) }
        val trackSummary = tracks.take(12).joinToString(separator = "; ") {
            "#${it.id}:${it.countState}/${it.stableZone} door=${it.isInDoor} lost=${it.framesSinceUpdate}"
        }
        log.debug(
            "Frame diagnostics: session={}, frame={}, size={}x{}, salonPoints={}, streetPoints={}, doorPoints={}, anchorRatio=({},{}), detections={} zones={}, detectionsInDoor={}, tracks={} [{}]",
            session.id,
            frameIdx,
            width,
            height,
            countingZones.salonPolygonPx.size,
            countingZones.streetPolygonPx.size,
            countingZones.doorPolygonPx.size,
            "%.2f".format(countAnchorXRatio),
            "%.2f".format(countAnchorYRatio),
            detections.size,
            detectionZones,
            detectionsInDoor,
            tracks.size,
            trackSummary,
        )
    }

    private fun updateInitialOnboardEstimate(
        session: AnalysisSession,
        detections: List<ru.rtds.pc.model.Detection>,
        countingZones: CountingZones,
    ) {
        val sides = detections.map { countingZones.zoneFor(it, countAnchorXRatio, countAnchorYRatio) }
        val doorwayCount = detections.count { countingZones.inDoor(it, countAnchorXRatio, countAnchorYRatio) }
        val insideCount = sides.count { it == DoorZoneSide.INSIDE }
        val bufferCount = sides.count { it == DoorZoneSide.BUFFER }
        val outsideCount = sides.count { it == DoorZoneSide.OUTSIDE }

        session.visibleDetections = detections.size
        session.insideDetections = insideCount
        session.bufferDetections = bufferCount
        session.doorwayDetections = doorwayCount
        session.outsideDetections = outsideCount

        if (session.initialOnboardDetected) return

        if (insideCount == 0 && detections.isNotEmpty() && session.initialOnboardFrames == 0) {
            log.warn(
                "Initial onboard: no detections in SALON polygon for session {} (visible={})",
                session.id,
                detections.size,
            )
        }

        val candidate = insideCount
        if (candidate > session.initialOnboardCandidate) {
            session.initialOnboardCandidate = candidate
            session.initialOnboard = candidate
            log.info(
                "Initial onboard estimate updated for session {}: {} (inside={}, visible={})",
                session.id,
                candidate,
                insideCount,
                detections.size,
            )
        }

        if (detections.isNotEmpty()) {
            session.initialOnboardFrames++
        }

        if (session.initialOnboardCandidate > 0) {
            session.initialOnboard = session.initialOnboardCandidate
        }

        if (session.initialOnboardFrames >= initialOnboardWarmupFrames) {
            session.initialOnboardDetected = true
            log.info("Initial onboard fixed for session {}: {}", session.id, session.initialOnboard)
        }
    }

    private fun emit(
        session: AnalysisSession,
        frameIdx: Int,
        img: java.awt.image.BufferedImage,
        w: Int,
        h: Int,
        tracks: List<ru.rtds.pc.model.TrackedPerson>,
        countingZones: CountingZones,
        events: List<PassengerCountEvent>,
    ) {
        val jpeg = frameReader.encodeJpegBase64(img, jpegQuality)
        val visibleTracks = tracks.filter { it.framesSinceUpdate == 0 }
        val boxes = visibleTracks.map {
            BoxDto(
                trackId = it.id,
                x1 = it.detection.x1,
                y1 = it.detection.y1,
                x2 = it.detection.x2,
                y2 = it.detection.y2,
                anchorX = it.detection.anchorX(countAnchorXRatio),
                anchorY = it.detection.anchorY(countAnchorYRatio),
                confidence = it.detection.confidence,
                isBoarded = it.isBoarded,
                isAlighted = it.isAlighted,
                inDoor = it.isInDoor,
                zone = it.stableZone.name,
                state = it.countState.name,
            )
        }
        val eventDtos = events.map {
            PassengerEventDto(
                trackId = it.trackId,
                direction = it.direction.name,
                frameIndex = it.frameIndex,
                from = it.from.name,
                to = it.to.name,
            )
        }
        val elapsedSec = ((System.currentTimeMillis() - session.startedAt) / 1000f).coerceAtLeast(0.001f)
        val fps = session.framesProcessed / elapsedSec

        wsHandler.send(
            session.id,
            FrameUpdateDto(
                frameIndex = frameIdx,
                frameJpegBase64 = jpeg,
                width = w,
                height = h,
                detections = boxes,
                salonPolygon = countingZones.salonPolygonPx,
                streetPolygon = countingZones.streetPolygonPx,
                doorPolygon = countingZones.doorPolygonPx,
                lineY = session.lineYRatio * h,
                doorTopY = session.lineYRatio * h,
                doorBottomY = session.lineYRatio * h,
                insideOnTop = session.insideOnTop,
                lineAx = session.lineAxRatio * w,
                lineAy = session.lineAyRatio * h,
                lineBx = session.lineBxRatio * w,
                lineBy = session.lineByRatio * h,
                lineAxRatio = session.lineAxRatio,
                lineAyRatio = session.lineAyRatio,
                lineBxRatio = session.lineBxRatio,
                lineByRatio = session.lineByRatio,
                insideOnPositiveSide = session.insideOnPositiveSide,
                doorCorridor = emptyList(),
                events = eventDtos,
                boardings = session.totalBoardings.get(),
                alightings = session.totalAlightings.get(),
                initialOnboard = session.initialOnboard,
                initialOnboardLocked = session.initialOnboardDetected,
                onboard = session.currentOnboard,
                visibleDetections = session.visibleDetections,
                insideDetections = session.insideDetections,
                bufferDetections = session.bufferDetections,
                doorwayDetections = session.doorwayDetections,
                outsideDetections = session.outsideDetections,
                fps = fps,
            )
        )
    }

    private fun sendFinished(session: AnalysisSession) {
        wsHandler.send(
            session.id,
            SessionFinishedDto(
                status = session.status.name,
                totalBoardings = session.totalBoardings.get(),
                totalAlightings = session.totalAlightings.get(),
                finalOnboard = session.currentOnboard,
                framesProcessed = session.framesProcessed,
                durationMs = session.durationMs(),
                errorMessage = session.errorMessage,
            )
        )
    }
}
