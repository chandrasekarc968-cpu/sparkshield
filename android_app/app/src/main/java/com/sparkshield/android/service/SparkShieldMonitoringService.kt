package com.sparkshield.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.sparkshield.android.data.SparkShieldDatabase
import com.sparkshield.android.data.entity.TamperEventEntity
import com.sparkshield.android.data.entity.TelemetrySnapshotEntity
import com.sparkshield.android.data.repository.RoomTelemetryRepository
import com.sparkshield.android.data.repository.TelemetryRepository
import com.sparkshield.android.features.FeatureExtractor
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.inference.CpuOnnxInferenceEngine
import com.sparkshield.android.inference.InferenceEngine
import com.sparkshield.android.inference.InferenceResult
import com.sparkshield.android.inference.QnnHtpInferenceEngine
import com.sparkshield.android.protocol.ProtocolResult
import com.sparkshield.android.protocol.SequenceStatus
import com.sparkshield.android.protocol.SequenceTracker
import com.sparkshield.android.protocol.TelemetryFrameParser
import com.sparkshield.android.transport.BleConnectionState
import com.sparkshield.android.transport.BleTelemetryProvider
import com.sparkshield.android.transport.MockTelemetryProvider
import com.sparkshield.android.transport.ProviderMode
import com.sparkshield.android.transport.TelemetryProvider
import com.sparkshield.android.ui.MonitoringState
import com.sparkshield.android.ui.TamperEvent
import com.sparkshield.android.websocket.TelemetryWsMessage
import com.sparkshield.android.websocket.WebSocketPublisher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android Foreground Service (connectedDevice) executing real-time smart-meter
 * telemetry frame parsing, feature extraction, edge neural network inference,
 * and confidence-gated tamper alerting.
 *
 * SIMULATION ONLY:
 * Does not hold a wake lock by default. Processes all frames on Dispatchers.Default.
 */
class SparkShieldMonitoringService(
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default
) : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private var processingJob: Job? = null
    private var bleMonitorJob: Job? = null

    private lateinit var notificationHelper: NotificationHelper
    private var telemetryProvider: TelemetryProvider? = null
    private var currentProviderMode: ProviderMode = ProviderMode.AUTO
    private lateinit var inferenceEngine: InferenceEngine
    private lateinit var featureExtractor: FeatureExtractor
    private lateinit var sequenceTracker: SequenceTracker
    private lateinit var alertGate: AlertGate
    private lateinit var webSocketPublisher: WebSocketPublisher
    private var telemetryRepository: TelemetryRepository? = null

    // Dedicated metrics counters
    private var receivedFramesCount: Long = 0L
    private var validFramesCount: Long = 0L
    private var invalidFramesCount: Long = 0L
    private var droppedFramesCount: Long = 0L
    private var inferredFramesCount: Long = 0L
    private var tamperAlertsCount: Long = 0L

    override fun onCreate() {
        super.onCreate()

        notificationHelper = NotificationHelper(this)
        featureExtractor = FeatureExtractor()
        sequenceTracker = SequenceTracker()
        alertGate = AlertGate(confidenceThreshold = 0.85f, cooldownMs = 5000L)
        webSocketPublisher = WebSocketPublisher(port = 8765)
        webSocketPublisher.start()

        inferenceEngine = QnnHtpInferenceEngine(
            context = applicationContext,
            cpuFallbackEngine = CpuOnnxInferenceEngine(context = applicationContext)
        )

        // Start ongoing foreground notification
        val initialNotification = notificationHelper.buildForegroundNotification("Initializing edge inference engine...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_SERVICE_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_SERVICE_ID, initialNotification)
        }

        instance = this

        try {
            val database = SparkShieldDatabase.getInstance(applicationContext)
            val repo = RoomTelemetryRepository(
                tamperDao = database.tamperEventDao(),
                snapshotDao = database.telemetrySnapshotDao(),
                scope = serviceScope
            )
            telemetryRepository = repo

            serviceScope.launch {
                repo.persistedTamperEventsCount.collect { count ->
                    _serviceState.update { it.copy(persistedTamperEvents = count) }
                }
            }
            serviceScope.launch {
                repo.persistedSnapshotsCount.collect { count ->
                    _serviceState.update { it.copy(persistedSnapshots = count) }
                }
            }
        } catch (e: Throwable) {
            // Gracefully handled if Android SQLite native runtime is unavailable (e.g. host JVM unit tests)
        }

        serviceScope.launch {
            val loadResult = inferenceEngine.load()
            val isLoaded = loadResult.isSuccess
            val loadError = loadResult.exceptionOrNull()?.message
            val qnnEngine = inferenceEngine as? QnnHtpInferenceEngine
            val acceleratorName = qnnEngine?.activeAccelerator ?: "CPU (ONNX)"
            val qnnStatus = qnnEngine?.qnnInitStatus ?: if (isLoaded) "READY_CPU" else "FAILED"
            val fallback = qnnEngine?.fallbackReason
            val ctxHash = qnnEngine?.modelContextHash

            _serviceState.update {
                it.copy(
                    isModelLoaded = isLoaded,
                    modelLoadError = loadError,
                    accelerator = acceleratorName,
                    qnnInitStatus = qnnStatus,
                    fallbackReason = fallback,
                    contextHash = ctxHash
                )
            }

            // Start provider after model loading finishes
            setProviderMode(currentProviderMode)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_PROVIDER_MODE) == true) {
            val requestedMode = ProviderMode.fromString(intent.getStringExtra(EXTRA_PROVIDER_MODE))
            if (requestedMode != currentProviderMode) {
                setProviderMode(requestedMode)
            }
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_TAMPER -> {
                val classId = intent.getIntExtra(EXTRA_TAMPER_CLASS_ID, ClassLabels.EMP.id)
                val count = intent.getIntExtra(EXTRA_TAMPER_BURST_COUNT, 8)
                val tamperClass = ClassLabels.fromId(classId)
                (telemetryProvider as? MockTelemetryProvider)?.triggerTamper(tamperClass, count)
            }
            else -> {
                _serviceState.update { it.copy(isServiceRunning = true) }
            }
        }
        return START_STICKY
    }

    private fun setProviderMode(mode: ProviderMode) {
        currentProviderMode = mode
        when (mode) {
            ProviderMode.MOCK -> {
                val mock = MockTelemetryProvider(intervalMs = 100L)
                switchProvider(
                    newProvider = mock,
                    modeName = "MOCK",
                    statusDetails = "In-memory synthetic telemetry stream (10 Hz)"
                )
            }
            ProviderMode.BLE -> {
                val ble = BleTelemetryProvider(applicationContext)
                switchProvider(
                    newProvider = ble,
                    modeName = "BLE",
                    statusDetails = "Connecting to SparkShield-Core via BLE..."
                )
            }
            ProviderMode.AUTO -> {
                val ble = BleTelemetryProvider(applicationContext)
                if (!ble.hasRequiredPermissions()) {
                    val missing = ble.getMissingPermissions().joinToString(", ")
                    val mock = MockTelemetryProvider(intervalMs = 100L)
                    switchProvider(
                        newProvider = mock,
                        modeName = "AUTO",
                        statusDetails = "Fallback to MOCK: Bluetooth permissions not granted ($missing)"
                    )
                } else {
                    switchProvider(
                        newProvider = ble,
                        modeName = "AUTO",
                        statusDetails = "Preferring BLE: Scanning for SparkShield-Core..."
                    )
                }
            }
        }
    }

    private fun switchProvider(newProvider: TelemetryProvider, modeName: String, statusDetails: String) {
        // 1. Cancel previous frame processing job atomically
        processingJob?.cancel()
        processingJob = null

        // 2. Cancel previous BLE monitoring job to prevent leaked callbacks
        bleMonitorJob?.cancel()
        bleMonitorJob = null

        // 3. Stop old provider cleanly if different
        val oldProvider = telemetryProvider
        if (oldProvider != null && oldProvider !== newProvider) {
            serviceScope.launch {
                oldProvider.stop()
            }
        }

        telemetryProvider = newProvider

        val isMock = newProvider is MockTelemetryProvider

        _serviceState.update {
            it.copy(
                isServiceRunning = true,
                providerMode = modeName,
                providerDetails = statusDetails,
                // Do not report isConnected=true before BLE reaches Streaming state
                isConnected = isMock,
                bleRssi = if (isMock) null else it.bleRssi
            )
        }

        // 4. If BLE provider, monitor connection state with automatic fallback in AUTO mode
        if (newProvider is BleTelemetryProvider) {
            bleMonitorJob = serviceScope.launch {
                launch {
                    newProvider.connectionState.collect { state ->
                        when (state) {
                            is BleConnectionState.Streaming -> {
                                _serviceState.update {
                                    it.copy(
                                        isConnected = true,
                                        providerDetails = "BLE Streaming from SparkShield-Core",
                                        bleRssi = newProvider.rssi.value
                                    )
                                }
                            }
                            is BleConnectionState.PermissionDenied -> {
                                _serviceState.update { it.copy(isConnected = false) }
                                if (currentProviderMode == ProviderMode.AUTO) {
                                    val missing = state.missingPermissions.joinToString(", ")
                                    switchProvider(
                                        newProvider = MockTelemetryProvider(intervalMs = 100L),
                                        modeName = "AUTO",
                                        statusDetails = "Fallback to MOCK: Permissions denied ($missing)"
                                    )
                                } else {
                                    _serviceState.update {
                                        it.copy(providerDetails = "BLE Error: Permissions denied")
                                    }
                                }
                            }
                            is BleConnectionState.AdapterUnavailable -> {
                                _serviceState.update { it.copy(isConnected = false) }
                                if (currentProviderMode == ProviderMode.AUTO) {
                                    switchProvider(
                                        newProvider = MockTelemetryProvider(intervalMs = 100L),
                                        modeName = "AUTO",
                                        statusDetails = "Fallback to MOCK: ${state.reason}"
                                    )
                                } else {
                                    _serviceState.update {
                                        it.copy(providerDetails = "BLE Error: ${state.reason}")
                                    }
                                }
                            }
                            is BleConnectionState.Disconnected -> {
                                _serviceState.update {
                                    it.copy(
                                        isConnected = false,
                                        providerDetails = "BLE: ${state.reason}"
                                    )
                                }
                            }
                            is BleConnectionState.Scanning -> {
                                _serviceState.update {
                                    it.copy(
                                        isConnected = false,
                                        providerDetails = "Scanning for SparkShield-Core..."
                                    )
                                }
                            }
                            is BleConnectionState.Connecting -> {
                                _serviceState.update {
                                    it.copy(
                                        isConnected = false,
                                        providerDetails = "Connecting to GATT server..."
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }

                launch {
                    newProvider.rssi.collect { rssiVal ->
                        if (rssiVal != null && _serviceState.value.isConnected) {
                            _serviceState.update { it.copy(bleRssi = rssiVal) }
                        }
                    }
                }
            }
        }

        // 5. Start new provider
        serviceScope.launch {
            newProvider.start()
        }

        // 6. Launch single fresh processingJob collecting from newProvider.frames
        processingJob = serviceScope.launch {
            newProvider.frames
                .catch { cause ->
                    _serviceState.update { current ->
                        current.copy(latestProtocolError = "Provider stream error: ${cause.message}")
                    }
                }
                .collect { rawBytes ->
                    processRawTelemetryFrame(rawBytes)
                }
        }
    }

    private suspend fun processRawTelemetryFrame(rawBytes: ByteArray) {
        receivedFramesCount++

        when (val parseResult = TelemetryFrameParser.parse(rawBytes)) {
            is ProtocolResult.Success -> {
                validFramesCount++
                val frame = parseResult.value

                // Sequence tracking & dropped packet calculation
                when (val seqStatus = sequenceTracker.process(frame.sequenceId)) {
                    is SequenceStatus.Gap -> {
                        droppedFramesCount += seqStatus.droppedCount
                    }
                    else -> {}
                }

                // 1. Sliding window feature extraction (128 floats)
                val featureTensor = featureExtractor.update(frame)

                // 2. Execute inference only when at least 8 frames are available (or warmup mode)
                val inferenceResult: InferenceResult
                if (featureExtractor.isReadyForInference && _serviceState.value.isModelLoaded) {
                    val inferResult = inferenceEngine.infer(featureTensor)
                    if (inferResult.isSuccess) {
                        inferenceResult = inferResult.getOrThrow()
                        inferredFramesCount++
                    } else {
                        inferenceResult = InferenceResult(
                            label = "NORMAL",
                            classIndex = 0,
                            probabilities = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
                            confidence = 1.0f,
                            inferenceTimeUs = 0L
                        )
                    }
                } else {
                    inferenceResult = InferenceResult(
                        label = "NORMAL (WARMING UP)",
                        classIndex = 0,
                        probabilities = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
                        confidence = 1.0f,
                        inferenceTimeUs = 0L
                    )
                }

                // 3. Confidence-gated alert decision (>= 0.85, 5s rate limiting, reset on NORMAL)
                var newTamperEvent: TamperEvent? = null
                if (featureExtractor.isReadyForInference) {
                    when (val decision = alertGate.evaluate(inferenceResult)) {
                        is AlertGate.AlertDecision.TriggerAlert -> {
                            tamperAlertsCount++
                            notificationHelper.postTamperAlert(decision)
                            newTamperEvent = TamperEvent(
                                timestampMs = decision.timestampMs,
                                tamperClass = decision.tamperClass,
                                confidence = decision.confidence,
                                message = decision.alertMessage
                            )
                            // Persist confirmed tamper alert to Room database via IO
                            val eventEntity = TamperEventEntity(
                                timestampMs = decision.timestampMs,
                                sequenceId = frame.sequenceId,
                                className = decision.tamperClass.name,
                                confidence = decision.confidence,
                                peakMv = frame.peakMv,
                                riseTimeNs = frame.riseTimeNs,
                                decayTimeUs = frame.decayTimeUs,
                                opticalMv = frame.opticalSensorMv,
                                message = decision.alertMessage
                            )
                            serviceScope.launch {
                                telemetryRepository?.recordTamperEvent(eventEntity)
                            }
                        }
                        is AlertGate.AlertDecision.Suppressed -> {}
                    }
                }

                // 4. Update UI StateFlow
                _serviceState.update { current ->
                    val updatedLog = if (newTamperEvent != null) {
                        (listOf(newTamperEvent) + current.tamperAlertLog).take(20)
                    } else {
                        current.tamperAlertLog
                    }

                    current.copy(
                        currentSequenceId = frame.sequenceId,
                        timestampMs = frame.timestampMs,
                        peakVoltageV = frame.peakV,
                        opticalSensorV = frame.opticalSensorV,
                        riseTimeNs = frame.riseTimeNs,
                        decayTimeMs = frame.decayTimeMs,
                        predictedClass = inferenceResult.label,
                        classIndex = inferenceResult.classIndex,
                        confidence = inferenceResult.confidence,
                        probabilities = inferenceResult.probabilities,
                        inferenceLatencyUs = inferenceResult.inferenceTimeUs,
                        receivedFrames = receivedFramesCount,
                        validFrames = validFramesCount,
                        invalidFrames = invalidFramesCount,
                        droppedFrames = droppedFramesCount,
                        inferredFrames = inferredFramesCount,
                        tamperAlerts = tamperAlertsCount,
                        latestProtocolError = null,
                        tamperAlertLog = updatedLog
                    )
                }

                // 5. Asynchronously broadcast frame to WebSocket dashboard clients
                val isTamper = inferenceResult.classIndex != ClassLabels.NORMAL.id && inferenceResult.confidence >= 0.85f
                val wsMessage = TelemetryWsMessage(
                    seqId = frame.sequenceId,
                    timestampMs = frame.timestampMs,
                    eventFlags = frame.eventFlags,
                    peakMv = frame.peakMv,
                    riseTimeNs = frame.riseTimeNs,
                    decayTimeUs = frame.decayTimeUs,
                    opticalMv = frame.opticalSensorMv,
                    fftBins = frame.fftEnergyBins.map { it.toInt() and 0xFF },
                    classification = inferenceResult.label,
                    confidence = inferenceResult.confidence,
                    inferenceTimeUs = inferenceResult.inferenceTimeUs,
                    tamperDetected = isTamper
                )
                webSocketPublisher.enqueueMessage(wsMessage)

                // 6. Record telemetry snapshot to in-memory batch buffer for persistence
                val snapshotEntity = TelemetrySnapshotEntity(
                    timestampMs = frame.timestampMs,
                    sequenceId = frame.sequenceId,
                    eventFlags = frame.eventFlags,
                    peakMv = frame.peakMv,
                    riseTimeNs = frame.riseTimeNs,
                    decayTimeUs = frame.decayTimeUs,
                    opticalMv = frame.opticalSensorMv,
                    classification = inferenceResult.label,
                    confidence = inferenceResult.confidence,
                    inferenceTimeUs = inferenceResult.inferenceTimeUs,
                    tamperDetected = isTamper
                )
                telemetryRepository?.recordTelemetrySnapshot(snapshotEntity)
            }
            is ProtocolResult.Failure -> {
                invalidFramesCount++
                _serviceState.update { current ->
                    current.copy(
                        receivedFrames = receivedFramesCount,
                        invalidFrames = invalidFramesCount,
                        latestProtocolError = parseResult.error.message
                    )
                }
            }
        }
    }

    private fun stopMonitoring() {
        processingJob?.cancel()
        processingJob = null

        bleMonitorJob?.cancel()
        bleMonitorJob = null

        webSocketPublisher.stop()

        val provider = telemetryProvider
        if (provider != null) {
            serviceScope.launch {
                provider.stop()
            }
        }

        telemetryRepository?.let { repo ->
            try {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2000L) {
                        repo.flushPendingSnapshots()
                    }
                }
            } catch (e: Exception) {
                // Ignore timeout or interrupt during service shutdown
            }
        }

        featureExtractor.reset()
        sequenceTracker.reset()
        alertGate.reset()

        _serviceState.update {
            it.copy(
                isServiceRunning = false,
                isConnected = false
            )
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        webSocketPublisher.stop()
        telemetryRepository?.stop()
        inferenceEngine.close()
        serviceScope.cancel()

        if (instance === this) {
            instance = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.sparkshield.android.action.START_MONITORING"
        const val ACTION_STOP = "com.sparkshield.android.action.STOP_MONITORING"
        const val ACTION_TRIGGER_TAMPER = "com.sparkshield.android.action.TRIGGER_TAMPER"

        const val EXTRA_TAMPER_CLASS_ID = "extra_tamper_class_id"
        const val EXTRA_TAMPER_BURST_COUNT = "extra_tamper_burst_count"
        const val EXTRA_PROVIDER_MODE = "extra_provider_mode"

        @Volatile
        private var instance: SparkShieldMonitoringService? = null

        private val _serviceState = MutableStateFlow(MonitoringState())
        val serviceState: StateFlow<MonitoringState> = _serviceState.asStateFlow()

        fun getTelemetryRepository(): TelemetryRepository? = instance?.telemetryRepository
        fun getInferenceEngine(): InferenceEngine? = instance?.inferenceEngine
        fun getWebSocketPublisher(): WebSocketPublisher? = instance?.webSocketPublisher

        fun setTelemetryRepositoryForTesting(repo: TelemetryRepository?) {
            instance?.telemetryRepository = repo
        }

        fun setInstanceForTesting(service: SparkShieldMonitoringService?) {
            instance = service
        }

        fun startService(context: Context) {
            val intent = Intent(context, SparkShieldMonitoringService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SparkShieldMonitoringService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerTamperInjection(context: Context, tamperClass: ClassLabels, burstCount: Int = 8) {
            val intent = Intent(context, SparkShieldMonitoringService::class.java).apply {
                action = ACTION_TRIGGER_TAMPER
                putExtra(EXTRA_TAMPER_CLASS_ID, tamperClass.id)
                putExtra(EXTRA_TAMPER_BURST_COUNT, burstCount)
            }
            context.startService(intent)
        }

        fun setProviderMode(context: Context, mode: com.sparkshield.android.transport.ProviderMode) {
            val intent = Intent(context, SparkShieldMonitoringService::class.java).apply {
                putExtra(EXTRA_PROVIDER_MODE, mode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
