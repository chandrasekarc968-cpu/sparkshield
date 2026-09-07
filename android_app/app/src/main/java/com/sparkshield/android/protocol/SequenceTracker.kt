package com.sparkshield.android.protocol

/**
 * Status resulting from processing a telemetry frame sequence ID.
 */
sealed class SequenceStatus {
    object Continuous : SequenceStatus()
    data class Gap(val droppedCount: Long) : SequenceStatus()
    object Duplicate : SequenceStatus()
    object OutOfOrder : SequenceStatus()

    val isContinuous: Boolean
        get() = this is Continuous
}

/**
 * Tracks packet sequence continuity, detecting dropped frames, duplicates, and 32-bit wrap-around.
 * Exact parity with python_core/frame_protocol.py:SequenceTracker.
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
     * Process a newly received sequence ID and return structured [SequenceStatus].
     *
     * @param sequenceId 32-bit unsigned sequence number.
     * @return [SequenceStatus] (Continuous, Gap, Duplicate, or OutOfOrder).
     */
    fun process(sequenceId: Long): SequenceStatus {
        totalReceived++

        val last = lastSequence
        if (last == null) {
            lastSequence = sequenceId
            return SequenceStatus.Continuous
        }

        val expected = (last + 1L) and 0xFFFFFFFFL

        if (sequenceId == expected) {
            lastSequence = sequenceId
            return SequenceStatus.Continuous
        }

        if (sequenceId == last) {
            totalDuplicates++
            return SequenceStatus.Duplicate
        }

        // Account for 32-bit unsigned wrap-around
        val diff = (sequenceId - last) and 0xFFFFFFFFL

        // If sequence is within forward window of 2^31 - 1
        return if (diff < 0x80000000L) {
            val dropped = diff - 1L
            totalDropped += dropped
            lastSequence = sequenceId
            SequenceStatus.Gap(dropped)
        } else {
            // Out of order or stale duplicate packet
            totalOutOfOrder++
            SequenceStatus.OutOfOrder
        }
    }

    /**
     * Legacy adapter returning [SequenceResult].
     */
    fun processSequence(sequenceId: Long): SequenceResult {
        return when (val status = process(sequenceId)) {
            is SequenceStatus.Continuous -> SequenceResult(isContinuous = true, droppedCount = 0)
            is SequenceStatus.Gap -> SequenceResult(isContinuous = false, droppedCount = status.droppedCount)
            is SequenceStatus.Duplicate -> SequenceResult(isContinuous = false, droppedCount = 0)
            is SequenceStatus.OutOfOrder -> SequenceResult(isContinuous = false, droppedCount = 0)
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
