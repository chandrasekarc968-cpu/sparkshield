package com.sparkshield.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sparkshield.android.ui.theme.ElectricBlue
import com.sparkshield.android.ui.theme.IntelligentCyan

@Composable
fun SparkShieldLogo(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    primaryColor: Color = ElectricBlue,
    accentColor: Color = IntelligentCyan
) {
    Canvas(modifier = modifier.size(size)) {
        val w = size.toPx()
        val h = size.toPx()

        // Draw Shield Path
        val shieldPath = Path().apply {
            moveTo(w * 0.5f, h * 0.1f) // Top center
            lineTo(w * 0.9f, h * 0.2f) // Top right
            lineTo(w * 0.9f, h * 0.6f) // Right side
            cubicTo(w * 0.9f, h * 0.85f, w * 0.5f, h * 0.95f, w * 0.5f, h * 0.95f) // Bottom curve
            cubicTo(w * 0.5f, h * 0.95f, w * 0.1f, h * 0.85f, w * 0.1f, h * 0.6f) // Left curve
            lineTo(w * 0.1f, h * 0.2f) // Top left
            close()
        }

        drawPath(
            path = shieldPath,
            color = primaryColor,
            style = Stroke(width = w * 0.08f)
        )

        // Draw Lightning Bolt (Subtle)
        val boltPath = Path().apply {
            moveTo(w * 0.55f, h * 0.35f)
            lineTo(w * 0.40f, h * 0.55f)
            lineTo(w * 0.50f, h * 0.55f)
            lineTo(w * 0.45f, h * 0.75f)
            lineTo(w * 0.60f, h * 0.50f)
            lineTo(w * 0.50f, h * 0.50f)
            close()
        }

        drawPath(
            path = boltPath,
            color = accentColor
        )
    }
}
