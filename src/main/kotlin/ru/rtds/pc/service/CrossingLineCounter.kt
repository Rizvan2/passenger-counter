package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.rtds.pc.model.TrackedPerson

/**
 * Подсчёт пересечений ВЕРТИКАЛЬНОЙ линии (делит кадр на левую и правую части).
 *
 * Конвенция:
 *   "справа от линии" (X больше lineX) = снаружи маршрутки / улица
 *   "слева от линии"  (X меньше lineX) = салон
 *
 * Логика:
 * - Появился справа → перешёл влево = ВОШЁЛ в маршрутку (boarding)
 * - Появился слева  → перешёл вправо = ВЫШЕЛ из маршрутки (alighting)
 * - Если человек ходит туда-сюда — счётчик корректно откатывается при возврате на исходную сторону.
 */
@Service
class CrossingLineCounter {
    private val log = LoggerFactory.getLogger(javaClass)

    data class CountDelta(val boardings: Int, val alightings: Int) {
        val isEmpty: Boolean get() = boardings == 0 && alightings == 0
    }

    /**
     * @param lineX позиция вертикальной линии (X-координата в пикселях)
     */
    fun updateTrackState(track: TrackedPerson, lineX: Float): CountDelta {
        val currentX = track.detection.centerX
        val isRight = currentX > lineX  // справа от линии = снаружи

        // Первое появление: фиксируем сторону
        if (track.firstSideAbove == null) {
            // Переиспользуем поле firstSideAbove для "справа" (true) / "слева" (false)
            track.firstSideAbove = isRight
            track.sideHistory.add(isRight)
            return CountDelta(0, 0)
        }

        // Был на одной стороне, теперь на другой?
        val lastSide = track.sideHistory.last()
        if (lastSide != isRight) {
            track.sideHistory.add(isRight)
            track.crossingCount++
            val firstWasRight = track.firstSideAbove!!
            val nowOpposite = firstWasRight != isRight

            return if (nowOpposite && !track.isBoarded && !track.isAlighted) {
                if (firstWasRight) {
                    // Появился справа (с улицы) → перешёл влево (в салон) = ВОШЁЛ
                    track.isBoarded = true
                    log.debug("Track {} BOARDED (right -> left)", track.id)
                    CountDelta(boardings = 1, alightings = 0)
                } else {
                    // Появился слева (в салоне) → перешёл вправо (на улицу) = ВЫШЕЛ
                    track.isAlighted = true
                    log.debug("Track {} ALIGHTED (left -> right)", track.id)
                    CountDelta(boardings = 0, alightings = 1)
                }
            } else if (!nowOpposite && (track.isBoarded || track.isAlighted)) {
                // Вернулся на свою исходную сторону → откатываем
                if (track.isBoarded) {
                    track.isBoarded = false
                    log.debug("Track {} UN-BOARDED (returned to start side)", track.id)
                    CountDelta(boardings = -1, alightings = 0)
                } else {
                    track.isAlighted = false
                    log.debug("Track {} UN-ALIGHTED (returned to start side)", track.id)
                    CountDelta(boardings = 0, alightings = -1)
                }
            } else {
                CountDelta(0, 0)
            }
        }
        return CountDelta(0, 0)
    }
}
