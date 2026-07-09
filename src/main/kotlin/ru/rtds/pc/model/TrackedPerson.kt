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
    var lastZone: DoorZoneSide = DoorZoneSide.BUFFER,
    var stableZone: DoorZoneSide = DoorZoneSide.BUFFER,
    var lastSeenZone: DoorZoneSide = DoorZoneSide.BUFFER,
    var stableZoneFrames: Int = 0,
    var transitionFrom: DoorZoneSide? = null,
    var stableAnchorX: Float? = null,
    var stableAnchorY: Float? = null,
    var stableBoxHeight: Float? = null,
    var smoothedHeadSize: Float? = null,
    val headSizeHistory: MutableList<Float> = mutableListOf(),
    var firstStableHeadSize: Float? = null,
    var lastStableHeadSize: Float? = null,
    var bornInDoor: Boolean = false,
    var isInDoor: Boolean = false,
    var wasInDoor: Boolean = false,
    var lastReliableInDoorFrame: Int? = null,
    var firstSeenFrame: Int? = null,
    var lastSeenFrame: Int? = null,
    var firstAnchorY: Float? = null,
    var firstAnchorX: Float? = null,
    var lastAnchorX: Float? = null,
    var lastAnchorY: Float? = null,
    var minAnchorX: Float = Float.POSITIVE_INFINITY,
    var maxAnchorX: Float = Float.NEGATIVE_INFINITY,
    var minAnchorY: Float = Float.POSITIVE_INFINITY,
    var maxAnchorY: Float = Float.NEGATIVE_INFINITY,
    var firstBoxHeight: Float? = null,
    var lastBoxHeight: Float? = null,
    var minBoxHeight: Float = Float.POSITIVE_INFINITY,
    var maxBoxHeight: Float = Float.NEGATIVE_INFINITY,
    var firstStableZone: DoorZoneSide? = null,
    var exitCountingOrigin: ExitCountingOrigin = ExitCountingOrigin.UNKNOWN,
    var countEligibleForExit: Boolean = false,
    var startupExitCandidate: Boolean = false,
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
    BOARDED,
    ALIGHTED
}

enum class DoorZoneSide {
    OUTSIDE,
    BUFFER,
    INSIDE
}

enum class ExitCountingOrigin {
    UNKNOWN,
    STARTUP_INSIDE,
    STARTUP_DOOR,
    STARTUP_OUTSIDE,
    SPAWN_INSIDE,
    INVALID
}
