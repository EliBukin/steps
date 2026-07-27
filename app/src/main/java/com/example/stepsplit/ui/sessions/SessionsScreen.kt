package com.example.stepsplit.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.model.SessionOrigin
import com.example.stepsplit.domain.model.WalkSession
import com.example.stepsplit.ui.common.formatClockTime
import com.example.stepsplit.ui.common.formatDateLabel
import com.example.stepsplit.ui.common.text

@Composable
fun SessionsScreen(
    uiState: SessionsUiState,
    onRefresh: () -> Unit,
    onReclassify: (Long, BoutClassification) -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    var sessionPendingReclassification by remember { mutableStateOf<WalkSession?>(null) }

    if (uiState.sessions.isEmpty() && !uiState.isLoading) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = stringResource(R.string.sessions_empty), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(text = stringResource(R.string.sessions_title), style = MaterialTheme.typography.titleLarge)
        }
        items(uiState.sessions, key = { it.id }) { session ->
            SessionCard(
                session = session,
                onReclassifyClick = { sessionPendingReclassification = session },
            )
        }
    }

    sessionPendingReclassification?.let { session ->
        ReclassifyDialog(
            session = session,
            onDismiss = { sessionPendingReclassification = null },
            onConfirm = { classification ->
                session.anchorEpochSecond?.let { onReclassify(it, classification) }
                sessionPendingReclassification = null
            },
        )
    }
}

@Composable
private fun SessionCard(session: WalkSession, onReclassifyClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${formatDateLabel(session.startDate())} ${formatClockTime(session.startEpochSecond)}" +
                        " – ${formatClockTime(session.endEpochSecond)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.session_duration_format, session.durationMinutes()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(text = "${session.steps} ${stringResource(R.string.unit_steps)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.cadence_unit_format, session.cadence),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(session.classification.text()) })
                AssistChip(onClick = {}, label = { Text(originText(session.origin)) })
            }

            Text(text = session.reasonCode.text(), style = MaterialTheme.typography.bodySmall)
            if (session.origin == SessionOrigin.AUTO) {
                Text(
                    text = stringResource(R.string.session_confidence_label, (session.confidence * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (session.isReclassifiable) {
                TextButton(onClick = onReclassifyClick) {
                    Text(stringResource(R.string.session_reclassify_action))
                }
            }
        }
    }
}

@Composable
private fun originText(origin: SessionOrigin): String = when (origin) {
    SessionOrigin.AUTO -> stringResource(R.string.session_origin_auto)
    SessionOrigin.MANUAL -> stringResource(R.string.session_origin_manual)
}

@Composable
private fun ReclassifyDialog(
    session: WalkSession,
    onDismiss: () -> Unit,
    onConfirm: (BoutClassification) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_reclassify_dialog_title)) },
        text = {
            Column {
                BoutClassification.entries.forEach { classification ->
                    TextButton(onClick = { onConfirm(classification) }) {
                        Text(classification.text())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun WalkSession.startDate() = java.time.Instant.ofEpochSecond(startEpochSecond).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
private fun WalkSession.durationMinutes() = ((endEpochSecond - startEpochSecond) / 60).toInt()
