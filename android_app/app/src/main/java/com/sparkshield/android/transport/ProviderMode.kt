package com.sparkshield.android.transport

/**
 * Runtime telemetry provider operational modes.
 */
enum class ProviderMode {
    /**
     * Local deterministic synthetic telemetry stream.
     */
    MOCK,

    /**
     * Physical Bluetooth Low Energy GATT Peripheral connection.
     */
    BLE,

    /**
     * Automatic mode: Prefers BLE, gracefully falling back to MOCK if
     * Bluetooth permissions, adapter, or device are unavailable.
     */
    AUTO;

    companion object {
        fun fromString(value: String?): ProviderMode {
            return when (value?.uppercase()) {
                "BLE" -> BLE
                "MOCK" -> MOCK
                else -> AUTO
            }
        }
    }
}
