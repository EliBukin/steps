package com.example.stepsplit.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A full-width progress bar, [fraction] clamped to 0..1. Material's current bar adds a dot at the
 * end of the track and a gap before it; with a tiny fraction (0.4% around the Earth) that read as
 * two dots on an empty line, so both are turned off here.
 */
@Composable
fun PlainProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(height),
        trackColor = MaterialTheme.colorScheme.secondaryContainer,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
