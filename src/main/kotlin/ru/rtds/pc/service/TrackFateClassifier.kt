package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.ExitCountingOrigin
import ru.rtds.pc.model.TrackTrajectory
import ru.rtds.pc.model.TrajectorySample
import kotlin.math.hypot

enum class TrackFate {
    ENTERED,
    EXITED,
    ENTERED_AND_EXITED,
    ONBOARD_STATIONARY,
    PASSERBY,
    UNDECIDED,
}

data class TrackFateResult(
    val trackId: Int,
    val boardings: Int,
    val alightings: Int,
    val fate: TrackFate,
    val reason: String,
)

data class OfflineCountResult(
    val boardings: Int,
    val alightings: Int,
    val perTrack: List<TrackFateResult>,
    val fateCounts: Map<TrackFate, Int>,
)

@Service
class TrackFateClassifier(
    @Value("\${pc.count-min-stable-frames:3}") private val minStableFrames: Int,
    @Value("\${pc.count-head-scale-grow-ratio:1.08}") private val growRatio: Float,
    @Value("\${pc.count-head-scale-shrink-ratio:0.92}") private val shrinkRatio: Float,
    @Value("\${pc.count-min-anchor-movement-px:40}") private val minMovementPx: Float,
    @Value("\${pc.count-lost-at-door-frames:3}") private val lostAtDoorFrames: Int,
    @Value("\${pc.count-min-body-height-ratio:0.0}") private val minBodyHeightRatio: Float,
    @Value("\${pc.count-successor-max-gap-frames:50}") private val successorMaxGapFrames: Int,
    @Value("\${pc.count-successor-max-distance-px:120}") private val successorMaxDistancePx: Float,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun classify(trajectories: Collection<TrackTrajectory>, finalFrameIndex: Int? = null): OfflineCountResult {
        val list = trajectories.filter { it.samples.isNotEmpty() }
        val perTrack = list.map { classifyTrack(it, hasStationaryNewbornSuccessor(it, list), finalFrameIndex) }
        val boardings = perTrack.sumOf { it.boardings }
        val alightings = perTrack.sumOf { it.alightings }
        val fateCounts = perTrack.groupingBy { it.fate }.eachCount()
        if (log.isDebugEnabled) {
            perTrack.filter { it.boardings != 0 || it.alightings != 0 || it.fate != TrackFate.UNDECIDED }
                .forEach { log.debug("Track fate: #{} -> {} (b={}, a={}) : {}", it.trackId, it.fate, it.boardings, it.alightings, it.reason) }
        }
        return OfflineCountResult(boardings, alightings, perTrack, fateCounts)
    }

    private fun hasStationaryNewbornSuccessor(
        track: TrackTrajectory,
        all: List<TrackTrajectory>,
    ): Boolean {
        val last = track.samples.lastOrNull() ?: return false
        for (other in all) {
            if (other === track) continue
            val first = other.samples.firstOrNull() ?: continue
            if (first.frameIndex <= last.frameIndex) continue
            if (first.frameIndex - last.frameIndex > successorMaxGapFrames) continue
            if (hypot(first.anchorX - last.anchorX, first.anchorY - last.anchorY) > successorMaxDistancePx) continue

            val early = other.samples.take(minStableFrames.coerceAtLeast(2))
            val maxShift = early.maxOf { hypot(it.anchorX - first.anchorX, it.anchorY - first.anchorY) }
            if (maxShift < minMovementPx) return true
        }
        return false
    }

    private fun classifyTrack(
        trajectory: TrackTrajectory,
        suppressLostAtExit: Boolean,
        finalFrameIndex: Int?,
    ): TrackFateResult {
        val id = trajectory.trackId
        val samples = trajectory.samples
        if (samples.size < minStableFrames) {
            return TrackFateResult(id, 0, 0, TrackFate.UNDECIDED, "too few samples (${samples.size})")
        }

        val origin = resolvedOrigin(trajectory)
        val startupAtExit = origin == ExitCountingOrigin.STARTUP_DOOR ||
            origin == ExitCountingOrigin.STARTUP_OUTSIDE
        if (!startupAtExit && minBodyHeightRatio > 0f && samples.maxOf { it.bodyHeightRatio } < minBodyHeightRatio) {
            return TrackFateResult(id, 0, 0, TrackFate.PASSERBY, "small silhouette (far pedestrian)")
        }

        val visits = buildStableVisits(samples)
        val everInDoor = samples.any { it.inDoor }
        val tailWindow = samples.takeLast(lostAtDoorFrames.coerceAtLeast(2))
        val endAtExit = tailWindow.any { it.inDoor || it.zone == DoorZoneSide.OUTSIDE }
        if (visits.isEmpty()) {
            if (startupAtExit && !visibleAtEnd(samples.last(), finalFrameIndex)) {
                return TrackFateResult(id, 0, 1, TrackFate.EXITED, "startup exit-area track disappeared before clip end")
            }
            return TrackFateResult(id, 0, 0, TrackFate.UNDECIDED, "no stable zone visit")
        }

        val boardings = 0
        var alightings = 0
        val reasons = StringBuilder()

        if (startupAtExit) {
            val returnedInside = visits.any { it.zone == DoorZoneSide.INSIDE }
            val visibleAtEnd = visibleAtEnd(samples.last(), finalFrameIndex)
            if (!returnedInside && !visibleAtEnd) {
                alightings = 1
                reasons.append("startup exit-area track disappeared before clip end; ")
            } else if (origin == ExitCountingOrigin.STARTUP_DOOR && visits.first().zone == DoorZoneSide.OUTSIDE) {
                alightings = 1
                reasons.append("startup door -> street exit; ")
            } else {
                reasons.append("startup exit-area not counted directly (returnedInside=$returnedInside, visibleAtEnd=$visibleAtEnd); ")
            }
        }

        if (alightings == 0) {
            for (k in 1 until visits.size) {
                val a = visits[k - 1]
                val b = visits[k]
                if (a.zone != DoorZoneSide.INSIDE || b.zone != DoorZoneSide.OUTSIDE) continue

                val shrank = ratio(a.medianHead, b.medianHead) <= shrinkRatio
                val moved = distance(a, b) >= minMovementPx
                if (moved && (everInDoor || shrank)) {
                    alightings++
                    reasons.append("salon->street exit (moved=$moved, shrank=$shrank, door=$everInDoor); ")
                } else {
                    reasons.append("salon->street rejected (moved=$moved, shrank=$shrank, door=$everInDoor); ")
                }
            }
        }

        val lastVisit = visits.last()
        if (!suppressLostAtExit && alightings == 0 && lastVisit.zone == DoorZoneSide.INSIDE && endAtExit) {
            val insideVisit = visits.last { it.zone == DoorZoneSide.INSIDE }
            val tail = tailExitSample(samples)
            val shrank = ratio(insideVisit.medianHead, tail.headSize) <= shrinkRatio
            val moved = hypot(tail.anchorX - insideVisit.anchorX, tail.anchorY - insideVisit.anchorY) >= minMovementPx
            val preFinal = samples.getOrNull(samples.size - 2)
            val sustained = preFinal != null &&
                hypot(preFinal.anchorX - insideVisit.anchorX, preFinal.anchorY - insideVisit.anchorY) >= minMovementPx * 0.6f
            if (moved && sustained && (shrank || tail.zone == DoorZoneSide.OUTSIDE)) {
                alightings++
                reasons.append("lost-at-exit exit (tail=${tail.zone}, shrank=$shrank, sustained=true); ")
            } else if (moved && !sustained) {
                reasons.append("lost-at-exit rejected: movement only in final sample; ")
            }
        } else if (suppressLostAtExit && alightings == 0 && lastVisit.zone == DoorZoneSide.INSIDE && endAtExit) {
            reasons.append("lost-at-exit suppressed: stationary newborn successor; ")
        }

        val fate = fateOf(boardings, alightings, visits, everInDoor)
        if (fate == TrackFate.ONBOARD_STATIONARY) reasons.append("stationary inside (pre-boarded); ")
        if (fate == TrackFate.PASSERBY) reasons.append("street-only, never entered; ")

        return TrackFateResult(id, boardings, alightings, fate, reasons.toString().trim())
    }

    private fun tailExitSample(samples: List<TrajectorySample>): TrajectorySample {
        val tail = samples.takeLast(lostAtDoorFrames.coerceAtLeast(2))
        return tail.lastOrNull { it.zone == DoorZoneSide.OUTSIDE }
            ?: tail.lastOrNull { it.inDoor }
            ?: samples.last()
    }

    private fun ratio(base: Float, value: Float): Float = if (base <= 1e-6f) 1f else value / base

    private fun distance(a: ZoneVisit, b: ZoneVisit): Float = hypot(b.anchorX - a.anchorX, b.anchorY - a.anchorY)

    private fun resolvedOrigin(trajectory: TrackTrajectory): ExitCountingOrigin {
        if (trajectory.exitCountingOrigin != ExitCountingOrigin.UNKNOWN) return trajectory.exitCountingOrigin
        val first = trajectory.samples.firstOrNull() ?: return ExitCountingOrigin.UNKNOWN
        return if (first.zone == DoorZoneSide.INSIDE && first.inSalonSpawn) {
            ExitCountingOrigin.SPAWN_INSIDE
        } else {
            ExitCountingOrigin.INVALID
        }
    }

    private fun visibleAtEnd(lastSample: TrajectorySample, finalFrameIndex: Int?): Boolean {
        val finalFrame = finalFrameIndex ?: return false
        return finalFrame - lastSample.frameIndex <= lostAtDoorFrames.coerceAtLeast(2)
    }

    private fun fateOf(
        boardings: Int,
        alightings: Int,
        visits: List<ZoneVisit>,
        everInDoor: Boolean,
    ): TrackFate = when {
        boardings > 0 && alightings > 0 -> TrackFate.ENTERED_AND_EXITED
        boardings > 0 -> TrackFate.ENTERED
        alightings > 0 -> TrackFate.EXITED
        visits.all { it.zone == DoorZoneSide.INSIDE } -> TrackFate.ONBOARD_STATIONARY
        visits.all { it.zone == DoorZoneSide.OUTSIDE } && !everInDoor -> TrackFate.PASSERBY
        else -> TrackFate.UNDECIDED
    }

    private fun buildStableVisits(samples: List<TrajectorySample>): List<ZoneVisit> {
        val sided = samples.filter { it.zone != DoorZoneSide.BUFFER }
        if (sided.isEmpty()) return emptyList()
        val visits = mutableListOf<ZoneVisit>()
        var start = 0
        while (start < sided.size) {
            var end = start
            while (end + 1 < sided.size && sided[end + 1].zone == sided[start].zone) end++
            val run = sided.subList(start, end + 1)
            if (run.size >= minStableFrames) {
                visits += ZoneVisit(
                    zone = sided[start].zone,
                    medianHead = median(run.map { it.headSize }),
                    anchorX = run.map { it.anchorX }.average().toFloat(),
                    anchorY = run.map { it.anchorY }.average().toFloat(),
                )
            }
            start = end + 1
        }
        return visits
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private data class ZoneVisit(
        val zone: DoorZoneSide,
        val medianHead: Float,
        val anchorX: Float,
        val anchorY: Float,
    )
}
