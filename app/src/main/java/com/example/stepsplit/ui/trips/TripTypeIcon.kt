package com.example.stepsplit.ui.trips

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.TripType

@DrawableRes
internal fun tripTypeIconRes(type: TripType): Int = when (type) {
    TripType.WALK -> R.drawable.ic_trip_walk_24
    TripType.DRIVE -> R.drawable.ic_trip_drive_24
}

/** "Walk" or "Drive". */
@StringRes
internal fun tripTypeLabelRes(type: TripType): Int = when (type) {
    TripType.WALK -> R.string.trip_type_walk
    TripType.DRIVE -> R.string.trip_type_drive
}

/**
 * A trip's Walk/Drive icon, in the app's primary color. Read out as the type's name unless
 * [contentDescription] says otherwise - pass null where the type is already written next to it.
 */
@Composable
internal fun TripTypeIcon(
    type: TripType,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = stringResource(tripTypeLabelRes(type)),
) {
    Icon(
        painter = painterResource(tripTypeIconRes(type)),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
