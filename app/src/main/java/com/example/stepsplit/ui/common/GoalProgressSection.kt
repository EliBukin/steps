package com.example.stepsplit.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.GoalProgress
import kotlin.math.roundToInt

/**
 * Progress is never capped in the numeric label - 18,000 of a 15,000 goal reads "120%". Only the
 * bar itself visually completes at 100%, per product requirement.
 */
@Composable
fun GoalProgressSection(
    title: String,
    progress: GoalProgress,
    modifier: Modifier = Modifier,
    contentDescriptionRes: Int = R.string.cd_daily_progress,
) {
    val percent = progress.percent.roundToInt()
    val progressDescription = stringResource(contentDescriptionRes, percent)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Text(
            text = if (progress.isGoalValid) {
                stringResource(R.string.percent_of_goal_format, percent)
            } else {
                stringResource(R.string.settings_goal_invalid_error)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        LinearProgressIndicator(
            progress = { progress.clampedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .semantics { contentDescription = progressDescription },
        )
    }
}
