package com.example.stepsplit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Default Material 3 type ramp with a heavier weight for the big step-count display. */
val StepSplitTypography = Typography().let { base ->
    base.copy(
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

val StatValueTextStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold)
