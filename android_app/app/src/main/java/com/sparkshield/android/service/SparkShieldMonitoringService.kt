package com.sparkshield.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.sparkshield.android.features.FeatureExtractor
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.inference.CpuOnnxInferenceEngine
import com.sparkshield.android.inference.InferenceEngine
import com.sparkshield.android.protocol.ProtocolResult
import com.sparkshield.android.protocol.SequenceTracker
import com.sparkshield.android.protocol.TelemetryFrameParser
import com.sparkshield.android.transport.MockTelemetryProvider
import com.sparkshield.android.transport.TelemetryProvider
import com.sparkshield.android.ui.MonitoringState
import com.sparkshield.android.ui.TamperEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Android Foreground Service (connectedDevice) executing real-time smart-meter
 * telemetry frame parsing, feature extraction, edge neural network inference,
 * and confidence-gated tamper alerting.
 */
class SparkShieldMonitoringService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var processingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var telemetryProvider: TelemetryProvider
    private lateinit var inferenceEngine: InferenceEngine
    private lateinit var featureExtractor: FeatureExtractor
    private lateinit var sequenceTracker: SequenceTracker
    private lateinit var alertGate: AlertGate

    override fun onCreate() {
        super.onCreate()

        notificationHelper = NotificationHelper(this)
        featureExtractor = FeatureExtractor()
        sequenceTracker = SequenceTracker()
        alertGate = AlertGate()

        // Decoupled architecture: Mock provider for Phase 3
        telemetryProvider = MockTelemetryProvider(rateHz = 10.0f)
        inferenceEngine = CpuOnnxInferenceEngine()

        // Acquire partial wake lock to guarantee continuous edge monitoring
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SparkShield::MonitoringWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 minutes timeout safety
        }

        // Promote to foreground service immediately
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
            inferenceEngine.initialize(applicationContext)
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
                val count = intent.getIntExtra(EXTRA_TAMPER_BURST_COUNT, 5)
                val tamperClass = ClassLabels.fromId(classId)
                (telemetryProvider as? MockTelemetryProvider)?.triggerTamper(tamperClass, count)
            }
            else -> {
                // Default: ACTION_START or service restart
                _serviceState.update { it.copy(isServiceRunning = true) }
            }
        }
        return START_STICKY
    }

    private fun startProcessingPipeline() {
        if (processingJob?.isActive == true) return

        telemetryProvider.start()
        _serviceState.update {
            it.copy(
                isServiceRunning = true,
                isConnected = true
            )
        }

        processingJob = serviceScope.launch {
            telemetryProvider.rawFrameFlow.collect { rawBytes ->
                when (val parseResult = TelemetryFrameParser.parse(rawBytes)) {
                    is ProtocolResult.Success -> {
                        val frame = parseResult.frame
                        val seqResult = sequenceTracker.processSequence(frame.sequenceId)

                        // 1. Sliding window feature extraction (128 floats)
                        val featureTensor = featureExtractor.update(frame)

                        // 2. Edge ONNX Runtime inference
                        val inferenceResult = inferenceEngine.predict(featureTensor)

                        // 3. Confidence-gated alert decision (>= 0.85, rate-limited)
                        val alertDecision = alertGate.evaluate(inferenceResult)

                        var newTamperEvent: TamperEvent? = null
                        if (alertDecision is AlertGate.AlertDecision.TriggerAlert) {
                            notificationHelper.postTamperAlert(
                                alertDecision.tamperClass,
                                alertDecision.confidence
                            )
                            newTamperEvent = TamperEvent(
                                timestampMs = alertDecision.timestampMs,
                                tamperClass = alertDecision.tamperClass,
                                confidence = alertDecision.confidence
                            )
                        }

                        // 4. Update observable UI state
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
                                predictedClass = inferenceResult.predictedClass,
                                confidence = inferenceResult.confidence,
                                probabilities = inferenceResult.probabilities,
                                latencyMs = inferenceResult.latencyMs,
                                framesReceived = sequenceTracker.totalReceived,
                                framesDropped = sequenceTracker.totalDropped,
                                lastTamperClass = if (newTamperEvent != null) newTamperEvent.tamperClass else current.lastTamperClass,
                                lastTamperTimestamp = if (newTamperEvent != null) newTamperEvent.timestampMs else current.lastTamperTimestamp,
                                lastTamperConfidence = if (newTamperEvent != null) newTamperEvent.confidence else current.lastTamperConfidence,
                                tamperAlertLog = updatedLog
                            )
                        }
                    }
                    is ProtocolResult.Failure -> {
                        // Dropped or corrupted frame
                    }
                }
            }
        }
    }

    private fun stopMonitoring() {
        processingJob?.cancel()
        processingJob = null
        telemetryProvider.stop()
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

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
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

        fun triggerTamperInjection(context: Context, tamperClass: ClassLabels, burstCount: Int = 5) {
            val intent = Intent(context, SparkShieldMonitoringService::class.java).apply {
                action = ACTION_TRIGGER_TAMPER
                putExtra(EXTRA_TAMPER_CLASS_ID, tamperClass.id)
                putExtra(EXTRA_TAMPER_BURST_COUNT, burstCount)
            }
            context.startService(intent)
        }
    }
}
