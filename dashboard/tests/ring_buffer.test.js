import test from 'node:test';
import assert from 'node:assert/strict';
import { RingBuffer } from '../src/ring_buffer.js';

test('RingBuffer - should initialize with given capacity', () => {
  const rb = new RingBuffer(10);
  assert.strictEqual(rb.capacity, 10);
  assert.strictEqual(rb.length, 0);
  assert.strictEqual(rb.last(), null);
  assert.deepStrictEqual(rb.toArray(), []);
});

test('RingBuffer - should store items up to capacity in FIFO order', () => {
  const rb = new RingBuffer(3);
  rb.push('A');
  rb.push('B');
  rb.push('C');

  assert.strictEqual(rb.length, 3);
  assert.strictEqual(rb.last(), 'C');
  assert.deepStrictEqual(rb.toArray(), ['A', 'B', 'C']);
});

test('RingBuffer - should evict oldest item when capacity is exceeded', () => {
  const rb = new RingBuffer(3);
  rb.push(1);
  rb.push(2);
  rb.push(3);
  rb.push(4); // evicts 1

  assert.strictEqual(rb.length, 3);
  assert.strictEqual(rb.last(), 4);
  assert.deepStrictEqual(rb.toArray(), [2, 3, 4]);

  rb.push(5); // evicts 2
  assert.strictEqual(rb.length, 3);
  assert.deepStrictEqual(rb.toArray(), [3, 4, 5]);
});

test('RingBuffer - clear should reset buffer', () => {
  const rb = new RingBuffer(5);
  rb.push(10);
  rb.push(20);
  assert.strictEqual(rb.length, 2);

  rb.clear();
  assert.strictEqual(rb.length, 0);
  assert.strictEqual(rb.last(), null);
  assert.deepStrictEqual(rb.toArray(), []);
});
