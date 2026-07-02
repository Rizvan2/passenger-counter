package ru.rtds.pc.model

/**
 * One recorded observation of a visible track on a processed frame.
 *
 * Only visible samples (detection actually present, not coasting) are recorded — coasting
 * frames carry stale boxes and would distort the head-size trend. The end of the sample list
 * therefore marks the last frame the track was really seen, which is what the offline
 * classifier uses to decide "left through the door" vs "walked deeper into the salon".
 */
data class TrajectorySample(
    val frameIndex: Int,
    val zone: DoorZoneSide,
    val inDoor: Boolean,
    val anchorX: Float,
    val anchorY: Float,
    /** Scale proxy: hypot(boxWidth, boxHeight). For a head box this is head size. */
    val headSize: Float,
    /**
     * Height of the full person (body) box as a fraction of frame height. Far-away street
     * pedestrians seen through the doorway stay small for their whole track; a track whose
     * body never exceeds a threshold ratio is classified as a passer-by and never counted.
     * Defaults to 1 (fully visible) so synthetic samples in tests behave as близкие люди.
     */
    val bodyHeightRatio: Float = 1f,
)

/** Full time-series of one track id across the whole clip. */
class TrackTrajectory(val trackId: Int) {
    val samples: MutableList<TrajectorySample> = ArrayList()

    fun add(sample: TrajectorySample) {
        samples.add(sample)
    }
}

/**
 * Per-run store of every track's trajectory. Instantiated locally for a single analysis run
 * (not a shared Spring bean) so nothing leaks between sessions.
 */
class TrackTrajectoryStore {
    private val byTrack = LinkedHashMap<Int, TrackTrajectory>()

    fun record(trackId: Int, sample: TrajectorySample) {
        byTrack.getOrPut(trackId) { TrackTrajectory(trackId) }.add(sample)
    }

    fun trajectories(): Collection<TrackTrajectory> = byTrack.values

    val size: Int get() = byTrack.size

    fun clear() = byTrack.clear()
}
