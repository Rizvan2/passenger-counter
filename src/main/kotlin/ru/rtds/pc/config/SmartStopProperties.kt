package ru.rtds.pc.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "pc.smart-stop")
data class SmartStopProperties(
    val enabled: Boolean = true,
    val calibrationSamples: Int = 6,
    val requirePassengerActivityForCalibration: Boolean = true,
    val minAnalysisSeconds: Double = 8.0,
    val doorOpenMaxDifference: Float = 0.10f,
    val doorClosedMinDifference: Float = 0.22f,
    val doorStableMotionMax: Float = 0.30f,
    val doorCloseConfirmSeconds: Double = 4.0,
    val doorClearSeconds: Double = 2.0,
    val inactivitySeconds: Double = 30.0,
    val sceneMotionThreshold: Float = 0.14f,
    val vehicleMotionConfirmSeconds: Double = 5.0,
    val motionFallbackEnabled: Boolean = true,
    val postRollSeconds: Double = 3.0,
    val maxAnalysisSeconds: Double = 0.0,
)
