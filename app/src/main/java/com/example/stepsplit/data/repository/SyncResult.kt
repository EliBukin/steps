package com.example.stepsplit.data.repository

import com.example.stepsplit.data.stepsource.StepSourceAvailability

sealed interface SyncResult {
    data class Success(val bucketsWritten: Int) : SyncResult
    data class Unavailable(val availability: StepSourceAvailability) : SyncResult
    data class Failed(val message: String) : SyncResult
}
