package ru.rtds.pc.model

import kotlin.math.sqrt

data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    /**
     * When head-tracking is on, this detection is the small HEAD box used for counting,
     * and [body] holds the original full person box. The tracker matches on [body] (large and
     * stable, strong ReID), while zones/anchor/scale use this head box. Null when the detection
     * is already the box we want to track (native head detector, or head-tracking disabled).
     */
    val body: Detection? = null,
) {
    /** Box to use for tracking/association/ReID: the body when present, otherwise self. */
    val bodyOrSelf: Detection get() = body ?: this

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

    fun anchorX(ratioFromLeft: Float): Float {
        val ratio = ratioFromLeft.coerceIn(0f, 1f)
        return x1 + width * ratio
    }

    fun headRegion(
        frameWidth: Int,
        frameHeight: Int,
        heightRatio: Float,
        widthRatio: Float,
        minSizePx: Float,
    ): Detection {
        val safeFrameWidth = frameWidth.toFloat().coerceAtLeast(1f)
        val safeFrameHeight = frameHeight.toFloat().coerceAtLeast(1f)
        val headHeight = (height * heightRatio.coerceIn(0.05f, 1f)).coerceAtLeast(minSizePx)
        val headWidth = (width * widthRatio.coerceIn(0.05f, 1f)).coerceAtLeast(minSizePx)
        val cx = centerX

        return copy(
            x1 = (cx - headWidth / 2f).coerceIn(0f, safeFrameWidth),
            y1 = y1.coerceIn(0f, safeFrameHeight),
            x2 = (cx + headWidth / 2f).coerceIn(0f, safeFrameWidth),
            y2 = (y1 + headHeight).coerceIn(0f, safeFrameHeight),
            body = null,
        )
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
