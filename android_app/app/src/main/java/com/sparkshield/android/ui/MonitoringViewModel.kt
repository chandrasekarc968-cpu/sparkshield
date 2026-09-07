package com.sparkshield.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.service.SparkShieldMonitoringService
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel connecting UI components with the background SparkShield monitoring service.
 */
class MonitoringViewModel : ViewModel() {

    val uiState: StateFlow<MonitoringState> = SparkShieldMonitoringService.serviceState

    fun startMonitoring(context: Context) {
        SparkShieldMonitoringService.startService(context)
    }

    fun stopMonitoring(context: Context) {
        SparkShieldMonitoringService.stopService(context)
    }

    fun injectTamperBurst(context: Context, tamperClass: ClassLabels, burstCount: Int = 5) {
        SparkShieldMonitoringService.triggerTamperInjection(context, tamperClass, burstCount)
    }
}
