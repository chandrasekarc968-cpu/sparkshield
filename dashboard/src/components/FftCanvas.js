/**
 * High-performance HTML5 Canvas renderer for the 8-bin FFT Energy Spectrum.
 */
export class FftCanvas {
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

    // Clear
    ctx.fillStyle = '#0B0F19';
    ctx.fillRect(0, 0, width, height);

    const bins = this.state.latest.fftBins || [0, 0, 0, 0, 0, 0, 0, 0];
    const binCount = 8;
    const padding = 12;
    const bottomLabelSpace = 20;
    const availableWidth = width - padding * 2;
    const barWidth = (availableWidth / binCount) * 0.75;
    const gap = (availableWidth - barWidth * binCount) / (binCount - 1);
    const usableHeight = height - padding - bottomLabelSpace;

    const bandNames = ['50Hz', '150Hz', '250Hz', '1kHz', '10kHz', '100k', '1MHz', '10M+'];

    for (let i = 0; i < binCount; i++) {
      const val = bins[i] || 0;
      const normalized = Math.min(1.0, Math.max(0.0, val / 255.0));
      const barHeight = Math.max(4, normalized * usableHeight);
      const x = padding + i * (barWidth + gap);
      const y = height - bottomLabelSpace - barHeight;

      // Color coding: High frequency bins (4-7) highlighted violet/magenta (EMP indicator)
      let gradient;
      if (i >= 4 && val > 80) {
        gradient = ctx.createLinearGradient(0, y, 0, y + barHeight);
        gradient.addColorStop(0, '#EC4899'); // Pink / Magenta
        gradient.addColorStop(1, '#8B5CF6'); // Purple
      } else {
        gradient = ctx.createLinearGradient(0, y, 0, y + barHeight);
        gradient.addColorStop(0, '#06B6D4'); // Cyan
        gradient.addColorStop(1, '#1E3A8A'); // Deep blue
      }

      ctx.fillStyle = gradient;
      ctx.fillRect(x, y, barWidth, barHeight);

      // Value label on top of bar
      ctx.fillStyle = '#9CA3AF';
      ctx.font = '9px monospace';
      ctx.textAlign = 'center';
      ctx.fillText(String(val), x + barWidth / 2, y - 4);

      // Frequency band label at bottom
      ctx.fillStyle = '#6B7280';
      ctx.font = '9px sans-serif';
      ctx.fillText(bandNames[i], x + barWidth / 2, height - 6);
    }

    this.animationFrameId = requestAnimationFrame(this._render);
  }
}
