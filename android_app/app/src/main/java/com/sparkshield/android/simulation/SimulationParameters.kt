package com.sparkshield.android.simulation

sealed interface SimulationParameters {
    val seed: Long
    fun validate(): List<String>
}

data class NormalParameters(
    val lineFreqHz: Float = 50.0f,
    val baseAmplitudeMv: Float = 3250.0f,
    val harmonicStrengthMv: Float = 35.0f,
    val surgeAmplitudeMv: Float = 0.0f,
    val noiseLevelMv: Float = 10.0f,
    val durationMs: Float = 20.0f,
    override val seed: Long = 42L
) : SimulationParameters {
    override fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (lineFreqHz != 50.0f && lineFreqHz != 60.0f) {
            errors.add("Line frequency must be 50 or 60 Hz")
        }
        if (baseAmplitudeMv !in 2000.0f..5000.0f) {
            errors.add("Base amplitude must be between 2000 and 5000 mV")
        }
        if (harmonicStrengthMv !in 0.0f..200.0f) {
            errors.add("Harmonic strength must be between 0 and 200 mV")
        }
        if (noiseLevelMv !in 0.0f..100.0f) {
            errors.add("Noise level must be between 0 and 100 mV")
        }
        if (durationMs !in 5.0f..200.0f) {
            errors.add("Duration must be between 5 and 200 ms")
        }
        return errors
    }
}

data class EmpParameters(
    val peakVoltageMv: Float = 52000.0f,
    val riseTimeNs: Float = 30.0f,
    val decayTimeUs: Float = 8.0f,
    val resonantFreqMhz: Float = 25.0f,
    val rfNoiseMv: Float = 80.0f,
    val ringDownMv: Float = 1500.0f,
    override val seed: Long = 42L
) : SimulationParameters {
    override fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (peakVoltageMv !in 15000.0f..65535.0f) {
            errors.add("EMP peak voltage must be between 15,000 and 65,535 mV")
        }
        if (riseTimeNs !in 10.0f..500.0f) {
            errors.add("EMP rise time must be between 10 and 500 ns")
        }
        if (decayTimeUs !in 0.5f..100.0f) {
            errors.add("EMP decay time must be between 0.5 and 100 µs")
        }
        if (resonantFreqMhz !in 1.0f..100.0f) {
            errors.add("Resonant frequency must be between 1 and 100 MHz")
        }
        return errors
    }
}

data class OpticalParameters(
    val saturationVoltageMv: Float = 4500.0f,
    val opticalOnsetMs: Float = 2.0f,
    val riseConstantMs: Float = 1.5f,
    val rippleNoiseMv: Float = 15.0f,
    val sustainedDurationMs: Float = 50.0f,
    override val seed: Long = 42L
) : SimulationParameters {
    override fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (saturationVoltageMv !in 2500.0f..5000.0f) {
            errors.add("Saturation voltage must be between 2500 and 5000 mV")
        }
        if (opticalOnsetMs !in 0.0f..20.0f) {
            errors.add("Optical onset must be between 0 and 20 ms")
        }
        if (riseConstantMs !in 0.1f..20.0f) {
            errors.add("Rise constant must be between 0.1 and 20 ms")
        }
        return errors
    }
}

data class SurgeParameters(
    val surgeAmplitudeMv: Float = 12000.0f,
    val riseTimeUs: Float = 20.0f,
    val decayTimeMs: Float = 2.5f,
    val ringFreqKhz: Float = 50.0f,
    val baselineVoltageMv: Float = 3250.0f,
    val noiseLevelMv: Float = 25.0f,
    override val seed: Long = 42L
) : SimulationParameters {
    override fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (surgeAmplitudeMv !in 4000.0f..30000.0f) {
            errors.add("Surge amplitude must be between 4,000 and 30,000 mV")
        }
        if (riseTimeUs !in 0.5f..100.0f) {
            errors.add("Surge rise time must be between 0.5 and 100 µs")
        }
        if (decayTimeMs !in 0.1f..20.0f) {
            errors.add("Surge decay time must be between 0.1 and 20 ms")
        }
        if (ringFreqKhz !in 5.0f..500.0f) {
            errors.add("Ring frequency must be between 5 and 500 kHz")
        }
        return errors
    }
}

data class SimulationPreset(
    val name: String,
    val mode: SimulationMode,
    val parameters: SimulationParameters
)

object SimulationPresets {
    val CLEAN_BASELINE = SimulationPreset("Clean baseline", SimulationMode.NORMAL, NormalParameters())
    val STRONG_EMP = SimulationPreset("Strong EMP", SimulationMode.EMP, EmpParameters(peakVoltageMv = 58000.0f, riseTimeNs = 20.0f, decayTimeUs = 6.0f, resonantFreqMhz = 30.0f, rfNoiseMv = 120.0f, ringDownMv = 2000.0f))
    val OPTICAL_RAIL_SATURATION = SimulationPreset("Optical rail saturation", SimulationMode.OPTICAL, OpticalParameters(saturationVoltageMv = 4800.0f, opticalOnsetMs = 1.5f, riseConstantMs = 1.0f, rippleNoiseMv = 12.0f, sustainedDurationMs = 60.0f))
    val MODERATE_GRID_SURGE = SimulationPreset("Moderate grid surge", SimulationMode.SURGE, SurgeParameters(surgeAmplitudeMv = 14500.0f, riseTimeUs = 15.0f, decayTimeMs = 3.0f, ringFreqKhz = 60.0f, baselineVoltageMv = 3250.0f, noiseLevelMv = 20.0f))

    val allPresets: List<SimulationPreset> = listOf(
        CLEAN_BASELINE,
        STRONG_EMP,
        OPTICAL_RAIL_SATURATION,
        MODERATE_GRID_SURGE
    )

    fun getPresets(): Map<String, Pair<SimulationMode, SimulationParameters>> = mapOf(
        "Clean baseline" to Pair(SimulationMode.NORMAL, NormalParameters()),
        "Strong EMP" to Pair(SimulationMode.EMP, STRONG_EMP.parameters),
        "Optical rail saturation" to Pair(SimulationMode.OPTICAL, OPTICAL_RAIL_SATURATION.parameters),
        "Moderate grid surge" to Pair(SimulationMode.SURGE, MODERATE_GRID_SURGE.parameters)
    )
}
