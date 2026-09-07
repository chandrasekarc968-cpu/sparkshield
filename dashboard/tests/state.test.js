import test from 'node:test';
import assert from 'node:assert/strict';
import { DashboardState } from '../src/state.js';

test('DashboardState - should process NORMAL frames correctly', () => {
  const state = new DashboardState(10, 5);

  const normalPayload = {
    seqId: 100,
    timestampMs: 1725700000000,
    eventFlags: 8,
    peakMv: 3200,
    riseTimeNs: 200,
    decayTimeUs: 400,
    opticalMv: 350,
    fftBins: [10, 20, 30, 40, 50, 40, 30, 20],
    classification: 'NORMAL',
    confidence: 0.985,
    inferenceTimeUs: 420,
    tamperDetected: false,
  };

  state.processFrame(normalPayload);

  assert.strictEqual(state.latest.classification, 'NORMAL');
  assert.strictEqual(state.latest.confidence, 0.985);
  assert.strictEqual(state.latest.tamperDetected, false);
  assert.strictEqual(state.stats.validFrames, 1);
  assert.strictEqual(state.stats.tamperAlertCount, 0);
  assert.strictEqual(state.events.length, 0);
  assert.strictEqual(state.history.length, 1);
});

test('DashboardState - should record tamper events for EMP, OPTICAL, SURGE', () => {
  const state = new DashboardState(10, 5);

  const tampers = [
    { cls: 'EMP', conf: 0.95, tamper: true },
    { cls: 'OPTICAL', conf: 0.99, tamper: true },
    { cls: 'SURGE', conf: 0.91, tamper: true },
  ];

  tampers.forEach((t, i) => {
    state.processFrame({
      seqId: 200 + i,
      timestampMs: 1725700000000 + i * 100,
      eventFlags: 1,
      peakMv: 12000,
      riseTimeNs: 25,
      decayTimeUs: 10,
      opticalMv: 4500,
      fftBins: [50, 60, 70, 80, 90, 80, 70, 60],
      classification: t.cls,
      confidence: t.conf,
      inferenceTimeUs: 500,
      tamperDetected: t.tamper,
    });
  });

  assert.strictEqual(state.stats.validFrames, 3);
  assert.strictEqual(state.stats.tamperAlertCount, 3);
  assert.strictEqual(state.events.length, 3);

  // Check most recent event is first (SURGE)
  assert.strictEqual(state.events[0].classification, 'SURGE');
  assert.strictEqual(state.events[1].classification, 'OPTICAL');
  assert.strictEqual(state.events[2].classification, 'EMP');
});

test('DashboardState - should enforce bounded event log capacity', () => {
  const maxEvents = 5;
  const state = new DashboardState(20, maxEvents);

  // Push 8 tamper events
  for (let i = 0; i < 8; i++) {
    state.processFrame({
      seqId: 300 + i,
      timestampMs: 1725700000000 + i * 100,
      classification: 'EMP',
      confidence: 0.95,
      tamperDetected: true,
      peakMv: 10000,
      opticalMv: 500,
    });
  }

  assert.strictEqual(state.events.length, maxEvents);
  assert.strictEqual(state.stats.tamperAlertCount, 8);
  // Newest event has seqId 307
  assert.strictEqual(state.events[0].seqId, 307);
});

test('DashboardState - should track dropped frames on sequence gaps', () => {
  const state = new DashboardState(10, 5);

  state.processFrame({ seqId: 10, classification: 'NORMAL', confidence: 1.0 });
  state.processFrame({ seqId: 11, classification: 'NORMAL', confidence: 1.0 });
  // Gap of 3 frames: expected 12, received 15 -> dropped 3 (12, 13, 14)
  state.processFrame({ seqId: 15, classification: 'NORMAL', confidence: 1.0 });

  assert.strictEqual(state.stats.droppedFrames, 3);
  assert.strictEqual(state.stats.validFrames, 3);
});

test('DashboardState - reset clears buffers and statistics', () => {
  const state = new DashboardState(10, 5);
  state.processFrame({ seqId: 1, classification: 'EMP', confidence: 0.95, tamperDetected: true });

  assert.strictEqual(state.stats.validFrames, 1);
  assert.strictEqual(state.events.length, 1);
  assert.strictEqual(state.history.length, 1);

  state.reset();

  assert.strictEqual(state.stats.validFrames, 0);
  assert.strictEqual(state.stats.tamperAlertCount, 0);
  assert.strictEqual(state.events.length, 0);
  assert.strictEqual(state.history.length, 0);
});
