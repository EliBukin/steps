package com.example.stepsplit.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.model.WalkSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionsUiState(
    val isLoading: Boolean = true,
    val sessions: List<WalkSession> = emptyList(),
)

class SessionsViewModel(private val repository: StepRepository) : ViewModel() {

    val uiState: StateFlow<SessionsUiState> = repository.observeSessions()
        .map { SessionsUiState(isLoading = false, sessions = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

    fun reclassify(anchorEpochSecond: Long, classification: BoutClassification) {
        viewModelScope.launch { repository.reclassify(anchorEpochSecond, classification) }
    }

    fun refresh() {
        viewModelScope.launch { repository.syncNow() }
    }
}
