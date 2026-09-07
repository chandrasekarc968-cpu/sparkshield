/**
 * Manages fine-grained DOM element updates for dashboard metrics and event logs.
 * Avoids global page re-renders by mutating only changed element values.
 */
export class MetricsCards {
  constructor(elements) {
    this.el = elements;
    this._lastClassification = null;
    this._lastTamperState = null;
  }

  /**
   * Updates metric displays based on current state.
   * @param {import('../state.js').DashboardState} state
   */
  update(state) {
    const { latest, stats } = state;

    // 1. Classification badge & card styling
    if (this.el.classificationBadge && this.el.classificationCard) {
      const cls = latest.classification;
      this.el.classificationBadge.textContent = cls;

      if (cls !== this._lastClassification) {
        this.el.classificationCard.classList.remove('status-normal', 'status-emp', 'status-optical', 'status-surge');
        this.el.classificationBadge.classList.remove('badge-normal', 'badge-emp', 'badge-optical', 'badge-surge');

        if (cls === 'EMP') {
          this.el.classificationCard.classList.add('status-emp');
          this.el.classificationBadge.classList.add('badge-emp');
        } else if (cls === 'OPTICAL') {
          this.el.classificationCard.classList.add('status-optical');
          this.el.classificationBadge.classList.add('badge-optical');
        } else if (cls === 'SURGE') {
          this.el.classificationCard.classList.add('status-surge');
          this.el.classificationBadge.classList.add('badge-surge');
        } else {
          this.el.classificationCard.classList.add('status-normal');
          this.el.classificationBadge.classList.add('badge-normal');
        }
        this._lastClassification = cls;
      }
    }

    // 2. Confidence & meter
    if (this.el.confidenceVal && this.el.confidenceBar) {
      const pct = (latest.confidence * 100).toFixed(1);
      this.el.confidenceVal.textContent = `${pct}%`;
      this.el.confidenceBar.style.width = `${pct}%`;
      if (latest.tamperDetected) {
        this.el.confidenceBar.style.backgroundColor = '#EF4444';
      } else {
        this.el.confidenceBar.style.backgroundColor = '#10B981';
      }
    }

    // 3. Peak voltage
    if (this.el.peakVoltageVal && this.el.peakVoltageSub) {
      this.el.peakVoltageVal.textContent = `${latest.peakMv.toLocaleString()} mV`;
      this.el.peakVoltageSub.textContent = `(${(latest.peakMv / 1000).toFixed(2)} V)`;
    }

    // 4. Optical sensor
    if (this.el.opticalSensorVal) {
      this.el.opticalSensorVal.textContent = `${latest.opticalMv.toLocaleString()} mV`;
    }

    // 5. Rise & decay times
    if (this.el.riseTimeVal) {
      this.el.riseTimeVal.textContent = `${latest.riseTimeNs.toLocaleString()} ns`;
    }
    if (this.el.decayTimeVal) {
      this.el.decayTimeVal.textContent = `${latest.decayTimeUs.toLocaleString()} µs`;
    }

    // 6. Inference Latency
    if (this.el.latencyVal) {
      this.el.latencyVal.textContent = `${latest.inferenceTimeUs} µs`;
    }

    // 7. Tamper State badge & alert banner
    if (this.el.tamperBadge) {
      if (latest.tamperDetected) {
        this.el.tamperBadge.textContent = 'ATTACK DETECTED';
        this.el.tamperBadge.className = 'badge badge-alert pulse';
      } else {
        this.el.tamperBadge.textContent = 'SYSTEM NORMAL';
        this.el.tamperBadge.className = 'badge badge-safe';
      }
    }

    // 8. High-priority alert banner
    if (this.el.alertBanner) {
      if (latest.tamperDetected) {
        let alertMsg = 'TAMPER ANOMALY DETECTED';
        if (latest.classification === 'EMP') {
          alertMsg = 'CRITICAL: High-voltage EMP transient simulated';
        } else if (latest.classification === 'OPTICAL') {
          alertMsg = 'CRITICAL: Optical sensor saturation attack simulated';
        } else if (latest.classification === 'SURGE') {
          alertMsg = 'WARNING: Inductive grid surge event simulated';
        }
        this.el.alertBanner.textContent = alertMsg;
        this.el.alertBanner.style.display = 'block';
        this.el.alertBanner.className = 'alert-banner alert-pulse';
      } else {
        this.el.alertBanner.style.display = 'none';
      }
    }

    // 9. Frame counts & diagnostic telemetry
    if (this.el.seqIdVal) this.el.seqIdVal.textContent = `#${latest.seqId}`;
    if (this.el.validFramesVal) this.el.validFramesVal.textContent = stats.validFrames.toLocaleString();
    if (this.el.fpsVal) this.el.fpsVal.textContent = `${stats.fps} FPS`;
    if (this.el.droppedFramesVal) this.el.droppedFramesVal.textContent = stats.droppedFrames.toLocaleString();
    if (this.el.alertsCountVal) this.el.alertsCountVal.textContent = stats.tamperAlertCount.toLocaleString();

    // 10. Event history table (render recent events)
    this._renderEventHistory(state.events);
  }

  _renderEventHistory(events) {
    if (!this.el.eventHistoryList) return;

    if (events.length === 0) {
      this.el.eventHistoryList.innerHTML = '<div class="empty-state">No tamper events detected yet.</div>';
      return;
    }

    // Re-render only if list changed
    const rowsHtml = events.slice(0, 15).map((evt) => {
      let badgeClass = 'badge-alert';
      if (evt.classification === 'SURGE') badgeClass = 'badge-warning';

      return `
        <div class="event-item">
          <span class="event-time">${evt.timeString}</span>
          <span class="event-seq">Seq #${evt.seqId}</span>
          <span class="badge ${badgeClass}">${evt.classification}</span>
          <span class="event-conf">${(evt.confidence * 100).toFixed(1)}%</span>
          <span class="event-peak">${evt.peakMv} mV</span>
        </div>
      `;
    }).join('');

    this.el.eventHistoryList.innerHTML = rowsHtml;
  }
}
