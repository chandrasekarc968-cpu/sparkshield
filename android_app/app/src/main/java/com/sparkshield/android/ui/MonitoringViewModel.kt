package com.sparkshield.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.sparkshield.android.data.entity.TamperEventEntity
import com.sparkshield.android.data.repository.TelemetryRepository
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.service.SparkShieldMonitoringService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * ViewModel connecting UI components with the background SparkShield monitoring service.
 */
class MonitoringViewModel : ViewModel() {

    val uiState: StateFlow<MonitoringState> = SparkShieldMonitoringService.serviceState

    val repository: TelemetryRepository?
        get() = SparkShieldMonitoringService.getTelemetryRepository()

    val recentPersistedAlerts: Flow<List<TamperEventEntity>>
        get() = repository?.recentTamperEvents ?: emptyFlow()

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
