package ru.rtds.pc.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DetectionTest {

    @Test
    fun `head region uses top part of person box`() {
        val person = Detection(x1 = 100f, y1 = 50f, x2 = 200f, y2 = 250f, confidence = 0.9f)

        val head = person.headRegion(
            frameWidth = 640,
            frameHeight = 480,
            heightRatio = 0.25f,
            widthRatio = 0.6f,
            minSizePx = 12f,
        )

        assertEquals(120f, head.x1)
        assertEquals(50f, head.y1)
        assertEquals(180f, head.x2)
        assertEquals(100f, head.y2)
        assertEquals(0.9f, head.confidence)
    }

    @Test
    fun `head region is clamped to frame bounds`() {
        val person = Detection(x1 = 0f, y1 = 0f, x2 = 20f, y2 = 40f, confidence = 0.8f)

        val head = person.headRegion(
            frameWidth = 15,
            frameHeight = 10,
            heightRatio = 0.5f,
            widthRatio = 1f,
            minSizePx = 12f,
        )

        assertEquals(0f, head.x1)
        assertEquals(0f, head.y1)
        assertEquals(15f, head.x2)
        assertEquals(10f, head.y2)
        assertEquals(0.8f, head.confidence)
    }

    @Test
    fun `bodyOrSelf returns attached body for tracking, head for counting`() {
        val person = Detection(x1 = 100f, y1 = 50f, x2 = 200f, y2 = 250f, confidence = 0.9f)
        val head = person.headRegion(
            frameWidth = 640,
            frameHeight = 480,
            heightRatio = 0.25f,
            widthRatio = 0.6f,
            minSizePx = 12f,
        ).copy(body = person)

        // counting uses the small head box
        assertEquals(120f, head.x1)
        assertEquals(100f, head.y2)
        // tracking uses the full body box
        assertEquals(person, head.bodyOrSelf)
        // a plain detection with no body tracks on itself
        assertEquals(person, person.bodyOrSelf)
    }
}
