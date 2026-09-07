package com.sparkshield.android.transport

import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.protocol.TelemetryFrame
import com.sparkshield.android.protocol.TelemetryFrameParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random

/**
 * Deterministic synthetic telemetry frame provider for smart-meter simulation.
 *
 * SIMULATION ONLY:
 * All telemetry, sensor signals, transients, and optical readings are purely mathematical
 * software simulations. No actual electrical meter, high voltage, RF radiation, or attack
 * hardware is involved.
 *
 * Default Demo Pattern:
 *   - 20 frames NORMAL
 *   - 8 frames EMP
 *   - 20 frames NORMAL
 *   - 8 frames OPTICAL
 *   - 20 frames NORMAL
 *   - 8 frames SURGE
 *   - repeat
 */
class MockTelemetryProvider(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val seed: Long = 42L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    val useDefaultDemoSequence: Boolean = true
) : TelemetryProvider {

    private val random = Random(seed)
    private var sequenceCounter: Long = 0L

    private val _frames = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    override val frames: Flow<ByteArray> = _frames.asSharedFlow()

    private var streamingJob: Job? = null
    @Volatile
    var isRunning: Boolean = false
        private set

    // Tamper burst injection overrides
    @Volatile
    private var manualTamperClass: ClassLabels? = null
    @Volatile
    private var manualTamperRemaining: Int = 0

    // Malformed frame injection for unit and system robustness tests
    @Volatile
    var nextFrameMalformed: Boolean = false

    // Demo sequence tracking: 20 Normal -> 8 Emp -> 20 Normal -> 8 Optical -> 20 Normal -> 8 Surge
    private var demoStepIndex: Int = 0
    private var demoStepFrameCount: Int = 0

    private val demoSchedule = listOf(
        DemoStep(ClassLabels.NORMAL, 20),
        DemoStep(ClassLabels.EMP, 8),
        DemoStep(ClassLabels.NORMAL, 20),
        DemoStep(ClassLabels.OPTICAL, 8),
        DemoStep(ClassLabels.NORMAL, 20),
        DemoStep(ClassLabels.SURGE, 8)
    )

    private data class DemoStep(val targetClass: ClassLabels, val frameCount: Int)

    /**
     * Manually schedule a burst of a specific tamper class for testing.
     */
    fun triggerTamper(targetClass: ClassLabels, burstCount: Int = 8) {
        manualTamperClass = targetClass
        manualTamperRemaining = burstCount.coerceAtLeast(1)
    }

    override suspend fun start() {
        if (isRunning) return
        isRunning = true

        streamingJob = scope.launch {
            while (isActive && isRunning) {
                val frameBytes = generateNextFrameBytes()
                _frames.emit(frameBytes)
                delay(intervalMs)
            }
        }
    }

    override suspend fun stop() {
        isRunning = false
        streamingJob?.cancel()
        streamingJob = null
    }

    /**
     * Generates the next 29-byte frame buffer (or malformed frame if requested).
     */
    fun generateNextFrameBytes(): ByteArray {
        if (nextFrameMalformed) {
            nextFrameMalformed = false
            // Inject malformed 29-byte frame with corrupted magic and CRC
            return ByteArray(TelemetryFrame.FRAME_LENGTH) { 0xFF.toByte() }
        }

        val frame = generateNextFrame()
        return TelemetryFrameParser.pack(frame)
    }

    /**
     * Generates a single deterministic TelemetryFrame.
     */
    fun generateNextFrame(): TelemetryFrame {
        val currentClass = determineCurrentClass()
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

    private fun determineCurrentClass(): ClassLabels {
        synchronized(this) {
            // Check manual burst override first
            if (manualTamperRemaining > 0 && manualTamperClass != null) {
                val target = manualTamperClass!!
                manualTamperRemaining--
                if (manualTamperRemaining == 0) {
                    manualTamperClass = null
                }
                return target
            }

            if (!useDefaultDemoSequence) {
                return ClassLabels.NORMAL
            }

            // Execute automated demo sequence: 20 Normal, 8 EMP, 20 Normal, 8 Optical, 20 Normal, 8 Surge
            val currentStep = demoSchedule[demoStepIndex]
            demoStepFrameCount++

            if (demoStepFrameCount >= currentStep.frameCount) {
                demoStepIndex = (demoStepIndex + 1) % demoSchedule.size
                demoStepFrameCount = 0
            }

            return currentStep.targetClass
        }
    }

    /**
     * Resets sequence and demo state.
     */
    fun reset(newSeed: Long? = null) {
        sequenceCounter = 0L
        demoStepIndex = 0
        demoStepFrameCount = 0
        manualTamperClass = null
        manualTamperRemaining = 0
        nextFrameMalformed = false
        if (newSeed != null) {
            random.setSeed(newSeed)
        }
    }

    // -------------------------------------------------------------------------------------
    // Class Generators Matching Python Ranges (SIMULATION ONLY)
    // -------------------------------------------------------------------------------------

    private fun generateNormal(seq: Long, ts: Long): TelemetryFrame {
        // Normal AC grid: 3250 mV nominal ADC voltage (+/- 100 mV variation)
        val peakMv = (3250 + random.nextInt(201) - 100).coerceIn(2800, 3600)
        // Normal quarter-cycle front equivalent (~500 us = 50,000 code units at 10 ns/LSB)
        val riseCode = (50000 + random.nextInt(1001) - 500).coerceIn(1000, 65535)
        // Millisecond-scale decay: ~5 ms (5,000 us)
        val decayUs = (5000 + random.nextInt(201) - 100).coerceIn(1000, 65535)
        // Optical sensor baseline ambient noise (100 - 250 mV)
        val optMv = (150 + random.nextInt(61) - 30).coerceIn(50, 400)

        // Fundamental 50/60Hz dominant in Bin 0, minor 3rd harmonic in Bin 1
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
        // Synthetic EMP transient: high peak voltage (38,000 - 65,000 mV)
        val peakMv = (38000 + random.nextInt(27000)).coerceIn(20000, 65535)
        // Ultrafast nanosecond rise time: 10 - 30 ns -> code 1 to 3
        val riseCode = (1 + random.nextInt(3))
        // Microsecond-scale decay: 1 - 15 us
        val decayUs = (1 + random.nextInt(15))
        val optMv = (160 + random.nextInt(61) - 30).coerceIn(50, 400)

        // Broad high-frequency spectral distribution across bins 3..7
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
        // Saturated optical sensor approaching rail (3,800 - 4,950 mV)
        val optMv = (3800 + random.nextInt(1151)).coerceIn(3200, 5000)
        // Slow saturation front (clamped to prevent overflow)
        val riseCode = (20000 + random.nextInt(25001)).coerceIn(0, 65535)
        val decayUs = (30000 + random.nextInt(30001)).coerceIn(0, 65535)

        // Low-frequency dominant spectrum
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
        // Inductive switching surge: medium-high peak (7,500 - 18,500 mV)
        val peakMv = (7500 + random.nextInt(11001)).coerceIn(6000, 25000)
        // Rise time 10 - 50 us -> 1,000 - 5,000
        val riseCode = (1000 + random.nextInt(4001))
        // Millisecond-scale decay: 500 - 5,000 us
        val decayUs = (500 + random.nextInt(4501))
        val optMv = (160 + random.nextInt(61) - 30).coerceIn(50, 400)

        // Resonant ring concentrated in bins 1 and 2
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

    companion object {
        const val DEFAULT_INTERVAL_MS = 100L // 10 Hz
    }
}
