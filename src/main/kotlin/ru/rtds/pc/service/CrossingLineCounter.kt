package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.CountedDirection
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.ExitCountingOrigin
import ru.rtds.pc.model.TrackCountState
import ru.rtds.pc.model.TrackedPerson
import kotlin.math.hypot

data class PassengerCountEvent(
    val trackId: Int,
    val direction: CountedDirection,
    val frameIndex: Int,
    val from: DoorZoneSide,
    val to: DoorZoneSide,
)

@Service
class CrossingLineCounter(
    @Value("\${pc.count-anchor-x-ratio:0.5}") private val countAnchorXRatio: Float,
    @Value("\${pc.count-anchor-y-ratio:0.5}") private val countAnchorYRatio: Float,
    @Value("\${pc.count-min-anchor-movement-px:40}") private val minAnchorMovementPx: Float,
    @Value("\${pc.count-head-scale-grow-ratio:1.08}") private val headScaleGrowRatio: Float,
    @Value("\${pc.count-head-scale-shrink-ratio:0.92}") private val headScaleShrinkRatio: Float,
    @Value("\${pc.count-scale-window:5}") private val scaleWindow: Int,
    @Value("\${pc.count-lost-at-door-frames:3}") private val lostAtDoorFrames: Int,
    @Value("\${pc.count-min-door-exit-visible-frames:12}") private val minDoorExitVisibleFrames: Int,
    @Value("\${pc.count-min-stable-frames:3}") private val minStableFrames: Int,
    @Value("\${pc.count-preboarded-exit-max-first-seen-frame:90}") private val preboardedExitMaxFirstSeenFrame: Int,
    @Value("\${pc.process-every-n-frames:1}") private val processEveryNFrames: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class CountDelta(
        val boardings: Int,
        val alightings: Int,
        val event: PassengerCountEvent? = null,
    ) {
        val isEmpty: Boolean get() = boardings == 0 && alightings == 0 && event == null
    }

    fun updateTrackState(
        track: TrackedPerson,
        zones: CountingZones,
        frameIndex: Int,
        allowPreboardedExit: Boolean = false,
    ): CountDelta {
        if (track.framesSinceUpdate > 0) {
            return onLostTrack(track, frameIndex, allowPreboardedExit)
        }

        val beforeStableZone = track.stableZone
        val beforeState = track.countState
        val beforeBoarded = track.isBoarded
        val beforeAlighted = track.isAlighted
        val previousStableAnchorX = track.stableAnchorX
        val previousStableAnchorY = track.stableAnchorY
        val previousStableZone = track.stableZone
        val previousStableHeadSize = track.lastStableHeadSize

        val anchorX = track.detection.anchorX(countAnchorXRatio)
        val anchorY = track.detection.anchorY(countAnchorYRatio)
        val currentZone = zones.zoneFor(anchorX, anchorY)
        val inDoor = zones.inDoor(anchorX, anchorY)
        initializeRuntimeOriginIfNeeded(track, zones, currentZone, inDoor, anchorX, anchorY, frameIndex)

        updateVisibleObservation(track, frameIndex, currentZone, inDoor, anchorX, anchorY)
        val stableChanged = updateStableZone(track, currentZone, anchorX, anchorY)

        val delta = when {
            track.isBoarded && stableChanged && track.stableZone == DoorZoneSide.OUTSIDE ->
                cancelBoarding(track, frameIndex)
            track.isAlighted && stableChanged && track.stableZone == DoorZoneSide.INSIDE ->
                cancelAlighting(track, frameIndex)
            stableChanged && track.stableZone == DoorZoneSide.INSIDE ->
                onStableInside(track, frameIndex)
            stableChanged && track.stableZone == DoorZoneSide.OUTSIDE ->
                onStableOutside(
                    track,
                    frameIndex,
                    allowPreboardedExit,
                    previousStableZone,
                    previousStableAnchorX,
                    previousStableAnchorY,
                    previousStableHeadSize,
                )
            else -> CountDelta(0, 0)
        }

        if (log.isDebugEnabled && (
                beforeStableZone != track.stableZone ||
                    beforeState != track.countState ||
                    beforeBoarded != track.isBoarded ||
                    beforeAlighted != track.isAlighted ||
                    !delta.isEmpty
                )
        ) {
            log.debug(
                "Track count state: frame={}, track={}, zone={}, stableZone={}, state={}, inDoor={}, stableFrames={}, headRatio={}, boarded={}, alighted={}, delta(boardings={}, alightings={})",
                frameIndex,
                track.id,
                currentZone,
                track.stableZone,
                track.countState,
                inDoor,
                track.stableZoneFrames,
                "%.3f".format(headRatio(track)),
                track.isBoarded,
                track.isAlighted,
                delta.boardings,
                delta.alightings,
            )
        }

        return delta
    }

    fun initializePrescanTrack(
        track: TrackedPerson,
        zones: CountingZones,
        frameIndex: Int,
    ): ExitCountingOrigin {
        val anchorX = track.detection.anchorX(countAnchorXRatio)
        val anchorY = track.detection.anchorY(countAnchorYRatio)
        val currentZone = zones.zoneFor(anchorX, anchorY)
        val inDoor = zones.inDoor(anchorX, anchorY)
        val origin = when {
            currentZone == DoorZoneSide.INSIDE -> ExitCountingOrigin.STARTUP_INSIDE
            inDoor || currentZone == DoorZoneSide.BUFFER -> ExitCountingOrigin.STARTUP_DOOR
            currentZone == DoorZoneSide.OUTSIDE -> ExitCountingOrigin.STARTUP_OUTSIDE
            else -> ExitCountingOrigin.INVALID
        }
        applyOrigin(track, origin, currentZone, inDoor, frameIndex)
        return origin
    }

    fun initializeRuntimeTrackIfNeeded(
        track: TrackedPerson,
        zones: CountingZones,
        frameIndex: Int,
    ): ExitCountingOrigin {
        val anchorX = track.detection.anchorX(countAnchorXRatio)
        val anchorY = track.detection.anchorY(countAnchorYRatio)
        val currentZone = zones.zoneFor(anchorX, anchorY)
        val inDoor = zones.inDoor(anchorX, anchorY)
        initializeRuntimeOriginIfNeeded(track, zones, currentZone, inDoor, anchorX, anchorY, frameIndex)
        return track.exitCountingOrigin
    }

    private fun onLostTrack(
        track: TrackedPerson,
        frameIndex: Int,
        allowPreboardedExit: Boolean,
    ): CountDelta {
        if (track.isBoarded && visitedDoorRecently(track, frameIndex)) {
            return cancelBoarding(track, frameIndex)
        }
        if (track.countedDirection == CountedDirection.ALIGHTING) return CountDelta(0, 0)
        val wasInside = wasStableInside(track)
        val canExitWithoutStableInside = canExitWithoutStableInside(track, allowPreboardedExit)
        if (!wasInside && !canExitWithoutStableInside) return CountDelta(0, 0)
        if (track.framesSinceUpdate < lostAtDoorFrames) return CountDelta(0, 0)

        val lastSeenOutside = track.lastSeenZone == DoorZoneSide.OUTSIDE
        val lastSeenAtDoor = visitedDoorRecently(track, frameIndex)
        if (!lastSeenOutside && !lastSeenAtDoor) return CountDelta(0, 0)
        if (!track.wasInDoor && !track.startupExitCandidate) return CountDelta(0, 0)
        if (!wasInside && !lastSeenOutside) return CountDelta(0, 0)

        val moved = movedEnough(
            track.stableAnchorX,
            track.stableAnchorY,
            track.detection.anchorX(countAnchorXRatio),
            track.detection.anchorY(countAnchorYRatio),
        )
        val preboardedExitEvidence = wasStableInside(track) || moved || movedDuringLifetime(track) || headShrank(track) || wasVisibleAtDoorLongEnough(track)
        if (!preboardedExitEvidence) return CountDelta(0, 0)
        if (!lastSeenOutside && !moved && !headShrank(track) && !canExitWithoutStableInside) {
            return CountDelta(0, 0)
        }
        return countAlighting(track, frameIndex, from = DoorZoneSide.INSIDE, to = DoorZoneSide.OUTSIDE)
    }

    private fun initializeRuntimeOriginIfNeeded(
        track: TrackedPerson,
        zones: CountingZones,
        currentZone: DoorZoneSide,
        inDoor: Boolean,
        anchorX: Float,
        anchorY: Float,
        frameIndex: Int,
    ) {
        if (track.exitCountingOrigin != ExitCountingOrigin.UNKNOWN) return
        val origin = if (currentZone == DoorZoneSide.INSIDE && zones.pointInSalonSpawn(anchorX, anchorY)) {
            ExitCountingOrigin.SPAWN_INSIDE
        } else {
            ExitCountingOrigin.INVALID
        }
        applyOrigin(track, origin, currentZone, inDoor, frameIndex)
    }

    private fun applyOrigin(
        track: TrackedPerson,
        origin: ExitCountingOrigin,
        currentZone: DoorZoneSide,
        inDoor: Boolean,
        frameIndex: Int,
    ) {
        track.exitCountingOrigin = origin
        track.countEligibleForExit = origin == ExitCountingOrigin.STARTUP_INSIDE ||
            origin == ExitCountingOrigin.STARTUP_DOOR ||
            origin == ExitCountingOrigin.STARTUP_OUTSIDE ||
            origin == ExitCountingOrigin.SPAWN_INSIDE
        track.startupExitCandidate = origin == ExitCountingOrigin.STARTUP_OUTSIDE

        if (!track.countEligibleForExit) return

        track.firstSeenFrame = track.firstSeenFrame ?: frameIndex
        track.lastSeenFrame = frameIndex
        track.firstStableZone = DoorZoneSide.INSIDE
        track.firstStableHeadSize = track.smoothedHeadSize ?: hypot(track.detection.width, track.detection.height)
        track.countState = TrackCountState.INSIDE
        track.wasInDoor = track.wasInDoor || inDoor || origin == ExitCountingOrigin.STARTUP_DOOR || origin == ExitCountingOrigin.STARTUP_OUTSIDE
        if (track.wasInDoor) {
            track.lastReliableInDoorFrame = track.lastReliableInDoorFrame ?: frameIndex
        }

        when (origin) {
            ExitCountingOrigin.STARTUP_OUTSIDE -> {
                track.stableZone = DoorZoneSide.OUTSIDE
                track.lastSeenZone = DoorZoneSide.OUTSIDE
            }
            ExitCountingOrigin.STARTUP_DOOR -> {
                track.stableZone = DoorZoneSide.BUFFER
                track.lastSeenZone = currentZone
            }
            else -> {
                track.stableZone = DoorZoneSide.INSIDE
                track.lastSeenZone = currentZone
            }
        }
    }

    private fun updateVisibleObservation(
        track: TrackedPerson,
        frameIndex: Int,
        currentZone: DoorZoneSide,
        inDoor: Boolean,
        anchorX: Float,
        anchorY: Float,
    ) {
        val headSize = hypot(track.detection.width, track.detection.height)
        val clampedWindow = scaleWindow.coerceAtLeast(2)

        if (track.firstSeenFrame == null) {
            track.firstSeenFrame = frameIndex
        }
        track.lastSeenFrame = frameIndex
        track.lastZone = currentZone
        track.lastSeenZone = currentZone
        track.wasInDoor = track.wasInDoor || inDoor
        track.isInDoor = inDoor
        if (inDoor) {
            track.lastReliableInDoorFrame = frameIndex
        }

        if (track.firstAnchorY == null) {
            track.firstAnchorY = anchorY
        }
        if (track.firstAnchorX == null) {
            track.firstAnchorX = anchorX
        }
        track.lastAnchorX = anchorX
        track.lastAnchorY = anchorY
        track.minAnchorX = minOf(track.minAnchorX, anchorX)
        track.maxAnchorX = maxOf(track.maxAnchorX, anchorX)
        track.minAnchorY = minOf(track.minAnchorY, anchorY)
        track.maxAnchorY = maxOf(track.maxAnchorY, anchorY)
        track.firstBoxHeight = track.firstBoxHeight ?: track.detection.height
        track.lastBoxHeight = track.detection.height
        track.minBoxHeight = minOf(track.minBoxHeight, track.detection.height)
        track.maxBoxHeight = maxOf(track.maxBoxHeight, track.detection.height)

        track.headSizeHistory += headSize
        while (track.headSizeHistory.size > clampedWindow) {
            track.headSizeHistory.removeAt(0)
        }
        track.smoothedHeadSize = track.headSizeHistory.average().toFloat()

        if (track.stableAnchorX == null) {
            track.stableAnchorX = anchorX
            track.stableAnchorY = anchorY
        }
    }

    private fun updateStableZone(
        track: TrackedPerson,
        currentZone: DoorZoneSide,
        anchorX: Float,
        anchorY: Float,
    ): Boolean {
        if (currentZone == DoorZoneSide.BUFFER) return false

        val candidateChanged = track.transitionFrom != currentZone
        if (candidateChanged) {
            track.transitionFrom = currentZone
            track.stableZoneFrames = 1
        } else {
            track.stableZoneFrames++
        }

        if (track.stableZoneFrames < minStableFrames) return false
        if (track.stableZone == currentZone) return false

        val smoothedHeadSize = track.smoothedHeadSize
        track.stableZone = currentZone
        track.transitionFrom = currentZone
        track.stableAnchorX = anchorX
        track.stableAnchorY = anchorY
        track.stableBoxHeight = track.detection.height
        if (track.firstStableZone == null) {
            track.firstStableZone = currentZone
            track.firstStableHeadSize = smoothedHeadSize
            track.bornInDoor = currentZone == DoorZoneSide.OUTSIDE
        }
        track.lastStableHeadSize = smoothedHeadSize
        return true
    }

    private fun onStableInside(
        track: TrackedPerson,
        frameIndex: Int,
    ): CountDelta {
        if (track.isAlighted || track.countedDirection == CountedDirection.ALIGHTING) {
            return cancelAlighting(track, frameIndex)
        }
        if (track.startupExitCandidate) {
            track.countEligibleForExit = false
            track.startupExitCandidate = false
            track.exitCountingOrigin = ExitCountingOrigin.INVALID
        }
        track.countState = TrackCountState.INSIDE
        return CountDelta(0, 0)
    }

    private fun onStableOutside(
        track: TrackedPerson,
        frameIndex: Int,
        allowPreboardedExit: Boolean,
        previousStableZone: DoorZoneSide,
        previousStableAnchorX: Float?,
        previousStableAnchorY: Float?,
        previousStableHeadSize: Float?,
    ): CountDelta {
        if (track.isBoarded) {
            return cancelBoarding(track, frameIndex)
        }

        val canExitWithoutStableInside = canExitWithoutStableInside(track, allowPreboardedExit)
        val wasInside = wasStableInside(track)
        if (!wasStableInside(track) && !canExitWithoutStableInside) {
            track.countState = TrackCountState.OUTSIDE
            return CountDelta(0, 0)
        }
        if (previousStableZone != DoorZoneSide.INSIDE && !visitedDoorRecently(track, frameIndex) && !canExitWithoutStableInside) {
            track.countState = TrackCountState.OUTSIDE
            return CountDelta(0, 0)
        }
        if (!visitedDoorRecently(track, frameIndex)) {
            track.countState = TrackCountState.OUTSIDE
            return CountDelta(0, 0)
        }
        val moved = movedEnough(
            previousStableAnchorX,
            previousStableAnchorY,
            track.detection.anchorX(countAnchorXRatio),
            track.detection.anchorY(countAnchorYRatio),
        )
        if (!moved && !canExitWithoutStableInside) {
            track.countState = TrackCountState.OUTSIDE
            return CountDelta(0, 0)
        }
        val shrank = headShrank(track, previousStableHeadSize)
        if (!wasInside && canExitWithoutStableInside && !moved && !movedDuringLifetime(track) && !shrank) {
            track.countState = TrackCountState.OUTSIDE
            return CountDelta(0, 0)
        }
        if (!visitedDoorRecently(track, frameIndex) && !shrank) {
            track.countState = TrackCountState.OUTSIDE
            return CountDelta(0, 0)
        }

        track.countState = TrackCountState.OUTSIDE
        track.lastStableHeadSize = track.smoothedHeadSize
        return CountDelta(0, 0)
    }

    private fun wasStableInside(track: TrackedPerson): Boolean =
        track.firstStableZone == DoorZoneSide.INSIDE || track.stableZone == DoorZoneSide.INSIDE || track.countState == TrackCountState.INSIDE

    private fun canExitWithoutStableInside(
        track: TrackedPerson,
        allowPreboardedExit: Boolean,
    ): Boolean {
        if (!allowPreboardedExit || !track.wasInDoor) return false
        val firstSeen = track.firstSeenFrame ?: return false
        return firstSeen <= preboardedExitMaxFirstSeenFrame.coerceAtLeast(0) ||
            track.startupExitCandidate ||
            track.exitCountingOrigin == ExitCountingOrigin.STARTUP_DOOR
    }

    private fun visitedDoorRecently(track: TrackedPerson, frameIndex: Int): Boolean {
        val seenAt = track.lastReliableInDoorFrame ?: return false
        val rawFrameWindow = (lostAtDoorFrames + 2) * processEveryNFrames.coerceAtLeast(1)
        return frameIndex - seenAt <= rawFrameWindow
    }

    private fun movedEnough(startX: Float?, startY: Float?, anchorX: Float, anchorY: Float): Boolean =
        stableAnchorMovement(startX, startY, anchorX, anchorY) >= minAnchorMovementPx

    private fun movedDuringLifetime(track: TrackedPerson): Boolean {
        val dx = track.maxAnchorX - track.minAnchorX
        val dy = track.maxAnchorY - track.minAnchorY
        return hypot(dx, dy) >= minAnchorMovementPx
    }

    private fun wasVisibleAtDoorLongEnough(track: TrackedPerson): Boolean {
        val firstSeen = track.firstSeenFrame ?: return false
        val lastSeen = track.lastSeenFrame ?: return false
        return track.wasInDoor && lastSeen - firstSeen >= minDoorExitVisibleFrames.coerceAtLeast(1)
    }

    private fun stableAnchorMovement(startX: Float?, startY: Float?, anchorX: Float, anchorY: Float): Float {
        if (startX == null || startY == null) return 0f
        return hypot(anchorX - startX, anchorY - startY)
    }

    private fun headRatio(track: TrackedPerson, baseline: Float? = null): Float {
        val current = track.smoothedHeadSize ?: return 1f
        val base = baseline ?: track.firstStableHeadSize ?: track.lastStableHeadSize ?: current
        if (base <= 1e-6f) return 1f
        return current / base
    }

    private fun headGrew(track: TrackedPerson, baseline: Float? = null): Boolean =
        headRatio(track, baseline) >= headScaleGrowRatio

    private fun headShrank(track: TrackedPerson, baseline: Float? = null): Boolean =
        headRatio(track, baseline) <= headScaleShrinkRatio

    private fun cancelBoarding(track: TrackedPerson, frameIndex: Int): CountDelta {
        track.isBoarded = false
        track.countedDirection = null
        track.countState = TrackCountState.OUTSIDE
        log.debug("Track {} cancelled boarding after returning toward street/door", track.id)
        return CountDelta(
            boardings = -1,
            alightings = 0,
            event = PassengerCountEvent(track.id, CountedDirection.CANCEL_BOARDING, frameIndex, DoorZoneSide.INSIDE, DoorZoneSide.OUTSIDE),
        )
    }

    private fun cancelAlighting(track: TrackedPerson, frameIndex: Int): CountDelta {
        track.isAlighted = false
        track.countedDirection = null
        track.countState = TrackCountState.INSIDE
        log.debug("Track {} cancelled alighting after returning into salon", track.id)
        return CountDelta(
            boardings = 0,
            alightings = -1,
            event = PassengerCountEvent(track.id, CountedDirection.CANCEL_ALIGHTING, frameIndex, DoorZoneSide.OUTSIDE, DoorZoneSide.INSIDE),
        )
    }

    private fun countBoarding(track: TrackedPerson, frameIndex: Int): CountDelta {
        if (track.isBoarded) return CountDelta(0, 0)
        track.isBoarded = true
        track.isAlighted = false
        track.countedDirection = CountedDirection.BOARDING
        track.lastCountFrame = frameIndex
        track.crossingCount++
        track.countState = TrackCountState.BOARDED
        log.debug("Track {} BOARDED", track.id)
        return CountDelta(
            boardings = 1,
            alightings = 0,
            event = PassengerCountEvent(track.id, CountedDirection.BOARDING, frameIndex, DoorZoneSide.OUTSIDE, DoorZoneSide.INSIDE),
        )
    }

    private fun countAlighting(
        track: TrackedPerson,
        frameIndex: Int,
        from: DoorZoneSide,
        to: DoorZoneSide,
    ): CountDelta {
        if (track.countedDirection == CountedDirection.ALIGHTING) return CountDelta(0, 0)
        track.isAlighted = true
        track.isBoarded = false
        track.countedDirection = CountedDirection.ALIGHTING
        track.lastCountFrame = frameIndex
        track.crossingCount++
        track.countState = TrackCountState.ALIGHTED
        log.debug("Track {} ALIGHTED", track.id)
        return CountDelta(
            boardings = 0,
            alightings = 1,
            event = PassengerCountEvent(track.id, CountedDirection.ALIGHTING, frameIndex, from, to),
        )
    }
}
