package ru.rtds.pc.service

import ru.rtds.pc.config.SmartStopProperties
import ru.rtds.pc.model.DoorVisualState
import ru.rtds.pc.model.NormalizedPoint
import ru.rtds.pc.model.SmartStopPhase
import ru.rtds.pc.model.SmartStopSnapshot
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight visual observer for an already recorded clip.
 *
 * It deliberately reports UNKNOWN instead of guessing while a passenger obscures the doorway,
 * the door is moving, or an open-door reference has not been collected yet. The reference is
 * collected only after passenger activity has proved that this is an actual door event.
 */
class SmartStopAnalyzer(
    private val properties: SmartStopProperties,
    private val enabled: Boolean,
) {
    private val gridWidth = 24
    private val gridHeight = 24

    private var lastFrameIndex: Int? = null
    private var lastDoorSignature: FloatArray? = null
    private var lastStreetSignature: FloatArray? = null
    private var openReferenceSum: FloatArray? = null
    private var openReferenceCount: Int = 0
    private var openReference: FloatArray? = null

    private var passengerActivitySeen = false
    private var lastPassengerActivitySeconds = 0.0
    private var closedStableSeconds = 0.0
    private var vehicleMovingSeconds = 0.0
    private var candidateReason: String? = null
    private var candidateStartedSeconds: Double? = null
    private var stopTriggered = false

    fun update(
        image: BufferedImage,
        frameIndex: Int,
        sourceFps: Double,
        doorPolygon: List<NormalizedPoint>,
        streetPolygon: List<NormalizedPoint>,
        passengerActivity: Boolean,
        passengerEvent: Boolean,
    ): SmartStopSnapshot {
        val safeFps = sourceFps.takeIf { it.isFinite() && it > 0.0 } ?: 25.0
        val videoTimeSeconds = frameIndex / safeFps
        val deltaSeconds = lastFrameIndex
            ?.let { max(0, frameIndex - it) / safeFps }
            ?: 0.0
        lastFrameIndex = frameIndex

        if (!enabled || !properties.enabled) {
            return SmartStopSnapshot(
                enabled = false,
                phase = SmartStopPhase.DISABLED,
                videoTimeSeconds = videoTimeSeconds,
            )
        }

        val activityNow = passengerActivity || passengerEvent
        if (activityNow) {
            passengerActivitySeen = true
            lastPassengerActivitySeconds = videoTimeSeconds
        }
        val inactivitySeconds = if (passengerActivitySeen) {
            max(0.0, videoTimeSeconds - lastPassengerActivitySeconds)
        } else {
            0.0
        }

        val doorSignature = signature(image, doorPolygon)
        val streetSignature = signature(image, streetPolygon)
        val doorMotion = difference(doorSignature, lastDoorSignature)
        val sceneMotion = difference(streetSignature, lastStreetSignature)
        lastDoorSignature = doorSignature
        lastStreetSignature = streetSignature

        if (sceneMotion >= properties.sceneMotionThreshold) {
            vehicleMovingSeconds += deltaSeconds
        } else {
            vehicleMovingSeconds = 0.0
        }
        val vehicleMoving =
            vehicleMovingSeconds >= properties.vehicleMotionConfirmSeconds.coerceAtLeast(0.0)

        collectOpenReferenceIfEligible(
            signature = doorSignature,
            passengerActivity = activityNow,
            doorMotion = doorMotion,
        )

        val reference = openReference
        val referenceDifference = difference(doorSignature, reference)
        val rawDoorState = when {
            reference == null -> DoorVisualState.CALIBRATING
            activityNow -> DoorVisualState.UNKNOWN
            doorMotion > properties.doorStableMotionMax -> DoorVisualState.UNKNOWN
            referenceDifference <= properties.doorOpenMaxDifference -> DoorVisualState.OPEN
            referenceDifference >= properties.doorClosedMinDifference -> DoorVisualState.CLOSED
            else -> DoorVisualState.UNKNOWN
        }
        val doorConfidence = confidence(rawDoorState, referenceDifference)

        if (rawDoorState == DoorVisualState.CLOSED) {
            closedStableSeconds += deltaSeconds
        } else {
            closedStableSeconds = 0.0
        }

        updateFinishCandidate(
            videoTimeSeconds = videoTimeSeconds,
            rawDoorState = rawDoorState,
            activityNow = activityNow,
            inactivitySeconds = inactivitySeconds,
            vehicleMoving = vehicleMoving,
        )

        val candidateStarted = candidateStartedSeconds
        val postRollRemaining = if (candidateStarted != null && !stopTriggered) {
            max(0.0, properties.postRollSeconds - (videoTimeSeconds - candidateStarted))
        } else {
            0.0
        }
        val phase = when {
            stopTriggered -> SmartStopPhase.FINISHED
            candidateReason != null -> SmartStopPhase.POST_ROLL
            reference == null -> SmartStopPhase.CALIBRATING
            rawDoorState == DoorVisualState.CLOSED -> SmartStopPhase.CLOSING
            else -> SmartStopPhase.MONITORING
        }

        return SmartStopSnapshot(
            enabled = true,
            phase = phase,
            doorState = rawDoorState,
            doorConfidence = doorConfidence,
            calibrationProgress = calibrationProgress(),
            doorReferenceDifference = referenceDifference,
            doorMotionScore = doorMotion,
            passengerActivity = activityNow,
            passengerActivitySeen = passengerActivitySeen,
            secondsSincePassengerActivity = inactivitySeconds,
            sceneMotionScore = sceneMotion,
            vehicleMoving = vehicleMoving,
            vehicleMovingSeconds = vehicleMovingSeconds,
            closedStableSeconds = closedStableSeconds,
            postRollRemainingSeconds = postRollRemaining,
            videoTimeSeconds = videoTimeSeconds,
            finishReason = candidateReason,
            stopTriggered = stopTriggered,
        )
    }

    private fun collectOpenReferenceIfEligible(
        signature: FloatArray,
        passengerActivity: Boolean,
        doorMotion: Float,
    ) {
        if (openReference != null) return
        if (properties.requirePassengerActivityForCalibration && !passengerActivitySeen) return
        if (passengerActivity || doorMotion > properties.doorStableMotionMax) return

        val targetSamples = properties.calibrationSamples.coerceAtLeast(1)
        val sum = openReferenceSum ?: FloatArray(signature.size).also { openReferenceSum = it }
        for (i in signature.indices) {
            val value = signature[i]
            if (value.isFinite()) sum[i] += value
        }
        openReferenceCount++
        if (openReferenceCount >= targetSamples) {
            openReference = FloatArray(sum.size) { index ->
                if (signature[index].isFinite()) sum[index] / openReferenceCount else Float.NaN
            }
        }
    }

    private fun updateFinishCandidate(
        videoTimeSeconds: Double,
        rawDoorState: DoorVisualState,
        activityNow: Boolean,
        inactivitySeconds: Double,
        vehicleMoving: Boolean,
    ) {
        if (stopTriggered) return

        val reason = candidateReason
        if (reason != null) {
            val cancelled = when (reason) {
                REASON_DOOR_CLOSED -> activityNow || rawDoorState != DoorVisualState.CLOSED
                REASON_INACTIVITY_AND_MOVEMENT -> activityNow || !vehicleMoving
                else -> false
            }
            if (cancelled) {
                candidateReason = null
                candidateStartedSeconds = null
                return
            }
            val started = candidateStartedSeconds ?: videoTimeSeconds
            if (videoTimeSeconds - started >= properties.postRollSeconds.coerceAtLeast(0.0)) {
                stopTriggered = true
            }
            return
        }

        if (videoTimeSeconds < properties.minAnalysisSeconds.coerceAtLeast(0.0)) return

        val maxSeconds = properties.maxAnalysisSeconds
        when {
            maxSeconds > 0.0 && videoTimeSeconds >= maxSeconds ->
                startCandidate(REASON_MAX_DURATION, videoTimeSeconds)

            rawDoorState == DoorVisualState.CLOSED &&
                closedStableSeconds >= properties.doorCloseConfirmSeconds.coerceAtLeast(0.0) &&
                inactivitySeconds >= properties.doorClearSeconds.coerceAtLeast(0.0) ->
                startCandidate(REASON_DOOR_CLOSED, videoTimeSeconds)

            properties.motionFallbackEnabled &&
                passengerActivitySeen &&
                inactivitySeconds >= properties.inactivitySeconds.coerceAtLeast(0.0) &&
                vehicleMoving ->
                startCandidate(REASON_INACTIVITY_AND_MOVEMENT, videoTimeSeconds)
        }
    }

    private fun startCandidate(reason: String, videoTimeSeconds: Double) {
        candidateReason = reason
        candidateStartedSeconds = videoTimeSeconds
        if (properties.postRollSeconds <= 0.0) stopTriggered = true
    }

    private fun calibrationProgress(): Float =
        (openReferenceCount.toFloat() / properties.calibrationSamples.coerceAtLeast(1))
            .coerceIn(0f, 1f)

    private fun confidence(state: DoorVisualState, referenceDifference: Float): Float = when (state) {
        DoorVisualState.OPEN -> {
            val threshold = properties.doorOpenMaxDifference.coerceAtLeast(0.001f)
            (1f - referenceDifference / threshold).coerceIn(0f, 1f)
        }

        DoorVisualState.CLOSED -> {
            val threshold = properties.doorClosedMinDifference.coerceAtLeast(0.001f)
            (0.5f + (referenceDifference - threshold) / threshold).coerceIn(0.5f, 1f)
        }

        DoorVisualState.CALIBRATING,
        DoorVisualState.UNKNOWN,
        -> 0f
    }

    private fun signature(image: BufferedImage, polygon: List<NormalizedPoint>): FloatArray {
        if (polygon.size < 3 || image.width <= 1 || image.height <= 1) {
            return FloatArray(gridWidth * gridHeight) { Float.NaN }
        }

        val minX = polygon.minOf { it.x }.coerceIn(0f, 1f)
        val maxX = polygon.maxOf { it.x }.coerceIn(0f, 1f)
        val minY = polygon.minOf { it.y }.coerceIn(0f, 1f)
        val maxY = polygon.maxOf { it.y }.coerceIn(0f, 1f)
        val values = FloatArray(gridWidth * gridHeight) { Float.NaN }

        for (gy in 0 until gridHeight) {
            val ny = minY + (gy + 0.5f) / gridHeight * (maxY - minY)
            for (gx in 0 until gridWidth) {
                val nx = minX + (gx + 0.5f) / gridWidth * (maxX - minX)
                if (!pointInPolygon(nx, ny, polygon)) continue
                val px = (nx * (image.width - 1)).toInt().coerceIn(0, image.width - 1)
                val py = (ny * (image.height - 1)).toInt().coerceIn(0, image.height - 1)
                val rgb = image.getRGB(px, py)
                val r = (rgb shr 16) and 0xff
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff
                values[gy * gridWidth + gx] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            }
        }
        normalize(values)
        return values
    }

    private fun normalize(values: FloatArray) {
        val valid = values.filter { it.isFinite() }
        if (valid.isEmpty()) return
        val mean = valid.average().toFloat()
        val variance = valid.sumOf { value ->
            val delta = value - mean
            (delta * delta).toDouble()
        } / valid.size
        val scale = (sqrt(variance).toFloat() + 0.08f).coerceAtLeast(0.08f)
        for (i in values.indices) {
            if (values[i].isFinite()) values[i] = (values[i] - mean) / scale
        }
    }

    private fun difference(current: FloatArray?, previous: FloatArray?): Float {
        if (current == null || previous == null || current.size != previous.size) return 0f
        var sum = 0f
        var count = 0
        for (i in current.indices) {
            val a = current[i]
            val b = previous[i]
            if (!a.isFinite() || !b.isFinite()) continue
            sum += abs(a - b)
            count++
        }
        if (count == 0) return 0f
        return (sum / count / 2f).coerceIn(0f, 1f)
    }

    private fun pointInPolygon(x: Float, y: Float, polygon: List<NormalizedPoint>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > y) != (pj.y > y)) {
                val denominator = (pj.y - pi.y).takeIf { abs(it) > 1e-7f } ?: 1e-7f
                val crossingX = (pj.x - pi.x) * (y - pi.y) / denominator + pi.x
                if (x < crossingX) inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        const val REASON_DOOR_CLOSED = "DOOR_CLOSED"
        const val REASON_INACTIVITY_AND_MOVEMENT = "INACTIVITY_AND_MOVEMENT"
        const val REASON_MAX_DURATION = "MAX_DURATION"
    }
}
