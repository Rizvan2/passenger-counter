package ru.rtds.pc.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.ExitCountingOrigin
import ru.rtds.pc.model.TrackTrajectory
import ru.rtds.pc.model.TrajectorySample

class TrackFateClassifierTest {

    private val classifier = TrackFateClassifier(
        minStableFrames = 3,
        growRatio = 1.08f,
        shrinkRatio = 0.92f,
        minMovementPx = 40f,
        lostAtDoorFrames = 3,
        minBodyHeightRatio = 0.18f,
        successorMaxGapFrames = 15,
        successorMaxDistancePx = 120f,
    )

    @Test
    fun `standing passenger whose id switches is not a phantom exit`() {
        // Track A: stands in the salon where the door polygon overlaps; near death the box
        // jitters (shrinks a bit and drifts 45px) — previously this looked like an exit.
        val a = track(
            30,
            sample(0, DoorZoneSide.INSIDE, false, 500f, 320f, 50f),
            sample(1, DoorZoneSide.INSIDE, false, 500f, 321f, 50f),
            sample(2, DoorZoneSide.INSIDE, false, 501f, 320f, 50f),
            sample(3, DoorZoneSide.INSIDE, true, 520f, 300f, 44f),
            sample(4, DoorZoneSide.INSIDE, true, 540f, 290f, 40f),
        )
        // Track B: born 3 frames after A's death at the same spot, standing still -> id switch.
        val b = track(
            31,
            sample(7, DoorZoneSide.INSIDE, false, 505f, 318f, 50f),
            sample(8, DoorZoneSide.INSIDE, false, 506f, 319f, 50f),
            sample(9, DoorZoneSide.INSIDE, false, 505f, 318f, 51f),
            sample(10, DoorZoneSide.INSIDE, false, 506f, 318f, 50f),
        )
        val result = classifier.classify(listOf(a, b))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `strict policy does not count a merge fragment lost before street confirmation`() {
        // A and B coexist from the start (so neither is a newborn successor of the other).
        // A walks to the door shrinking and dies mid-way (merged into B); B completes the exit.
        val a = track(
            32,
            sample(0, DoorZoneSide.INSIDE, false, 400f, 320f, 52f),
            sample(1, DoorZoneSide.INSIDE, false, 400f, 318f, 52f),
            sample(2, DoorZoneSide.INSIDE, false, 401f, 319f, 52f),
            sample(3, DoorZoneSide.BUFFER, true, 390f, 260f, 44f),
            sample(4, DoorZoneSide.BUFFER, true, 385f, 220f, 40f),
        )
        val b = track(
            33,
            sample(0, DoorZoneSide.INSIDE, false, 430f, 330f, 50f),
            sample(1, DoorZoneSide.INSIDE, false, 429f, 328f, 50f),
            sample(2, DoorZoneSide.INSIDE, false, 430f, 329f, 50f),
            sample(4, DoorZoneSide.BUFFER, true, 420f, 250f, 42f),
            sample(5, DoorZoneSide.OUTSIDE, false, 415f, 180f, 34f),
            sample(6, DoorZoneSide.OUTSIDE, false, 414f, 178f, 33f),
            sample(7, DoorZoneSide.OUTSIDE, false, 415f, 177f, 33f),
        )
        b.exitCountingOrigin = ExitCountingOrigin.STARTUP_INSIDE
        val result = classifier.classify(listOf(a, b))
        assertEquals(0, result.boardings)
        assertEquals(1, result.alightings)
    }

    @Test
    fun `far small silhouette is a passer-by and never counted`() {
        // A pedestrian seen through the doorway: drifts across zones, but body stays tiny.
        val t = track(
            20,
            sample(0, DoorZoneSide.OUTSIDE, false, 350f, 150f, 20f, body = 0.10f),
            sample(1, DoorZoneSide.OUTSIDE, false, 360f, 150f, 20f, body = 0.11f),
            sample(2, DoorZoneSide.OUTSIDE, false, 370f, 150f, 21f, body = 0.10f),
            sample(3, DoorZoneSide.BUFFER, true, 380f, 160f, 22f, body = 0.12f),
            sample(4, DoorZoneSide.INSIDE, false, 390f, 170f, 22f, body = 0.11f),
            sample(5, DoorZoneSide.INSIDE, false, 400f, 175f, 23f, body = 0.12f),
            sample(6, DoorZoneSide.INSIDE, false, 410f, 178f, 23f, body = 0.11f),
        )
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
        assertEquals(TrackFate.PASSERBY, result.perTrack.single().fate)
    }

    @Test
    fun `track born at door that grows into salon is not counted as boarding`() {
        // Id switched mid-entry: this "salon half" has no stable OUTSIDE visit at all.
        val t = track(
            21,
            sample(0, DoorZoneSide.BUFFER, true, 350f, 200f, 30f),
            sample(1, DoorZoneSide.BUFFER, true, 352f, 230f, 34f),
            sample(2, DoorZoneSide.INSIDE, false, 356f, 280f, 44f),
            sample(3, DoorZoneSide.INSIDE, false, 360f, 300f, 48f),
            sample(4, DoorZoneSide.INSIDE, false, 362f, 305f, 50f),
            sample(5, DoorZoneSide.INSIDE, false, 363f, 306f, 51f),
        )
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `standing passenger brushing the door polygon is not a boarding`() {
        // Pre-boarded person standing where the door polygon overlaps the salon: door contact,
        // but no growth and no movement -> must NOT be counted as an entry.
        val t = track(
            22,
            sample(0, DoorZoneSide.BUFFER, true, 500f, 320f, 50f),
            sample(1, DoorZoneSide.INSIDE, false, 501f, 321f, 50f),
            sample(2, DoorZoneSide.INSIDE, false, 500f, 320f, 51f),
            sample(3, DoorZoneSide.INSIDE, false, 502f, 322f, 50f),
            sample(4, DoorZoneSide.INSIDE, true, 501f, 321f, 50f),
            sample(5, DoorZoneSide.INSIDE, false, 500f, 320f, 50f),
        )
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `street to salon with door and growth is ignored`() {
        val t = track(
            1,
            // 3x on the street, small head, high in frame
            sample(0, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(1, DoorZoneSide.OUTSIDE, false, 350f, 150f, 31f),
            sample(2, DoorZoneSide.OUTSIDE, false, 350f, 152f, 30f),
            // passing through the door
            sample(3, DoorZoneSide.BUFFER, true, 350f, 230f, 40f),
            sample(4, DoorZoneSide.BUFFER, true, 350f, 260f, 45f),
            // 3x confirmed inside, bigger head, lower in frame
            sample(5, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            sample(6, DoorZoneSide.INSIDE, false, 350f, 305f, 51f),
            sample(7, DoorZoneSide.INSIDE, false, 350f, 305f, 52f),
        )
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `salon to street with door and shrink is one alighting`() {
        val t = track(
            2,
            sample(0, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            sample(1, DoorZoneSide.INSIDE, false, 350f, 302f, 51f),
            sample(2, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            sample(3, DoorZoneSide.BUFFER, true, 350f, 250f, 40f),
            sample(4, DoorZoneSide.BUFFER, true, 350f, 200f, 34f),
            sample(5, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(6, DoorZoneSide.OUTSIDE, false, 350f, 150f, 29f),
            sample(7, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
        )
        t.exitCountingOrigin = ExitCountingOrigin.STARTUP_INSIDE
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(1, result.alightings)
    }

    @Test
    fun `stationary passenger inside is not counted`() {
        val t = track(
            3,
            *(0..6).map { sample(it, DoorZoneSide.INSIDE, false, 500f, 320f, 50f) }.toTypedArray(),
        )
        t.exitCountingOrigin = ExitCountingOrigin.STARTUP_INSIDE
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `street-only passer-by is not counted`() {
        val t = track(
            4,
            *(0..6).map { sample(it, DoorZoneSide.OUTSIDE, false, 350f, 120f, 20f) }.toTypedArray(),
        )
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `entered then lost deep in salon is not counted as exit`() {
        val t = track(
            5,
            // comes from street
            sample(0, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(1, DoorZoneSide.OUTSIDE, false, 350f, 150f, 31f),
            sample(2, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(3, DoorZoneSide.BUFFER, true, 350f, 240f, 42f),
            // confirmed inside
            sample(4, DoorZoneSide.INSIDE, false, 360f, 300f, 50f),
            sample(5, DoorZoneSide.INSIDE, false, 360f, 305f, 51f),
            sample(6, DoorZoneSide.INSIDE, false, 360f, 305f, 52f),
            // walks deeper into the salon (grows, NOT in door) then track ends
            sample(7, DoorZoneSide.INSIDE, false, 520f, 340f, 58f),
            sample(8, DoorZoneSide.INSIDE, false, 560f, 350f, 60f),
        )
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `inside then vanished at door with shrink counts old style exit`() {
        val t = track(
            6,
            sample(0, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            sample(1, DoorZoneSide.INSIDE, false, 350f, 302f, 51f),
            sample(2, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            // moves into the door and shrinks, then the track is lost (no OUTSIDE confirmation)
            sample(3, DoorZoneSide.BUFFER, true, 350f, 240f, 40f),
            sample(4, DoorZoneSide.BUFFER, true, 350f, 200f, 34f),
            sample(5, DoorZoneSide.BUFFER, true, 350f, 180f, 30f),
        )
        t.exitCountingOrigin = ExitCountingOrigin.STARTUP_INSIDE
        val result = classifier.classify(listOf(t))
        assertEquals(0, result.boardings)
        assertEquals(1, result.alightings)
    }

    @Test
    fun `two independent tracks sum their fates`() {
        val enter = track(
            10,
            sample(0, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(1, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(2, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(3, DoorZoneSide.BUFFER, true, 350f, 240f, 42f),
            sample(4, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            sample(5, DoorZoneSide.INSIDE, false, 350f, 300f, 51f),
            sample(6, DoorZoneSide.INSIDE, false, 350f, 300f, 52f),
        )
        val exit = track(
            11,
            sample(0, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            sample(1, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            sample(2, DoorZoneSide.INSIDE, false, 350f, 300f, 50f),
            sample(3, DoorZoneSide.BUFFER, true, 350f, 240f, 40f),
            sample(4, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(5, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(6, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
        )
        exit.exitCountingOrigin = ExitCountingOrigin.STARTUP_INSIDE
        val result = classifier.classify(listOf(enter, exit))
        assertEquals(0, result.boardings)
        assertEquals(1, result.alightings)
    }

    @Test
    fun `standing passenger absorbed by a neighbour box is not an exit`() {
        // Stands still the whole track; the FINAL sample jumps toward the neighbour who swallowed
        // his box (merge artifact): big displacement + shrink, in the door-overlap area. There is
        // no newborn successor (the neighbour's track existed before). Must NOT count as exit.
        val a = track(
            40,
            sample(0, DoorZoneSide.INSIDE, false, 500f, 320f, 50f),
            sample(1, DoorZoneSide.INSIDE, false, 500f, 321f, 50f),
            sample(2, DoorZoneSide.INSIDE, false, 501f, 320f, 50f),
            sample(3, DoorZoneSide.INSIDE, false, 500f, 320f, 51f),
            sample(4, DoorZoneSide.INSIDE, true, 430f, 260f, 40f),
        )
        // Neighbour who passed by and absorbed him — coexisted from the start.
        val b = track(
            41,
            sample(0, DoorZoneSide.INSIDE, false, 470f, 340f, 52f),
            sample(1, DoorZoneSide.INSIDE, false, 460f, 330f, 52f),
            sample(2, DoorZoneSide.INSIDE, false, 452f, 322f, 52f),
            sample(3, DoorZoneSide.INSIDE, false, 445f, 315f, 53f),
            sample(4, DoorZoneSide.INSIDE, false, 440f, 310f, 53f),
            sample(5, DoorZoneSide.INSIDE, false, 438f, 308f, 53f),
        )
        val result = classifier.classify(listOf(a, b))
        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `startup outside track counts only after disappearing before clip end`() {
        val t = track(
            50,
            sample(10, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(11, DoorZoneSide.OUTSIDE, false, 352f, 148f, 30f),
            sample(12, DoorZoneSide.OUTSIDE, false, 354f, 146f, 29f),
        )
        t.exitCountingOrigin = ExitCountingOrigin.STARTUP_OUTSIDE

        val result = classifier.classify(listOf(t), finalFrameIndex = 40)

        assertEquals(0, result.boardings)
        assertEquals(1, result.alightings)
    }

    @Test
    fun `startup outside track visible at clip end stays pending and is not counted`() {
        val t = track(
            51,
            sample(35, DoorZoneSide.OUTSIDE, false, 350f, 150f, 30f),
            sample(38, DoorZoneSide.OUTSIDE, false, 352f, 148f, 30f),
            sample(40, DoorZoneSide.OUTSIDE, false, 354f, 146f, 29f),
        )
        t.exitCountingOrigin = ExitCountingOrigin.STARTUP_OUTSIDE

        val result = classifier.classify(listOf(t), finalFrameIndex = 40)

        assertEquals(0, result.boardings)
        assertEquals(0, result.alightings)
    }

    private fun track(id: Int, vararg samples: TrajectorySample): TrackTrajectory {
        val t = TrackTrajectory(id)
        samples.forEach { t.add(it) }
        return t
    }

    private fun sample(frame: Int, zone: DoorZoneSide, inDoor: Boolean, x: Float, y: Float, head: Float, body: Float = 1f) =
        TrajectorySample(frameIndex = frame, zone = zone, inDoor = inDoor, anchorX = x, anchorY = y, headSize = head, bodyHeightRatio = body)
}
