package ru.rtds.pc.persistence.analysis

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "analysis_results")
class AnalysisResultEntity(
    @Id
    @Column(name = "session_id", nullable = false, length = 64)
    var sessionId: String = "",

    @Column(name = "source_path", nullable = false, columnDefinition = "text")
    var sourcePath: String = "",

    // Имя файла отдельно от пути — путь меняется когда файл перемещается
    // в processed/, а имя остаётся стабильным идентификатором записи
    @Column(name = "video_name", nullable = false, length = 512)
    var videoName: String = "",

    // SHA-256 файла — для персистентного дедупа при рестарте приложения.
    // Без этого повторная store-and-forward доставка одного файла
    // после рестарта запустит анализ заново.
    @Column(name = "source_hash", length = 64)
    var sourceHash: String? = null,

    // Источник видео: FTP (с регистратора) или MANUAL (загружено вручную через UI)
    @Column(name = "source", nullable = false, length = 16)
    var source: String = Source.MANUAL.name,

    @Column(name = "status", nullable = false, length = 32)
    var status: String = "",

    @Column(name = "total_boardings", nullable = false)
    var totalBoardings: Int = 0,

    @Column(name = "total_alightings", nullable = false)
    var totalAlightings: Int = 0,

    @Column(name = "final_onboard", nullable = false)
    var finalOnboard: Int = 0,

    @Column(name = "initial_onboard", nullable = false)
    var initialOnboard: Int = 0,

    @Column(name = "frames_processed", nullable = false)
    var framesProcessed: Int = 0,

    @Column(name = "duration_ms", nullable = false)
    var durationMs: Long = 0,

    @Column(name = "line_y_ratio", nullable = false)
    var lineYRatio: Float = 0f,

    @Column(name = "inside_on_top", nullable = false)
    var insideOnTop: Boolean = true,

    @Column(name = "started_at_ms", nullable = false)
    var startedAtMs: Long = 0,

    @Column(name = "finished_at_ms", nullable = false)
    var finishedAtMs: Long = 0,

    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String? = null,
) {
    enum class Source { FTP, MANUAL }
}