package com.sparkshield.android.simulation

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sparkshield.android.inference.ClassLabels
import com.sparkshield.android.ui.theme.*
import kotlin.math.max
import kotlin.math.min

@Composable
fun SimulationLabScreen(
    viewModel: SimulationViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showHelpDialog by remember { mutableStateOf(false) }
    var selectedHelpTerm by remember { mutableStateOf<String?>(null) }
    var selectedSamplePoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    val darkBg = Color(0xFF0D1117)
    val cardBg = Color(0xFF161B22)
    val cardBorder = Color(0xFF30363D)
    val accentCyan = Color(0xFF06B6D4)
    val alertRed = Color(0xFFDA3633)
    val safeGreen = Color(0xFF238636)
    val warningAmber = Color(0xFFD29922)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(darkBg)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header with Badge, Safety Disclosure, and Reset/Info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SparkShield Simulation Lab",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF388BFD).copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF388BFD))
                            ) {
                                Text(
                                    text = "SOFTWARE SIMULATION",
                                    color = Color(0xFF58A6FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (uiState.publishToDashboard) warningAmber.copy(alpha = 0.2f) else Color(0xFF30363D),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (uiState.publishToDashboard) warningAmber else cardBorder)
                            ) {
                                Text(
                                    text = if (uiState.publishToDashboard) "PUBLISHING (WS)" else "LOCAL SIMULATION ONLY",
                                    color = if (uiState.publishToDashboard) warningAmber else Color(0xFF8B949E),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Row {
                        IconButton(onClick = { showHelpDialog = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Help & Info", tint = Color(0xFF8B949E))
                        }
                        IconButton(onClick = {
                            viewModel.resetToDefaults()
                            Toast.makeText(context, "Restored documented defaults", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset Defaults", tint = Color(0xFF8B949E))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tune a synthetic signal, inspect its measurable signature, and compare the model result with the expected attack class.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8B949E)
                )

                // Safety Boundary Notice
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Safety Boundary: Pure software simulation. Does not control physical meters, high-voltage hardware, or pulse generators.",
                    fontSize = 11.sp,
                    color = Color(0xFFF0883E),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }

        // 2. Attack Mode Selector Tabs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "ATTACK MODE SELECTOR",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF8B949E),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    SimulationMode.values().forEach { mode ->
                        val isSelected = uiState.mode == mode
                        val modeColor = when (mode) {
                            SimulationMode.NORMAL -> safeGreen
                            SimulationMode.EMP -> alertRed
                            SimulationMode.OPTICAL -> warningAmber
                            SimulationMode.SURGE -> Color(0xFFA371F7)
                        }

                        Button(
                            onClick = { viewModel.setMode(mode) },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .padding(horizontal = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) modeColor else Color(0xFF21262D),
                                contentColor = if (isSelected) Color.White else Color(0xFFC9D1D9)
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(
                                text = mode.displayName,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Presets Bar
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (preset in SimulationPresets.allPresets) {
                        val isSelected = uiState.selectedPreset == preset.name
                        OutlinedButton(
                            onClick = { viewModel.loadPreset(preset.name) },
                            modifier = Modifier.height(36.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) Color(0xFF1F6FEB).copy(alpha = 0.2f) else Color.Transparent,
                                contentColor = if (isSelected) Color(0xFF58A6FF) else Color(0xFF8B949E)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF58A6FF) else cardBorder
                            )
                        ) {
                            Text(text = preset.name, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 3. Dynamic Parameter Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SYNTHETIC SIGNAL PARAMETERS",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF8B949E),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Seed: ${uiState.seed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF58A6FF)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                when (val p = uiState.parameters) {
                    is NormalParameters -> {
                        SliderControl(
                            label = "Line Frequency",
                            value = p.lineFreqHz,
                            unit = "Hz",
                            range = 45f..65f,
                            onValueChange = { viewModel.updateParameters(p.copy(lineFreqHz = it)) },
                            onInfoClick = { selectedHelpTerm = "Line frequency: standard AC grid power frequency (50 or 60 Hz)." }
                        )
                        SliderControl(
                            label = "Base Amplitude [Frame Wire]",
                            value = p.baseAmplitudeMv,
                            unit = "mV",
                            range = 2500f..4500f,
                            onValueChange = { viewModel.updateParameters(p.copy(baseAmplitudeMv = it)) },
                            onInfoClick = { selectedHelpTerm = "Base amplitude: fundamental grid peak voltage serialized into 29-byte frame." }
                        )
                        SliderControl(
                            label = "Harmonic Strength [Vis-Only]",
                            value = p.harmonicStrengthMv,
                            unit = "mV",
                            range = 0f..200f,
                            onValueChange = { viewModel.updateParameters(p.copy(harmonicStrengthMv = it)) },
                            onInfoClick = { selectedHelpTerm = "Harmonic strength: 3rd harmonic distortion level in synthetic waveform." }
                        )
                        SliderControl(
                            label = "Noise Level [Vis-Only]",
                            value = p.noiseLevelMv,
                            unit = "mV",
                            range = 0f..100f,
                            onValueChange = { viewModel.updateParameters(p.copy(noiseLevelMv = it)) },
                            onInfoClick = { selectedHelpTerm = "Noise level: Gaussian noise standard deviation in millivolts." }
                        )
                        SliderControl(
                            label = "Duration",
                            value = p.durationMs,
                            unit = "ms",
                            range = 10f..50f,
                            onValueChange = { viewModel.updateParameters(p.copy(durationMs = it)) }
                        )
                    }
                    is EmpParameters -> {
                        SliderControl(
                            label = "Peak Voltage [Frame Wire]",
                            value = p.peakVoltageMv,
                            unit = "mV",
                            range = 15000f..65535f,
                            onValueChange = { viewModel.updateParameters(p.copy(peakVoltageMv = it)) },
                            onInfoClick = { selectedHelpTerm = "Peak voltage: maximum transient voltage reached during EMP pulse." }
                        )
                        SliderControl(
                            label = "Rise Time [Frame Wire]",
                            value = p.riseTimeNs,
                            unit = "ns",
                            range = 1f..100f,
                            onValueChange = { viewModel.updateParameters(p.copy(riseTimeNs = it)) },
                            onInfoClick = { selectedHelpTerm = "Rise time: how quickly the signal reaches peak voltage (nanoseconds)." }
                        )
                        SliderControl(
                            label = "Decay Time [Frame Wire]",
                            value = p.decayTimeUs,
                            unit = "µs",
                            range = 1f..100f,
                            onValueChange = { viewModel.updateParameters(p.copy(decayTimeUs = it)) },
                            onInfoClick = { selectedHelpTerm = "Decay time: exponential decay duration to 1/e of peak voltage." }
                        )
                        SliderControl(
                            label = "Resonant Frequency",
                            value = p.resonantFreqMhz,
                            unit = "MHz",
                            range = 10f..100f,
                            onValueChange = { viewModel.updateParameters(p.copy(resonantFreqMhz = it)) },
                            onInfoClick = { selectedHelpTerm = "Resonant frequency: ringing frequency excited in smart-meter wiring." }
                        )
                        SliderControl(
                            label = "Ring-Down Amplitude",
                            value = p.ringDownMv,
                            unit = "mV",
                            range = 0f..10000f,
                            onValueChange = { viewModel.updateParameters(p.copy(ringDownMv = it)) }
                        )
                        SliderControl(
                            label = "RF Noise Level",
                            value = p.rfNoiseMv,
                            unit = "mV",
                            range = 0f..500f,
                            onValueChange = { viewModel.updateParameters(p.copy(rfNoiseMv = it)) }
                        )
                    }
                    is OpticalParameters -> {
                        SliderControl(
                            label = "Saturation Voltage [Frame Wire]",
                            value = p.saturationVoltageMv,
                            unit = "mV",
                            range = 2500f..5000f,
                            onValueChange = { viewModel.updateParameters(p.copy(saturationVoltageMv = it)) },
                            onInfoClick = { selectedHelpTerm = "Optical saturation: photodiode voltage level under direct laser/optical blinding." }
                        )
                        SliderControl(
                            label = "Optical Onset Time",
                            value = p.opticalOnsetMs,
                            unit = "ms",
                            range = 0f..10f,
                            onValueChange = { viewModel.updateParameters(p.copy(opticalOnsetMs = it)) }
                        )
                        SliderControl(
                            label = "Rise Constant",
                            value = p.riseConstantMs,
                            unit = "ms",
                            range = 0.1f..5f,
                            onValueChange = { viewModel.updateParameters(p.copy(riseConstantMs = it)) }
                        )
                        SliderControl(
                            label = "Sustained Duration",
                            value = p.sustainedDurationMs,
                            unit = "ms",
                            range = 5f..50f,
                            onValueChange = { viewModel.updateParameters(p.copy(sustainedDurationMs = it)) }
                        )
                        SliderControl(
                            label = "Ripple Noise",
                            value = p.rippleNoiseMv,
                            unit = "mV",
                            range = 0f..100f,
                            onValueChange = { viewModel.updateParameters(p.copy(rippleNoiseMv = it)) }
                        )
                    }
                    is SurgeParameters -> {
                        SliderControl(
                            label = "Surge Amplitude [Frame Wire]",
                            value = p.surgeAmplitudeMv,
                            unit = "mV",
                            range = 4000f..40000f,
                            onValueChange = { viewModel.updateParameters(p.copy(surgeAmplitudeMv = it)) },
                            onInfoClick = { selectedHelpTerm = "Surge amplitude: peak inductive kick voltage." }
                        )
                        SliderControl(
                            label = "Rise Time [Frame Wire]",
                            value = p.riseTimeUs,
                            unit = "µs",
                            range = 0.5f..10f,
                            onValueChange = { viewModel.updateParameters(p.copy(riseTimeUs = it)) }
                        )
                        SliderControl(
                            label = "Decay Time [Frame Wire]",
                            value = p.decayTimeMs,
                            unit = "ms",
                            range = 0.1f..20f,
                            onValueChange = { viewModel.updateParameters(p.copy(decayTimeMs = it)) }
                        )
                        SliderControl(
                            label = "Ring Frequency",
                            value = p.ringFreqKhz,
                            unit = "kHz",
                            range = 10f..200f,
                            onValueChange = { viewModel.updateParameters(p.copy(ringFreqKhz = it)) }
                        )
                        SliderControl(
                            label = "Baseline Voltage",
                            value = p.baselineVoltageMv,
                            unit = "mV",
                            range = 1000f..5000f,
                            onValueChange = { viewModel.updateParameters(p.copy(baselineVoltageMv = it)) }
                        )
                    }
                }

                // Random Seed Control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Deterministic Seed", fontSize = 12.sp, color = Color(0xFFC9D1D9))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.setSeed(uiState.seed + 1) },
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                        ) {
                            Text(text = "Next Seed (${uiState.seed})", fontSize = 11.sp, color = Color(0xFF58A6FF))
                        }
                    }
                }

                // Validation Errors
                if (uiState.validationErrors.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = alertRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, alertRed)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            uiState.validationErrors.forEach { err ->
                                Text(text = "• $err", color = alertRed, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // 4. Generate & Replay Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.generateAndClassify() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Generate & Classify", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.generate8FrameWindow() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "8-Frame Window", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (uiState.isReplaying) viewModel.stopReplay() else viewModel.startReplay()
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (uiState.isReplaying) warningAmber.copy(alpha = 0.2f) else Color.Transparent,
                            contentColor = if (uiState.isReplaying) warningAmber else Color(0xFFC9D1D9)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (uiState.isReplaying) warningAmber else cardBorder)
                    ) {
                        Icon(
                            if (uiState.isReplaying) Icons.Default.Stop else Icons.Default.FastForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (uiState.isReplaying) "Stop Replay" else "Replay Signal", fontSize = 11.sp)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Checkbox(
                            checked = uiState.publishToDashboard,
                            onCheckedChange = { viewModel.setPublishToDashboard(it) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = warningAmber,
                                uncheckedColor = Color(0xFF8B949E)
                            )
                        )
                        Text(text = "Publish to Dashboard (WS)", fontSize = 11.sp, color = Color(0xFFC9D1D9))
                    }
                }
            }
        }

        // 5. Visual Output: Waveform Canvas & 8-Bin FFT Panel
        val result = uiState.result
        if (result != null) {
            // A. Waveform Canvas
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TIME-DOMAIN VOLTAGE WAVEFORM",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF8B949E),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Bounded 256 Samples (${"%.1f".format(result.sampleRateKhz)} kHz)",
                            fontSize = 10.sp,
                            color = Color(0xFF8B949E),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Waveform Canvas (performant Compose Canvas approach)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color(0xFF090D13), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF21262D), RoundedCornerShape(6.dp))
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val count = result.voltageSamplesMv.size
                                    if (count > 0) {
                                        val idx = ((offset.x / size.width) * count).toInt().coerceIn(0, count - 1)
                                        selectedSamplePoint = Pair(result.timePointsUs[idx], result.voltageSamplesMv[idx])
                                    }
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
                            val w = size.width
                            val h = size.height
                            val midY = h / 2f

                            // Draw subtle grid lines
                            drawLine(Color(0xFF1F242C), Offset(0f, midY), Offset(w, midY), strokeWidth = 1f)
                            drawLine(Color(0xFF1F242C), Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
                            drawLine(Color(0xFF1F242C), Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

                            val samples = result.voltageSamplesMv
                            if (samples.isNotEmpty()) {
                                var maxAbs = 1f
                                for (s in samples) {
                                    val abs = kotlin.math.abs(s)
                                    if (abs > maxAbs) maxAbs = abs
                                }
                                val scaleY = (h * 0.42f) / maxAbs

                                val path = Path()
                                val stepX = w / (samples.size - 1).coerceAtLeast(1)

                                for (i in samples.indices) {
                                    val x = i * stepX
                                    val y = midY - (samples[i] * scaleY)
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }

                                val waveColor = when (result.mode) {
                                    SimulationMode.NORMAL -> Color(0xFF06B6D4)
                                    SimulationMode.EMP -> alertRed
                                    SimulationMode.OPTICAL -> warningAmber
                                    SimulationMode.SURGE -> Color(0xFFA371F7)
                                }

                                drawPath(
                                    path = path,
                                    color = waveColor,
                                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                                )

                                // Draw moving replay cursor
                                if (uiState.isReplaying) {
                                    val cursorX = (uiState.replayCursorIndex.toFloat() / samples.size) * w
                                    drawLine(
                                        color = Color.Yellow,
                                        start = Offset(cursorX, 0f),
                                        end = Offset(cursorX, h),
                                        strokeWidth = 2f
                                    )
                                }
                            }
                        }

                        // Readout banner when user taps canvas
                        selectedSamplePoint?.let { (timeUs, voltMv) ->
                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                                shape = RoundedCornerShape(4.dp),
                                color = Color.Black.copy(alpha = 0.8f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
                            ) {
                                Text(
                                    text = "t: ${"%.1f".format(timeUs)} µs | V: ${"%.1f".format(voltMv)} mV",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "0 µs", fontSize = 10.sp, color = Color(0xFF8B949E))
                        Text(text = "Tap canvas to inspect sample coordinates", fontSize = 9.sp, color = Color(0xFF484F58))
                        Text(text = "${"%.1f".format(result.timePointsUs.lastOrNull() ?: 0f)} µs", fontSize = 10.sp, color = Color(0xFF8B949E))
                    }
                }
            }

            // B. Frequency Panel: 8 FFT Energy Bins
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "8-BIN FFT PROTOCOL SPECTRUM",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF8B949E),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Normalized [0..255] Wire Energy",
                            fontSize = 10.sp,
                            color = Color(0xFF8B949E)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        val bins = result.fftEnergyBins
                        val binLabels = listOf("DC", "10k", "50k", "200k", "1M", "5M", "20M", "50M")
                        for (i in bins.indices) {
                            val energy = bins[i]
                            val fraction = (energy / 255f).coerceIn(0.04f, 1f)
                            val isHighFreq = i >= 4
                            val barColor = if (isHighFreq) alertRed else accentCyan

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                Text(
                                    text = "$energy",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF8B949E)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(fraction)
                                        .background(barColor, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = binLabels.getOrElse(i) { "B$i" },
                                    fontSize = 9.sp,
                                    color = if (isHighFreq) Color(0xFFF85149) else Color(0xFF58A6FF),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // C. Classification & Explainability Result
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (result.tamperDetected) alertRed else cardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "EDGE AI CLASSIFIER",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF8B949E),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = result.observedClass,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = when (result.observedClass) {
                                    "NORMAL" -> safeGreen
                                    "EMP" -> alertRed
                                    "OPTICAL" -> warningAmber
                                    "SURGE" -> Color(0xFFA371F7)
                                    else -> Color.White
                                }
                            )
                        }

                        // Tamper Alert Gate
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (result.tamperDetected) alertRed else safeGreen.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (result.tamperDetected) alertRed else safeGreen)
                        ) {
                            Text(
                                text = if (result.tamperDetected) "TAMPER DETECTED" else "SYSTEM BENIGN",
                                color = if (result.tamperDetected) Color.White else safeGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Probability Bars
                    val classes = listOf("NORMAL", "EMP", "OPTICAL", "SURGE")
                    val probColors = listOf(safeGreen, alertRed, warningAmber, Color(0xFFA371F7))
                    for (i in classes.indices) {
                        val prob = result.probabilities.getOrElse(i) { 0f }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = classes[i],
                                fontSize = 11.sp,
                                color = Color(0xFFC9D1D9),
                                modifier = Modifier.width(64.dp)
                            )
                            LinearProgressIndicator(
                                progress = { prob },
                                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = probColors[i],
                                trackColor = Color(0xFF21262D)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${"%.1f".format(prob * 100)}%",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF8B949E),
                                modifier = Modifier.width(44.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Decision Narrative Explanation
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF090D13),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF21262D)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Psychology, contentDescription = null, tint = Color(0xFF58A6FF), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Decision Explanation", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58A6FF))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = result.explanation, fontSize = 11.sp, color = Color(0xFFC9D1D9))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Engine: ${result.inferenceEngine} | Latency: ${result.inferenceLatencyUs} µs | Gate Threshold: 0.85",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF8B949E)
                            )
                        }
                    }
                }
            }

            // D. Expected vs Observed Comparison
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "MODEL AGREEMENT & GROUND TRUTH",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF8B949E),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "Expected Class", fontSize = 10.sp, color = Color(0xFF8B949E))
                            Text(text = result.expectedClass, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column {
                            Text(text = "Observed Class", fontSize = 10.sp, color = Color(0xFF8B949E))
                            Text(text = result.observedClass, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "Status", fontSize = 10.sp, color = Color(0xFF8B949E))
                            Text(
                                text = result.matchStatus,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (result.matchStatus == "Model agreement") safeGreen else warningAmber
                            )
                        }
                    }

                    // Delta comparison with baseline
                    val comp = uiState.comparison
                    if (comp != null && result.mode != SimulationMode.NORMAL) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = cardBorder, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Signature Shift vs Normal Baseline:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF58A6FF))
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Δ Peak: ${comp.deltaPeakMv.toInt()} mV", fontSize = 10.sp, color = Color(0xFFC9D1D9))
                            Text(text = "Δ Rise: ${comp.deltaRiseNs} ns", fontSize = 10.sp, color = Color(0xFFC9D1D9))
                            Text(text = "Shift: ${comp.spectralShift}", fontSize = 10.sp, color = Color(0xFF58A6FF))
                        }
                    }
                }
            }

            // E. Collapsible 29-Byte Protocol Preview & Exports
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleFramePreview() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF58A6FF), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "29-BYTE FRAME PREVIEW",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            if (uiState.isFramePreviewExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Color(0xFF8B949E)
                        )
                    }

                    AnimatedVisibility(visible = uiState.isFramePreviewExpanded) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF090D13),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF21262D)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = result.hexFrame.chunked(2).joinToString(" "),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF7EE787),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Header: 0x5353 | Seq: #${result.sequenceId} | Event Flags: 0x${"%02X".format(result.eventFlags)} | CRC16: 0x${"%04X".format(result.crc16)} (Valid)",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF8B949E)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.copyHexFrame(context)
                                        Toast.makeText(context, "Hex frame copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC9D1D9))
                                ) {
                                    Text(text = "Copy Hex", fontSize = 10.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        viewModel.copyJsonTelemetry(context)
                                        Toast.makeText(context, "JSON telemetry copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC9D1D9))
                                ) {
                                    Text(text = "Export JSON", fontSize = 10.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val csv = viewModel.exportCsv()
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Waveform CSV", csv))
                                        Toast.makeText(context, "Waveform CSV copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC9D1D9))
                                ) {
                                    Text(text = "Export CSV", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Help / Concept Info Dialog
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Simulation Lab Guide", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Safety Disclosure:",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF0883E),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "This simulation tool synthesizes digital waveforms mathematically for AI model evaluation. It does NOT command or emit physical high voltage, electromagnetic pulses, or laser hardware.",
                        fontSize = 11.sp,
                        color = Color(0xFFC9D1D9)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Key Technical Terms:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "• Rise time: How quickly the voltage reaches its peak. EMP features 1-100 ns, while grid transients feature microsecond fronts.", fontSize = 11.sp, color = Color(0xFF8B949E))
                    Text(text = "• Decay time: Duration taken for transient energy to dissipate.", fontSize = 11.sp, color = Color(0xFF8B949E))
                    Text(text = "• Resonance: Ring-down oscillation frequencies excited across inductances.", fontSize = 11.sp, color = Color(0xFF8B949E))
                    Text(text = "• 8-Bin FFT: Protocol wire frequency distribution from 0 Hz (DC) to 50 MHz.", fontSize = 11.sp, color = Color(0xFF8B949E))
                    Text(text = "• Alert Threshold: Fixed at 0.85 (85%) confidence. Non-normal predictions below 0.85 do not raise alarms.", fontSize = 11.sp, color = Color(0xFF8B949E))
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Understood", color = Color(0xFF58A6FF))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }

    // Term Quick-Explanation Bottom Sheet / Dialog
    selectedHelpTerm?.let { termInfo ->
        AlertDialog(
            onDismissRequest = { selectedHelpTerm = null },
            title = { Text("Parameter Definition", fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text(text = termInfo, color = Color(0xFFC9D1D9), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { selectedHelpTerm = null }) {
                    Text("OK", color = Color(0xFF58A6FF))
                }
            },
            containerColor = Color(0xFF161B22)
        )
    }
}

@Composable
private fun SliderControl(
    label: String,
    value: Float,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onInfoClick: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, fontSize = 11.sp, color = Color(0xFFC9D1D9))
                if (onInfoClick != null) {
                    IconButton(onClick = onInfoClick, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Info", tint = Color(0xFF8B949E), modifier = Modifier.size(12.dp))
                    }
                }
            }
            Text(
                text = "${if (value >= 1000f) "%.1f".format(value) else "%.2f".format(value)} $unit",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF58A6FF)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF58A6FF),
                activeTrackColor = Color(0xFF1F6FEB),
                inactiveTrackColor = Color(0xFF21262D)
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}
