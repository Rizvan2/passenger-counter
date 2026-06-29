package ru.rtds.pc.model

data class TrackedPerson(
    val id: Int,
    var detection: Detection,
    var framesSinceUpdate: Int = 0,
    var embedding: FloatArray? = null,
    val sideHistory: MutableList<Boolean> = mutableListOf(),
    var firstSideAbove: Boolean? = null,
    var isBoarded: Boolean = false,
    var isAlighted: Boolean = false,
    var crossingCount: Int = 0,
    var countedDirection: CountedDirection? = null,
    var lastCountFrame: Int = 0,
    var countState: TrackCountState = TrackCountState.UNKNOWN,
    var lastZone: DoorZoneSide = DoorZoneSide.DOORWAY,
    var stableZone: DoorZoneSide = DoorZoneSide.DOORWAY,
    var stableZoneFrames: Int = 0,
    var transitionFrom: DoorZoneSide? = null,
    var firstAnchorY: Float? = null,
    var lastAnchorY: Float? = null,
    var minAnchorY: Float = Float.POSITIVE_INFINITY,
    var maxAnchorY: Float = Float.NEGATIVE_INFINITY,
    var firstStableZone: DoorZoneSide? = null,
)

enum class CountedDirection {
    BOARDING,
    ALIGHTING,
    CANCEL_BOARDING,
    CANCEL_ALIGHTING
}

enum class TrackCountState {
    UNKNOWN,
    OUTSIDE,
    INSIDE,
    IN_DOORWAY_FROM_OUTSIDE,
    IN_DOORWAY_FROM_INSIDE,
    BOARDED,
    ALIGHTED
}

enum class DoorZoneSide {
    OUTSIDE,
    DOORWAY,
    INSIDE
}
