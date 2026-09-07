package com.sparkshield.android.transport

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.protocol.TelemetryFrame
import com.sparkshield.android.protocol.TelemetryFrameParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random

/**
 * Deterministic, seedable synthetic telemetry frame provider for smart-meter simulation.
 *
 * Implements 10 Hz frame streaming with simulated benign grid, EMP-like transient,
 * optical saturation, and inductive surge classes matching python_core/signal_models.py.
 */
class MockTelemetryProvider(
    private val rateHz: Float = 10.0f,
    private val seed: Long = 42L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : TelemetryProvider {

    private val random = Random(seed)
    private var sequenceCounter: Long = 0L
    private val intervalMs: Long = (1000.0f / rateHz.coerceAtLeast(0.1f)).toLong()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    override val rawFrameFlow: SharedFlow<ByteArray> = _rawFrameFlow.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var streamingJob: Job? = null

    // Tamper burst injection state
    @Volatile
    private var activeClass: ClassLabels = ClassLabels.NORMAL
    @Volatile
    private var tamperBurstRemaining: Int = 0

    /**
     * Trigger a burst of consecutive tamper frames for manual testing or scenario simulation.
     */
    fun triggerTamper(targetClass: ClassLabels, burstCount: Int = 5) {
        activeClass = targetClass
        tamperBurstRemaining = burstCount.coerceAtLeast(1)
    }

    override fun start() {
        if (_isConnected.value) return
        _isConnected.value = true

        streamingJob = scope.launch {
            while (isActive && _isConnected.value) {
                val frame = generateNextFrame()
                val packed = TelemetryFrameParser.pack(frame)
                _rawFrameFlow.emit(packed)
                delay(intervalMs)
            }
        }
    }

    override fun stop() {
        _isConnected.value = false
        streamingJob?.cancel()
        streamingJob = null
    }

    /**
     * Generates a single deterministic TelemetryFrame according to current active class.
     */
    fun generateNextFrame(): TelemetryFrame {
        val currentClass: ClassLabels
        synchronized(this) {
            if (tamperBurstRemaining > 0) {
                currentClass = activeClass
                tamperBurstRemaining--
                if (tamperBurstRemaining == 0) {
                    activeClass = ClassLabels.NORMAL
                }
            } else {
                currentClass = ClassLabels.NORMAL
            }
        }

        val seq = sequenceCounter
        sequenceCounter = (sequenceCounter + 1L) and 0xFFFFFFFFL
        val ts = seq * intervalMs

        return when (currentClass) {
            ClassLabels.NORMAL -> generateNormal(seq, ts)
            ClassLabels.EMP -> generateEmp(seq, ts)
            ClassLabels.OPTICAL -> generateOptical(seq, ts)
            ClassLabels.SURGE -> generateSurge(seq, ts)
        }
    }

    private fun generateNormal(seq: Long, ts: Long): TelemetryFrame {
        val peakMv = (3250 + random.nextInt(201) - 100).coerceIn(2800, 3600)
        val riseCode = (50000 + random.nextInt(1001) - 500).coerceIn(1000, 65535)
        val decayUs = (5000 + random.nextInt(201) - 100).coerceIn(1000, 65535)
        val optMv = (150 + random.nextInt(61) - 30).coerceIn(50, 400)

        val bins = byteArrayOf(
            (220 + random.nextInt(21) - 10).coerceIn(180, 255).toByte(),
            (35 + random.nextInt(11) - 5).coerceIn(10, 60).toByte(),
            (12 + random.nextInt(7) - 3).coerceIn(2, 25).toByte(),
            (4 + random.nextInt(3) - 1).coerceIn(0, 10).toByte(),
            (2 + random.nextInt(3) - 1).coerceIn(0, 6).toByte(),
            1.toByte(),
            0.toByte(),
            0.toByte()
        )

        return TelemetryFrame(
            sequenceId = seq,
            timestampMs = ts,
            eventFlags = TelemetryFrame.FLAG_NORMAL,
            peakMv = peakMv,
            riseTimeCode = riseCode,
            decayTimeUs = decayUs,
            opticalSensorMv = optMv,
            fftEnergyBins = bins
        )
    }

    private fun generateEmp(seq: Long, ts: Long): TelemetryFrame {
        val peakMv = (38000 + random.nextInt(27000)).coerceIn(20000, 65535)
        val riseCode = (1 + random.nextInt(3)) // 10..30 ns
        val decayUs = (1 + random.nextInt(15)) // 1..15 µs
        val optMv = (160 + random.nextInt(61) - 30).coerceIn(50, 400)

        val bins = byteArrayOf(
            (110 + random.nextInt(41)).toByte(),
            (140 + random.nextInt(51)).toByte(),
            (170 + random.nextInt(51)).toByte(),
            (200 + random.nextInt(56)).toByte(),
            (210 + random.nextInt(46)).toByte(),
            (190 + random.nextInt(56)).toByte(),
            (170 + random.nextInt(61)).toByte(),
            (140 + random.nextInt(71)).toByte()
        )

        return TelemetryFrame(
            sequenceId = seq,
            timestampMs = ts,
            eventFlags = TelemetryFrame.FLAG_EMP,
            peakMv = peakMv,
            riseTimeCode = riseCode,
            decayTimeUs = decayUs,
            opticalSensorMv = optMv,
            fftEnergyBins = bins
        )
    }

    private fun generateOptical(seq: Long, ts: Long): TelemetryFrame {
        val peakMv = (3250 + random.nextInt(201) - 100).coerceIn(2800, 3600)
        val optMv = (3800 + random.nextInt(1151)).coerceIn(3200, 5000)
        val riseCode = (20000 + random.nextInt(25001)).coerceIn(0, 65535)
        val decayUs = (30000 + random.nextInt(30001)).coerceIn(0, 65535)

        val bins = byteArrayOf(
            (240 + random.nextInt(21) - 10).coerceIn(200, 255).toByte(),
            (30 + random.nextInt(11) - 5).coerceIn(10, 50).toByte(),
            (10 + random.nextInt(7) - 3).coerceIn(2, 20).toByte(),
            4.toByte(),
            2.toByte(),
            1.toByte(),
            0.toByte(),
            0.toByte()
        )

        return TelemetryFrame(
            sequenceId = seq,
            timestampMs = ts,
            eventFlags = TelemetryFrame.FLAG_OPTICAL,
            peakMv = peakMv,
            riseTimeCode = riseCode,
            decayTimeUs = decayUs,
            opticalSensorMv = optMv,
            fftEnergyBins = bins
        )
    }

    private fun generateSurge(seq: Long, ts: Long): TelemetryFrame {
        val peakMv = (7500 + random.nextInt(11001)).coerceIn(6000, 25000)
        val riseCode = (1000 + random.nextInt(4001))
        val decayUs = (500 + random.nextInt(4501))
        val optMv = (160 + random.nextInt(61) - 30).coerceIn(50, 400)

        val bins = byteArrayOf(
            (140 + random.nextInt(51)).toByte(),
            (210 + random.nextInt(46)).toByte(),
            (180 + random.nextInt(51)).toByte(),
            (60 + random.nextInt(51)).toByte(),
            (20 + random.nextInt(31)).toByte(),
            (5 + random.nextInt(16)).toByte(),
            2.toByte(),
            0.toByte()
        )

        return TelemetryFrame(
            sequenceId = seq,
            timestampMs = ts,
            eventFlags = TelemetryFrame.FLAG_SURGE,
            peakMv = peakMv,
            riseTimeCode = riseCode,
            decayTimeUs = decayUs,
            opticalSensorMv = optMv,
            fftEnergyBins = bins
        )
    }
}
