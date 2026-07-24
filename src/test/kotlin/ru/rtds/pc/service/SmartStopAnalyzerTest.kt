package ru.rtds.pc.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.rtds.pc.config.SmartStopProperties
import ru.rtds.pc.model.DoorVisualState
import ru.rtds.pc.model.NormalizedPoint
import java.awt.Color
import java.awt.image.BufferedImage

class SmartStopAnalyzerTest {
    private val fullFrame = listOf(
        NormalizedPoint(0f, 0f),
        NormalizedPoint(1f, 0f),
        NormalizedPoint(1f, 1f),
        NormalizedPoint(0f, 1f),
    )

    @Test
    fun `confirmed door closure triggers stop after post roll`() {
        val analyzer = SmartStopAnalyzer(
            properties = properties(),
            enabled = true,
        )
        val open = striped(vertical = true)
        val closed = striped(vertical = false)

        analyzer.update(open, 0, 10.0, fullFrame, fullFrame, false, true)
        val calibrated = analyzer.update(open, 10, 10.0, fullFrame, fullFrame, false, false)
        assertEquals(DoorVisualState.OPEN, calibrated.doorState)

        analyzer.update(closed, 20, 10.0, fullFrame, fullFrame, false, false)
        analyzer.update(closed, 30, 10.0, fullFrame, fullFrame, false, false)
        val postRoll = analyzer.update(closed, 40, 10.0, fullFrame, fullFrame, false, false)
        assertEquals(SmartStopAnalyzer.REASON_DOOR_CLOSED, postRoll.finishReason)
        assertFalse(postRoll.stopTriggered)

        val stopped = analyzer.update(closed, 50, 10.0, fullFrame, fullFrame, false, false)
        assertTrue(stopped.stopTriggered)
    }

    @Test
    fun `passenger in doorway prevents close confirmation`() {
        val analyzer = SmartStopAnalyzer(properties(), enabled = true)
        val open = striped(vertical = true)
        val closed = striped(vertical = false)

        analyzer.update(open, 0, 10.0, fullFrame, fullFrame, false, true)
        analyzer.update(open, 10, 10.0, fullFrame, fullFrame, false, false)
        repeat(5) { index ->
            val snapshot = analyzer.update(
                closed,
                20 + index * 10,
                10.0,
                fullFrame,
                fullFrame,
                true,
                false,
            )
            assertEquals(DoorVisualState.UNKNOWN, snapshot.doorState)
            assertFalse(snapshot.stopTriggered)
        }
    }

    @Test
    fun `door reopening cancels post roll`() {
        val analyzer = SmartStopAnalyzer(properties(), enabled = true)
        val open = striped(vertical = true)
        val closed = striped(vertical = false)

        analyzer.update(open, 0, 10.0, fullFrame, fullFrame, false, true)
        analyzer.update(open, 10, 10.0, fullFrame, fullFrame, false, false)
        analyzer.update(closed, 20, 10.0, fullFrame, fullFrame, false, false)
        analyzer.update(closed, 30, 10.0, fullFrame, fullFrame, false, false)
        val postRoll = analyzer.update(closed, 40, 10.0, fullFrame, fullFrame, false, false)
        assertEquals(SmartStopAnalyzer.REASON_DOOR_CLOSED, postRoll.finishReason)

        val reopened = analyzer.update(open, 50, 10.0, fullFrame, fullFrame, false, false)

        assertFalse(reopened.stopTriggered)
        assertEquals(null, reopened.finishReason)
        assertEquals(DoorVisualState.OPEN, reopened.doorState)
    }

    @Test
    fun `observer stays calibrating until passenger activity was seen`() {
        val analyzer = SmartStopAnalyzer(properties(), enabled = true)
        val open = striped(vertical = true)

        val snapshot = analyzer.update(open, 100, 10.0, fullFrame, fullFrame, false, false)

        assertEquals(DoorVisualState.CALIBRATING, snapshot.doorState)
        assertEquals(0f, snapshot.calibrationProgress)
        assertFalse(snapshot.stopTriggered)
    }

    private fun properties() = SmartStopProperties(
        enabled = true,
        calibrationSamples = 2,
        minAnalysisSeconds = 0.0,
        doorOpenMaxDifference = 0.05f,
        doorClosedMinDifference = 0.10f,
        doorStableMotionMax = 1f,
        doorCloseConfirmSeconds = 2.0,
        doorClearSeconds = 0.0,
        inactivitySeconds = 100.0,
        sceneMotionThreshold = 1f,
        vehicleMotionConfirmSeconds = 100.0,
        postRollSeconds = 1.0,
    )

    private fun striped(vertical: Boolean): BufferedImage {
        val image = BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val firstBand = if (vertical) x < image.width / 2 else y < image.height / 2
                image.setRGB(x, y, if (firstBand) Color.WHITE.rgb else Color.DARK_GRAY.rgb)
            }
        }
        return image
    }
}
