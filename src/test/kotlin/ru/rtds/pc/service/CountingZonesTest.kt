package ru.rtds.pc.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.NormalizedPoint

class CountingZonesTest {
    @Test
    fun `legacy horizontal split keeps top-bottom semantics`() {
        val zones = CountingZones.fromLegacyLine(
            lineAxRatio = 0f,
            lineAyRatio = 0.5f,
            lineBxRatio = 1f,
            lineByRatio = 0.5f,
            insideOnPositiveSide = false,
            frameWidth = 100,
            frameHeight = 100,
        )

        assertEquals(DoorZoneSide.INSIDE, zones.zoneFor(50f, 40f))
        assertEquals(DoorZoneSide.BUFFER, zones.zoneFor(50f, 50f))
        assertEquals(DoorZoneSide.OUTSIDE, zones.zoneFor(50f, 60f))
        assertTrue(zones.inDoor(50f, 50f))
    }

    @Test
    fun `overlap between polygons becomes buffer`() {
        val zones = CountingZones.fromNormalizedPolygons(
            salonPolygonRatio = rectangle(0.1f, 0.1f, 0.7f, 0.7f),
            streetPolygonRatio = rectangle(0.4f, 0.4f, 0.9f, 0.9f),
            doorPolygonRatio = rectangle(0.45f, 0.45f, 0.55f, 0.55f),
            frameWidth = 100,
            frameHeight = 100,
        )

        assertEquals(DoorZoneSide.BUFFER, zones.zoneFor(50f, 50f))
        assertEquals(DoorZoneSide.INSIDE, zones.zoneFor(20f, 20f))
        assertEquals(DoorZoneSide.OUTSIDE, zones.zoneFor(85f, 85f))
        assertTrue(zones.inDoor(50f, 50f))
    }

    @Test
    fun `point outside both polygons is buffer`() {
        val zones = CountingZones.fromNormalizedPolygons(
            salonPolygonRatio = rectangle(0.1f, 0.1f, 0.3f, 0.3f),
            streetPolygonRatio = rectangle(0.7f, 0.7f, 0.9f, 0.9f),
            doorPolygonRatio = rectangle(0.45f, 0.45f, 0.55f, 0.55f),
            frameWidth = 100,
            frameHeight = 100,
        )

        assertEquals(DoorZoneSide.BUFFER, zones.zoneFor(50f, 50f))
    }

    private fun rectangle(x1: Float, y1: Float, x2: Float, y2: Float): List<NormalizedPoint> = listOf(
        NormalizedPoint(x1, y1),
        NormalizedPoint(x2, y1),
        NormalizedPoint(x2, y2),
        NormalizedPoint(x1, y2),
    )
}
