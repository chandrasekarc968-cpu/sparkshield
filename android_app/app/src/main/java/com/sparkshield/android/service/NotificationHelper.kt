package com.sparkshield.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.sparkshield.android.MainActivity
import com.sparkshield.android.R
import com.sparkshield.android.inference.ClassLabels

/**
 * Manages notification channels, foreground service persistent notifications,
 * and high-priority heads-up tamper alerts with haptic vibration patterns.
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
                context.getString(R.string.channel_monitoring_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_monitoring_desc)
                setShowBadge(false)
            }

            // High priority channel for confidence-gated tamper alerts
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                context.getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_alerts_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(monitoringChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    /**
     * Builds the persistent notification required for the foreground service.
     */
    fun buildForegroundNotification(statusText: String = "Monitoring smart-meter telemetry"): Notification {
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
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Dispatches a high-priority heads-up tamper alert notification and triggers haptic feedback.
     */
    fun postTamperAlert(tamperClass: ClassLabels, confidence: Float) {
        val title = "TAMPER DETECTED: ${tamperClass.name}"
        val text = "Confidence: ${(confidence * 100).toInt()}% - Edge ML confirmed anomalous transient signature."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            tamperClass.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ALERT_BASE_ID + tamperClass.id, notification)
        triggerHapticPattern(tamperClass)
    }

    /**
     * Executes specialized haptic vibration signatures based on the tamper class.
     */
    fun triggerHapticPattern(tamperClass: ClassLabels) {
        val pattern = when (tamperClass) {
            ClassLabels.EMP -> longArrayOf(0, 100, 50, 100, 50, 300)      // Rapid bursts
            ClassLabels.OPTICAL -> longArrayOf(0, 400, 200, 400)           // Sustained pulses
            ClassLabels.SURGE -> longArrayOf(0, 250, 100, 250)             // Double shock
            ClassLabels.NORMAL -> return
        }

        val amplitudes = when (tamperClass) {
            ClassLabels.EMP -> intArrayOf(0, 255, 0, 255, 0, 255)
            ClassLabels.OPTICAL -> intArrayOf(0, 180, 0, 255)
            ClassLabels.SURGE -> intArrayOf(0, 220, 0, 220)
            ClassLabels.NORMAL -> return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
            // Safe fallback if permission or hardware unavailable in emulator
        }
    }

    companion object {
        const val CHANNEL_MONITORING_ID = "sparkshield_monitoring_channel"
        const val CHANNEL_ALERTS_ID = "sparkshield_tamper_alerts_channel"

        const val NOTIFICATION_SERVICE_ID = 1001
        const val NOTIFICATION_ALERT_BASE_ID = 2000
    }
}
