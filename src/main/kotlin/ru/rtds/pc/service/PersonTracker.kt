package ru.rtds.pc.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.rtds.pc.model.Detection
import ru.rtds.pc.model.TrackedPerson
import java.awt.image.BufferedImage

/**
 * Трекер с двумя стратегиями ассоциации:
 * 1) IoU-матчинг с активными треками (для соседних кадров)
 * 2) ReID-матчинг с "потерянными" треками (для возвращения человека в кадр)
 *
 * Когда человек уходит из кадра — трек переходит в lostTracks с сохранёнными embedding.
 * Когда появляется новая детекция, у которой нет соседа по IoU, проверяем эмбеддинг
 * против lostTracks. Если совпало — это тот же человек, восстанавливаем его id.
 */
@Service
class PersonTracker(
    private val reidService: ReidService,
    @Value("\${pc.reid-similarity-threshold}") private val reidThreshold: Float,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val iouMatchThreshold = 0.3f
    private val maxFramesLostBeforeArchive = 30
    // Внутри одного видео lostTracks никогда не очищаются — мы помним всех до конца ролика

    private val activeTracks = mutableListOf<TrackedPerson>()
    private val lostTracks = mutableListOf<TrackedPerson>()
    private var nextTrackId = 1

    fun reset() {
        activeTracks.clear()
        lostTracks.clear()
        nextTrackId = 1
    }

    fun update(detections: List<Detection>, frame: BufferedImage): List<TrackedPerson> {
        val unmatchedDets = detections.toMutableList()

        // 1) IoU-матчинг активных треков
        for (track in activeTracks) {
            val best = unmatchedDets
                .map { it to it.iou(track.detection) }
                .filter { it.second >= iouMatchThreshold }
                .maxByOrNull { it.second }
                ?.first
            if (best != null) {
                track.detection = best
                track.framesSinceUpdate = 0
                unmatchedDets.remove(best)
            } else {
                track.framesSinceUpdate++
            }
        }

        // 2) Архивируем потерянные треки (но не забываем их полностью)
        val toArchive = activeTracks.filter { it.framesSinceUpdate > maxFramesLostBeforeArchive }
        if (toArchive.isNotEmpty()) {
            activeTracks.removeAll(toArchive)
            lostTracks.addAll(toArchive)
            log.debug("Archived {} tracks (now lost: {})", toArchive.size, lostTracks.size)
        }

        // 3) Для каждой непривязанной детекции — пробуем ReID против lostTracks
        val reidEnabled = reidService.isAvailable()
        for (det in unmatchedDets) {
            val embedding = if (reidEnabled) reidService.extract(frame, det) else null

            var resurrected: TrackedPerson? = null
            if (embedding != null) {
                var bestSim = reidThreshold
                var bestLost: TrackedPerson? = null
                for (lost in lostTracks) {
                    val lostEmb = lost.embedding ?: continue
                    val sim = reidService.similarity(embedding, lostEmb)
                    if (sim > bestSim) {
                        bestSim = sim
                        bestLost = lost
                    }
                }
                if (bestLost != null) {
                    resurrected = bestLost
                    lostTracks.remove(bestLost)
                    log.debug("ReID: resurrected track id={} (sim={})", bestLost.id, bestSim)
                }
            }

            if (resurrected != null) {
                // Восстанавливаем
                resurrected.detection = det
                resurrected.framesSinceUpdate = 0
                resurrected.embedding = embedding ?: resurrected.embedding
                activeTracks.add(resurrected)
            } else {
                // Новый трек
                val track = TrackedPerson(
                    id = nextTrackId++,
                    detection = det,
                    embedding = embedding,
                )
                activeTracks.add(track)
            }
        }

        // 4) Обновляем эмбеддинги активных треков периодически (раз в N кадров)
        if (reidEnabled) {
            for (track in activeTracks) {
                if (track.embedding == null) {
                    track.embedding = reidService.extract(frame, track.detection)
                }
            }
        }

        return activeTracks.toList()
    }

    fun getAllTracks(): List<TrackedPerson> = activeTracks + lostTracks
}
