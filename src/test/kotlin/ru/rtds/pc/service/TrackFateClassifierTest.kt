package ru.rtds.pc.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.TrackTrajectory
import ru.rtds.pc.model.TrajectorySample

class TrackFateClassifierTest {

    private val classifier = TrackFateClassifier(
        minStableFrames = 3,
        growRatio = 1.08f,
        shrinkRatio = 0.92f,
        minMovementPx = 40f,
        lostAtDoorFrames = 3,
    )

    @Test
    fun `street to salon with door and growth is one boarding`() {
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
        assertEquals(1, result.boardings)
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
        assertEquals(1, result.boardings)
        assertEquals(0, result.alightings)
    }

    @Test
    fun `inside then vanished at door with shrink is one alighting`() {
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
        val result = classifier.classify(listOf(enter, exit))
        assertEquals(1, result.boardings)
        assertEquals(1, result.alightings)
    }

    private fun track(id: Int, vararg samples: TrajectorySample): TrackTrajectory {
        val t = TrackTrajectory(id)
        samples.forEach { t.add(it) }
        return t
    }

    private fun sample(frame: Int, zone: DoorZoneSide, inDoor: Boolean, x: Float, y: Float, head: Float) =
        TrajectorySample(frameIndex = frame, zone = zone, inDoor = inDoor, anchorX = x, anchorY = y, headSize = head)
}
