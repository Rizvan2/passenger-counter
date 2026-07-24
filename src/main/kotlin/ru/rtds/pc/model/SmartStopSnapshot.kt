package ru.rtds.pc.model

enum class DoorVisualState {
    CALIBRATING,
    OPEN,
    CLOSED,
    UNKNOWN,
}

enum class SmartStopPhase {
    DISABLED,
    CALIBRATING,
    MONITORING,
    CLOSING,
    POST_ROLL,
    FINISHED,
}

data class SmartStopSnapshot(
    val enabled: Boolean = false,
    val phase: SmartStopPhase = SmartStopPhase.DISABLED,
    val doorState: DoorVisualState = DoorVisualState.UNKNOWN,
    val doorConfidence: Float = 0f,
    val calibrationProgress: Float = 0f,
    val doorReferenceDifference: Float = 0f,
    val doorMotionScore: Float = 0f,
    val passengerActivity: Boolean = false,
    val passengerActivitySeen: Boolean = false,
    val secondsSincePassengerActivity: Double = 0.0,
    val sceneMotionScore: Float = 0f,
    val vehicleMoving: Boolean = false,
    val vehicleMovingSeconds: Double = 0.0,
    val closedStableSeconds: Double = 0.0,
    val postRollRemainingSeconds: Double = 0.0,
    val videoTimeSeconds: Double = 0.0,
    val finishReason: String? = null,
    val stopTriggered: Boolean = false,
)
