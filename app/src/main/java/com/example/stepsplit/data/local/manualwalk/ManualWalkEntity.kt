package com.example.stepsplit.data.local.manualwalk

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An explicit "Start walk / Finish walk" session. [endEpochSecond] and [steps] are null while the
 * walk is ongoing; persisting the ongoing row immediately (on Start) means process death or
 * rotation never silently loses an in-progress manual walk. At most one row with a null
 * [endEpochSecond] should exist at a time - enforced by the repository, not the schema, since
 * Room has no direct "at most one null" constraint.
 *
 * [autoCompleted] is true when the walk was ended by the inactivity auto-completion mechanism
 * (see StepRepository.maybeAutoCompleteOngoingManualWalk) rather than by the user pressing
 * Finish or explicitly resolving a stale-walk prompt. [autoCompletionMessageShown] tracks whether
 * the one-shot "ended automatically" notification has already been shown to the user - persisted
 * (not just in-memory ViewModel state) so an auto-completion that happens during a background
 * WorkManager sync still reliably notifies the user the next time the app is opened.
 */
@Entity(tableName = "manual_walks")
data class ManualWalkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochSecond: Long,
    val endEpochSecond: Long?,
    val steps: Long?,
    val createdAtEpochSecond: Long,
    val autoCompleted: Boolean = false,
    val autoCompletionMessageShown: Boolean = false,
)
