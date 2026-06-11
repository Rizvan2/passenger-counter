package ru.rtds.pc.model

import kotlin.math.sqrt

data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
) {
    val centerX: Float get() = (x1 + x2) / 2f
    val centerY: Float get() = (y1 + y2) / 2f
    val bottomCenterX: Float get() = centerX
    val bottomCenterY: Float get() = y2
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1

    fun anchorY(ratioFromTop: Float): Float {
        val ratio = ratioFromTop.coerceIn(0f, 1f)
        return y1 + height * ratio
    }

    fun centerDistanceTo(other: Detection): Float {
        val dx = centerX - other.centerX
        val dy = centerY - other.centerY
        return sqrt(dx * dx + dy * dy)
    }

    fun iou(other: Detection): Float {
        val ix1 = maxOf(x1, other.x1)
        val iy1 = maxOf(y1, other.y1)
        val ix2 = minOf(x2, other.x2)
        val iy2 = minOf(y2, other.y2)
        if (ix2 <= ix1 || iy2 <= iy1) return 0f
        val intersection = (ix2 - ix1) * (iy2 - iy1)
        val union = width * height + other.width * other.height - intersection
        return intersection / union
    }
}
