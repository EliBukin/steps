package com.example.stepsplit.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.ui.theme.IncidentalSegmentColor
import com.example.stepsplit.ui.theme.WorkoutSegmentColor

/**
 * A minimal Compose-native stacked bar chart for the 7-day history - deliberately not a full
 * charting library, since seven bars with two segments each does not need one.
 */
@Composable
fun WeeklyStackedBarChart(
    days: List<DateStepBreakdown>,
    modifier: Modifier = Modifier,
    barAreaHeight: androidx.compose.ui.unit.Dp = 140.dp,
) {
    val maxSteps = (days.maxOfOrNull { it.totalSteps } ?: 0L).coerceAtLeast(1L)
    Row(
        modifier = modifier.fillMaxWidth().height(barAreaHeight),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            DayBar(day = day, maxSteps = maxSteps, modifier = Modifier.fillMaxHeight().width(28.dp))
        }
    }
}

@Composable
private fun DayBar(day: DateStepBreakdown, maxSteps: Long, modifier: Modifier = Modifier) {
    val workoutFraction = (day.workoutSteps.toFloat() / maxSteps).coerceIn(0f, 1f)
    val incidentalFraction = (day.incidentalSteps.toFloat() / maxSteps).coerceIn(0f, 1f)
    val workoutDescription = stringResource(R.string.cd_workout_segment, day.workoutSteps.toString())
    val incidentalDescription = stringResource(R.string.cd_incidental_segment, day.incidentalSteps.toString())

    Column(
        modifier = modifier.semantics { contentDescription = "$workoutDescription, $incidentalDescription" },
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight((1f - workoutFraction - incidentalFraction).coerceAtLeast(0f).coerceAtLeast(0.0001f)),
        )
        if (incidentalFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(incidentalFraction.coerceAtLeast(0.0001f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(IncidentalSegmentColor),
            )
        }
        if (workoutFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(workoutFraction.coerceAtLeast(0.0001f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(WorkoutSegmentColor),
            )
        }
    }
}

@Composable
fun LegendDot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(10.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}
