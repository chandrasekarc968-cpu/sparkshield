package com.sparkshield.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.sparkshield.android.features.FeatureExtractor
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.inference.CpuOnnxInferenceEngine
import com.sparkshield.android.inference.InferenceEngine
import com.sparkshield.android.inference.InferenceResult
import com.sparkshield.android.protocol.ProtocolResult
import com.sparkshield.android.protocol.SequenceStatus
import com.sparkshield.android.protocol.SequenceTracker
import com.sparkshield.android.protocol.TelemetryFrameParser
import com.sparkshield.android.transport.MockTelemetryProvider
import com.sparkshield.android.transport.TelemetryProvider
import com.sparkshield.android.ui.MonitoringState
import com.sparkshield.android.ui.TamperEvent
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

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var telemetryProvider: TelemetryProvider
    private lateinit var inferenceEngine: InferenceEngine
    private lateinit var featureExtractor: FeatureExtractor
    private lateinit var sequenceTracker: SequenceTracker
    private lateinit var alertGate: AlertGate

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

        // Decoupled architecture: Mock provider for simulation
        telemetryProvider = MockTelemetryProvider(intervalMs = 100L)
        inferenceEngine = CpuOnnxInferenceEngine(context = applicationContext)

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

        serviceScope.launch {
            val loadResult = inferenceEngine.load()
            val isLoaded = loadResult.isSuccess
            val loadError = loadResult.exceptionOrNull()?.message

            _serviceState.update {
                it.copy(
                    isModelLoaded = isLoaded,
                    modelLoadError = loadError
                )
            }

            startProcessingPipeline()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    private fun startProcessingPipeline() {
        if (processingJob?.isActive == true) return

        serviceScope.launch {
            telemetryProvider.start()
        }

        _serviceState.update {
            it.copy(
                isServiceRunning = true,
                isConnected = true
            )
        }

        processingJob = serviceScope.launch {
            telemetryProvider.frames
                .catch { cause ->
                    // Handle provider failures gracefully without crashing service
                    _serviceState.update { current ->
                        current.copy(latestProtocolError = "Provider stream error: ${cause.message}")
                    }
                }
                .collect { rawBytes ->
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
        }
    }

    private fun stopMonitoring() {
        processingJob?.cancel()
        processingJob = null

        serviceScope.launch {
            telemetryProvider.stop()
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
        inferenceEngine.close()
        serviceScope.cancel()

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

        private val _serviceState = MutableStateFlow(MonitoringState())
        val serviceState: StateFlow<MonitoringState> = _serviceState.asStateFlow()

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
    }
}
