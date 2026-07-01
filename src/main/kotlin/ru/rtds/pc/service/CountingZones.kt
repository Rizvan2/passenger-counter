package ru.rtds.pc.service

import ru.rtds.pc.dto.LinePointDto
import ru.rtds.pc.model.Detection
import ru.rtds.pc.model.DoorZoneSide
import ru.rtds.pc.model.NormalizedPoint
import kotlin.math.abs

data class CountingZones(
    val salonPolygonRatio: List<NormalizedPoint>,
    val streetPolygonRatio: List<NormalizedPoint>,
    val doorPolygonRatio: List<NormalizedPoint>,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    val salonPolygonPx: List<LinePointDto> = salonPolygonRatio.map { it.toPixel(frameWidth, frameHeight) }
    val streetPolygonPx: List<LinePointDto> = streetPolygonRatio.map { it.toPixel(frameWidth, frameHeight) }
    val doorPolygonPx: List<LinePointDto> = doorPolygonRatio.map { it.toPixel(frameWidth, frameHeight) }

    fun zoneFor(anchorX: Float, anchorY: Float): DoorZoneSide {
        val inSalon = pointInPolygon(anchorX, anchorY, salonPolygonPx)
        val inStreet = pointInPolygon(anchorX, anchorY, streetPolygonPx)
        return when {
            inSalon && inStreet -> DoorZoneSide.BUFFER
            inSalon -> DoorZoneSide.INSIDE
            inStreet -> DoorZoneSide.OUTSIDE
            else -> DoorZoneSide.BUFFER
        }
    }

    fun zoneFor(detection: Detection, anchorXRatio: Float, anchorYRatio: Float): DoorZoneSide =
        zoneFor(detection.anchorX(anchorXRatio), detection.anchorY(anchorYRatio))

    fun pointInSalon(detection: Detection, anchorXRatio: Float, anchorYRatio: Float): Boolean =
        pointInPolygon(detection.anchorX(anchorXRatio), detection.anchorY(anchorYRatio), salonPolygonPx)

    fun inDoor(anchorX: Float, anchorY: Float): Boolean =
        pointInPolygon(anchorX, anchorY, doorPolygonPx)

    fun inDoor(detection: Detection, anchorXRatio: Float, anchorYRatio: Float): Boolean =
        inDoor(detection.anchorX(anchorXRatio), detection.anchorY(anchorYRatio))

    companion object {
        fun sanitize(points: List<NormalizedPoint>?): List<NormalizedPoint> =
            points.orEmpty().map { it.clamped() }.takeIf { it.size >= 3 } ?: emptyList()

        fun fromNormalizedPolygons(
            salonPolygonRatio: List<NormalizedPoint>,
            streetPolygonRatio: List<NormalizedPoint>,
            doorPolygonRatio: List<NormalizedPoint>,
            frameWidth: Int,
            frameHeight: Int,
        ): CountingZones = CountingZones(
            salonPolygonRatio = sanitize(salonPolygonRatio),
            streetPolygonRatio = sanitize(streetPolygonRatio),
            doorPolygonRatio = sanitize(doorPolygonRatio),
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )

        fun fromLegacyLine(
            lineAxRatio: Float,
            lineAyRatio: Float,
            lineBxRatio: Float,
            lineByRatio: Float,
            insideOnPositiveSide: Boolean,
            frameWidth: Int,
            frameHeight: Int,
        ): CountingZones {
            val lineA = PixelPoint(
                x = lineAxRatio.coerceIn(0f, 1f) * frameWidth,
                y = lineAyRatio.coerceIn(0f, 1f) * frameHeight,
            )
            val lineB = PixelPoint(
                x = lineBxRatio.coerceIn(0f, 1f) * frameWidth,
                y = lineByRatio.coerceIn(0f, 1f) * frameHeight,
            )
            val frame = listOf(
                PixelPoint(0f, 0f),
                PixelPoint(frameWidth.toFloat(), 0f),
                PixelPoint(frameWidth.toFloat(), frameHeight.toFloat()),
                PixelPoint(0f, frameHeight.toFloat()),
            )
            val salon = clipPolygonToHalfPlane(frame, lineA, lineB, keepPositive = insideOnPositiveSide)
            val street = clipPolygonToHalfPlane(frame, lineA, lineB, keepPositive = !insideOnPositiveSide)
            val door = legacyDoorPolygon(lineA, lineB, frameWidth.toFloat(), frameHeight.toFloat())
            return CountingZones(
                salonPolygonRatio = salon.map {
                    NormalizedPoint(
                        x = (it.x / frameWidth).coerceIn(0f, 1f),
                        y = (it.y / frameHeight).coerceIn(0f, 1f),
                    )
                },
                streetPolygonRatio = street.map {
                    NormalizedPoint(
                        x = (it.x / frameWidth).coerceIn(0f, 1f),
                        y = (it.y / frameHeight).coerceIn(0f, 1f),
                    )
                },
                doorPolygonRatio = door.map {
                    NormalizedPoint(
                        x = (it.x / frameWidth).coerceIn(0f, 1f),
                        y = (it.y / frameHeight).coerceIn(0f, 1f),
                    )
                },
                frameWidth = frameWidth,
                frameHeight = frameHeight,
            )
        }

        fun legacyPolygonsFromLine(
            lineAxRatio: Float,
            lineAyRatio: Float,
            lineBxRatio: Float,
            lineByRatio: Float,
            insideOnPositiveSide: Boolean,
        ): Triple<List<NormalizedPoint>, List<NormalizedPoint>, List<NormalizedPoint>> {
            val zones = fromLegacyLine(
                lineAxRatio = lineAxRatio,
                lineAyRatio = lineAyRatio,
                lineBxRatio = lineBxRatio,
                lineByRatio = lineByRatio,
                insideOnPositiveSide = insideOnPositiveSide,
                frameWidth = 1000,
                frameHeight = 1000,
            )
            return Triple(zones.salonPolygonRatio, zones.streetPolygonRatio, zones.doorPolygonRatio)
        }

        fun pointInPolygon(x: Float, y: Float, polygon: List<LinePointDto>): Boolean {
            if (polygon.size < 3) return false
            var inside = false
            var j = polygon.lastIndex
            for (i in polygon.indices) {
                val pi = polygon[i]
                val pj = polygon[j]
                val intersects = ((pi.y > y) != (pj.y > y)) &&
                    (x < (pj.x - pi.x) * (y - pi.y) / ((pj.y - pi.y).takeIf { abs(it) > 1e-6f } ?: 1e-6f) + pi.x)
                if (intersects) inside = !inside
                j = i
            }
            return inside
        }

        private fun clipPolygonToHalfPlane(
            polygon: List<PixelPoint>,
            lineA: PixelPoint,
            lineB: PixelPoint,
            keepPositive: Boolean,
        ): List<PixelPoint> {
            if (polygon.isEmpty()) return emptyList()
            val result = mutableListOf<PixelPoint>()
            var previous = polygon.last()
            var previousInside = isInsideHalfPlane(previous, lineA, lineB, keepPositive)
            for (current in polygon) {
                val currentInside = isInsideHalfPlane(current, lineA, lineB, keepPositive)
                if (currentInside != previousInside) {
                    result += intersection(previous, current, lineA, lineB)
                }
                if (currentInside) {
                    result += current
                }
                previous = current
                previousInside = currentInside
            }
            return result
        }

        private fun isInsideHalfPlane(point: PixelPoint, lineA: PixelPoint, lineB: PixelPoint, keepPositive: Boolean): Boolean {
            val cross = signedCross(lineA, lineB, point)
            return if (keepPositive) cross >= -1e-4f else cross <= 1e-4f
        }

        private fun intersection(
            start: PixelPoint,
            end: PixelPoint,
            lineA: PixelPoint,
            lineB: PixelPoint,
        ): PixelPoint {
            val startCross = signedCross(lineA, lineB, start)
            val endCross = signedCross(lineA, lineB, end)
            val denom = startCross - endCross
            val t = if (abs(denom) < 1e-6f) 0f else startCross / denom
            return PixelPoint(
                x = start.x + (end.x - start.x) * t,
                y = start.y + (end.y - start.y) * t,
            )
        }

        private fun signedCross(a: PixelPoint, b: PixelPoint, p: PixelPoint): Float =
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

        private fun legacyDoorPolygon(
            lineA: PixelPoint,
            lineB: PixelPoint,
            frameWidth: Float,
            frameHeight: Float,
        ): List<PixelPoint> {
            val midX = (lineA.x + lineB.x) / 2f
            val midY = (lineA.y + lineB.y) / 2f
            val lineLength = kotlin.math.hypot(lineB.x - lineA.x, lineB.y - lineA.y)
            val doorWidth = (lineLength * 0.35f).coerceAtLeast(frameWidth * 0.08f).coerceAtMost(frameWidth * 0.28f)
            val doorHeight = (frameHeight * 0.22f).coerceAtLeast(90f).coerceAtMost(frameHeight * 0.45f)
            val halfWidth = doorWidth / 2f
            val halfHeight = doorHeight / 2f
            return listOf(
                PixelPoint((midX - halfWidth).coerceIn(0f, frameWidth), (midY - halfHeight).coerceIn(0f, frameHeight)),
                PixelPoint((midX + halfWidth).coerceIn(0f, frameWidth), (midY - halfHeight).coerceIn(0f, frameHeight)),
                PixelPoint((midX + halfWidth).coerceIn(0f, frameWidth), (midY + halfHeight).coerceIn(0f, frameHeight)),
                PixelPoint((midX - halfWidth).coerceIn(0f, frameWidth), (midY + halfHeight).coerceIn(0f, frameHeight)),
            )
        }
    }

    private fun NormalizedPoint.toPixel(frameWidth: Int, frameHeight: Int): LinePointDto =
        LinePointDto(
            x = x.coerceIn(0f, 1f) * frameWidth,
            y = y.coerceIn(0f, 1f) * frameHeight,
        )

    private data class PixelPoint(
        val x: Float,
        val y: Float,
    )
}
