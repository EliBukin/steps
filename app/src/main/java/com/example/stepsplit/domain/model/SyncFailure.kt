package com.example.stepsplit.domain.model

/**
 * Broad, localizable categories for why the most recent sync attempt failed - deliberately not
 * raw exception text, which can't be localized and may leak implementation detail to the user.
 */
enum class SyncFailureCategory {
    /** [com.example.stepsplit.data.stepsource.StepSource.ensureSubscribed] returned false. */
    SUBSCRIPTION_FAILED,

    /** The step source's own read call failed - e.g. a non-success Local Recording API response. */
    READ_FAILED,

    /** Any other unexpected failure during the sync pipeline. */
    UNKNOWN,
}

/**
 * A structured record of the most recent sync failure, persisted (not just held in memory) so a
 * failure that happens during a background WorkManager sync is still visible the next time the
 * app is opened - see [com.example.stepsplit.data.settings.SettingsRepository]. Cleared the next
 * time a sync genuinely succeeds; a source-availability problem (missing permission, API
 * unavailable) is a separate, orthogonal concern - see
 * [com.example.stepsplit.data.stepsource.StepSourceAvailability] - and does not go through this.
 */
data class SyncFailure(
    val category: SyncFailureCategory,
    val atEpochSecond: Long,
)
