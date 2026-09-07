import { RingBuffer } from './ring_buffer.js';

/**
 * Central state store for SparkShield real-time telemetry.
 * Memory-bounded to prevent browser memory leaks during 24/7 monitoring.
 */
export class DashboardState {
  constructor(maxHistory = 120, maxEvents = 50) {
    this.maxHistory = maxHistory;
    this.maxEvents = maxEvents;

    // Time-series history for canvas waveforms
    this.history = new RingBuffer(maxHistory);

    // Event history log (bounded array)
    this.events = [];

    // Current latest frame data
    this.latest = {
      seqId: 0,
      timestampMs: 0,
      eventFlags: 0,
      peakMv: 0,
      riseTimeNs: 0,
      decayTimeUs: 0,
      opticalMv: 0,
      fftBins: [0, 0, 0, 0, 0, 0, 0, 0],
      classification: 'NORMAL',
      confidence: 1.0,
      inferenceTimeUs: 0,
      tamperDetected: false,
    };

    // Diagnostics and health counters
    this.stats = {
      totalReceived: 0,
      validFrames: 0,
      invalidFrames: 0,
      droppedFrames: 0,
      tamperAlertCount: 0,
      lastSequenceId: -1,
      fps: 0,
    };

    // FPS calculation window
    this._frameCountSinceLastSec = 0;
    this._lastFpsCalculation = Date.now();

    // Listeners
    this._subscribers = new Set();
  }

  /**
   * Subscribes a listener to state changes.
   * @param {Function} callback
   * @returns {Function} Unsubscribe function
   */
  subscribe(callback) {
    this._subscribers.add(callback);
    return () => this._subscribers.delete(callback);
  }

  _notify() {
    for (const callback of this._subscribers) {
      try {
        callback(this);
      } catch (e) {
        console.error('Error in state subscriber:', e);
      }
    }
  }

  /**
   * Ingests a new telemetry frame payload from WebSocket.
   * @param {Object} data - Deserialized JSON frame
   */
  processFrame(data) {
    if (!data || typeof data !== 'object') {
      this.stats.invalidFrames++;
      this._notify();
      return;
    }

    this.stats.totalReceived++;
    this.stats.validFrames++;

    // Calculate sequence drops
    if (this.stats.lastSequenceId >= 0 && data.seqId > this.stats.lastSequenceId + 1) {
      const dropped = data.seqId - (this.stats.lastSequenceId + 1);
      this.stats.droppedFrames += dropped;
    }
    this.stats.lastSequenceId = data.seqId;

    // Update latest
    this.latest = {
      seqId: Number(data.seqId) || 0,
      timestampMs: Number(data.timestampMs) || Date.now(),
      eventFlags: Number(data.eventFlags) || 0,
      peakMv: Number(data.peakMv) || 0,
      riseTimeNs: Number(data.riseTimeNs) || 0,
      decayTimeUs: Number(data.decayTimeUs) || 0,
      opticalMv: Number(data.opticalMv) || 0,
      fftBins: Array.isArray(data.fftBins) ? data.fftBins : [0, 0, 0, 0, 0, 0, 0, 0],
      classification: String(data.classification || 'NORMAL').toUpperCase(),
      confidence: Math.min(1.0, Math.max(0.0, Number(data.confidence) || 0)),
      inferenceTimeUs: Number(data.inferenceTimeUs) || 0,
      tamperDetected: Boolean(data.tamperDetected),
    };

    // Push to waveform ring buffer
    this.history.push({
      seqId: this.latest.seqId,
      timestampMs: this.latest.timestampMs,
      peakMv: this.latest.peakMv,
      opticalMv: this.latest.opticalMv,
      classification: this.latest.classification,
      confidence: this.latest.confidence,
      tamperDetected: this.latest.tamperDetected,
    });

    // Handle tamper event logging
    if (this.latest.tamperDetected) {
      this.stats.tamperAlertCount++;
      const eventItem = {
        id: `${this.latest.seqId}-${this.latest.timestampMs}`,
        seqId: this.latest.seqId,
        timestampMs: this.latest.timestampMs,
        classification: this.latest.classification,
        confidence: this.latest.confidence,
        peakMv: this.latest.peakMv,
        opticalMv: this.latest.opticalMv,
        timeString: new Date(this.latest.timestampMs).toLocaleTimeString(),
      };

      // Add to front of bounded list
      this.events.unshift(eventItem);
      if (this.events.length > this.maxEvents) {
        this.events.pop();
      }
    }

    // Update incoming frames per second
    this._frameCountSinceLastSec++;
    const now = Date.now();
    if (now - this._lastFpsCalculation >= 1000) {
      this.stats.fps = Math.round((this._frameCountSinceLastSec * 1000) / (now - this._lastFpsCalculation));
      this._frameCountSinceLastSec = 0;
      this._lastFpsCalculation = now;
    }

    this._notify();
  }

  /**
   * Resets all history and counters.
   */
  reset() {
    this.history.clear();
    this.events = [];
    this.stats = {
      totalReceived: 0,
      validFrames: 0,
      invalidFrames: 0,
      droppedFrames: 0,
      tamperAlertCount: 0,
      lastSequenceId: -1,
      fps: 0,
    };
    this._notify();
  }
}
