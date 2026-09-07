package com.sparkshield.android.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.sparkshield.android.protocol.ProtocolResult
import com.sparkshield.android.protocol.SequenceStatus
import com.sparkshield.android.protocol.SequenceTracker
import com.sparkshield.android.protocol.TelemetryFrameParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Connection states for Bluetooth Low Energy GATT transport.
 */
sealed class BleConnectionState {
    data class Disconnected(val reason: String = "") : BleConnectionState()
    object Scanning : BleConnectionState()
    object Connecting : BleConnectionState()
    object Connected : BleConnectionState()
    object ServicesDiscovered : BleConnectionState()
    object NotificationsEnabled : BleConnectionState()
    object Streaming : BleConnectionState()
    data class PermissionDenied(val missingPermissions: List<String>) : BleConnectionState()
    data class AdapterUnavailable(val reason: String) : BleConnectionState()
}

/**
 * Production Bluetooth Low Energy GATT Telemetry Provider.
 *
 * Implements the TelemetryProvider contract over real BLE GATT characteristics:
 * - Service UUID: 1A860001-C7E2-432A-8C2A-8B6C7741E001
 * - Telemetry Characteristic: 1A860002-C7E2-432A-8C2A-8B6C7741E001
 * - CCCD Descriptor: 00002902-0000-1000-8000-00805f9b34fb
 * - MTU negotiation: 247 bytes
 *
 * SIMULATION ONLY:
 * Ingests 29-byte software-simulated smart-meter frames from SparkShield-Core.
 */
class BleTelemetryProvider(
    private val context: Context? = null,
    private val targetDeviceName: String = TARGET_DEVICE_NAME,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : TelemetryProvider {

    private val tag = "SparkShieldBLE"
    private val providerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var reconnectJob: Job? = null

    // Bounded channel: capacity 100, drops oldest on backpressure, never blocks GATT callbacks
    internal val frameChannel = Channel<ByteArray>(
        capacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val frames: Flow<ByteArray> = frameChannel.receiveAsFlow()

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected("Initial state"))
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val _droppedFrameCount = MutableStateFlow(0L)
    val droppedFrameCount: StateFlow<Long> = _droppedFrameCount.asStateFlow()

    private val _lastFrameTimeMs = MutableStateFlow(0L)
    val lastFrameTimeMs: StateFlow<Long> = _lastFrameTimeMs.asStateFlow()

    private val _reconnectAttempts = MutableStateFlow(0)
    val reconnectAttempts: StateFlow<Int> = _reconnectAttempts.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isManuallyStopped = false
    internal val sequenceTracker = SequenceTracker()

    init {
        try {
            val bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
        } catch (e: Throwable) {
            bluetoothAdapter = null
        }
    }

    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) {}
    }

    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) {}
    }

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        } catch (_: Throwable) {}
    }

    /**
     * Checks whether required Bluetooth runtime permissions are granted.
     */
    fun hasRequiredPermissions(): Boolean {
        val ctx = context ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val scanGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                val connectGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                scanGranted && connectGranted
            } else {
                true
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Returns list of missing Bluetooth permissions for UI prompts.
     */
    fun getMissingPermissions(): List<String> {
        val ctx = context ?: return listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val missing = mutableListOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        } catch (e: Throwable) {
            missing.add(Manifest.permission.BLUETOOTH_SCAN)
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return missing
    }

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        isManuallyStopped = false

        if (!hasRequiredPermissions()) {
            val missing = getMissingPermissions()
            logW(tag, "Missing required BLE permissions: $missing")
            _connectionState.value = BleConnectionState.PermissionDenied(missing)
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            logW(tag, "Bluetooth adapter is disabled or unavailable")
            _connectionState.value = BleConnectionState.AdapterUnavailable("Bluetooth adapter is turned off or not supported")
            return
        }

        startScanning()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (isScanning || isManuallyStopped) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.AdapterUnavailable("BLE Scanner unavailable")
            return
        }

        val filterByName = ScanFilter.Builder().setDeviceName(targetDeviceName).build()
        val filterByService = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _connectionState.value = BleConnectionState.Scanning
        isScanning = true
        Log.i(tag, "Starting BLE scan for '$targetDeviceName' or UUID $SERVICE_UUID...")

        try {
            scanner.startScan(listOf(filterByName, filterByService), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start BLE scan: ${e.message}", e)
            isScanning = false
            _connectionState.value = BleConnectionState.Disconnected("Scan failed: ${e.message}")
            scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            Log.i(tag, "BLE scan stopped.")
        } catch (e: Exception) {
            Log.w(tag, "Error stopping scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val devName = device.name ?: result.scanRecord?.deviceName

            if (devName == targetDeviceName || result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true) {
                Log.i(tag, "Found target BLE peripheral: ${device.address} ($devName), RSSI: ${result.rssi}")
                stopScanning()
                _rssi.value = result.rssi
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "BLE Scan failed with error code: $errorCode")
            isScanning = false
            _connectionState.value = BleConnectionState.Disconnected("Scan failed (code $errorCode)")
            scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.Connecting
        Log.i(tag, "Connecting to GATT server on ${device.address}...")

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(tag, "GATT Connection state changed: status=$status, newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = BleConnectionState.Connected
                _reconnectAttempts.value = 0

                // Negotiate MTU 247 for atomic 29-byte frame delivery
                val requested = gatt?.requestMtu(247) ?: false
                Log.i(tag, "Requested MTU 247: $requested")

                // Discover GATT services
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = BleConnectionState.Disconnected("GATT disconnected (status $status)")
                closeGatt()
                if (!isManuallyStopped) {
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.e(tag, "Service discovery failed with status $status")
                _connectionState.value = BleConnectionState.Disconnected("Service discovery failed")
                scheduleReconnect()
                return
            }

            _connectionState.value = BleConnectionState.ServicesDiscovered
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.w(tag, "SparkShield Service $SERVICE_UUID not found on device")
                _connectionState.value = BleConnectionState.Disconnected("Service not found")
                scheduleReconnect()
                return
            }

            val telemetryChar = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (telemetryChar == null) {
                Log.w(tag, "Telemetry Characteristic $CHARACTERISTIC_UUID not found")
                _connectionState.value = BleConnectionState.Disconnected("Characteristic not found")
                scheduleReconnect()
                return
            }

            // Enable local notifications
            gatt.setCharacteristicNotification(telemetryChar, true)

            // Write CCCD descriptor (0x2902) to enable remote notifications
            val descriptor = telemetryChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                Log.i(tag, "Writing CCCD descriptor to enable telemetry notifications...")
            } else {
                Log.w(tag, "CCCD descriptor not found on characteristic")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(tag, "Telemetry notifications enabled successfully over BLE.")
                _connectionState.value = BleConnectionState.NotificationsEnabled
                _connectionState.value = BleConnectionState.Streaming
            } else {
                Log.w(tag, "Descriptor write failed with status $status")
            }
        }

        // Deprecated callback for Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (characteristic?.uuid == CHARACTERISTIC_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                if (value != null) {
                    handleIncomingFrame(value)
                }
            }
        }

        // Modern callback for Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                handleIncomingFrame(value)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }
    }

    internal fun handleIncomingFrame(rawBytes: ByteArray) {
        _lastFrameTimeMs.value = System.currentTimeMillis()

        // Validate frame integrity using existing TelemetryFrameParser
        when (val result = TelemetryFrameParser.parse(rawBytes)) {
            is ProtocolResult.Success -> {
                val frame = result.value
                when (val seq = sequenceTracker.process(frame.sequenceId)) {
                    is SequenceStatus.Gap -> {
                        _droppedFrameCount.value += seq.droppedCount
                    }
                    else -> {}
                }

                // Offer to bounded channel; non-blocking
                frameChannel.trySend(rawBytes)
            }
            is ProtocolResult.Failure -> {
                logW(tag, "Rejected invalid BLE frame: ${result.error.message}")
            }
        }
    }

    internal fun calculateBackoffDelayMs(attempts: Int): Long {
        return minOf(1000L * (1L shl minOf(attempts, 4)), 15000L)
    }

    internal fun setConnectionStateForTesting(state: BleConnectionState) {
        _connectionState.value = state
    }

    private fun scheduleReconnect() {
        if (isManuallyStopped) return

        reconnectJob?.cancel()
        reconnectJob = providerScope.launch {
            _reconnectAttempts.value++
            val attempts = _reconnectAttempts.value
            // Bounded exponential backoff: 1s, 2s, 4s, 8s, up to 15s max
            val delayMs = calculateBackoffDelayMs(attempts)
            logI(tag, "Scheduling BLE reconnect attempt #$attempts in ${delayMs}ms...")
            delay(delayMs)

            if (isActive && !isManuallyStopped) {
                start()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing GATT: ${e.message}")
        }
        bluetoothGatt = null
    }

    override suspend fun stop() {
        isManuallyStopped = true
        reconnectJob?.cancel()
        reconnectJob = null

        stopScanning()
        closeGatt()
        sequenceTracker.reset()
        _connectionState.value = BleConnectionState.Disconnected("Manually stopped")
        Log.i(tag, "BleTelemetryProvider stopped cleanly.")
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("1A860001-C7E2-432A-8C2A-8B6C7741E001")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("1A860002-C7E2-432A-8C2A-8B6C7741E001")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TARGET_DEVICE_NAME: String = "SparkShield-Core"
    }
}
