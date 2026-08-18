package com.example.stepsplit.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.ui.theme.IncidentalSegmentColor
import com.example.stepsplit.ui.theme.WorkoutSegmentColor

private val YAxisWidth = 36.dp
private val GridLineFractions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

/** Extra breathing room above [LabelHeadroom]'s own line-height estimate, so it is never a tight fit. */
private val LabelHeadroomBuffer = 4.dp

/**
 * A modern seven-day stacked bar chart: day labels under every bar, a numeric gridline scale, an
 * optional labelled daily-goal reference line, and an always-visible exact total above each bar so
 * the precise number never depends on reading bar height/color alone. [days] is drawn left-to-right
 * exactly as given (the caller keeps it oldest-to-newest).
 *
 * The colored bars, the gridlines, and the goal line all share exactly one plotting rectangle
 * ([barAreaHeight] tall) - a day at [scaleMax] therefore always touches the top gridline precisely.
 * The per-day total label is drawn as a floating overlay positioned purely from that same fraction
 * (never inside the bar's own weighted stack, which would let the label's height silently steal
 * plotting space from the bar - see `DayBar` below), so a fixed [LabelHeadroom] blank strip is
 * reserved above the whole plotting rectangle for it to float into even for a 100%-of-scale bar,
 * keeping every label inside the chart's own bounds regardless of font scale.
 */
@Composable
fun WeeklyStackedBarChart(
    days: List<DateStepBreakdown>,
    dailyGoal: Long,
    modifier: Modifier = Modifier,
    barAreaHeight: Dp = 160.dp,
) {
    val scaleMax = remember(days, dailyGoal) { ChartScale.computeMax(days.map { it.totalSteps }, dailyGoal) }
    val goalFraction = if (dailyGoal > 0) (dailyGoal.toFloat() / scaleMax.toFloat()).coerceIn(0f, 1f) else null
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    val labelLineHeight = MaterialTheme.typography.labelSmall.lineHeight
    val density = LocalDensity.current
    val labelHeadroom = remember(labelLineHeight, density) { with(density) { labelLineHeight.toDp() } + LabelHeadroomBuffer }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(modifier = Modifier.height(labelHeadroom))
        Row(modifier = Modifier.fillMaxWidth()) {
            YAxisLabels(scaleMax = scaleMax, height = barAreaHeight)
            Box(modifier = Modifier.weight(1f).height(barAreaHeight)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    GridLineFractions.forEach { fraction ->
                        val y = size.height * (1f - fraction)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    if (goalFraction != null) {
                        val y = size.height * (1f - goalFraction)
                        drawLine(
                            color = goalColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    days.forEach { day ->
                        DayBar(
                            day = day,
                            scaleMax = scaleMax,
                            barAreaHeight = barAreaHeight,
                            modifier = Modifier.fillMaxHeight().weight(1f),
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(YAxisWidth))
            days.forEach { day ->
                Text(
                    text = formatDayLabel(day.date),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (dailyGoal > 0) {
            GoalLegend(dailyGoal = dailyGoal, color = goalColor)
        }
        ChartLegend()
    }
}

@Composable
private fun YAxisLabels(scaleMax: Long, height: Dp) {
    Column(
        modifier = Modifier.width(YAxisWidth).height(height),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        Text(text = "$scaleMax", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = "${scaleMax / 2}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = "0", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * One day's bar, bottom-anchored within a [barAreaHeight]-tall slot: [ChartScale.computeTotalFraction]
 * gives the bar's own height as an explicit fraction of [barAreaHeight] - the exact rectangle the
 * gridlines/goal line in [WeeklyStackedBarChart] are drawn against - so it lines up with the scale
 * independent of anything else in this composable. The total-steps label is a second, separate child
 * of the same [Box], aligned to the bar's own bottom edge and then shifted up by the bar's own height
 * via [Modifier.offset] - "floating" directly above the bar's current top without living inside its
 * weighted stack, so the label's height can never distort the bar's measured height the way it did
 * before this fix (a label inside the same weighted `Column` as the bar segments caused Compose to
 * subtract the label's height from the available space before distributing the segment weights,
 * silently shrinking every bar). [WeeklyStackedBarChart] reserves a blank `labelHeadroom` strip above
 * the whole plotting rectangle so this offset label stays inside the chart's own bounds even for a
 * day at or above [scaleMax] (bar height == barAreaHeight, offset == -barAreaHeight).
 */
@Composable
private fun DayBar(day: DateStepBreakdown, scaleMax: Long, barAreaHeight: Dp, modifier: Modifier = Modifier) {
    val totalFraction = ChartScale.computeTotalFraction(day.totalSteps, scaleMax)
    val fractions = ChartScale.computeSegmentFractions(day.workoutSteps, day.incidentalSteps, scaleMax)
    val barHeight = barAreaHeight * totalFraction
    val totalDescription = stringResource(R.string.cd_total_steps, day.totalSteps.toString())
    val workoutDescription = stringResource(R.string.cd_workout_segment, day.workoutSteps.toString())
    val incidentalDescription = stringResource(R.string.cd_incidental_segment, day.incidentalSteps.toString())

    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "$totalDescription, $workoutDescription, $incidentalDescription"
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(barHeight).align(Alignment.BottomCenter)) {
            if (fractions.incidental > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(fractions.incidental)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(IncidentalSegmentColor),
                )
            }
            if (fractions.workout > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(fractions.workout)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(WorkoutSegmentColor),
                )
            }
        }
        Text(
            text = "${day.totalSteps}",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = -barHeight),
        )
    }
}

@Composable
private fun GoalLegend(dailyGoal: Long, color: Color) {
    val description = stringResource(R.string.history_chart_goal_label, dailyGoal.toString())
    Row(
        modifier = Modifier
            .padding(start = YAxisWidth)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.width(14.dp).height(2.dp).background(color))
        Text(text = description, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ChartLegend() {
    Row(modifier = Modifier.padding(start = YAxisWidth), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendDot(color = WorkoutSegmentColor)
            Text(stringResource(R.string.label_workout_steps), style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendDot(color = IncidentalSegmentColor)
            Text(stringResource(R.string.label_incidental_steps), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun LegendDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(10.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}
