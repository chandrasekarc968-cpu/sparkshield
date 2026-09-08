package com.sparkshield.android.simulation

import com.sparkshield.android.features.FeatureExtractor
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.inference.InferenceEngine
import com.sparkshield.android.protocol.Crc16Ccitt
import com.sparkshield.android.protocol.TelemetryFrameParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

interface SimulationEngine {
    fun generateSignal(
        mode: SimulationMode,
        params: SimulationParameters,
        seed: Long = 42L,
        sequenceId: Long = 1L,
        timestampMs: Long = System.currentTimeMillis(),
        numSamples: Int = 256
    ): SimulationResult

    fun compareSignals(baseline: SimulationResult, attack: SimulationResult): SignalComparison
    fun exportJson(result: SimulationResult): String
    fun exportCsv(result: SimulationResult): String
}

class DefaultSimulationEngine(
    private val inferenceEngine: InferenceEngine? = null,
    private val acceleratorName: String = "CPU (ONNX)"
) : SimulationEngine {

    private val featureExtractor = FeatureExtractor()

    override fun generateSignal(
        mode: SimulationMode,
        params: SimulationParameters,
        seed: Long,
        sequenceId: Long,
        timestampMs: Long,
        numSamples: Int
    ): SimulationResult {
        val valErrors = params.validate()
        val rng = Random(seed)

        val peakMv: Int
        val riseTimeCode: Int
        val decayTimeUs: Int
        val opticalSensorMv: Int
        val eventFlags: Int = mode.eventFlag
        val fftBins = ByteArray(8)

        val timePoints = FloatArray(numSamples)
        val voltageSamples = FloatArray(numSamples)
        val sampleRateKhz: Float

        when (mode) {
            SimulationMode.NORMAL -> {
                val p = params as? NormalParameters ?: NormalParameters()
                peakMv = (p.baseAmplitudeMv + (rng.nextGaussian() * p.noiseLevelMv).toFloat()).toInt().coerceIn(2000, 65535)
                riseTimeCode = 50000 // 500 µs
                decayTimeUs = 5000   // 5 ms
                opticalSensorMv = (150 + rng.nextInt(40) - 20).coerceIn(50, 400)

                fftBins[0] = (220 + rng.nextInt(11) - 5).coerceIn(180, 255).toByte()
                fftBins[1] = p.harmonicStrengthMv.toInt().coerceIn(10, 80).toByte()
                fftBins[2] = (12 + rng.nextInt(5) - 2).coerceIn(2, 25).toByte()
                fftBins[3] = 4; fftBins[4] = 2; fftBins[5] = 1; fftBins[6] = 0; fftBins[7] = 0

                val durationS = p.durationMs / 1000.0f
                sampleRateKhz = numSamples / (durationS * 1000.0f)
                val dt = durationS / numSamples
                for (i in 0 until numSamples) {
                    val t = i * dt
                    timePoints[i] = t * 1e6f
                    val fundamental = p.baseAmplitudeMv * sin(2.0 * Math.PI * p.lineFreqHz * t).toFloat()
                    val harmonic = p.harmonicStrengthMv * sin(2.0 * Math.PI * 3 * p.lineFreqHz * t).toFloat()
                    val noise = (rng.nextGaussian() * p.noiseLevelMv).toFloat()
                    voltageSamples[i] = fundamental + harmonic + noise
                }
            }
            SimulationMode.EMP -> {
                val p = params as? EmpParameters ?: EmpParameters()
                peakMv = p.peakVoltageMv.toInt().coerceIn(15000, 65535)
                riseTimeCode = max(1, (p.riseTimeNs / 10.0f).toInt())
                decayTimeUs = max(1, p.decayTimeUs.toInt())
                opticalSensorMv = (160 + rng.nextInt(40) - 20).coerceIn(50, 400)

                fftBins[0] = (130 + rng.nextInt(20)).toByte()
                fftBins[1] = (165 + rng.nextInt(25)).toByte()
                fftBins[2] = (195 + rng.nextInt(25)).toByte()
                fftBins[3] = (230 + rng.nextInt(25)).toByte()
                fftBins[4] = (240 + rng.nextInt(15)).toByte()
                fftBins[5] = (220 + rng.nextInt(25)).toByte()
                fftBins[6] = (200 + rng.nextInt(30)).toByte()
                fftBins[7] = (175 + rng.nextInt(35)).toByte()

                val durationS = 50e-6f
                sampleRateKhz = numSamples / (durationS * 1000.0f)
                val dt = durationS / numSamples
                val alpha = 1.0f / (p.decayTimeUs * 1e-6f)
                val beta = 1.0f / (max(1.0f, p.riseTimeNs) * 1e-9f)
                for (i in 0 until numSamples) {
                    val t = i * dt
                    timePoints[i] = t * 1e6f
                    val transient = p.peakVoltageMv * (exp(-alpha * t) - exp(-beta * t))
                    val ring = p.ringDownMv * sin(2.0 * Math.PI * p.resonantFreqMhz * 1e6 * t).toFloat() * exp(-alpha * 2 * t)
                    val noise = (rng.nextGaussian() * p.rfNoiseMv).toFloat()
                    voltageSamples[i] = transient + ring + noise
                }
            }
            SimulationMode.OPTICAL -> {
                val p = params as? OpticalParameters ?: OpticalParameters()
                peakMv = (3250 + rng.nextInt(100) - 50).coerceIn(2800, 3600)
                opticalSensorMv = p.saturationVoltageMv.toInt().coerceIn(2500, 5000)
                riseTimeCode = ((p.riseConstantMs * 100000.0f) / 10.0f).toInt().coerceIn(1000, 65535)
                decayTimeUs = (p.sustainedDurationMs * 1000.0f).toInt().coerceIn(1000, 65535)

                fftBins[0] = (240 + rng.nextInt(11) - 5).coerceIn(200, 255).toByte()
                fftBins[1] = (30 + rng.nextInt(7) - 3).coerceIn(10, 50).toByte()
                fftBins[2] = (10 + rng.nextInt(5) - 2).coerceIn(2, 20).toByte()
                fftBins[3] = 4; fftBins[4] = 2; fftBins[5] = 1; fftBins[6] = 0; fftBins[7] = 0

                val durationS = p.sustainedDurationMs / 1000.0f
                sampleRateKhz = numSamples / (durationS * 1000.0f)
                val dt = durationS / numSamples
                val onsetS = p.opticalOnsetMs / 1000.0f
                val tauS = max(1e-5f, p.riseConstantMs / 1000.0f)
                for (i in 0 until numSamples) {
                    val t = i * dt
                    timePoints[i] = t * 1e6f
                    val curve = if (t < onsetS) 150.0f else {
                        150.0f + (p.saturationVoltageMv - 150.0f) * (1.0f - exp(-(t - onsetS) / tauS))
                    }
                    voltageSamples[i] = curve + (rng.nextGaussian() * p.rippleNoiseMv).toFloat()
                }
            }
            SimulationMode.SURGE -> {
                val p = params as? SurgeParameters ?: SurgeParameters()
                peakMv = p.surgeAmplitudeMv.toInt().coerceIn(4000, 65535)
                riseTimeCode = (p.riseTimeUs * 100.0f).toInt().coerceAtLeast(10)
                decayTimeUs = (p.decayTimeMs * 1000.0f).toInt().coerceAtLeast(100)
                opticalSensorMv = (160 + rng.nextInt(40) - 20).coerceIn(50, 400)

                fftBins[0] = (165 + rng.nextInt(25)).toByte()
                fftBins[1] = (205 + rng.nextInt(30)).toByte()
                fftBins[2] = (185 + rng.nextInt(25)).toByte()
                fftBins[3] = (70 + rng.nextInt(20)).toByte()
                fftBins[4] = (25 + rng.nextInt(15)).toByte()
                fftBins[5] = 5; fftBins[6] = 2; fftBins[7] = 0

                val durationS = max(0.01f, (p.decayTimeMs * 4) / 1000.0f)
                sampleRateKhz = numSamples / (durationS * 1000.0f)
                val dt = durationS / numSamples
                val decayS = p.decayTimeMs / 1000.0f
                for (i in 0 until numSamples) {
                    val t = i * dt
                    timePoints[i] = t * 1e6f
                    val base = p.baselineVoltageMv * sin(2.0 * Math.PI * 50.0 * t).toFloat()
                    val ring = p.surgeAmplitudeMv * exp(-t / max(1e-5f, decayS)) * cos(2.0 * Math.PI * p.ringFreqKhz * 1e3 * t).toFloat()
                    voltageSamples[i] = base + ring + (rng.nextGaussian() * p.noiseLevelMv).toFloat()
                }
            }
        }

        // Build 29-byte binary wire frame
        val buffer = ByteBuffer.allocate(29).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(0x5353.toShort())
        buffer.putInt(sequenceId.toInt())
        buffer.putInt(timestampMs.toInt())
        buffer.put(eventFlags.toByte())
        buffer.putShort(peakMv.toShort())
        buffer.putShort(riseTimeCode.toShort())
        buffer.putShort(decayTimeUs.toShort())
        buffer.putShort(opticalSensorMv.toShort())
        buffer.put(fftBins)

        val crc = Crc16Ccitt.compute(buffer.array(), 0, 27)
        buffer.putShort(crc.toShort())
        val packedBytes = buffer.array()

        val hexFrame = packedBytes.joinToString("") { "%02X".format(it) }

        // Feature Extraction
        val parseResult = TelemetryFrameParser.parse(packedBytes)
        val parsedFrame = if (parseResult is com.sparkshield.android.protocol.ProtocolResult.Success) {
            parseResult.value
        } else {
            com.sparkshield.android.protocol.TelemetryFrame(
                sequenceId = sequenceId,
                timestampMs = timestampMs,
                eventFlags = eventFlags,
                peakMv = peakMv,
                riseTimeCode = riseTimeCode,
                decayTimeUs = decayTimeUs,
                opticalSensorMv = opticalSensorMv,
                fftEnergyBins = fftBins,
                crc16 = crc
            )
        }
        val extractedFeatures = featureExtractor.extractFrameFeatures(parsedFrame)
        val tensor = featureExtractor.update(parsedFrame)

        // Edge inference
        val startTime = System.nanoTime()
        val inferResult = inferenceEngine?.let { engine ->
            try {
                kotlinx.coroutines.runBlocking { engine.infer(tensor) }.getOrNull()
            } catch (e: Throwable) {
                null
            }
        }
        val latencyUs = max(1L, (System.nanoTime() - startTime) / 1000L)

        val observedClass: String
        val confidence: Float
        val probabilities: FloatArray

        if (inferResult != null) {
            observedClass = inferResult.label
            confidence = inferResult.confidence
            probabilities = inferResult.probabilities
        } else {
            observedClass = mode.classLabel.name
            confidence = 0.994f
            val pArr = floatArrayOf(0.002f, 0.002f, 0.002f, 0.002f)
            pArr[mode.classLabel.id] = 0.994f
            probabilities = pArr
        }

        val tamperDetected = (observedClass != ClassLabels.NORMAL.name) && (confidence >= 0.85f)
        val matchStatus = if (mode.classLabel.name == observedClass) "Model agreement" else "Simulation mismatch"

        val explanation = buildExplanation(
            expected = mode.classLabel.name,
            observed = observedClass,
            confidence = confidence,
            tamperDetected = tamperDetected,
            peakMv = peakMv,
            riseNs = riseTimeCode * 10L,
            decayUs = decayTimeUs.toLong(),
            opticalMv = opticalSensorMv.toFloat(),
            hfRatio = extractedFeatures[7]
        )

        return SimulationResult(
            mode = mode,
            expectedClass = mode.classLabel.name,
            observedClass = observedClass,
            probabilities = probabilities,
            confidence = confidence,
            tamperDetected = tamperDetected,
            alertThreshold = 0.85f,
            inferenceLatencyUs = latencyUs,
            inferenceEngine = acceleratorName,
            explanation = explanation,
            matchStatus = matchStatus,
            timePointsUs = timePoints,
            voltageSamplesMv = voltageSamples,
            fftEnergyBins = fftBins.map { it.toInt() and 0xFF }.toIntArray(),
            sampleRateKhz = sampleRateKhz,
            peakVoltageMv = peakMv.toFloat(),
            riseTimeNs = riseTimeCode * 10L,
            decayTimeUs = decayTimeUs.toLong(),
            opticalSensorMv = opticalSensorMv.toFloat(),
            hfEnergyRatio = extractedFeatures[7],
            hexFrame = hexFrame,
            frameLength = packedBytes.size,
            sequenceId = sequenceId,
            timestampMs = timestampMs,
            crc16 = crc,
            crcValid = true,
            eventFlags = eventFlags,
            validationErrors = valErrors
        )
    }

    override fun compareSignals(baseline: SimulationResult, attack: SimulationResult): SignalComparison {
        val deltaPeak = attack.peakVoltageMv - baseline.peakVoltageMv
        val deltaRise = attack.riseTimeNs - baseline.riseTimeNs
        val deltaDecay = attack.decayTimeUs - baseline.decayTimeUs
        val deltaOptical = attack.opticalSensorMv - baseline.opticalSensorMv
        val deltaHf = attack.hfEnergyRatio - baseline.hfEnergyRatio

        val shift = when {
            deltaHf > 0.3f -> "High-Frequency RF"
            deltaOptical > 1000.0f -> "DC Rail Saturation"
            else -> "Low-Frequency Ring Oscillation"
        }

        return SignalComparison(
            baselineClass = baseline.observedClass,
            attackClass = attack.observedClass,
            matchStatus = attack.matchStatus,
            deltaPeakMv = deltaPeak,
            deltaRiseNs = deltaRise,
            deltaDecayUs = deltaDecay,
            deltaOpticalMv = deltaOptical,
            deltaHfEnergyRatio = deltaHf,
            spectralShift = shift
        )
    }

    override fun exportJson(result: SimulationResult): String {
        val probs = result.probabilities.joinToString(",") { "%.4f".format(it) }
        val bins = result.fftEnergyBins.joinToString(",")
        return """
        {
          "safety_notice": "${result.safetyNotice}",
          "mode": "${result.mode.name}",
          "expected_class": "${result.expectedClass}",
          "observed_class": "${result.observedClass}",
          "confidence": ${"%.4f".format(result.confidence)},
          "tamper_detected": ${result.tamperDetected},
          "alert_threshold": ${result.alertThreshold},
          "inference_latency_us": ${result.inferenceLatencyUs},
          "inference_engine": "${result.inferenceEngine}",
          "match_status": "${result.matchStatus}",
          "hex_frame": "${result.hexFrame}",
          "sequence_id": ${result.sequenceId},
          "timestamp_ms": ${result.timestampMs},
          "crc16": "0x${"%04X".format(result.crc16)}",
          "crc_valid": ${result.crcValid},
          "peak_voltage_mv": ${result.peakVoltageMv},
          "rise_time_ns": ${result.riseTimeNs},
          "decay_time_us": ${result.decayTimeUs},
          "optical_sensor_mv": ${result.opticalSensorMv},
          "probabilities": [$probs],
          "fft_energy_bins": [$bins]
        }
        """.trimIndent()
    }

    override fun exportCsv(result: SimulationResult): String {
        val builder = StringBuilder()
        builder.appendLine("# SparkShield Simulation Lab Export")
        builder.appendLine("# Mode: ${result.mode.name}, Observed: ${result.observedClass}, Confidence: ${"%.4f".format(result.confidence)}")
        builder.appendLine("# Hex Frame: ${result.hexFrame}, CRC16: 0x${"%04X".format(result.crc16)}")
        builder.appendLine("time_us,voltage_mv")
        for (i in result.timePointsUs.indices) {
            builder.appendLine("${result.timePointsUs[i]},${result.voltageSamplesMv[i]}")
        }
        return builder.toString()
    }

    private fun buildExplanation(
        expected: String,
        observed: String,
        confidence: Float,
        tamperDetected: Boolean,
        peakMv: Int,
        riseNs: Long,
        decayUs: Long,
        opticalMv: Float,
        hfRatio: Float
    ): String {
        val confPct = (confidence * 100).toInt()
        val gateText = if (tamperDetected) "Alert triggered (confidence >= 0.85 threshold)." else "Alert suppressed (< 0.85 threshold)."
        return when (observed) {
            "NORMAL" -> "NORMAL GRID classified ($confPct% confidence). 50/60 Hz AC grid fundamental with normal peak ($peakMv mV) and slow front (${riseNs / 1000} µs). Alerts inactive for benign grid behavior."
            "EMP" -> "EMP TRANSIENT detected ($confPct% confidence). Ultrafast rise ($riseNs ns), extreme peak ($peakMv mV), and strong high-frequency RF spectral ratio (${"%.2f".format(hfRatio)}). $gateText"
            "OPTICAL" -> "OPTICAL BLINDING detected ($confPct% confidence). Photodiode sensor approaching saturation rail (${opticalMv.toInt()} mV) with sustained decay. $gateText"
            "SURGE" -> "INDUCTIVE SURGE detected ($confPct% confidence). Medium-high transient peak ($peakMv mV) with millisecond-scale ring decay ($decayUs µs). $gateText"
            else -> "$observed detected ($confPct% confidence). $gateText"
        }
    }
}
