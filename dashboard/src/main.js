import { DashboardState } from './state.js';
import { WebSocketClient } from './websocket_client.js';
import { WaveformCanvas } from './components/WaveformCanvas.js';
import { FftCanvas } from './components/FftCanvas.js';
import { MetricsCards } from './components/MetricsCards.js';

document.addEventListener('DOMContentLoaded', () => {
  // Parse URL query parameter: ?ws=ws://192.168.1.50:8765
  const urlParams = new URLSearchParams(window.location.search);
  const defaultWsUrl = urlParams.get('ws') || 'ws://localhost:8765';

  // DOM elements cache
  const elements = {
    wsUrlInput: document.getElementById('ws-url-input'),
    btnToggleConnect: document.getElementById('btn-toggle-connect'),
    statusDot: document.getElementById('status-dot'),
    statusText: document.getElementById('status-text'),
    alertBanner: document.getElementById('alert-banner'),

    classificationCard: document.getElementById('card-classification'),
    classificationVal: document.getElementById('val-classification'),
    classificationBadge: document.getElementById('badge-classification'),

    confidenceVal: document.getElementById('val-confidence'),
    confidenceBar: document.getElementById('bar-confidence'),

    peakVoltageVal: document.getElementById('val-peak-voltage'),
    peakVoltageSub: document.getElementById('val-peak-voltage-sub'),

    opticalSensorVal: document.getElementById('val-optical-sensor'),
    riseTimeVal: null,
    decayTimeVal: null,
    transientTimingVal: document.getElementById('val-transient-timing'),

    latencyVal: document.getElementById('val-latency'),
    tamperBadge: document.getElementById('badge-tamper'),

    seqIdVal: document.getElementById('val-sequence'),
    validFramesVal: document.getElementById('val-valid-frames'),
    fpsVal: document.getElementById('val-fps'),
    droppedFramesVal: document.getElementById('val-dropped-frames'),
    alertsCountVal: document.getElementById('val-alerts-count'),

    eventHistoryList: document.getElementById('event-history-list'),

    btnInjectEmp: document.getElementById('btn-inject-emp'),
    btnInjectOptical: document.getElementById('btn-inject-optical'),
    btnInjectSurge: document.getElementById('btn-inject-surge'),
    btnDemoMode: document.getElementById('btn-demo-mode'),
  };

  elements.wsUrlInput.value = defaultWsUrl;

  // Initialize central state & UI components
  const state = new DashboardState(120, 50);
  const metricsCards = new MetricsCards(elements);

  // Subscribe metrics updates
  state.subscribe((currentState) => {
    metricsCards.update(currentState);
    if (elements.transientTimingVal) {
      elements.transientTimingVal.textContent =
        `Rise: ${currentState.latest.riseTimeNs}ns | Decay: ${currentState.latest.decayTimeUs}µs`;
    }
  });

  // Initialize Canvas Visualizers
  const waveformCanvasEl = document.getElementById('waveform-canvas');
  const fftCanvasEl = document.getElementById('fft-canvas');

  let waveformRenderer = null;
  let fftRenderer = null;

  if (waveformCanvasEl) {
    waveformRenderer = new WaveformCanvas(waveformCanvasEl, state);
    waveformRenderer.start();
  }

  if (fftCanvasEl) {
    fftRenderer = new FftCanvas(fftCanvasEl, state);
    fftRenderer.start();
  }

  // Status helper
  function updateConnectionStatusUI(status, details) {
    elements.statusDot.className = 'status-dot';

    switch (status) {
      case 'CONNECTED':
        elements.statusDot.classList.add('connected');
        elements.statusText.textContent = 'Live Connected';
        elements.btnToggleConnect.textContent = 'Disconnect';
        elements.btnToggleConnect.className = 'btn';
        break;
      case 'CONNECTING':
        elements.statusDot.classList.add('connecting');
        elements.statusText.textContent = 'Connecting...';
        elements.btnToggleConnect.textContent = 'Cancel';
        elements.btnToggleConnect.className = 'btn';
        break;
      case 'STALE':
        elements.statusDot.classList.add('stale');
        elements.statusText.textContent = 'Stream Stalled';
        break;
      case 'DISCONNECTED':
      default:
        elements.statusDot.classList.add('disconnected');
        elements.statusText.textContent = 'Disconnected';
        elements.btnToggleConnect.textContent = 'Connect';
        elements.btnToggleConnect.className = 'btn btn-primary';
        break;
    }
  }

  // Initialize WebSocket client
  const wsClient = new WebSocketClient({
    url: defaultWsUrl,
    staleTimeoutMs: 3000,
    onMessage: (data) => {
      state.processFrame(data);
    },
    onStatusChange: (status, details) => {
      updateConnectionStatusUI(status, details);
    },
    onError: (err) => {
      console.warn('WebSocket client notice:', err.message);
    }
  });

  // Connect/Disconnect button handler
  elements.btnToggleConnect.addEventListener('click', () => {
    if (wsClient.getStatus() === 'CONNECTED' || wsClient.getStatus() === 'CONNECTING') {
      wsClient.disconnect();
    } else {
      const targetUrl = elements.wsUrlInput.value.trim() || defaultWsUrl;
      wsClient.connect(targetUrl);
    }
  });

  // Enter key inside URL input connects immediately
  elements.wsUrlInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      elements.btnToggleConnect.click();
    }
  });

  // Simulation Trigger Buttons (for interactive demonstration)
  function sendMockTamperCommand(tamperType) {
    if (wsClient.socket && wsClient.socket.readyState === WebSocket.OPEN) {
      try {
        wsClient.socket.send(JSON.stringify({ action: 'trigger_tamper', class: tamperType, count: 8 }));
      } catch (_) {}
    }
  }

  if (elements.btnInjectEmp) {
    elements.btnInjectEmp.addEventListener('click', () => sendMockTamperCommand('emp'));
  }
  if (elements.btnInjectOptical) {
    elements.btnInjectOptical.addEventListener('click', () => sendMockTamperCommand('optical'));
  }
  if (elements.btnInjectSurge) {
    elements.btnInjectSurge.addEventListener('click', () => sendMockTamperCommand('surge'));
  }
  if (elements.btnDemoMode) {
    elements.btnDemoMode.addEventListener('click', () => {
      state.reset();
    });
  }

  // Automatically initiate connection
  wsClient.connect();

  // Cleanup on page unload
  window.addEventListener('beforeunload', () => {
    wsClient.disconnect();
    if (waveformRenderer) waveformRenderer.destroy();
    if (fftRenderer) fftRenderer.destroy();
  });
});
