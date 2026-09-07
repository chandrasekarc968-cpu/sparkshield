package com.sparkshield.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.sparkshield.android.MainActivity
import com.sparkshield.android.R
import com.sparkshield.android.inference.ClassLabels

/**
 * Manages notification channels, foreground service persistent notifications,
 * and high-priority simulation tamper alerts with haptic vibration patterns.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Low priority channel for continuous background monitoring
            val monitoringChannel = NotificationChannel(
                CHANNEL_MONITORING_ID,
                "SparkShield Edge Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing smart-meter telemetry edge monitoring (SIMULATION ONLY)"
                setShowBadge(false)
            }

            // High priority channel: SparkShield Simulation Alerts
            val alertsChannel = NotificationChannel(
                CHANNEL_SIMULATION_ALERTS_ID,
                "SparkShield Simulation Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Confidence-gated smart-meter tamper alerts (SIMULATION ONLY)"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(monitoringChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    /**
     * Builds the persistent notification required for the foreground service.
     */
    fun buildForegroundNotification(statusText: String = "Monitoring smart-meter telemetry (SIMULATION ONLY)"): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITORING_ID)
            .setContentTitle("SparkShield [SIMULATION ONLY]")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Dispatches a high-priority heads-up tamper alert notification and triggers haptic vibration.
     */
    fun postTamperAlert(decision: AlertGate.AlertDecision.TriggerAlert) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            decision.tamperClass.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val confPercent = (decision.confidence * 100).toInt()
        val content = "${decision.alertMessage} (Confidence: $confPercent%) - SIMULATION ONLY"

        val notification = NotificationCompat.Builder(context, CHANNEL_SIMULATION_ALERTS_ID)
            .setContentTitle("[SIMULATION ONLY] ${decision.tamperClass.name} Detected")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ALERT_BASE_ID + decision.tamperClass.id, notification)
        triggerShortVibration()
    }

    /**
     * Triggers a short vibration pattern only for a gated alert. Does not use full-screen intents.
     */
    fun triggerShortVibration() {
        val pattern = longArrayOf(0, 250, 100, 250) // Short double pulse
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL_MONITORING_ID = "sparkshield_monitoring_channel"
        const val CHANNEL_SIMULATION_ALERTS_ID = "sparkshield_simulation_alerts"

        const val NOTIFICATION_SERVICE_ID = 1001
        const val NOTIFICATION_ALERT_BASE_ID = 2000
    }
}
