package ru.rtds.pc.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.rtds.pc.model.Detection
import ru.rtds.pc.model.NormalizedPoint
import ru.rtds.pc.model.TrackedPerson

class CrossingLineCounterTest {
    private val zones = CountingZones.fromNormalizedPolygons(
        salonPolygonRatio = rectangle(0f, 0f, 1f, 0.45f),
        streetPolygonRatio = rectangle(0f, 0.55f, 1f, 1f),
        doorPolygonRatio = rectangle(0.35f, 0.40f, 0.65f, 0.60f),
        frameWidth = 100,
        frameHeight = 100,
    )

    private val counter = CrossingLineCounter(
        countAnchorXRatio = 0.5f,
        countAnchorYRatio = 0.75f,
        minAnchorMovementPx = 1f,
        headScaleGrowRatio = 1.05f,
        headScaleShrinkRatio = 0.95f,
        scaleWindow = 3,
        lostAtDoorFrames = 2,
        minDoorExitVisibleFrames = 3,
        minStableFrames = 1,
    )

    @Test
    fun `street to salon with head growth counts one boarding`() {
        val track = TrackedPerson(1, detection(anchorY = 70f, height = 20f))
        counter.updateTrackState(track, zones, 1)
        track.detection = detection(anchorY = 35f, height = 28f)

        val delta = counter.updateTrackState(track, zones, 2)

        assertEquals(1, delta.boardings)
        assertEquals(0, delta.alightings)
    }

    @Test
    fun `salon to street after door visit and shrink counts one alighting`() {
        val track = TrackedPerson(1, detection(anchorY = 35f, height = 28f))
        counter.updateTrackState(track, zones, 1)
        track.detection = detection(anchorY = 50f, height = 24f)
        counter.updateTrackState(track, zones, 2)
        track.detection = detection(anchorY = 70f, height = 20f)

        val delta = counter.updateTrackState(track, zones, 3)

        assertEquals(0, delta.boardings)
        assertEquals(1, delta.alightings)
    }

    @Test
    fun `lost at door after shrink counts one alighting`() {
        val track = TrackedPerson(1, detection(anchorY = 35f, height = 28f))
        counter.updateTrackState(track, zones, 1)
        track.detection = detection(anchorY = 50f, height = 22f)
        counter.updateTrackState(track, zones, 2)

        track.framesSinceUpdate = 1
        counter.updateTrackState(track, zones, 3)
        track.framesSinceUpdate = 2
        val delta = counter.updateTrackState(track, zones, 4)

        assertEquals(0, delta.boardings)
        assertEquals(1, delta.alightings)
    }

    @Test
    fun `preboarded person seen in door then outside counts one alighting`() {
        val track = TrackedPerson(1, detection(anchorY = 50f, height = 24f))
        counter.updateTrackState(track, zones, 1, allowPreboardedExit = true)
        track.detection = detection(anchorY = 70f, height = 22f)

        val delta = counter.updateTrackState(track, zones, 2, allowPreboardedExit = true)

        assertEquals(0, delta.boardings)
        assertEquals(1, delta.alightings)
    }

    @Test
    fun `person already standing in door and street does not count as alighting`() {
        val overlappingExitZones = CountingZones.fromNormalizedPolygons(
            salonPolygonRatio = rectangle(0f, 0f, 1f, 0.45f),
            streetPolygonRatio = rectangle(0.35f, 0.55f, 0.65f, 0.85f),
            doorPolygonRatio = rectangle(0.35f, 0.40f, 0.65f, 0.85f),
            frameWidth = 100,
            frameHeight = 100,
        )
        val track = TrackedPerson(1, detection(anchorY = 70f, height = 20f))

        val delta = counter.updateTrackState(track, overlappingExitZones, 1, allowPreboardedExit = true)

        assertEquals(0, delta.boardings)
        assertEquals(0, delta.alightings)
    }

    @Test
    fun `long visible door track lost at exit counts one alighting`() {
        val doorOnlyExitZones = CountingZones.fromNormalizedPolygons(
            salonPolygonRatio = rectangle(0f, 0f, 1f, 0.45f),
            streetPolygonRatio = rectangle(0.7f, 0.7f, 0.9f, 0.9f),
            doorPolygonRatio = rectangle(0.35f, 0.40f, 0.65f, 0.85f),
            frameWidth = 100,
            frameHeight = 100,
        )
        val track = TrackedPerson(1, detection(anchorY = 50f, height = 20f))
        counter.updateTrackState(track, doorOnlyExitZones, 1, allowPreboardedExit = true)
        counter.updateTrackState(track, doorOnlyExitZones, 4, allowPreboardedExit = true)
        counter.updateTrackState(track, doorOnlyExitZones, 7, allowPreboardedExit = true)

        track.framesSinceUpdate = 1
        counter.updateTrackState(track, doorOnlyExitZones, 8, allowPreboardedExit = true)
        track.framesSinceUpdate = 2
        val delta = counter.updateTrackState(track, doorOnlyExitZones, 9, allowPreboardedExit = true)

        assertEquals(0, delta.boardings)
        assertEquals(1, delta.alightings)
    }

    @Test
    fun `stable salon occupant does not increment counters`() {
        val track = TrackedPerson(1, detection(anchorY = 35f, height = 24f))

        counter.updateTrackState(track, zones, 1)
        track.detection = detection(anchorY = 34f, height = 24f)
        val delta = counter.updateTrackState(track, zones, 2)

        assertEquals(0, delta.boardings)
        assertEquals(0, delta.alightings)
    }

    @Test
    fun `salon without growth does not count boarding`() {
        val track = TrackedPerson(1, detection(anchorY = 70f, height = 24f))
        counter.updateTrackState(track, zones, 1)
        track.detection = detection(anchorY = 35f, height = 24f)

        val delta = counter.updateTrackState(track, zones, 2)

        assertEquals(0, delta.boardings)
        assertEquals(0, delta.alightings)
    }

    @Test
    fun `boarding is cancelled after return outside`() {
        val track = TrackedPerson(1, detection(anchorY = 70f, height = 20f))
        counter.updateTrackState(track, zones, 1)
        track.detection = detection(anchorY = 35f, height = 28f)
        counter.updateTrackState(track, zones, 2)
        track.detection = detection(anchorY = 50f, height = 24f)
        counter.updateTrackState(track, zones, 3)
        track.detection = detection(anchorY = 70f, height = 20f)

        val cancel = counter.updateTrackState(track, zones, 4)

        assertEquals(-1, cancel.boardings)
        assertEquals(0, cancel.alightings)
    }

    @Test
    fun `person outside both polygons stays buffer and is never counted`() {
        val isolatedZones = CountingZones.fromNormalizedPolygons(
            salonPolygonRatio = rectangle(0.1f, 0.1f, 0.3f, 0.3f),
            streetPolygonRatio = rectangle(0.7f, 0.7f, 0.9f, 0.9f),
            doorPolygonRatio = rectangle(0.45f, 0.45f, 0.55f, 0.55f),
            frameWidth = 100,
            frameHeight = 100,
        )
        val track = TrackedPerson(1, detection(anchorY = 50f))

        val first = counter.updateTrackState(track, isolatedZones, 1)
        track.detection = detection(anchorY = 51f)
        val second = counter.updateTrackState(track, isolatedZones, 2)

        assertEquals(0, first.boardings + first.alightings + second.boardings + second.alightings)
    }

    private fun rectangle(x1: Float, y1: Float, x2: Float, y2: Float): List<NormalizedPoint> = listOf(
        NormalizedPoint(x1, y1),
        NormalizedPoint(x2, y1),
        NormalizedPoint(x2, y2),
        NormalizedPoint(x1, y2),
    )

    private fun detection(anchorY: Float, height: Float = 20f): Detection {
        val y1 = anchorY - height * 0.75f
        return Detection(
            x1 = 45f,
            y1 = y1,
            x2 = 55f,
            y2 = y1 + height,
            confidence = 0.9f,
        )
    }
}
