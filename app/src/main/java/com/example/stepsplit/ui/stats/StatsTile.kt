package com.example.stepsplit.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A compact, equal-height stat tile for the Stats screen's two-column grid - deliberately separate
 * from [com.example.stepsplit.ui.common.StatCard], which the Today screen also uses and which this
 * redesign must not change. Text inside [content] is never truncated (no `maxLines`/ellipsis is
 * applied here); callers must avoid setting those themselves too, so large font scales wrap instead
 * of clipping.
 */
@Composable
fun StatsTile(
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxHeight()
            .semantics { this.contentDescription = contentDescription },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxHeight(),
            content = content,
        )
    }
}
