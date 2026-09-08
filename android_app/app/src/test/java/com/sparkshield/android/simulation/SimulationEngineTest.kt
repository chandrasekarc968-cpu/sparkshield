package com.sparkshield.android.simulation

import com.sparkshield.android.protocol.Crc16Ccitt
import com.sparkshield.android.protocol.TelemetryFrame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SimulationEngineTest {

    private lateinit var engine: SimulationEngine

    @Before
    fun setUp() {
        engine = DefaultSimulationEngine()
    }

    @Test
    fun testAllModesGenerateSignals() {
        for (mode in SimulationMode.values()) {
            val params = when (mode) {
                SimulationMode.NORMAL -> NormalParameters()
                SimulationMode.EMP -> EmpParameters()
                SimulationMode.OPTICAL -> OpticalParameters()
                SimulationMode.SURGE -> SurgeParameters()
            }
            val result = engine.generateSignal(mode, params, seed = 100L)
            assertNotNull(result)
            assertEquals(mode, result.mode)
            assertEquals(mode.classLabel.name, result.expectedClass)
            assertEquals(29, result.frameLength)
            assertEquals(8, result.fftEnergyBins.size)
            assertTrue(result.confidence in 0.0f..1.0f)
            assertTrue(result.probabilities.size == 4)
        }
    }

    @Test
    fun testDefaultParameterPresets() {
        for (preset in SimulationPresets.allPresets) {
            val errors = preset.parameters.validate()
            assertTrue("Preset ${preset.name} had validation errors: $errors", errors.isEmpty())
            val res = engine.generateSignal(preset.mode, preset.parameters, seed = 42L)
            assertEquals(preset.mode.classLabel.name, res.expectedClass)
        }
    }

    @Test
    fun testParameterValidationRanges() {
        val invalidNormal = NormalParameters(lineFreqHz = 75.0f, baseAmplitudeMv = 1000.0f)
        val errors = invalidNormal.validate()
        assertTrue(errors.any { it.contains("50 or 60 Hz") })
        assertTrue(errors.any { it.contains("Base amplitude") })

        val invalidEmp = EmpParameters(peakVoltageMv = 5000.0f, riseTimeNs = 2.0f)
        val empErrors = invalidEmp.validate()
        assertTrue(empErrors.any { it.contains("peak voltage") })
    }

    @Test
    fun testDeterministicSeedProducesIdenticalSignals() {
        val params = EmpParameters(peakVoltageMv = 50000f, riseTimeNs = 25f)
        val res1 = engine.generateSignal(SimulationMode.EMP, params, seed = 12345L)
        val res2 = engine.generateSignal(SimulationMode.EMP, params, seed = 12345L)

        assertEquals(res1.hexFrame, res2.hexFrame)
        assertEquals(res1.crc16, res2.crc16)
        assertArrayEquals(res1.fftEnergyBins, res2.fftEnergyBins)
        assertEquals(res1.voltageSamplesMv.size, res2.voltageSamplesMv.size)
        for (i in res1.voltageSamplesMv.indices) {
            assertEquals(res1.voltageSamplesMv[i], res2.voltageSamplesMv[i], 0.0001f)
        }
    }

    @Test
    fun testDifferentSeedProducesDifferentNoise() {
        val params = NormalParameters()
        val res1 = engine.generateSignal(SimulationMode.NORMAL, params, seed = 111L)
        val res2 = engine.generateSignal(SimulationMode.NORMAL, params, seed = 999L)

        var hasDifference = false
        for (i in res1.voltageSamplesMv.indices) {
            if (kotlin.math.abs(res1.voltageSamplesMv[i] - res2.voltageSamplesMv[i]) > 0.001f) {
                hasDifference = true
                break
            }
        }
        assertTrue("Different seeds should produce different noise realizations", hasDifference)
    }

    @Test
    fun testFrameProtocolEncodingAndCrc() {
        val res = engine.generateSignal(SimulationMode.EMP, EmpParameters(), seed = 42L, sequenceId = 99L)
        assertEquals(29, res.frameLength)

        val bytes = res.hexFrame.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(29, bytes.size)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.short.toInt() and 0xFFFF
        assertEquals(0x5353, magic)

        val seq = buffer.int
        assertEquals(99, seq)

        buffer.int // timestamp
        val flag = buffer.get().toInt() and 0xFF
        assertEquals(TelemetryFrame.FLAG_EMP, flag)

        val computedCrc = Crc16Ccitt.compute(bytes, 0, 27)
        buffer.position(27)
        val frameCrc = buffer.short.toInt() and 0xFFFF
        assertEquals(computedCrc, frameCrc)
        assertTrue(res.crcValid)
    }

    @Test
    fun testAlertGateBehavior() {
        // NORMAL mode: never triggers tamper alert
        val normalRes = engine.generateSignal(SimulationMode.NORMAL, NormalParameters(), seed = 42L)
        assertFalse("NORMAL mode must never trigger tamper alert", normalRes.tamperDetected)

        // EMP mode: triggers tamper alert when confidence >= 0.85
        val empRes = engine.generateSignal(SimulationMode.EMP, EmpParameters(), seed = 42L)
        if (empRes.confidence >= 0.85f && empRes.observedClass != "NORMAL") {
            assertTrue(empRes.tamperDetected)
        }
    }

    @Test
    fun testSignalComparisonAndShifts() {
        val baseline = engine.generateSignal(SimulationMode.NORMAL, NormalParameters(), seed = 42L)
        val emp = engine.generateSignal(SimulationMode.EMP, EmpParameters(), seed = 42L)

        val comp = engine.compareSignals(baseline, emp)
        assertEquals("NORMAL", comp.baselineClass)
        assertEquals("EMP", comp.attackClass)
        assertTrue(comp.deltaPeakMv > 0)
        assertEquals("High-Frequency RF", comp.spectralShift)
    }

    @Test
    fun testJsonAndCsvExport() {
        val res = engine.generateSignal(SimulationMode.OPTICAL, OpticalParameters(), seed = 42L)
        val json = engine.exportJson(res)
        assertTrue(json.contains("\"mode\": \"OPTICAL\""))
        assertTrue(json.contains("\"tamper_detected\":"))
        assertTrue(json.contains("\"safety_notice\":"))
        assertTrue(json.contains("\"crc16\":"))

        val csv = engine.exportCsv(res)
        assertTrue(csv.contains("time_us,voltage_mv"))
        assertTrue(csv.lines().size > 10)
    }
}
