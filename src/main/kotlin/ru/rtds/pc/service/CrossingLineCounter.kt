package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.CountedDirection
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.TrackCountState
import ru.rtds.pc.model.TrackedPerson

data class DoorCountingZone(
    val centerY: Float,
    val halfWidthPx: Float,
    val insideOnTop: Boolean = true,
) {
    val topBoundaryY: Float get() = centerY - halfWidthPx
    val bottomBoundaryY: Float get() = centerY + halfWidthPx

    fun sideFor(anchorY: Float): DoorZoneSide {
        val isAbove = anchorY < topBoundaryY
        val isBelow = anchorY > bottomBoundaryY
        return when {
            isAbove -> if (insideOnTop) DoorZoneSide.INSIDE else DoorZoneSide.OUTSIDE
            isBelow -> if (insideOnTop) DoorZoneSide.OUTSIDE else DoorZoneSide.INSIDE
            else -> DoorZoneSide.DOORWAY
        }
    }
}

data class PassengerCountEvent(
    val trackId: Int,
    val direction: CountedDirection,
    val frameIndex: Int,
    val from: DoorZoneSide,
    val to: DoorZoneSide,
)

@Service
class CrossingLineCounter(
    @Value("\${pc.count-anchor-y-ratio:0.75}") private val countAnchorYRatio: Float,
    @Value("\${pc.count-min-anchor-movement-px:40}") private val minAnchorMovementPx: Float,
    // false = ждём стабильной зоны INSIDE/OUTSIDE (надёжнее при узком кадре над дверью).
    // true  = считаем сразу при пересечении линии (быстрее, но даёт ложные срабатывания
    //         если человек разворачивается в проёме или трек теряется до стабилизации).
    @Value("\${pc.count-center-line-crossing:false}") private val countCenterLineCrossing: Boolean,
    // Сколько кадров подряд трек должен находиться в одной зоне прежде чем она считается "стабильной".
    // При processEveryN=3 и 25fps: 3 кадра ≈ 1 секунда реального времени.
    @Value("\${pc.count-min-stable-frames:3}") private val minStableFrames: Int,
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
        zone: DoorCountingZone,
        frameIndex: Int
    ): CountDelta {
        val beforeLastZone = track.lastZone
        val beforeStableZone = track.stableZone
        val beforeStableFrames = track.stableZoneFrames
        val beforeState = track.countState
        val beforeTransitionFrom = track.transitionFrom
        val beforeBoarded = track.isBoarded
        val beforeAlighted = track.isAlighted
        val previousAnchorY = track.lastAnchorY
        val anchorY = track.detection.anchorY(countAnchorYRatio)
        updateAnchorHistory(track, anchorY)
        val currentZone = zone.sideFor(anchorY)
        updateStableZone(track, currentZone)

        val delta = if (
            countCenterLineCrossing &&
            !track.isBoarded &&
            crossedCenterTowardInside(previousAnchorY, anchorY, zone) &&
            movedTowardInside(track, zone)
        ) {
            countBoarding(track, frameIndex, "center line -> inside")
        } else if (track.stableZoneFrames < minStableFrames) {
            CountDelta(0, 0)
        } else if (track.isAlighted && track.lastZone == DoorZoneSide.INSIDE) {
            // Count rollback must win over the generic state machine. This keeps the
            // counters symmetric: onboard passenger leaves and returns => cancel exit;
            // outside passenger enters and returns => cancel entry.
            cancelAlighting(track, frameIndex)
        } else if (track.isBoarded && track.lastZone == DoorZoneSide.OUTSIDE) {
            cancelBoarding(track, frameIndex)
        } else {
            when (track.countState) {
                TrackCountState.UNKNOWN -> initialize(track, zone, frameIndex)
                TrackCountState.OUTSIDE -> fromOutside(track, frameIndex)
                TrackCountState.INSIDE -> fromInside(track, zone, frameIndex)
                TrackCountState.IN_DOORWAY_FROM_OUTSIDE -> confirmFromOutside(track, frameIndex)
                TrackCountState.IN_DOORWAY_FROM_INSIDE -> confirmFromInside(track, zone, frameIndex)
                TrackCountState.BOARDED -> handleBoarded(track, frameIndex)
                TrackCountState.ALIGHTED -> handleAlighted(track, frameIndex)
            }
        }

        if (log.isDebugEnabled && (
                    beforeLastZone != track.lastZone ||
                            beforeStableZone != track.stableZone ||
                            beforeState != track.countState ||
                            beforeTransitionFrom != track.transitionFrom ||
                            beforeBoarded != track.isBoarded ||
                            beforeAlighted != track.isAlighted ||
                            !delta.isEmpty
                    )
        ) {
            log.debug(
                "Track count state: frame={}, track={}, anchorY={}, anchorRatio={}, movement={}, currentZone={}, lastZone {}->{}, stableZone {}->{} stableFrames {}->{}, state {}->{}, transition {}->{}, boarded {}->{}, alighted {}->{}, delta(boardings={}, alightings={})",
                frameIndex,
                track.id,
                "%.1f".format(anchorY),
                "%.2f".format(countAnchorYRatio),
                "%.1f".format(anchorMovement(track)),
                currentZone,
                beforeLastZone,
                track.lastZone,
                beforeStableZone,
                track.stableZone,
                beforeStableFrames,
                track.stableZoneFrames,
                beforeState,
                track.countState,
                beforeTransitionFrom,
                track.transitionFrom,
                beforeBoarded,
                track.isBoarded,
                beforeAlighted,
                track.isAlighted,
                delta.boardings,
                delta.alightings,
            )
        }
        return delta
    }

    private fun updateAnchorHistory(track: TrackedPerson, anchorY: Float) {
        if (track.firstAnchorY == null) {
            track.firstAnchorY = anchorY
        }
        track.lastAnchorY = anchorY
        track.minAnchorY = minOf(track.minAnchorY, anchorY)
        track.maxAnchorY = maxOf(track.maxAnchorY, anchorY)
    }

    private fun anchorMovement(track: TrackedPerson): Float {
        return track.maxAnchorY - track.minAnchorY
    }

    private fun movedTowardInside(track: TrackedPerson, zone: DoorCountingZone): Boolean {
        val first = track.firstAnchorY ?: return false
        val last = track.lastAnchorY ?: return false
        val delta = last - first
        return if (zone.insideOnTop) delta <= -minAnchorMovementPx else delta >= minAnchorMovementPx
    }

    private fun movedTowardOutside(track: TrackedPerson, zone: DoorCountingZone): Boolean {
        val first = track.firstAnchorY ?: return false
        val last = track.lastAnchorY ?: return false
        val delta = last - first
        return if (zone.insideOnTop) delta >= minAnchorMovementPx else delta <= -minAnchorMovementPx
    }

    private fun crossedCenterTowardInside(previousAnchorY: Float?, currentAnchorY: Float, zone: DoorCountingZone): Boolean {
        val previous = previousAnchorY ?: return false
        return if (zone.insideOnTop) {
            previous >= zone.centerY && currentAnchorY < zone.centerY
        } else {
            previous <= zone.centerY && currentAnchorY > zone.centerY
        }
    }

    private fun updateStableZone(track: TrackedPerson, currentZone: DoorZoneSide) {
        if (track.lastZone == currentZone) {
            track.stableZoneFrames++
        } else {
            track.lastZone = currentZone
            track.stableZoneFrames = 1
        }
        if (track.stableZoneFrames >= minStableFrames) {
            track.stableZone = currentZone
        }
    }

    private fun initialize(track: TrackedPerson, zone: DoorCountingZone, frameIndex: Int): CountDelta {
        when (track.stableZone) {
            DoorZoneSide.OUTSIDE -> {
                track.firstStableZone = track.firstStableZone ?: DoorZoneSide.OUTSIDE
                track.countState = TrackCountState.OUTSIDE
                track.firstSideAbove = true
            }
            DoorZoneSide.INSIDE -> {
                if (track.firstStableZone == null && movedTowardInside(track, zone)) {
                    return countBoarding(track, frameIndex, "doorway/start -> inside")
                }
                track.firstStableZone = track.firstStableZone ?: DoorZoneSide.INSIDE
                track.countState = TrackCountState.INSIDE
                track.firstSideAbove = false
            }
            DoorZoneSide.DOORWAY -> Unit
        }
        track.sideHistory.add(track.stableZone == DoorZoneSide.OUTSIDE)
        return CountDelta(0, 0)
    }

    private fun fromOutside(track: TrackedPerson, frameIndex: Int): CountDelta {
        if (track.lastZone == DoorZoneSide.DOORWAY) {
            track.countState = TrackCountState.IN_DOORWAY_FROM_OUTSIDE
            track.transitionFrom = DoorZoneSide.OUTSIDE
        } else if (track.lastZone == DoorZoneSide.INSIDE) {
            return if (track.isAlighted) {
                cancelAlighting(track, frameIndex)
            } else {
                countBoarding(track, frameIndex)
            }
        }
        return CountDelta(0, 0)
    }

    private fun fromInside(track: TrackedPerson, zone: DoorCountingZone, frameIndex: Int): CountDelta {
        if (track.lastZone == DoorZoneSide.DOORWAY) {
            track.countState = TrackCountState.IN_DOORWAY_FROM_INSIDE
            track.transitionFrom = DoorZoneSide.INSIDE
        } else if (track.lastZone == DoorZoneSide.OUTSIDE) {
            return if (track.isBoarded) {
                cancelBoarding(track, frameIndex)
            } else if (movedTowardOutside(track, zone)) {
                countAlighting(track, frameIndex)
            } else {
                CountDelta(0, 0)
            }
        }
        return CountDelta(0, 0)
    }

    private fun confirmFromOutside(track: TrackedPerson, frameIndex: Int): CountDelta {
        return when (track.lastZone) {
            DoorZoneSide.INSIDE -> {
                if (track.isAlighted) cancelAlighting(track, frameIndex) else countBoarding(track, frameIndex)
            }
            DoorZoneSide.OUTSIDE -> {
                track.countState = TrackCountState.OUTSIDE
                track.transitionFrom = null
                CountDelta(0, 0)
            }
            DoorZoneSide.DOORWAY -> CountDelta(0, 0)
        }
    }

    private fun confirmFromInside(track: TrackedPerson, zone: DoorCountingZone, frameIndex: Int): CountDelta {
        return when (track.lastZone) {
            DoorZoneSide.OUTSIDE -> {
                if (track.isBoarded) {
                    cancelBoarding(track, frameIndex)
                } else if (movedTowardOutside(track, zone)) {
                    countAlighting(track, frameIndex)
                } else {
                    track.countState = TrackCountState.OUTSIDE
                    track.transitionFrom = null
                    CountDelta(0, 0)
                }
            }
            DoorZoneSide.INSIDE -> {
                track.countState = TrackCountState.INSIDE
                track.transitionFrom = null
                CountDelta(0, 0)
            }
            DoorZoneSide.DOORWAY -> CountDelta(0, 0)
        }
    }

    private fun handleBoarded(track: TrackedPerson, frameIndex: Int): CountDelta {
        if (track.lastZone == DoorZoneSide.DOORWAY) {
            track.countState = TrackCountState.IN_DOORWAY_FROM_INSIDE
            track.transitionFrom = DoorZoneSide.INSIDE
        }
        if (track.lastZone == DoorZoneSide.OUTSIDE && track.isBoarded) {
            return cancelBoarding(track, frameIndex)
        }
        return CountDelta(0, 0)
    }

    private fun handleAlighted(track: TrackedPerson, frameIndex: Int): CountDelta {
        if (track.lastZone == DoorZoneSide.DOORWAY) {
            track.countState = TrackCountState.IN_DOORWAY_FROM_OUTSIDE
            track.transitionFrom = DoorZoneSide.OUTSIDE
        }
        if (track.lastZone == DoorZoneSide.INSIDE && track.isAlighted) {
            return cancelAlighting(track, frameIndex)
        }
        return CountDelta(0, 0)
    }

    private fun cancelBoarding(track: TrackedPerson, frameIndex: Int): CountDelta {
        track.isBoarded = false
        track.countedDirection = null
        track.countState = TrackCountState.OUTSIDE
        track.transitionFrom = null
        log.debug("Track {} cancelled boarding after returning outside", track.id)
        return CountDelta(
            boardings = -1,
            alightings = 0,
            event = PassengerCountEvent(track.id, CountedDirection.CANCEL_BOARDING, frameIndex, DoorZoneSide.INSIDE, DoorZoneSide.OUTSIDE)
        )
    }

    private fun cancelAlighting(track: TrackedPerson, frameIndex: Int): CountDelta {
        track.isAlighted = false
        track.countedDirection = null
        track.countState = TrackCountState.INSIDE
        track.transitionFrom = null
        log.debug("Track {} cancelled alighting after returning inside", track.id)
        return CountDelta(
            boardings = 0,
            alightings = -1,
            event = PassengerCountEvent(track.id, CountedDirection.CANCEL_ALIGHTING, frameIndex, DoorZoneSide.OUTSIDE, DoorZoneSide.INSIDE)
        )
    }

    private fun countBoarding(track: TrackedPerson, frameIndex: Int, reason: String = "outside -> doorway -> inside"): CountDelta {
        if (track.isBoarded) return CountDelta(0, 0)

        track.isBoarded = true
        track.isAlighted = false
        track.countedDirection = CountedDirection.BOARDING
        track.lastCountFrame = frameIndex
        track.crossingCount++
        track.countState = TrackCountState.BOARDED
        track.transitionFrom = null
        log.debug("Track {} BOARDED ({})", track.id, reason)
        return CountDelta(
            boardings = 1,
            alightings = 0,
            event = PassengerCountEvent(track.id, CountedDirection.BOARDING, frameIndex, DoorZoneSide.OUTSIDE, DoorZoneSide.INSIDE)
        )
    }

    private fun countAlighting(track: TrackedPerson, frameIndex: Int): CountDelta {
        if (track.isAlighted) return CountDelta(0, 0)

        track.isAlighted = true
        track.isBoarded = false
        track.countedDirection = CountedDirection.ALIGHTING
        track.lastCountFrame = frameIndex
        track.crossingCount++
        track.countState = TrackCountState.ALIGHTED
        track.transitionFrom = null
        log.debug("Track {} ALIGHTED (inside -> doorway -> outside)", track.id)
        return CountDelta(
            boardings = 0,
            alightings = 1,
            event = PassengerCountEvent(track.id, CountedDirection.ALIGHTING, frameIndex, DoorZoneSide.INSIDE, DoorZoneSide.OUTSIDE)
        )
    }
}