package com.example.stepsplit.data.repository

/** UI-facing projection of the currently ongoing manual walk, if any. */
data class OngoingManualWalkStatus(
    val startEpochSecond: Long,
    val hasRecordedSteps: Boolean,
)

/** UI-facing projection of a manual walk the inactivity mechanism finished automatically. */
data class AutoCompletedWalk(
    val id: Long,
    val startEpochSecond: Long,
    val endEpochSecond: Long,
)
