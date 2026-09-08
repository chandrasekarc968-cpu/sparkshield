import { SimulationMode, Presets, SimulationEngine } from '../simulation_engine.js';

export class SimulationLabComponent {
  constructor(containerEl, onPublishToDashboard = null) {
    this.container = containerEl;
    this.engine = new SimulationEngine();
    this.onPublish = onPublishToDashboard;

    this.currentMode = SimulationMode.NORMAL;
    this.currentPreset = 'Clean baseline';
    this.currentParams = { ...Presets['Clean baseline'].params };
    this.currentSeed = 42;
    this.baselineResult = this.engine.generateSignal(SimulationMode.NORMAL, Presets['Clean baseline'].params, 42);
    this.currentResult = this.baselineResult;

    this.isReplaying = false;
    this.replayCursor = 0;
    this.replayAnimId = null;
    this.hoverPoint = null;

    this.initDOM();
    this.attachEventListeners();
    this.render();
  }

  initDOM() {
    this.container.innerHTML = `
      <div class="sim-lab-wrapper">
        <!-- Safety Disclosure Banner -->
        <div class="sim-disclosure-banner">
          <span class="sim-pill">SOFTWARE SIMULATION</span>
          <span class="sim-notice">Safe mathematical model evaluation. Never controls physical meters or high-voltage equipment.</span>
          <button id="sim-btn-help" class="btn btn-sm btn-outline">ℹ Help / Info</button>
        </div>

        <!-- Mode & Preset Selector -->
        <div class="sim-section card">
          <div class="sim-section-header">
            <h3>ATTACK MODE SELECTOR</h3>
            <div class="sim-presets-bar" id="sim-presets-container"></div>
          </div>
          <div class="sim-mode-buttons">
            <button class="sim-mode-btn active" data-mode="NORMAL">NORMAL GRID</button>
            <button class="sim-mode-btn" data-mode="EMP">EMP TRANSIENT</button>
            <button class="sim-mode-btn" data-mode="OPTICAL">OPTICAL BLINDING</button>
            <button class="sim-mode-btn" data-mode="SURGE">INDUCTIVE SURGE</button>
          </div>
        </div>

        <!-- Two Column Layout: Parameters on Left, Visuals on Right -->
        <div class="sim-grid">
          <!-- Left: Parameter Controls -->
          <div class="card sim-params-panel">
            <div class="sim-section-header">
              <h3>SIGNAL PARAMETERS</h3>
              <button id="sim-btn-reset" class="btn btn-sm btn-outline">Reset Defaults</button>
            </div>
            <div id="sim-dynamic-controls" class="sim-controls-list"></div>

            <div class="sim-seed-row">
              <label>Deterministic Seed: <strong id="sim-seed-val">42</strong></label>
              <button id="sim-btn-next-seed" class="btn btn-sm">Next Seed (+1)</button>
            </div>

            <div class="sim-action-buttons">
              <button id="sim-btn-generate" class="btn btn-primary" style="flex: 2;">⚡ Generate & Classify</button>
              <button id="sim-btn-replay" class="btn" style="flex: 1;">▶ Replay</button>
            </div>
          </div>

          <!-- Right: Visualizer & Classifier Results -->
          <div class="sim-results-panel">
            <!-- Waveform Canvas -->
            <div class="card chart-card">
              <div class="chart-header">
                <div class="chart-title">Time-Domain Voltage Waveform (Bounded 256 Samples)</div>
                <div id="sim-waveform-readout" class="sim-readout">Hover to inspect (t, V)</div>
              </div>
              <div class="canvas-wrapper" style="height: 180px;">
                <canvas id="sim-waveform-canvas"></canvas>
              </div>
            </div>

            <!-- 8-Bin FFT Energy Spectrum -->
            <div class="card chart-card">
              <div class="chart-header">
                <div class="chart-title">8-Bin FFT Protocol Spectrum (Wire Normalized [0..255])</div>
                <div class="chart-legend">
                  <span style="color: #06B6D4;">Low Freq (Grid)</span> | <span style="color: #EF4444;">High Freq (EMP/RF)</span>
                </div>
              </div>
              <div class="canvas-wrapper" style="height: 100px;">
                <canvas id="sim-fft-canvas"></canvas>
              </div>
            </div>

            <!-- Classifier & Explainability Card -->
            <div class="card sim-ai-card" id="sim-card-classifier">
              <div class="sim-ai-header">
                <div>
                  <div class="card-label">CLASSIFIER DECISION</div>
                  <div class="card-value" id="sim-val-class" style="font-size: 22px;">NORMAL</div>
                </div>
                <div>
                  <span class="badge" id="sim-badge-alert" style="font-size: 13px;">BENIGN</span>
                </div>
              </div>

              <!-- Probability Bars -->
              <div class="sim-probs-container" id="sim-probs-container"></div>

              <!-- Explanation Narrative -->
              <div class="sim-explanation-box">
                <div style="color: #58A6FF; font-weight: 600; margin-bottom: 4px;">Decision Explanation:</div>
                <div id="sim-val-explanation" style="font-size: 12px; color: #C9D1D9;"></div>
                <div id="sim-val-meta" style="font-size: 11px; color: #8B949E; margin-top: 6px; font-family: monospace;"></div>
              </div>
            </div>

            <!-- Model Agreement & Comparison -->
            <div class="card" id="sim-card-comparison">
              <div class="chart-header">
                <div class="chart-title">Model Agreement &amp; Signature Shift vs Baseline</div>
                <span id="sim-val-match-status" class="badge badge-normal">Model agreement</span>
              </div>
              <div class="sim-comp-grid" id="sim-comp-details"></div>
            </div>

            <!-- 29-Byte Protocol Frame Preview -->
            <div class="card">
              <div class="chart-header" style="cursor: pointer;" id="sim-toggle-frame">
                <div class="chart-title">29-Byte Binary Wire Frame Preview</div>
                <span id="sim-frame-chevron">▼ Click to Expand</span>
              </div>
              <div id="sim-frame-body" style="display: none; margin-top: 12px;">
                <pre id="sim-hex-frame" class="sim-hex-box"></pre>
                <div id="sim-frame-metadata" class="sim-frame-meta"></div>
                <div class="sim-export-buttons">
                  <button id="sim-btn-copy-hex" class="btn btn-sm">Copy Hex Frame</button>
                  <button id="sim-btn-export-json" class="btn btn-sm">Export JSON</button>
                  <button id="sim-btn-export-csv" class="btn btn-sm">Export Waveform CSV</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    `;
  }

  attachEventListeners() {
    // Mode Buttons
    this.container.querySelectorAll('.sim-mode-btn').forEach(btn => {
      btn.addEventListener('click', (e) => {
        const mode = e.target.dataset.mode;
        this.selectMode(mode);
      });
    });

    // Reset Defaults
    const btnReset = this.container.querySelector('#sim-btn-reset');
    if (btnReset) {
      btnReset.addEventListener('click', () => {
        const def = Presets[this.currentPreset] || Object.values(Presets).find(p => p.mode === this.currentMode);
        if (def) {
          this.currentParams = { ...def.params };
          this.generate();
        }
      });
    }

    // Next Seed
    const btnNextSeed = this.container.querySelector('#sim-btn-next-seed');
    if (btnNextSeed) {
      btnNextSeed.addEventListener('click', () => {
        this.currentSeed++;
        this.container.querySelector('#sim-seed-val').textContent = this.currentSeed;
        this.generate();
      });
    }

    // Generate Button
    const btnGenerate = this.container.querySelector('#sim-btn-generate');
    if (btnGenerate) {
      btnGenerate.addEventListener('click', () => this.generate());
    }

    // Replay Button
    const btnReplay = this.container.querySelector('#sim-btn-replay');
    if (btnReplay) {
      btnReplay.addEventListener('click', () => this.toggleReplay());
    }

    // Toggle Frame Preview
    const toggleFrame = this.container.querySelector('#sim-toggle-frame');
    const frameBody = this.container.querySelector('#sim-frame-body');
    const chevron = this.container.querySelector('#sim-frame-chevron');
    if (toggleFrame && frameBody) {
      toggleFrame.addEventListener('click', () => {
        const isHidden = frameBody.style.display === 'none';
        frameBody.style.display = isHidden ? 'block' : 'none';
        chevron.textContent = isHidden ? '▲ Click to Collapse' : '▼ Click to Expand';
      });
    }

    // Copy Hex
    const btnCopyHex = this.container.querySelector('#sim-btn-copy-hex');
    if (btnCopyHex) {
      btnCopyHex.addEventListener('click', () => {
        navigator.clipboard.writeText(this.currentResult.hexFrame);
        btnCopyHex.textContent = 'Copied!';
        setTimeout(() => { btnCopyHex.textContent = 'Copy Hex Frame'; }, 1500);
      });
    }

    // Export JSON
    const btnExportJson = this.container.querySelector('#sim-btn-export-json');
    if (btnExportJson) {
      btnExportJson.addEventListener('click', () => {
        const json = this.engine.exportJson(this.currentResult);
        this.downloadFile(`sparkshield_${this.currentMode.toLowerCase()}_${this.currentResult.sequenceId}.json`, json, 'application/json');
      });
    }

    // Export CSV
    const btnExportCsv = this.container.querySelector('#sim-btn-export-csv');
    if (btnExportCsv) {
      btnExportCsv.addEventListener('click', () => {
        const csv = this.engine.exportCsv(this.currentResult);
        this.downloadFile(`waveform_${this.currentMode.toLowerCase()}_${this.currentResult.sequenceId}.csv`, csv, 'text/csv');
      });
    }

    // Waveform Canvas mousemove readout
    const canvas = this.container.querySelector('#sim-waveform-canvas');
    if (canvas) {
      canvas.addEventListener('mousemove', (e) => {
        const rect = canvas.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const fraction = Math.max(0, Math.min(1, x / rect.width));
        const idx = Math.floor(fraction * this.currentResult.voltageSamplesMv.length);
        const t = this.currentResult.timePointsUs[idx] || 0;
        const v = this.currentResult.voltageSamplesMv[idx] || 0;
        this.hoverPoint = { t, v, x };
        const readout = this.container.querySelector('#sim-waveform-readout');
        if (readout) {
          readout.textContent = `t = ${t.toFixed(1)} µs | V = ${v.toFixed(1)} mV`;
        }
        this.drawWaveform();
      });

      canvas.addEventListener('mouseleave', () => {
        this.hoverPoint = null;
        const readout = this.container.querySelector('#sim-waveform-readout');
        if (readout) readout.textContent = 'Hover to inspect (t, V)';
        this.drawWaveform();
      });
    }

    // Info/Help Modal
    const btnHelp = this.container.querySelector('#sim-btn-help');
    if (btnHelp) {
      btnHelp.addEventListener('click', () => {
        alert(
          "SparkShield Simulation Lab - Guide\n\n" +
          "• Safety Boundary: Pure software-simulated signals. Does not interact with physical smart meters, pulses, or high voltages.\n" +
          "• Rise time: Time taken to reach peak voltage (ns/µs).\n" +
          "• Decay time: Dissipation time constant.\n" +
          "• 8-Bin FFT: Protocol wire frequency distribution from 0 Hz to 50 MHz.\n" +
          "• Tamper Gate: Classification alarms trigger only when non-normal and confidence >= 0.85.\n" +
          "• Model Agreement: Indicates mathematical classifier alignment with synthetic preset."
        );
      });
    }
  }

  selectMode(mode) {
    this.currentMode = mode;
    this.container.querySelectorAll('.sim-mode-btn').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.mode === mode);
    });

    // Pick first matching preset
    const presetEntry = Object.entries(Presets).find(([_, p]) => p.mode === mode);
    if (presetEntry) {
      this.currentPreset = presetEntry[0];
      this.currentParams = { ...presetEntry[1].params };
    }
    this.generate();
  }

  loadPreset(presetName) {
    const p = Presets[presetName];
    if (!p) return;
    this.currentPreset = presetName;
    this.currentMode = p.mode;
    this.currentParams = { ...p.params };

    this.container.querySelectorAll('.sim-mode-btn').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.mode === p.mode);
    });
    this.generate();
  }

  generate() {
    this.currentResult = this.engine.generateSignal(this.currentMode, this.currentParams, this.currentSeed);
    if (this.currentMode === SimulationMode.NORMAL) {
      this.baselineResult = this.currentResult;
    }
    this.render();
  }

  toggleReplay() {
    const btn = this.container.querySelector('#sim-btn-replay');
    if (this.isReplaying) {
      this.isReplaying = false;
      cancelAnimationFrame(this.replayAnimId);
      if (btn) btn.textContent = '▶ Replay';
      this.drawWaveform();
    } else {
      this.isReplaying = true;
      this.replayCursor = 0;
      if (btn) btn.textContent = '⏹ Stop Replay';
      const loop = () => {
        if (!this.isReplaying) return;
        this.replayCursor = (this.replayCursor + 4) % this.currentResult.voltageSamplesMv.length;
        this.drawWaveform();
        this.replayAnimId = requestAnimationFrame(loop);
      };
      this.replayAnimId = requestAnimationFrame(loop);
    }
  }

  render() {
    this.renderPresets();
    this.renderParameterControls();
    this.renderClassifierResult();
    this.renderComparison();
    this.renderFramePreview();
    this.drawWaveform();
    this.drawFft();
  }

  renderPresets() {
    const presetsEl = this.container.querySelector('#sim-presets-container');
    if (!presetsEl) return;
    presetsEl.innerHTML = Object.keys(Presets).map(name => {
      const active = name === this.currentPreset ? 'preset-active' : '';
      return `<button class="sim-preset-btn ${active}" data-preset="${name}">${name}</button>`;
    }).join('');

    presetsEl.querySelectorAll('.sim-preset-btn').forEach(btn => {
      btn.addEventListener('click', () => this.loadPreset(btn.dataset.preset));
    });
  }

  renderParameterControls() {
    const controlsEl = this.container.querySelector('#sim-dynamic-controls');
    if (!controlsEl) return;

    let html = '';
    const p = this.currentParams;

    if (this.currentMode === SimulationMode.NORMAL) {
      html += this.createSlider('lineFreqHz', 'Line Frequency', p.lineFreqHz, 'Hz', 45, 65, 1);
      html += this.createSlider('baseAmplitudeMv', 'Base Amplitude', p.baseAmplitudeMv, 'mV', 2000, 5000, 50);
      html += this.createSlider('harmonicStrengthMv', 'Harmonic Strength', p.harmonicStrengthMv, 'mV', 0, 200, 5);
      html += this.createSlider('noiseLevelMv', 'Noise Level', p.noiseLevelMv, 'mV', 0, 100, 2);
    } else if (this.currentMode === SimulationMode.EMP) {
      html += this.createSlider('peakVoltageMv', 'Peak Voltage [Wire]', p.peakVoltageMv, 'mV', 15000, 65535, 500);
      html += this.createSlider('riseTimeNs', 'Rise Time [Wire]', p.riseTimeNs, 'ns', 1, 100, 1);
      html += this.createSlider('decayTimeUs', 'Decay Time [Wire]', p.decayTimeUs, 'µs', 1, 100, 1);
      html += this.createSlider('resonantFreqMhz', 'Resonant Frequency', p.resonantFreqMhz, 'MHz', 10, 100, 5);
      html += this.createSlider('rfNoiseMv', 'RF Noise Level', p.rfNoiseMv, 'mV', 0, 500, 10);
    } else if (this.currentMode === SimulationMode.OPTICAL) {
      html += this.createSlider('saturationVoltageMv', 'Saturation Voltage [Wire]', p.saturationVoltageMv, 'mV', 2500, 5000, 50);
      html += this.createSlider('opticalOnsetMs', 'Optical Onset', p.opticalOnsetMs, 'ms', 0, 10, 0.5);
      html += this.createSlider('riseConstantMs', 'Rise Constant', p.riseConstantMs, 'ms', 0.1, 5.0, 0.1);
      html += this.createSlider('rippleNoiseMv', 'Ripple Noise', p.rippleNoiseMv, 'mV', 0, 100, 2);
    } else if (this.currentMode === SimulationMode.SURGE) {
      html += this.createSlider('surgeAmplitudeMv', 'Surge Amplitude [Wire]', p.surgeAmplitudeMv, 'mV', 4000, 30000, 500);
      html += this.createSlider('riseTimeUs', 'Rise Time [Wire]', p.riseTimeUs, 'µs', 0.5, 20, 0.5);
      html += this.createSlider('decayTimeMs', 'Decay Time [Wire]', p.decayTimeMs, 'ms', 0.1, 10, 0.2);
      html += this.createSlider('ringFreqKhz', 'Ring Frequency', p.ringFreqKhz, 'kHz', 10, 200, 5);
    }

    controlsEl.innerHTML = html;

    // Attach slider change handlers
    controlsEl.querySelectorAll('input[type="range"]').forEach(input => {
      input.addEventListener('input', (e) => {
        const paramKey = e.target.dataset.param;
        const val = parseFloat(e.target.value);
        this.currentParams[paramKey] = val;
        const display = controlsEl.querySelector(`#val-${paramKey}`);
        if (display) display.textContent = `${val} ${e.target.dataset.unit}`;
        this.generate();
      });
    });
  }

  createSlider(param, label, val, unit, min, max, step) {
    return `
      <div class="sim-slider-group">
        <div class="sim-slider-label-row">
          <span>${label}</span>
          <strong id="val-${param}">${val} ${unit}</strong>
        </div>
        <input type="range" min="${min}" max="${max}" step="${step}" value="${val}" data-param="${param}" data-unit="${unit}" class="sim-slider" />
      </div>
    `;
  }

  renderClassifierResult() {
    const res = this.currentResult;
    const classVal = this.container.querySelector('#sim-val-class');
    const alertBadge = this.container.querySelector('#sim-badge-alert');
    const explanationEl = this.container.querySelector('#sim-val-explanation');
    const metaEl = this.container.querySelector('#sim-val-meta');

    if (classVal) {
      classVal.textContent = res.observedClass;
      classVal.style.color = res.observedClass === 'NORMAL' ? '#10B981' : (res.observedClass === 'EMP' ? '#EF4444' : '#F59E0B');
    }

    if (alertBadge) {
      if (res.tamperDetected) {
        alertBadge.textContent = 'TAMPER DETECTED';
        alertBadge.className = 'badge badge-tamper';
      } else {
        alertBadge.textContent = 'SYSTEM BENIGN';
        alertBadge.className = 'badge badge-safe';
      }
    }

    if (explanationEl) {
      explanationEl.textContent = res.explanation;
    }

    if (metaEl) {
      metaEl.textContent = `Accelerator: ${res.inferenceEngine} | Latency: ${res.inferenceLatencyUs} µs | Gate Threshold: 0.85`;
    }

    // Probability Bars
    const probsEl = this.container.querySelector('#sim-probs-container');
    if (probsEl) {
      const labels = ['NORMAL', 'EMP', 'OPTICAL', 'SURGE'];
      const colors = ['#10B981', '#EF4444', '#F59E0B', '#8B5CF6'];
      probsEl.innerHTML = labels.map((lbl, idx) => {
        const prob = res.probabilities[idx] || 0;
        const pct = (prob * 100).toFixed(1);
        return `
          <div class="sim-prob-row">
            <span style="width: 70px; font-size: 11px;">${lbl}</span>
            <div class="sim-prob-bar-bg">
              <div class="sim-prob-bar-fill" style="width: ${pct}%; background-color: ${colors[idx]};"></div>
            </div>
            <span style="width: 50px; text-align: right; font-family: monospace; font-size: 11px;">${pct}%</span>
          </div>
        `;
      }).join('');
    }
  }

  renderComparison() {
    const compEl = this.container.querySelector('#sim-comp-details');
    if (!compEl) return;
    const comp = this.engine.compareSignals(this.baselineResult, this.currentResult);

    compEl.innerHTML = `
      <div class="sim-comp-item">
        <div class="sim-comp-label">Baseline Class</div>
        <div class="sim-comp-value" style="color: #10B981;">${comp.baselineClass}</div>
      </div>
      <div class="sim-comp-item">
        <div class="sim-comp-label">Observed Attack</div>
        <div class="sim-comp-value" style="color: #58A6FF;">${comp.attackClass}</div>
      </div>
      <div class="sim-comp-item">
        <div class="sim-comp-label">Δ Peak Amplitude</div>
        <div class="sim-comp-value">${comp.deltaPeakMv >= 0 ? '+' : ''}${Math.round(comp.deltaPeakMv)} mV</div>
      </div>
      <div class="sim-comp-item">
        <div class="sim-comp-label">Δ Rise Time</div>
        <div class="sim-comp-value">${comp.deltaRiseNs >= 0 ? '+' : ''}${comp.deltaRiseNs} ns</div>
      </div>
      <div class="sim-comp-item">
        <div class="sim-comp-label">Spectral Shift</div>
        <div class="sim-comp-value" style="color: #F59E0B;">${comp.spectralShift}</div>
      </div>
    `;
  }

  renderFramePreview() {
    const res = this.currentResult;
    const hexEl = this.container.querySelector('#sim-hex-frame');
    const metaEl = this.container.querySelector('#sim-frame-metadata');
    if (hexEl) {
      hexEl.textContent = res.hexFrame.match(/.{1,2}/g).join(' ');
    }
    if (metaEl) {
      metaEl.textContent = `Header: 0x5353 | Seq: #${res.sequenceId} | Event Flags: 0x${res.eventFlags.toString(16).padStart(2, '0').toUpperCase()} | CRC16: 0x${res.crc16.toString(16).toUpperCase()} (Valid)`;
    }
  }

  drawWaveform() {
    const canvas = this.container.querySelector('#sim-waveform-canvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const w = (canvas.width = canvas.parentElement.clientWidth);
    const h = (canvas.height = 180);

    ctx.fillStyle = '#090D13';
    ctx.fillRect(0, 0, w, h);

    const midY = h / 2;
    // Grid Lines
    ctx.strokeStyle = '#1F242C';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, midY); ctx.lineTo(w, midY);
    ctx.moveTo(0, h * 0.25); ctx.lineTo(w, h * 0.25);
    ctx.moveTo(0, h * 0.75); ctx.lineTo(w, h * 0.75);
    ctx.stroke();

    const samples = this.currentResult.voltageSamplesMv;
    if (!samples || samples.length === 0) return;

    let maxAbs = 1;
    for (let i = 0; i < samples.length; i++) {
      const abs = Math.abs(samples[i]);
      if (abs > maxAbs) maxAbs = abs;
    }
    const scaleY = (h * 0.42) / maxAbs;
    const stepX = w / (samples.length - 1);

    ctx.beginPath();
    for (let i = 0; i < samples.length; i++) {
      const x = i * stepX;
      const y = midY - samples[i] * scaleY;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }

    const waveColor = this.currentMode === SimulationMode.NORMAL ? '#06B6D4' : (this.currentMode === SimulationMode.EMP ? '#EF4444' : '#F59E0B');
    ctx.strokeStyle = waveColor;
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';
    ctx.stroke();

    // Draw moving replay cursor
    if (this.isReplaying) {
      const cursorX = (this.replayCursor / samples.length) * w;
      ctx.strokeStyle = '#FACC15';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(cursorX, 0); ctx.lineTo(cursorX, h);
      ctx.stroke();
    }

    // Draw hover point
    if (this.hoverPoint) {
      ctx.strokeStyle = '#FFFFFF';
      ctx.setLineDash([2, 2]);
      ctx.beginPath();
      ctx.moveTo(this.hoverPoint.x, 0); ctx.lineTo(this.hoverPoint.x, h);
      ctx.stroke();
      ctx.setLineDash([]);
    }
  }

  drawFft() {
    const canvas = this.container.querySelector('#sim-fft-canvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const w = (canvas.width = canvas.parentElement.clientWidth);
    const h = (canvas.height = 100);

    ctx.fillStyle = '#090D13';
    ctx.fillRect(0, 0, w, h);

    const bins = this.currentResult.fftEnergyBins;
    const labels = ['DC', '10k', '50k', '200k', '1M', '5M', '20M', '50M'];
    const barWidth = (w / 8) - 6;

    for (let i = 0; i < 8; i++) {
      const energy = bins[i] || 0;
      const fraction = Math.max(0.05, energy / 255);
      const barHeight = fraction * (h - 22);
      const x = i * (w / 8) + 3;
      const y = h - 18 - barHeight;

      ctx.fillStyle = i >= 4 ? '#EF4444' : '#06B6D4';
      ctx.fillRect(x, y, barWidth, barHeight);

      // Label
      ctx.fillStyle = '#8B949E';
      ctx.font = '10px monospace';
      ctx.textAlign = 'center';
      ctx.fillText(labels[i], x + barWidth / 2, h - 4);
    }
  }

  downloadFile(filename, content, type) {
    const blob = new Blob([content], { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  destroy() {
    this.isReplaying = false;
    cancelAnimationFrame(this.replayAnimId);
  }
}
