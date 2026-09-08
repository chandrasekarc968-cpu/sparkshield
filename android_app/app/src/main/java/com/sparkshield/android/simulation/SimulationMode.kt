package com.sparkshield.android.simulation

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.protocol.TelemetryFrame
import com.sparkshield.android.protocol.TelemetryFrameParser

/**
 * Supported simulation attack modes for the SparkShield Simulation Lab.
 *
 * SAFETY BOUNDARY:
 * Software-only models for cyber-physical visualization and education.
 * Never controls real physical equipment, lasers, or high-voltage hardware.
 */
enum class SimulationMode(val displayName: String, val eventFlag: Int, val classLabel: ClassLabels) {
    NORMAL("Normal Grid", TelemetryFrame.FLAG_NORMAL, ClassLabels.NORMAL),
    EMP("EMP Transient", TelemetryFrame.FLAG_EMP, ClassLabels.EMP),
    OPTICAL("Optical Blinding", TelemetryFrame.FLAG_OPTICAL, ClassLabels.OPTICAL),
    SURGE("Inductive Surge", TelemetryFrame.FLAG_SURGE, ClassLabels.SURGE);

    companion object {
        fun fromString(name: String?): SimulationMode {
            return when (name?.uppercase()) {
                "EMP" -> EMP
                "OPTICAL" -> OPTICAL
                "SURGE" -> SURGE
                else -> NORMAL
            }
        }
    }
}
