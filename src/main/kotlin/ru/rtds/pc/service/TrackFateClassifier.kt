package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.DoorZoneSide
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

/**
 * Second pass of the offline pipeline. The first pass (detection + tracking + ReID) has already
 * recorded every track's full trajectory; here we decide each track's fate knowing the whole
 * path, which the streaming counter cannot do because it must decide as frames arrive.
 *
 * Model for this camera angle (oblique over the front door, deep salon mostly out of view):
 *  - the DOOR is the portal where tracks are born and die and where people are seen full-height;
 *  - direction is read from head-size trend (grows toward camera = inward, shrinks toward the
 *    bright doorway = outward);
 *  - the near-salon zone confirms an entry, but we do NOT require the person to stay in it,
 *    because they legitimately walk into the unseen depth of the salon right after.
 *
 * Counting is per track id; identity across occlusion is ReID's job, so one id == one person.
 */
@Service
class TrackFateClassifier(
    @Value("\${pc.count-min-stable-frames:3}") private val minStableFrames: Int,
    @Value("\${pc.count-head-scale-grow-ratio:1.08}") private val growRatio: Float,
    @Value("\${pc.count-head-scale-shrink-ratio:0.92}") private val shrinkRatio: Float,
    @Value("\${pc.count-min-anchor-movement-px:40}") private val minMovementPx: Float,
    @Value("\${pc.count-lost-at-door-frames:3}") private val lostAtDoorFrames: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun classify(trajectories: Collection<TrackTrajectory>): OfflineCountResult {
        val perTrack = trajectories.map { classifyTrack(it) }
        val boardings = perTrack.sumOf { it.boardings }
        val alightings = perTrack.sumOf { it.alightings }
        val fateCounts = perTrack.groupingBy { it.fate }.eachCount()
        if (log.isDebugEnabled) {
            perTrack.filter { it.boardings != 0 || it.alightings != 0 || it.fate != TrackFate.UNDECIDED }
                .forEach { log.debug("Track fate: #{} -> {} (b={}, a={}) : {}", it.trackId, it.fate, it.boardings, it.alightings, it.reason) }
        }
        return OfflineCountResult(boardings, alightings, perTrack, fateCounts)
    }

    private fun classifyTrack(trajectory: TrackTrajectory): TrackFateResult {
        val id = trajectory.trackId
        val samples = trajectory.samples
        if (samples.size < minStableFrames) {
            return TrackFateResult(id, 0, 0, TrackFate.UNDECIDED, "too few samples (${samples.size})")
        }

        val visits = buildStableVisits(samples)
        val everInDoor = samples.any { it.inDoor }
        val tailWindow = samples.takeLast(lostAtDoorFrames.coerceAtLeast(2))
        val endAtExit = tailWindow.any { it.inDoor || it.zone == DoorZoneSide.OUTSIDE }

        // No confirmed side at all: pure buffer/jitter, or seen too briefly to trust.
        if (visits.isEmpty()) {
            return TrackFateResult(id, 0, 0, TrackFate.UNDECIDED, "no stable zone visit")
        }

        var boardings = 0
        var alightings = 0
        val reasons = StringBuilder()

        // Count every confirmed side change. Because visits already require min-stable-frames and
        // collapse same-zone runs, jitter near the boundary cannot produce a transition here.
        for (k in 1 until visits.size) {
            val a = visits[k - 1]
            val b = visits[k]
            if (a.zone == DoorZoneSide.OUTSIDE && b.zone == DoorZoneSide.INSIDE) {
                val grew = ratio(a.medianHead, b.medianHead) >= growRatio
                val moved = distance(a, b) >= minMovementPx
                // Door passage is the strongest evidence of a real street->salon transit on this
                // angle; head growth is a fallback when the door polygon is poorly placed.
                if (moved && (everInDoor || grew)) {
                    boardings++
                    reasons.append("street->salon entry (moved=$moved, grew=$grew, door=$everInDoor); ")
                } else {
                    reasons.append("street->salon rejected (moved=$moved, grew=$grew, door=$everInDoor); ")
                }
            } else if (a.zone == DoorZoneSide.INSIDE && b.zone == DoorZoneSide.OUTSIDE) {
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

        // If a track is first detected in the door and then stabilizes in the salon, there may be
        // no stable OUTSIDE visit at all: the person entered while already under the camera.
        if (boardings == 0 && everInDoor && visits.first().zone == DoorZoneSide.INSIDE) {
            boardings++
            reasons.append("born-in-door -> salon entry; ")
        }

        // "Left through the door but never re-confirmed OUTSIDE": the person walked from the salon
        // into the door/street edge and vanished without a stable OUTSIDE visit.
        // Distinguished from "walked deep into the salon and sat" by: last confirmed side INSIDE,
        // last samples at the exit, head shrinking, or real movement toward the exit.
        val lastVisit = visits.last()
        if (alightings == 0 && lastVisit.zone == DoorZoneSide.INSIDE && endAtExit) {
            val insideVisit = visits.last { it.zone == DoorZoneSide.INSIDE }
            val tail = tailExitSample(samples)
            val shrank = ratio(insideVisit.medianHead, tail.headSize) <= shrinkRatio
            val moved = hypot(tail.anchorX - insideVisit.anchorX, tail.anchorY - insideVisit.anchorY) >= minMovementPx
            if (tail.zone == DoorZoneSide.OUTSIDE || shrank || moved) {
                alightings++
                reasons.append("lost-at-exit exit (tail=${tail.zone}, shrank=$shrank, moved=$moved); ")
            }
        }

        val fate = fateOf(boardings, alightings, visits, everInDoor)
        if (fate == TrackFate.ONBOARD_STATIONARY) reasons.append("stationary inside (pre-boarded); ")
        if (fate == TrackFate.PASSERBY) reasons.append("street-only, never entered; ")

        return TrackFateResult(id, boardings, alightings, fate, reasons.toString().trim())
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

    /**
     * Compress the sample stream (ignoring BUFFER as a transparent neutral zone) into maximal
     * runs of the same confirmed side, keeping only runs of at least min-stable-frames.
     */
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

    private fun tailExitSample(samples: List<TrajectorySample>): TrajectorySample {
        val tail = samples.takeLast(lostAtDoorFrames.coerceAtLeast(2))
        // Prefer the last sample actually seen at the exit, else the very last sample.
        return tail.lastOrNull { it.zone == DoorZoneSide.OUTSIDE }
            ?: tail.lastOrNull { it.inDoor }
            ?: samples.last()
    }

    private fun ratio(base: Float, value: Float): Float = if (base <= 1e-6f) 1f else value / base

    private fun distance(a: ZoneVisit, b: ZoneVisit): Float = hypot(b.anchorX - a.anchorX, b.anchorY - a.anchorY)

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
