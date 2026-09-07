/**
 * High-performance HTML5 Canvas renderer for real-time scrolling voltage waveforms.
 * Driven strictly by requestAnimationFrame to decouple rendering from network arrival rates.
 */
export class WaveformCanvas {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {import('../state.js').DashboardState} state
   */
  constructor(canvas, state) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.state = state;
    this.animationFrameId = null;

    this._handleResize = this._handleResize.bind(this);
    this._render = this._render.bind(this);

    window.addEventListener('resize', this._handleResize);
    this._handleResize();
  }

  _handleResize() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.floor(rect.width * dpr);
    this.canvas.height = Math.floor(rect.height * dpr);
  }

  start() {
    if (!this.animationFrameId) {
      this.animationFrameId = requestAnimationFrame(this._render);
    }
  }

  stop() {
    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }
  }

  destroy() {
    this.stop();
    window.removeEventListener('resize', this._handleResize);
  }

  _render() {
    const ctx = this.ctx;
    const width = this.canvas.width;
    const height = this.canvas.height;

    if (width === 0 || height === 0) {
      this.animationFrameId = requestAnimationFrame(this._render);
      return;
    }

    // Clear canvas
    ctx.fillStyle = '#0B0F19';
    ctx.fillRect(0, 0, width, height);

    // Draw background grid
    this._drawGrid(ctx, width, height);

    const history = this.state.history.toArray();
    if (history.length >= 2) {
      this._drawWaveform(ctx, width, height, history);
    } else {
      // Empty state placeholder
      ctx.fillStyle = '#4B5563';
      ctx.font = `${Math.max(12, Math.floor(height * 0.05))}px 'JetBrains Mono', monospace`;
      ctx.textAlign = 'center';
      ctx.fillText('AWAITING TELEMETRY STREAM...', width / 2, height / 2);
    }

    this.animationFrameId = requestAnimationFrame(this._render);
  }

  _drawGrid(ctx, width, height) {
    ctx.strokeStyle = 'rgba(31, 41, 55, 0.6)';
    ctx.lineWidth = 1;

    // Horizontal grid lines (voltage divisions)
    const hSteps = 5;
    for (let i = 1; i < hSteps; i++) {
      const y = Math.floor((height / hSteps) * i);
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();
    }

    // Vertical time division lines
    const vSteps = 8;
    for (let i = 1; i < vSteps; i++) {
      const x = Math.floor((width / vSteps) * i);
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, height);
      ctx.stroke();
    }

    // Scale labels
    ctx.fillStyle = '#6B7280';
    ctx.font = '10px monospace';
    ctx.textAlign = 'left';
    ctx.fillText('20 kV', 8, 14);
    ctx.fillText('10 kV', 8, height / 2);
    ctx.fillText('0 V', 8, height - 6);
  }

  _drawWaveform(ctx, width, height, history) {
    const maxVoltage = 20000; // 20,000 mV = 20 V or 20 kV scale
    const paddingBottom = 20;
    const paddingTop = 20;
    const usableHeight = height - paddingTop - paddingBottom;

    // 1. Draw Peak Voltage line (Cyan)
    ctx.beginPath();
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = '#06B6D4'; // Cyan neon
    ctx.shadowColor = '#06B6D4';
    ctx.shadowBlur = 8;

    const stepX = width / (this.state.maxHistory - 1);
    const startOffset = width - (history.length - 1) * stepX;

    for (let i = 0; i < history.length; i++) {
      const point = history[i];
      const x = startOffset + i * stepX;
      const normalizedV = Math.min(1.0, Math.max(0.0, point.peakMv / maxVoltage));
      const y = height - paddingBottom - normalizedV * usableHeight;

      if (i === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    }
    ctx.stroke();
    ctx.shadowBlur = 0; // Reset blur

    // 2. Draw Optical Sensor Voltage line (Amber / Gold)
    ctx.beginPath();
    ctx.lineWidth = 1.8;
    ctx.strokeStyle = '#F59E0B'; // Amber neon
    ctx.shadowColor = '#F59E0B';
    ctx.shadowBlur = 6;

    for (let i = 0; i < history.length; i++) {
      const point = history[i];
      const x = startOffset + i * stepX;
      // Optical sensor max 5000 mV
      const normalizedOpt = Math.min(1.0, Math.max(0.0, point.opticalMv / 5000));
      const y = height - paddingBottom - normalizedOpt * (usableHeight * 0.5); // lower half emphasis

      if (i === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    }
    ctx.stroke();
    ctx.shadowBlur = 0;

    // 3. Current value dot at latest point
    const lastPoint = history[history.length - 1];
    const lastX = width;
    const lastPeakNorm = Math.min(1.0, Math.max(0.0, lastPoint.peakMv / maxVoltage));
    const lastY = height - paddingBottom - lastPeakNorm * usableHeight;

    ctx.fillStyle = lastPoint.tamperDetected ? '#EF4444' : '#06B6D4';
    ctx.beginPath();
    ctx.arc(lastX - 4, lastY, 5, 0, Math.PI * 2);
    ctx.fill();
  }
}
