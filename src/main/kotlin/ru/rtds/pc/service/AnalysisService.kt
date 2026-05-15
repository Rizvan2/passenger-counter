package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import ru.rtds.pc.dto.BoxDto
import ru.rtds.pc.dto.FrameUpdateDto
import ru.rtds.pc.dto.SessionFinishedDto
import ru.rtds.pc.model.AnalysisSession
import ru.rtds.pc.model.SessionStatus
import ru.rtds.pc.websocket.AnalysisWebSocketHandler
import java.io.File

@Service
class AnalysisService(
    private val frameReader: VideoFrameReader,
    private val yoloDetector: YoloDetectorService,
    private val tracker: PersonTracker,
    private val crossingCounter: CrossingLineCounter,
    private val wsHandler: AnalysisWebSocketHandler,
    @Value("\${pc.yolo-input-size}") private val yoloInputSize: Int,
    @Value("\${pc.process-every-n-frames}") private val processEveryN: Int,
    @Value("\${pc.emit-frame-every-ms}") private val emitEveryMs: Long,
    @Value("\${pc.jpeg-quality}") private val jpegQuality: Float,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
            wsHandler.close(session.id)
        }
    }

    private fun runInternal(session: AnalysisSession) {
        val file = File(session.sourcePath)
        if (!file.exists() || !file.canRead()) {
            throw IllegalArgumentException("Видеофайл не найден или нет доступа: ${session.sourcePath}")
        }

        tracker.reset()
        var lastEmittedMs = 0L

        val ok = frameReader.process(session.sourcePath, skipFrames = processEveryN - 1) { frameIdx, img, w, h ->
            if (session.stopRequested) {
                session.status = SessionStatus.STOPPED
                return@process false
            }

            val input = frameReader.preprocess(img, yoloInputSize)
            val detections = yoloDetector.detect(input, w, h)
            val tracks = tracker.update(detections, img)

            // Вертикальная линия: X-координата = ширина * ratio
            val lineX = w * session.lineYRatio  // поле lineYRatio переиспользуем как X
            for (t in tracks) {
                val delta = crossingCounter.updateTrackState(t, lineX)
                if (delta.boardings != 0) session.totalBoardings.addAndGet(delta.boardings)
                if (delta.alightings != 0) session.totalAlightings.addAndGet(delta.alightings)
            }
            session.currentOnboard =
                session.initialOnboard + session.totalBoardings.get() - session.totalAlightings.get()

            session.framesProcessed++

            // Emit ~10 раз/сек
            val now = System.currentTimeMillis()
            if (now - lastEmittedMs >= emitEveryMs) {
                emit(session, frameIdx, img, w, h, tracks, lineX)
                lastEmittedMs = now
            }
            true
        }

        if (session.status == SessionStatus.RUNNING) {
            session.status = if (ok) SessionStatus.FINISHED else SessionStatus.FAILED
        }
    }

    private fun emit(
        session: AnalysisSession,
        frameIdx: Int,
        img: java.awt.image.BufferedImage,
        w: Int, h: Int,
        tracks: List<ru.rtds.pc.model.TrackedPerson>,
        lineX: Float,
    ) {
        val jpeg = frameReader.encodeJpegBase64(img, jpegQuality)
        val boxes = tracks.map {
            BoxDto(
                trackId = it.id,
                x1 = it.detection.x1, y1 = it.detection.y1,
                x2 = it.detection.x2, y2 = it.detection.y2,
                confidence = it.detection.confidence,
                isBoarded = it.isBoarded,
                isAlighted = it.isAlighted,
            )
        }
        val elapsedSec = ((System.currentTimeMillis() - session.startedAt) / 1000f).coerceAtLeast(0.001f)
        val fps = session.framesProcessed / elapsedSec

        wsHandler.send(
            session.id,
            FrameUpdateDto(
                frameIndex = frameIdx,
                frameJpegBase64 = jpeg,
                width = w, height = h,
                detections = boxes,
                lineY = lineX,  // используем поле как X-координату линии
                boardings = session.totalBoardings.get(),
                alightings = session.totalAlightings.get(),
                onboard = session.currentOnboard,
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
                durationMs = (session.finishedAt ?: System.currentTimeMillis()) - session.startedAt,
                errorMessage = session.errorMessage,
            )
        )
    }
}
