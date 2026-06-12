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
)
