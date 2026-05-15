package ru.rtds.pc.model

/**
 * Один отслеживаемый человек в рамках сессии обработки видео.
 *
 * id — уникальный идентификатор. Может быть переиспользован при re-ID:
 * если человек ушёл из кадра и вернулся, и его embedding совпал —
 * получит тот же id.
 *
 * counted — учтён ли этот человек как пассажир (boarded или alighted).
 * Если человек переходит через линию несколько раз туда-сюда, считаем
 * его окончательное направление в конце видео.
 */
data class TrackedPerson(
    val id: Int,
    var detection: Detection,
    var framesSinceUpdate: Int = 0,
    // Эмбеддинг ReID — для определения того же человека после возвращения
    var embedding: FloatArray? = null,
    // История пересечений линии (true = был выше линии)
    val sideHistory: MutableList<Boolean> = mutableListOf(),
    // Состояние: где впервые увидели человека
    var firstSideAbove: Boolean? = null,
    // Текущее состояние: помечен как зашедший
    var isBoarded: Boolean = false,
    // Текущее состояние: помечен как вышедший
    var isAlighted: Boolean = false,
    // Сколько раз пересекал линию
    var crossingCount: Int = 0,
)
