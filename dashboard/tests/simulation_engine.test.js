import test from 'node:test';
import assert from 'node:assert/strict';
import { SimulationMode, Presets, SimulationEngine, computeCrc16Ccitt } from '../src/simulation_engine.js';

test('SimulationEngine - generates signals for all attack modes', () => {
  const engine = new SimulationEngine();
  for (const mode of Object.values(SimulationMode)) {
    const res = engine.generateSignal(mode, null, 42, 256);
    assert.strictEqual(res.mode, mode);
    assert.strictEqual(res.expectedClass, mode);
    assert.strictEqual(res.frameLength, 29);
    assert.strictEqual(res.hexFrame.length, 58);
    assert.strictEqual(res.fftEnergyBins.length, 8);
    assert.strictEqual(res.voltageSamplesMv.length, 256);
    assert.strictEqual(res.crcValid, true);
  }
});

test('SimulationEngine - presets validation', () => {
  const engine = new SimulationEngine();
  for (const [name, preset] of Object.entries(Presets)) {
    const res = engine.generateSignal(preset.mode, preset.params, 42);
    assert.strictEqual(res.mode, preset.mode);
    assert.strictEqual(res.observedClass, preset.mode);
  }
});

test('SimulationEngine - deterministic seed produces identical signals', () => {
  const engine = new SimulationEngine();
  const res1 = engine.generateSignal(SimulationMode.EMP, Presets['Strong EMP'].params, 9999, 256, 1, 1000);
  const res2 = engine.generateSignal(SimulationMode.EMP, Presets['Strong EMP'].params, 9999, 256, 1, 1000);

  assert.strictEqual(res1.hexFrame, res2.hexFrame);
  assert.strictEqual(res1.crc16, res2.crc16);
  assert.deepStrictEqual(res1.fftEnergyBins, res2.fftEnergyBins);
  assert.deepStrictEqual(res1.voltageSamplesMv, res2.voltageSamplesMv);
});

test('SimulationEngine - different seed produces different noise', () => {
  const engine = new SimulationEngine();
  const res1 = engine.generateSignal(SimulationMode.NORMAL, Presets['Clean baseline'].params, 100);
  const res2 = engine.generateSignal(SimulationMode.NORMAL, Presets['Clean baseline'].params, 200);

  let diffFound = false;
  for (let i = 0; i < res1.voltageSamplesMv.length; i++) {
    if (Math.abs(res1.voltageSamplesMv[i] - res2.voltageSamplesMv[i]) > 0.001) {
      diffFound = true;
      break;
    }
  }
  assert.strictEqual(diffFound, true);
});

test('SimulationEngine - 29-byte frame encoding and CRC verification', () => {
  const engine = new SimulationEngine();
  const res = engine.generateSignal(SimulationMode.SURGE, Presets['Moderate grid surge'].params, 42);
  const bytes = [];
  for (let i = 0; i < res.hexFrame.length; i += 2) {
    bytes.push(parseInt(res.hexFrame.slice(i, i + 2), 16));
  }
  assert.strictEqual(bytes.length, 29);

  // Magic 0x5353
  assert.strictEqual(bytes[0], 0x53);
  assert.strictEqual(bytes[1], 0x53);

  // Recompute CRC over first 27 bytes
  const calculatedCrc = computeCrc16Ccitt(bytes, 27);
  const frameCrc = (bytes[27] << 8) | bytes[28];
  assert.strictEqual(calculatedCrc, frameCrc);
  assert.strictEqual(res.crc16, calculatedCrc);
});

test('SimulationEngine - alert gate behavior', () => {
  const engine = new SimulationEngine();
  const normalRes = engine.generateSignal(SimulationMode.NORMAL, null, 42);
  assert.strictEqual(normalRes.tamperDetected, false);

  const empRes = engine.generateSignal(SimulationMode.EMP, null, 42);
  assert.strictEqual(empRes.tamperDetected, true);
});

test('SimulationEngine - comparison and exports', () => {
  const engine = new SimulationEngine();
  const baseline = engine.generateSignal(SimulationMode.NORMAL, null, 42);
  const emp = engine.generateSignal(SimulationMode.EMP, null, 42);

  const comp = engine.compareSignals(baseline, emp);
  assert.strictEqual(comp.baselineClass, 'NORMAL');
  assert.strictEqual(comp.attackClass, 'EMP');
  assert.strictEqual(comp.spectralShift, 'High-Frequency RF');

  const json = engine.exportJson(emp);
  assert.ok(json.includes('"mode": "EMP"'));
  assert.ok(json.includes('"tamper_detected": true'));

  const csv = engine.exportCsv(emp);
  assert.ok(csv.includes('time_us,voltage_mv'));
});
