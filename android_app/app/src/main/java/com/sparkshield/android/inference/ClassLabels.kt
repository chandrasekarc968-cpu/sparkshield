package com.sparkshield.android.inference

import com.sparkshield.android.protocol.TelemetryFrame

/**
 * 4-class classification targets matching python_core/signal_models.py:SignalClass.
 */
enum class ClassLabels(val id: Int, val eventFlag: Int) {
    NORMAL(0, TelemetryFrame.FLAG_NORMAL),
    EMP(1, TelemetryFrame.FLAG_EMP),
    OPTICAL(2, TelemetryFrame.FLAG_OPTICAL),
    SURGE(3, TelemetryFrame.FLAG_SURGE);

    val isTamper: Boolean
        get() = this != NORMAL

    companion object {
        val NAMES = arrayOf("NORMAL", "EMP", "OPTICAL", "SURGE")

        fun fromId(id: Int): ClassLabels {
            return entries.firstOrNull { it.id == id } ?: NORMAL
        }

        fun fromFlag(flag: Int): ClassLabels {
            return entries.firstOrNull { (flag and it.eventFlag) != 0 } ?: NORMAL
        }
    }
}
