package com.example.stepsplit.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.domain.stats.LifetimeStatsCalculator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StatsViewModel(private val repository: StepRepository) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = repository.observeLifetimeStats()
        .map { lifetime -> StatsUiState(isLoading = false, stats = LifetimeStatsCalculator.calculate(lifetime)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun refresh() {
        viewModelScope.launch { repository.syncNow() }
    }
}
