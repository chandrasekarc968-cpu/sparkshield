package com.sparkshield.android.protocol

/**
 * Tracks packet sequence continuity, detecting dropped frames, duplicates, and 32-bit wrap-around.
 * Parity with python_core/frame_protocol.py:SequenceTracker.
 */
class SequenceTracker(initialSequence: Long? = null) {

    var lastSequence: Long? = initialSequence
        private set

    var totalReceived: Long = 0
        private set

    var totalDropped: Long = 0
        private set

    var totalDuplicates: Long = 0
        private set

    var totalOutOfOrder: Long = 0
        private set

    data class SequenceResult(val isContinuous: Boolean, val droppedCount: Long)

    /**
     * Process a newly received sequence ID.
     *
     * @param sequenceId 32-bit unsigned sequence number.
     * @return [SequenceResult] indicating continuity and number of dropped frames.
     */
    fun processSequence(sequenceId: Long): SequenceResult {
        totalReceived++

        val last = lastSequence
        if (last == null) {
            lastSequence = sequenceId
            return SequenceResult(isContinuous = true, droppedCount = 0)
        }

        val expected = (last + 1L) and 0xFFFFFFFFL

        if (sequenceId == expected) {
            lastSequence = sequenceId
            return SequenceResult(isContinuous = true, droppedCount = 0)
        }

        if (sequenceId == last) {
            totalDuplicates++
            return SequenceResult(isContinuous = false, droppedCount = 0)
        }

        // Account for 32-bit unsigned wrap-around
        val diff = (sequenceId - last) and 0xFFFFFFFFL

        // If sequence is within forward window of 2^31 - 1
        return if (diff < 0x80000000L) {
            val dropped = diff - 1L
            totalDropped += dropped
            lastSequence = sequenceId
            SequenceResult(isContinuous = false, droppedCount = dropped)
        } else {
            // Out of order or stale packet
            totalOutOfOrder++
            SequenceResult(isContinuous = false, droppedCount = 0)
        }
    }

    /**
     * Reset tracker state.
     */
    fun reset() {
        lastSequence = null
        totalReceived = 0
        totalDropped = 0
        totalDuplicates = 0
        totalOutOfOrder = 0
    }
}
