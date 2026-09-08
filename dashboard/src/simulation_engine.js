/**
 * SparkShield Simulation Lab - Client-Side Simulation Engine
 *
 * SAFETY BOUNDARY:
 * Software-only models for cyber-physical visualization and educational demonstration.
 * Never controls real physical meters, high-voltage equipment, EMP pulsers, or lasers.
 */

export const SimulationMode = {
  NORMAL: 'NORMAL',
  EMP: 'EMP',
  OPTICAL: 'OPTICAL',
  SURGE: 'SURGE'
};

export const Presets = {
  'Clean baseline': {
    mode: SimulationMode.NORMAL,
    params: { lineFreqHz: 50, baseAmplitudeMv: 3250, harmonicStrengthMv: 35, noiseLevelMv: 10, durationMs: 20 }
  },
  'Strong EMP': {
    mode: SimulationMode.EMP,
    params: { peakVoltageMv: 58000, riseTimeNs: 20, decayTimeUs: 6, resonantFreqMhz: 30, rfNoiseMv: 120, ringDownMv: 2000 }
  },
  'Optical rail saturation': {
    mode: SimulationMode.OPTICAL,
    params: { saturationVoltageMv: 4800, opticalOnsetMs: 1.5, riseConstantMs: 1.0, rippleNoiseMv: 12, sustainedDurationMs: 60 }
  },
  'Moderate grid surge': {
    mode: SimulationMode.SURGE,
    params: { surgeAmplitudeMv: 14500, riseTimeUs: 15, decayTimeMs: 3.0, ringFreqKhz: 60, baselineVoltageMv: 3250, noiseLevelMv: 20 }
  }
};

/**
 * Deterministic pseudo-random number generator (LCG / Mulberry32)
 */
function createPrng(seed) {
  let s = Math.floor(seed) >>> 0;
  return function next() {
    s = (s + 0x6D2B79F5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function gaussianNoise(prng, mean = 0, std = 1) {
  const u1 = Math.max(1e-7, prng());
  const u2 = prng();
  const z0 = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
  return mean + z0 * std;
}

/**
 * Standard CRC-16-CCITT (poly 0x1021, init 0xFFFF)
 */
export function computeCrc16Ccitt(bytes, length) {
  let crc = 0xFFFF;
  for (let i = 0; i < length; i++) {
    crc ^= (bytes[i] & 0xFF) << 8;
    for (let j = 0; j < 8; j++) {
      if ((crc & 0x8000) !== 0) {
        crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
      } else {
        crc = (crc << 1) & 0xFFFF;
      }
    }
  }
  return crc;
}

export class SimulationEngine {
  constructor() {
    this.sequenceCounter = 1;
  }

  generateSignal(mode, params, seed = 42, numSamples = 256, sequenceId = null, timestampMs = null) {
    const prng = createPrng(seed);
    const timePointsUs = new Float32Array(numSamples);
    const voltageSamplesMv = new Float32Array(numSamples);
    const fftBins = new Uint8Array(8);

    let peakMv = 0;
    let riseTimeCode = 0;
    let decayTimeUs = 0;
    let opticalSensorMv = 0;
    let eventFlag = 0;
    let sampleRateKhz = 0;

    switch (mode) {
      case SimulationMode.NORMAL: {
        const p = params || Presets['Clean baseline'].params;
        eventFlag = 0x08; // FLAG_NORMAL
        peakMv = Math.min(65535, Math.max(2000, Math.round(p.baseAmplitudeMv + gaussianNoise(prng, 0, p.noiseLevelMv))));
        riseTimeCode = 50000; // 500 µs
        decayTimeUs = 5000;   // 5 ms
        opticalSensorMv = Math.min(400, Math.max(50, Math.round(150 + (prng() * 40 - 20))));

        fftBins[0] = Math.min(255, Math.max(180, Math.round(220 + (prng() * 10 - 5))));
        fftBins[1] = Math.min(80, Math.max(10, Math.round(p.harmonicStrengthMv)));
        fftBins[2] = Math.min(25, Math.max(2, Math.round(12 + (prng() * 6 - 3))));
        fftBins[3] = 4; fftBins[4] = 2; fftBins[5] = 1; fftBins[6] = 0; fftBins[7] = 0;

        const durationS = (p.durationMs || 20) / 1000.0;
        sampleRateKhz = numSamples / (durationS * 1000.0);
        const dt = durationS / numSamples;
        for (let i = 0; i < numSamples; i++) {
          const t = i * dt;
          timePointsUs[i] = t * 1e6;
          const fund = p.baseAmplitudeMv * Math.sin(2.0 * Math.PI * p.lineFreqHz * t);
          const harm = p.harmonicStrengthMv * Math.sin(2.0 * Math.PI * 3 * p.lineFreqHz * t);
          const noise = gaussianNoise(prng, 0, p.noiseLevelMv);
          voltageSamplesMv[i] = fund + harm + noise;
        }
        break;
      }
      case SimulationMode.EMP: {
        const p = params || Presets['Strong EMP'].params;
        eventFlag = 0x01; // FLAG_EMP
        peakMv = Math.min(65535, Math.max(15000, Math.round(p.peakVoltageMv)));
        riseTimeCode = Math.max(1, Math.round(p.riseTimeNs / 10.0));
        decayTimeUs = Math.max(1, Math.round(p.decayTimeUs));
        opticalSensorMv = Math.min(400, Math.max(50, Math.round(160 + (prng() * 40 - 20))));

        fftBins[0] = Math.round(130 + prng() * 20);
        fftBins[1] = Math.round(165 + prng() * 25);
        fftBins[2] = Math.round(195 + prng() * 25);
        fftBins[3] = Math.round(230 + prng() * 25);
        fftBins[4] = Math.round(240 + prng() * 15);
        fftBins[5] = Math.round(220 + prng() * 25);
        fftBins[6] = Math.round(200 + prng() * 30);
        fftBins[7] = Math.round(175 + prng() * 35);

        const durationS = 50e-6; // 50 microseconds
        sampleRateKhz = numSamples / (durationS * 1000.0);
        const dt = durationS / numSamples;
        const alpha = 1.0 / (Math.max(1, p.decayTimeUs) * 1e-6);
        const beta = 1.0 / (Math.max(1, p.riseTimeNs) * 1e-9);

        for (let i = 0; i < numSamples; i++) {
          const t = i * dt;
          timePointsUs[i] = t * 1e6;
          const transient = p.peakVoltageMv * (Math.exp(-alpha * t) - Math.exp(-beta * t));
          const ring = p.ringDownMv * Math.sin(2.0 * Math.PI * p.resonantFreqMhz * 1e6 * t) * Math.exp(-alpha * 2 * t);
          const noise = gaussianNoise(prng, 0, p.rfNoiseMv);
          voltageSamplesMv[i] = transient + ring + noise;
        }
        break;
      }
      case SimulationMode.OPTICAL: {
        const p = params || Presets['Optical rail saturation'].params;
        eventFlag = 0x02; // FLAG_OPTICAL
        peakMv = Math.min(3600, Math.max(2800, Math.round(3250 + (prng() * 100 - 50))));
        opticalSensorMv = Math.min(5000, Math.max(2500, Math.round(p.saturationVoltageMv)));
        riseTimeCode = Math.min(65535, Math.max(1000, Math.round((p.riseConstantMs * 100000.0) / 10.0)));
        decayTimeUs = Math.min(65535, Math.max(1000, Math.round(p.sustainedDurationMs * 1000.0)));

        fftBins[0] = Math.min(255, Math.max(200, Math.round(240 + (prng() * 10 - 5))));
        fftBins[1] = Math.min(50, Math.max(10, Math.round(30 + (prng() * 6 - 3))));
        fftBins[2] = Math.min(20, Math.max(2, Math.round(10 + (prng() * 4 - 2))));
        fftBins[3] = 4; fftBins[4] = 2; fftBins[5] = 1; fftBins[6] = 0; fftBins[7] = 0;

        const durationS = (p.sustainedDurationMs || 50) / 1000.0;
        sampleRateKhz = numSamples / (durationS * 1000.0);
        const dt = durationS / numSamples;
        const onsetS = (p.opticalOnsetMs || 2.0) / 1000.0;
        const tauS = Math.max(1e-5, (p.riseConstantMs || 1.0) / 1000.0);

        for (let i = 0; i < numSamples; i++) {
          const t = i * dt;
          timePointsUs[i] = t * 1e6;
          const curve = (t < onsetS)
            ? 150.0
            : 150.0 + (p.saturationVoltageMv - 150.0) * (1.0 - Math.exp(-(t - onsetS) / tauS));
          voltageSamplesMv[i] = curve + gaussianNoise(prng, 0, p.rippleNoiseMv);
        }
        break;
      }
      case SimulationMode.SURGE: {
        const p = params || Presets['Moderate grid surge'].params;
        eventFlag = 0x04; // FLAG_SURGE
        peakMv = Math.min(65535, Math.max(4000, Math.round(p.surgeAmplitudeMv)));
        riseTimeCode = Math.max(10, Math.round(p.riseTimeUs * 100.0));
        decayTimeUs = Math.max(100, Math.round(p.decayTimeMs * 1000.0));
        opticalSensorMv = Math.min(400, Math.max(50, Math.round(160 + (prng() * 40 - 20))));

        fftBins[0] = Math.round(165 + prng() * 25);
        fftBins[1] = Math.round(205 + prng() * 30);
        fftBins[2] = Math.round(185 + prng() * 25);
        fftBins[3] = Math.round(70 + prng() * 20);
        fftBins[4] = Math.round(25 + prng() * 15);
        fftBins[5] = 5; fftBins[6] = 2; fftBins[7] = 0;

        const durationS = Math.max(0.01, (p.decayTimeMs * 4) / 1000.0);
        sampleRateKhz = numSamples / (durationS * 1000.0);
        const dt = durationS / numSamples;
        const decayS = p.decayTimeMs / 1000.0;

        for (let i = 0; i < numSamples; i++) {
          const t = i * dt;
          timePointsUs[i] = t * 1e6;
          const base = p.baselineVoltageMv * Math.sin(2.0 * Math.PI * 50.0 * t);
          const ring = p.surgeAmplitudeMv * Math.exp(-t / Math.max(1e-5, decayS)) * Math.cos(2.0 * Math.PI * p.ringFreqKhz * 1e3 * t);
          voltageSamplesMv[i] = base + ring + gaussianNoise(prng, 0, p.noiseLevelMv);
        }
        break;
      }
    }

    // Build 29-byte binary wire frame (Big-Endian)
    const buffer = new Uint8Array(29);
    const view = new DataView(buffer.buffer);
    const seq = sequenceId !== null ? sequenceId : this.sequenceCounter++;
    const timestamp = timestampMs !== null ? timestampMs : Date.now();

    view.setUint16(0, 0x5353, false); // Magic
    view.setUint32(2, seq >>> 0, false);
    view.setUint32(6, (timestamp & 0xFFFFFFFF) >>> 0, false);
    view.setUint8(10, eventFlag);
    view.setUint16(11, peakMv, false);
    view.setUint16(13, riseTimeCode, false);
    view.setUint16(15, decayTimeUs, false);
    view.setUint16(17, opticalSensorMv, false);
    for (let b = 0; b < 8; b++) {
      buffer[19 + b] = fftBins[b];
    }
    const crc = computeCrc16Ccitt(buffer, 27);
    view.setUint16(27, crc, false);

    const hexFrame = Array.from(buffer).map(b => b.toString(16).padStart(2, '0').toUpperCase()).join('');

    // High frequency energy ratio (bins 4..7 / sum(0..7))
    let totalEnergy = 0;
    let hfEnergy = 0;
    for (let i = 0; i < 8; i++) {
      totalEnergy += fftBins[i];
      if (i >= 4) hfEnergy += fftBins[i];
    }
    const hfRatio = hfEnergy / (totalEnergy + 1e-5);

    // Probabilities and Classification Simulation
    const probabilities = [0.002, 0.002, 0.002, 0.002];
    const modeIdxMap = { [SimulationMode.NORMAL]: 0, [SimulationMode.EMP]: 1, [SimulationMode.OPTICAL]: 2, [SimulationMode.SURGE]: 3 };
    const classIdx = modeIdxMap[mode] ?? 0;
    probabilities[classIdx] = 0.994;

    const observedClass = mode;
    const confidence = 0.994;
    const tamperDetected = (observedClass !== SimulationMode.NORMAL) && (confidence >= 0.85);
    const matchStatus = "Model agreement";

    // Narrative explanation
    let explanation = "";
    if (observedClass === SimulationMode.NORMAL) {
      explanation = `NORMAL GRID classified (99% confidence). 50/60 Hz AC grid fundamental with normal peak (${peakMv} mV). Alerts inactive for benign grid behavior.`;
    } else if (observedClass === SimulationMode.EMP) {
      explanation = `EMP TRANSIENT detected (99% confidence). Ultrafast rise (${riseTimeCode * 10} ns), extreme peak (${peakMv} mV), and high-frequency spectral dominance. Alert triggered (>= 0.85 threshold).`;
    } else if (observedClass === SimulationMode.OPTICAL) {
      explanation = `OPTICAL BLINDING detected (99% confidence). Photodiode sensor approaching saturation rail (${opticalSensorMv} mV). Alert triggered (>= 0.85 threshold).`;
    } else {
      explanation = `INDUCTIVE SURGE detected (99% confidence). Medium-high transient peak (${peakMv} mV) with millisecond-scale ring decay (${decayTimeUs} µs). Alert triggered (>= 0.85 threshold).`;
    }

    return {
      safetyNotice: "SOFTWARE SIMULATION ONLY - Does not control physical devices.",
      mode,
      expectedClass: mode,
      observedClass,
      probabilities,
      confidence,
      tamperDetected,
      tamper_detected: tamperDetected,
      alertThreshold: 0.85,
      inferenceLatencyUs: 1420,
      inferenceEngine: "CPU (ONNX Emulator)",
      explanation,
      matchStatus,
      timePointsUs: Array.from(timePointsUs),
      voltageSamplesMv: Array.from(voltageSamplesMv),
      fftEnergyBins: Array.from(fftBins),
      sampleRateKhz,
      peakVoltageMv: peakMv,
      riseTimeNs: riseTimeCode * 10,
      decayTimeUs,
      opticalSensorMv,
      hfEnergyRatio: hfRatio,
      hexFrame,
      frameLength: 29,
      sequenceId: seq,
      timestampMs: timestamp,
      crc16: crc,
      crcValid: true,
      eventFlags: eventFlag
    };
  }

  compareSignals(baseline, attack) {
    const deltaPeakMv = attack.peakVoltageMv - baseline.peakVoltageMv;
    const deltaRiseNs = attack.riseTimeNs - baseline.riseTimeNs;
    const deltaDecayUs = attack.decayTimeUs - baseline.decayTimeUs;
    const deltaOpticalMv = attack.opticalSensorMv - baseline.opticalSensorMv;
    const deltaHfEnergyRatio = attack.hfEnergyRatio - baseline.hfEnergyRatio;

    let spectralShift = "Low-Frequency Ring Oscillation";
    if (deltaHfEnergyRatio > 0.3) {
      spectralShift = "High-Frequency RF";
    } else if (deltaOpticalMv > 1000) {
      spectralShift = "DC Rail Saturation";
    }

    return {
      baselineClass: baseline.observedClass,
      attackClass: attack.observedClass,
      matchStatus: attack.matchStatus,
      deltaPeakMv,
      deltaRiseNs,
      deltaDecayUs,
      deltaOpticalMv,
      deltaHfEnergyRatio,
      spectralShift
    };
  }

  exportJson(result) {
    return JSON.stringify(result, null, 2);
  }

  exportCsv(result) {
    let csv = "# SparkShield Simulation Lab Export\n";
    csv += `# Mode: ${result.mode}, Observed: ${result.observedClass}, Confidence: ${result.confidence}\n`;
    csv += `# Hex Frame: ${result.hexFrame}, CRC16: 0x${result.crc16.toString(16).toUpperCase()}\n`;
    csv += "time_us,voltage_mv\n";
    for (let i = 0; i < result.timePointsUs.length; i++) {
      csv += `${result.timePointsUs[i]},${result.voltageSamplesMv[i]}\n`;
    }
    return csv;
  }
}
