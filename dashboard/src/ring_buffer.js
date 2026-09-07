/**
 * Fixed-capacity circular ring buffer preventing unbounded memory growth.
 * Provides O(1) append with automatic FIFO eviction of oldest items.
 */
export class RingBuffer {
  /**
   * @param {number} capacity - Maximum number of elements to retain.
   */
  constructor(capacity = 120) {
    if (capacity <= 0) throw new Error('Capacity must be greater than 0');
    this.capacity = capacity;
    this.buffer = new Array(capacity);
    this.head = 0;
    this.size = 0;
  }

  /**
   * Appends an item to the ring buffer.
   * If the buffer is full, the oldest element is overwritten.
   * @param {*} item
   */
  push(item) {
    const writeIdx = (this.head + this.size) % this.capacity;
    this.buffer[writeIdx] = item;
    if (this.size < this.capacity) {
      this.size++;
    } else {
      this.head = (this.head + 1) % this.capacity;
    }
  }

  /**
   * Returns all items in chronological order (oldest first, newest last).
   * @returns {Array}
   */
  toArray() {
    const result = new Array(this.size);
    for (let i = 0; i < this.size; i++) {
      result[i] = this.buffer[(this.head + i) % this.capacity];
    }
    return result;
  }

  /**
   * Returns the newest element in the buffer, or null if empty.
   * @returns {*}
   */
  last() {
    if (this.size === 0) return null;
    const lastIdx = (this.head + this.size - 1) % this.capacity;
    return this.buffer[lastIdx];
  }

  /**
   * Returns the item at index (0 = oldest, size-1 = newest).
   * @param {number} index
   * @returns {*}
   */
  get(index) {
    if (index < 0 || index >= this.size) return undefined;
    return this.buffer[(this.head + index) % this.capacity];
  }

  /**
   * Current number of stored elements.
   * @returns {number}
   */
  get length() {
    return this.size;
  }

  /**
   * Clears all elements.
   */
  clear() {
    this.head = 0;
    this.size = 0;
  }
}
