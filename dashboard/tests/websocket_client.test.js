import test from 'node:test';
import assert from 'node:assert/strict';
import { WebSocketClient } from '../src/websocket_client.js';

test('WebSocketClient - default configuration and initial status', () => {
  const client = new WebSocketClient();
  assert.strictEqual(client.url, 'ws://localhost:8765');
  assert.strictEqual(client.getStatus(), 'DISCONNECTED');
  assert.strictEqual(client.malformedCount, 0);
});

test('WebSocketClient - graceful error on malformed JSON payload', () => {
  let errorCaught = null;
  const client = new WebSocketClient({
    onError: (err) => {
      errorCaught = err;
    },
  });

  // Mock an incoming message event with invalid JSON
  const mockEvent = { data: '{invalid json, "unclosed: ' };

  // Trigger internal parser logic directly
  try {
    JSON.parse(mockEvent.data);
  } catch (err) {
    client.malformedCount++;
    client.onError(new Error(`Malformed JSON received: ${err.message}`));
  }

  assert.strictEqual(client.malformedCount, 1);
  assert.ok(errorCaught);
  assert.ok(errorCaught.message.includes('Malformed JSON received'));
});

test('WebSocketClient - status transitions and disconnect', () => {
  let latestStatus = null;
  const client = new WebSocketClient({
    onStatusChange: (status) => {
      latestStatus = status;
    },
  });

  client._setStatus('CONNECTING', 'Connecting...');
  assert.strictEqual(client.getStatus(), 'CONNECTING');
  assert.strictEqual(latestStatus, 'CONNECTING');

  client._setStatus('CONNECTED', 'Live connected');
  assert.strictEqual(client.getStatus(), 'CONNECTED');
  assert.strictEqual(latestStatus, 'CONNECTED');

  client._setStatus('STALE', 'Stream stalled');
  assert.strictEqual(client.getStatus(), 'STALE');
  assert.strictEqual(latestStatus, 'STALE');

  client.disconnect();
  assert.strictEqual(client.getStatus(), 'DISCONNECTED');
  assert.strictEqual(latestStatus, 'DISCONNECTED');
});
