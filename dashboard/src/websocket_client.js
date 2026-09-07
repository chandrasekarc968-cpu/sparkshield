/**
 * Resilient WebSocket client with exponential backoff reconnects,
 * heartbeat monitoring, stale data detection, and graceful malformed payload handling.
 */
export class WebSocketClient {
  /**
   * @param {Object} options
   * @param {string} [options.url] - WebSocket endpoint URL.
   * @param {Function} [options.onMessage] - Invoked with parsed JSON payload.
   * @param {Function} [options.onStatusChange] - Invoked on state transition.
   * @param {Function} [options.onError] - Invoked on protocol error.
   * @param {number} [options.staleTimeoutMs=3000] - Duration without messages before marking STALE.
   */
  constructor(options = {}) {
    this.url = options.url || 'ws://localhost:8765';
    this.onMessage = options.onMessage || (() => {});
    this.onStatusChange = options.onStatusChange || (() => {});
    this.onError = options.onError || (() => {});
    this.staleTimeoutMs = options.staleTimeoutMs || 3000;

    this.socket = null;
    this.status = 'DISCONNECTED'; // CONNECTING, CONNECTED, STALE, DISCONNECTED
    this.reconnectAttempts = 0;
    this.maxReconnectDelayMs = 10000;
    this.reconnectTimer = null;
    this.staleWatchdogTimer = null;
    this.isManualDisconnect = false;
    this.lastMessageTime = 0;
    this.malformedCount = 0;
  }

  /**
   * Updates status and informs listeners.
   */
  _setStatus(newStatus, details = '') {
    if (this.status !== newStatus) {
      this.status = newStatus;
      this.onStatusChange(newStatus, details);
    }
  }

  /**
   * Connects to the WebSocket server.
   * @param {string} [overrideUrl]
   */
  connect(overrideUrl) {
    if (overrideUrl) this.url = overrideUrl;
    this.isManualDisconnect = false;

    if (this.socket && (this.socket.readyState === 0 || this.socket.readyState === 1)) {
      this.socket.close();
    }

    clearTimeout(this.reconnectTimer);
    this._setStatus('CONNECTING', `Connecting to ${this.url}...`);

    try {
      if (typeof WebSocket === 'undefined') {
        this._setStatus('DISCONNECTED', 'WebSocket is not available in this environment');
        return;
      }

      this.socket = new WebSocket(this.url);

      this.socket.onopen = () => {
        this.reconnectAttempts = 0;
        this.lastMessageTime = Date.now();
        this._setStatus('CONNECTED', `Connected to ${this.url}`);
        this._resetStaleWatchdog();
      };

      this.socket.onmessage = (event) => {
        this.lastMessageTime = Date.now();
        this._resetStaleWatchdog();
        if (this.status === 'STALE') {
          this._setStatus('CONNECTED', `Telemetry stream restored`);
        }

        try {
          const data = JSON.parse(event.data);
          this.onMessage(data);
        } catch (err) {
          this.malformedCount++;
          this.onError(new Error(`Malformed JSON received: ${err.message}`));
        }
      };

      this.socket.onerror = (event) => {
        this.onError(new Error(`WebSocket error on ${this.url}`));
      };

      this.socket.onclose = (event) => {
        this._clearTimers();
        if (!this.isManualDisconnect) {
          this._setStatus('DISCONNECTED', `Connection lost (${event.code || 'closed'}). Reconnecting...`);
          this._scheduleReconnect();
        } else {
          this._setStatus('DISCONNECTED', 'Disconnected by user');
        }
      };
    } catch (e) {
      this._setStatus('DISCONNECTED', `Failed to open socket: ${e.message}`);
      this._scheduleReconnect();
    }
  }

  _resetStaleWatchdog() {
    clearTimeout(this.staleWatchdogTimer);
    this.staleWatchdogTimer = setTimeout(() => {
      if (this.status === 'CONNECTED') {
        this._setStatus('STALE', `No telemetry frames received for ${this.staleTimeoutMs / 1000}s`);
      }
    }, this.staleTimeoutMs);
  }

  _scheduleReconnect() {
    clearTimeout(this.reconnectTimer);
    this.reconnectAttempts++;
    // Exponential backoff with 20% random jitter
    const baseDelay = Math.min(1000 * Math.pow(1.5, this.reconnectAttempts - 1), this.maxReconnectDelayMs);
    const jitter = baseDelay * 0.2 * (Math.random() - 0.5);
    const delay = Math.max(500, Math.floor(baseDelay + jitter));

    this.reconnectTimer = setTimeout(() => {
      if (!this.isManualDisconnect) {
        this.connect();
      }
    }, delay);
  }

  _clearTimers() {
    clearTimeout(this.reconnectTimer);
    clearTimeout(this.staleWatchdogTimer);
  }

  /**
   * Closes active connection and stops auto-reconnects.
   */
  disconnect() {
    this.isManualDisconnect = true;
    this._clearTimers();
    if (this.socket) {
      try {
        this.socket.close(1000, 'User initiated disconnect');
      } catch (_) {}
      this.socket = null;
    }
    this._setStatus('DISCONNECTED', 'Disconnected');
  }

  /**
   * Forces reconnect to the current or new URL.
   */
  reconnect(newUrl) {
    this.disconnect();
    this.connect(newUrl);
  }

  getStatus() {
    return this.status;
  }
}
