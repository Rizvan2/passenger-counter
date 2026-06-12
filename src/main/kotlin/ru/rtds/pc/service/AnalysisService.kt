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
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.SessionStatus
import ru.rtds.pc.persistence.analysis.AnalysisResultPersistenceService
import ru.rtds.pc.websocket.AnalysisWebSocketHandler
import java.io.File

@Service
class AnalysisService(
    private val frameReader: VideoFrameReader,
    private val yoloDetector: YoloDetectorService,
    private val tracker: PersonTracker,
    private val crossingCounter: CrossingLineCounter,
    private val wsHandler: AnalysisWebSocketHandler,
    private val analysisResultPersistenceService: AnalysisResultPersistenceService,
    private val uploadedVideoCleanupService: UploadedVideoCleanupService,
    @Value("\${pc.process-every-n-frames}") private val processEveryN: Int,
    @Value("\${pc.emit-frame-every-ms}") private val emitEveryMs: Long,
    @Value("\${pc.jpeg-quality}") private val jpegQuality: Float,
    @Value("\${pc.analysis-log-every-n-frames:30}") private val analysisLogEveryNFrames: Int,
    @Value("\${pc.count-anchor-y-ratio:0.55}") private val countAnchorYRatio: Float,
    @Value("\${pc.door-zone-half-width-ratio:0.04}") private val doorZoneHalfWidthRatio: Float,
    @Value("\${pc.door-zone-min-half-width-px:10}") private val doorZoneMinHalfWidthPx: Float,
    @Value("\${pc.door-zone-max-half-width-px:60}") private val doorZoneMaxHalfWidthPx: Float,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val initialOnboardWarmupFrames = 25

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
        var lastEmittedMs = 0L
        val pendingEvents = mutableListOf<PassengerCountEvent>()
        log.info(
            "Analysis started: session={}, file='{}', processEveryN={}, lineYRatio={}, insideSide={}",
            session.id,
            session.sourcePath,
            processEveryN,
            session.lineYRatio,
            if (session.insideOnTop) "top" else "bottom",
        )

        val ok = frameReader.process(session.sourcePath, skipFrames = processEveryN - 1) { frameIdx, img, w, h ->
            if (session.stopRequested) {
                session.status = SessionStatus.STOPPED
                return@process false
            }

            val input = frameReader.preprocess(img, yoloDetector.inputSize)
            val detections = yoloDetector.detect(input, w, h)
            val lineY = h * session.lineYRatio
            val doorHalfWidth = doorHalfWidth(h)
            val doorZone = DoorCountingZone(lineY, doorHalfWidth, session.insideOnTop)
            updateInitialOnboardEstimate(session, detections, doorZone)
            val tracks = tracker.update(session.id, detections, img, lineY, session.insideOnTop)
            logFrameDiagnostics(session, frameIdx, w, h, detections, tracks, doorZone)

            for (track in tracks) {
                val delta = crossingCounter.updateTrackState(track, doorZone, frameIdx)
                if (delta.boardings != 0) session.totalBoardings.addAndGet(delta.boardings)
                if (delta.alightings != 0) session.totalAlightings.addAndGet(delta.alightings)
                delta.event?.let {
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
            session.currentOnboard =
                session.initialOnboard + session.totalBoardings.get() - session.totalAlightings.get()

            session.framesProcessed++

            val now = System.currentTimeMillis()
            if (now - lastEmittedMs >= emitEveryMs) {
                emit(session, frameIdx, img, w, h, tracks, doorZone, pendingEvents.toList())
                pendingEvents.clear()
                lastEmittedMs = now
            }
            true
        }

        if (session.status == SessionStatus.RUNNING) {
            session.status = if (ok) SessionStatus.FINISHED else SessionStatus.FAILED
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

    private fun doorHalfWidth(frameHeight: Int): Float {
        val minWidth = doorZoneMinHalfWidthPx.coerceAtLeast(1f)
        val maxWidth = doorZoneMaxHalfWidthPx.coerceAtLeast(minWidth)
        return (frameHeight * doorZoneHalfWidthRatio).coerceIn(minWidth, maxWidth)
    }

    private fun logFrameDiagnostics(
        session: AnalysisSession,
        frameIdx: Int,
        width: Int,
        height: Int,
        detections: List<ru.rtds.pc.model.Detection>,
        tracks: List<ru.rtds.pc.model.TrackedPerson>,
        doorZone: DoorCountingZone,
    ) {
        if (!log.isDebugEnabled) return
        val every = analysisLogEveryNFrames.coerceAtLeast(1)
        if (frameIdx % every != 0) return

        val detectionZones = detections.groupingBy { doorZone.sideFor(it.anchorY(countAnchorYRatio)) }.eachCount()
        val trackSummary = tracks.take(12).joinToString(separator = "; ") {
            "#${it.id}:${it.countState}/${it.stableZone} anchorY=${"%.1f".format(it.detection.anchorY(countAnchorYRatio))} lost=${it.framesSinceUpdate}"
        }
        log.debug(
            "Frame diagnostics: session={}, frame={}, size={}x{}, lineY={}, doorway=[{},{}], insideSide={}, anchorRatio={}, detections={} zones={}, tracks={} [{}]",
            session.id,
            frameIdx,
            width,
            height,
            "%.1f".format(doorZone.centerY),
            "%.1f".format(doorZone.topBoundaryY),
            "%.1f".format(doorZone.bottomBoundaryY),
            if (doorZone.insideOnTop) "top" else "bottom",
            "%.2f".format(countAnchorYRatio),
            detections.size,
            detectionZones,
            tracks.size,
            trackSummary,
        )
    }

    private fun updateInitialOnboardEstimate(
        session: AnalysisSession,
        detections: List<ru.rtds.pc.model.Detection>,
        doorZone: DoorCountingZone,
    ) {
        val insideCount = detections.count { doorZone.sideFor(it.anchorY(countAnchorYRatio)) == DoorZoneSide.INSIDE }
        val doorwayCount = detections.count { doorZone.sideFor(it.anchorY(countAnchorYRatio)) == DoorZoneSide.DOORWAY }
        val outsideCount = detections.count { doorZone.sideFor(it.anchorY(countAnchorYRatio)) == DoorZoneSide.OUTSIDE }

        session.visibleDetections = detections.size
        session.insideDetections = insideCount
        session.doorwayDetections = doorwayCount
        session.outsideDetections = outsideCount

        if (session.initialOnboardDetected) return

        // If the configured side is wrong or the line cuts the frame poorly, still avoid a useless zero.
        val fallbackVisibleCount = if (insideCount == 0 && detections.isNotEmpty()) detections.size else 0
        val candidate = maxOf(insideCount, fallbackVisibleCount)

        if (candidate > session.initialOnboardCandidate) {
            session.initialOnboardCandidate = candidate
            session.initialOnboard = candidate
            log.info(
                "Initial onboard estimate updated for session {}: {} (inside={}, visible={}, side={})",
                session.id,
                candidate,
                insideCount,
                detections.size,
                if (doorZone.insideOnTop) "top" else "bottom",
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
        doorZone: DoorCountingZone,
        events: List<PassengerCountEvent>,
    ) {
        val jpeg = frameReader.encodeJpegBase64(img, jpegQuality)
        val boxes = tracks.map {
            BoxDto(
                trackId = it.id,
                x1 = it.detection.x1,
                y1 = it.detection.y1,
                x2 = it.detection.x2,
                y2 = it.detection.y2,
                confidence = it.detection.confidence,
                isBoarded = it.isBoarded,
                isAlighted = it.isAlighted,
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
                lineY = doorZone.centerY,
                doorTopY = doorZone.topBoundaryY,
                doorBottomY = doorZone.bottomBoundaryY,
                insideOnTop = doorZone.insideOnTop,
                events = eventDtos,
                boardings = session.totalBoardings.get(),
                alightings = session.totalAlightings.get(),
                initialOnboard = session.initialOnboard,
                initialOnboardLocked = session.initialOnboardDetected,
                onboard = session.currentOnboard,
                visibleDetections = session.visibleDetections,
                insideDetections = session.insideDetections,
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
