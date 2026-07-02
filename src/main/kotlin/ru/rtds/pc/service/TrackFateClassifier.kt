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
    @Value("\${pc.count-min-body-height-ratio:0.0}") private val minBodyHeightRatio: Float,
    @Value("\${pc.count-successor-max-gap-frames:50}") private val successorMaxGapFrames: Int,
    @Value("\${pc.count-successor-max-distance-px:120}") private val successorMaxDistancePx: Float,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun classify(trajectories: Collection<TrackTrajectory>): OfflineCountResult {
        val list = trajectories.filter { it.samples.isNotEmpty() }
        val perTrack = list.map { classifyTrack(it, hasStationaryNewbornSuccessor(it, list)) }
        val boardings = perTrack.sumOf { it.boardings }
        val alightings = perTrack.sumOf { it.alightings }
        val fateCounts = perTrack.groupingBy { it.fate }.eachCount()
        if (log.isDebugEnabled) {
            perTrack.filter { it.boardings != 0 || it.alightings != 0 || it.fate != TrackFate.UNDECIDED }
                .forEach { log.debug("Track fate: #{} -> {} (b={}, a={}) : {}", it.trackId, it.fate, it.boardings, it.alightings, it.reason) }
        }
        return OfflineCountResult(boardings, alightings, perTrack, fateCounts)
    }

    /**
     * Id-switch detector for a STANDING person: track A dies and, right after, a NEW track is
     * born at (almost) the same spot and stands still. That is the same person re-identified
     * under a new id — not an exit — so A's lost-at-exit branch must be suppressed.
     *
     * Deliberately narrow so it does NOT fire on a merge of two exiting people: there the other
     * track already EXISTED before A died (they coexisted), so it is not a newborn successor,
     * and A keeps its lost-at-exit. A walking successor (next exiter appearing at the door, or an
     * id switch mid-exit) moves in its early window and is also not matched here.
     */
    private fun hasStationaryNewbornSuccessor(
        track: TrackTrajectory,
        all: List<TrackTrajectory>,
    ): Boolean {
        val last = track.samples.lastOrNull() ?: return false
        for (other in all) {
            if (other === track) continue
            val first = other.samples.firstOrNull() ?: continue
            // Born strictly AFTER this track's death, within a short gap.
            if (first.frameIndex <= last.frameIndex) continue
            if (first.frameIndex - last.frameIndex > successorMaxGapFrames) continue
            // Born where this track died.
            if (hypot(first.anchorX - last.anchorX, first.anchorY - last.anchorY) > successorMaxDistancePx) continue
            // ...and standing still in its early window (a re-born standing person, not a walker).
            val early = other.samples.take(minStableFrames.coerceAtLeast(2))
            val maxShift = early.maxOf { hypot(it.anchorX - first.anchorX, it.anchorY - first.anchorY) }
            if (maxShift < minMovementPx) return true
        }
        return false
    }

    private fun classifyTrack(trajectory: TrackTrajectory, suppressLostAtExit: Boolean): TrackFateResult {
        val id = trajectory.trackId
        val samples = trajectory.samples
        if (samples.size < minStableFrames) {
            return TrackFateResult(id, 0, 0, TrackFate.UNDECIDED, "too few samples (${samples.size})")
        }

        val visits = buildStableVisits(samples)
        val everInDoor = samples.any { it.inDoor }
        val tailWindow = samples.takeLast(lostAtDoorFrames.coerceAtLeast(2))
        val endAtExit = tailWindow.any { it.inDoor || it.zone == DoorZoneSide.OUTSIDE }

        // Far-silhouette guard: a street pedestrian seen through the doorway stays SMALL for the
        // whole track (never comes near the camera). A real boarder/alighter is close to the door
        // at some point, so their body box is large in frame at least once. If the body never
        // exceeded the ratio, this is a passer-by outside — never count it, whatever zones its
        // anchor drifted through.
        if (minBodyHeightRatio > 0f && samples.maxOf { it.bodyHeightRatio } < minBodyHeightRatio) {
            return TrackFateResult(id, 0, 0, TrackFate.PASSERBY, "small silhouette (far pedestrian)")
        }

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

        // If a track is first detected at the door and then stabilizes in the salon, there may be
        // no stable OUTSIDE visit at all (typical when an id switches mid-entry: the "salon half"
        // of the person is born at the door). Count it as a boarding, but ONLY with arrival
        // evidence — otherwise every pre-boarded passenger standing where the door polygon
        // overlaps the salon gets a phantom +1:
        //  * door contact happened at the START of the track (early samples), not just any brush;
        //  * the head GREW from the early samples to the stable salon visit (came toward camera);
        //  * the anchor really MOVED (standing people fail this).
        if (boardings == 0 && alightings == 0 && visits.first().zone == DoorZoneSide.INSIDE) {
            val earlyWindow = samples.take(minStableFrames.coerceAtLeast(2))
            val originAtDoor = earlyWindow.any { it.inDoor || it.zone == DoorZoneSide.OUTSIDE }
            if (originAtDoor) {
                val insideVisit = visits.first { it.zone == DoorZoneSide.INSIDE }
                val earlyHead = median(earlyWindow.map { it.headSize })
                val early = earlyWindow.first()
                val grew = ratio(earlyHead, insideVisit.medianHead) >= growRatio
                val moved = hypot(insideVisit.anchorX - early.anchorX, insideVisit.anchorY - early.anchorY) >= minMovementPx
                if (grew && moved) {
                    boardings++
                    reasons.append("born-at-door entry (grew + moved into salon); ")
                } else {
                    reasons.append("born-at-door rejected (grew=$grew, moved=$moved); ")
                }
            }
        }

        // "Left through the door but never re-confirmed OUTSIDE": the person walked from the salon
        // into the door/street edge and vanished without a stable OUTSIDE visit.
        // Requirements are conjunctive on purpose: the anchor must REALLY move toward the exit AND
        // either shrink (walked away from camera) or end on the street side. A standing person
        // whose box jitters (shrank-alone / moved-alone) must not qualify. And if this track has a
        // stationary newborn successor at its death spot, the "death" is an id switch of a standing
        // passenger — not an exit — so the whole branch is suppressed.
        val lastVisit = visits.last()
        if (!suppressLostAtExit && alightings == 0 && lastVisit.zone == DoorZoneSide.INSIDE && endAtExit) {
            val insideVisit = visits.last { it.zone == DoorZoneSide.INSIDE }
            val tail = tailExitSample(samples)
            val shrank = ratio(insideVisit.medianHead, tail.headSize) <= shrinkRatio
            val moved = hypot(tail.anchorX - insideVisit.anchorX, tail.anchorY - insideVisit.anchorY) >= minMovementPx
            // Sustained-travel guard: when a neighbour's box swallows a standing person, only the
            // FINAL sample(s) jump toward the neighbour — all "movement" lives in one corrupt
            // frame. A real exiter covers the distance over several frames, so the second-to-last
            // sample is already well on its way toward the exit. Require that pre-final progress.
            val preFinal = samples.getOrNull(samples.size - 2)
            val sustained = preFinal != null &&
                hypot(preFinal.anchorX - insideVisit.anchorX, preFinal.anchorY - insideVisit.anchorY) >= minMovementPx * 0.6f
            if (moved && sustained && (shrank || tail.zone == DoorZoneSide.OUTSIDE)) {
                alightings++
                reasons.append("lost-at-exit exit (tail=${tail.zone}, shrank=$shrank, sustained=true); ")
            } else if (moved && !sustained) {
                reasons.append("lost-at-exit rejected: movement only in final sample (box-merge artifact); ")
            }
        } else if (suppressLostAtExit && alightings == 0 && lastVisit.zone == DoorZoneSide.INSIDE && endAtExit) {
            reasons.append("lost-at-exit suppressed: stationary newborn successor (id switch); ")
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
