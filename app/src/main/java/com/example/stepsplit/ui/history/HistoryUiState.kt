package com.example.stepsplit.ui.history

import com.example.stepsplit.domain.aggregation.DateStepBreakdown

data class HistoryUiState(
    val isLoading: Boolean = true,
    val days: List<DateStepBreakdown> = emptyList(),
    val dailyGoal: Long = 0,
)
