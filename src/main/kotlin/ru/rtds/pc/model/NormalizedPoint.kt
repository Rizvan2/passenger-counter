package ru.rtds.pc.model

data class NormalizedPoint(
    val x: Float,
    val y: Float,
) {
    fun clamped(): NormalizedPoint = NormalizedPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
    )
}
