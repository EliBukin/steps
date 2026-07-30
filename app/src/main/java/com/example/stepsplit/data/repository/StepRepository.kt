package com.example.stepsplit.data.repository

import androidx.room.withTransaction
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bout.WalkBoutEntity
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.local.manualwalk.ManualWalkEntity
import com.example.stepsplit.data.local.override.SessionOverrideEntity
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.RawStepInterval
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.aggregation.BucketNormalizer
import com.example.stepsplit.domain.aggregation.DatedBucket
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.aggregation.RawInterval
import com.example.stepsplit.domain.aggregation.StepAggregator
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.classification.CLASSIFIER_VERSION
import com.example.stepsplit.domain.classification.ClassificationReasonCode
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.classification.ClassifiedBout
import com.example.stepsplit.domain.classification.MinuteBucket
import com.example.stepsplit.domain.classification.WalkClassifier
import com.example.stepsplit.domain.model.SessionMerger
import com.example.stepsplit.domain.model.WalkSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every read/write path over step data: importing from [stepSource], normalizing into
 * one-minute buckets, running the classifier, merging manual overrides, and exposing UI-ready
 * flows. A single [syncMutex] serializes import+classify pipelines so a WorkManager sync racing
 * an app-start sync can never interleave writes - combined with Room's own transactions, this
 * makes concurrent triggers safe by construction rather than by luck.
 */
class StepRepository(
    private val database: StepSplitDatabase,
    private val stepSource: StepSource,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) {
    private val syncMutex = Mutex()

    suspend fun checkAvailability(): StepSourceAvailability = stepSource.checkAvailability()

    suspend fun syncNow(): SyncResult = syncMutex.withLock { syncNowLocked() }

    private suspend fun syncNowLocked(): SyncResult {
        val availability = stepSource.checkAvailability()
        if (availability !is StepSourceAvailability.Available) {
            return SyncResult.Unavailable(availability)
        }
        if (!stepSource.ensureSubscribed()) {
            // Without a live subscription a "successful" read could just be an empty/stale one -
            // report this as a failure rather than silently recording an empty sync as success.
            return SyncResult.Failed("Unable to subscribe to the step data source")
        }

        return try {
            val now = clock.instant()
            val zone = clock.zone
            val latestBucketEnd = database.stepBucketDao().latestBucketEnd(stepSource.id)
            val windowStart = computeWindowStart(latestBucketEnd, now)

            val rawIntervals = stepSource.readSteps(windowStart, now)

            val reconcileStart = alignToMinute(windowStart.epochSecond)
            // The minute containing "now" may still be in progress, so it is upserted like any
            // other minute below but never reconciled-by-deletion purely for being absent - a
            // partial read of it isn't proof it's actually zero yet.
            val reconcileEndExclusive = alignToMinute(now.epochSecond)

            val existingByMinute = database.stepBucketDao()
                .getFrom(stepSource.id, reconcileStart)
                .associateBy { it.startEpochSecond }

            val bucketEntities = normalizeToEntities(rawIntervals, stepSource.id, zone, now, existingByMinute)

            database.withTransaction {
                database.stepBucketDao().deleteInRange(stepSource.id, reconcileStart, reconcileEndExclusive)
                database.stepBucketDao().upsertAll(bucketEntities)
            }

            maybeAutoCompleteOngoingManualWalk(now)
            recomputeClassification()
            settingsRepository.setLastSuccessfulSync(now)
            SyncResult.Success(bucketEntities.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: "Unknown sync failure")
        }
    }

    /**
     * First import ever: read the full retained history. Later imports: re-read a rolling
     * overlap window ending at the last known bucket so late/corrected data reconciles, clamped
     * to the source's retention floor so we never request data it no longer has.
     */
    private fun computeWindowStart(latestBucketEnd: Long?, now: Instant): Instant {
        val retentionFloor = now.minus(RETENTION_WINDOW)
        if (latestBucketEnd == null) return retentionFloor
        val overlapStart = Instant.ofEpochSecond(latestBucketEnd).minus(SYNC_OVERLAP)
        return if (overlapStart.isBefore(retentionFloor)) retentionFloor else overlapStart
    }

    /**
     * [existingByMinute] carries forward each minute's *original* zoneId/localDate when it was
     * already stored - a re-import (e.g. from the sync overlap window) must never let a later
     * device timezone change retroactively move a past minute to a different calendar day. Only
     * minutes with no prior row get zoneId/localDate computed fresh from [zone].
     */
    private fun normalizeToEntities(
        raw: List<RawStepInterval>,
        source: String,
        zone: ZoneId,
        importedAt: Instant,
        existingByMinute: Map<Long, StepBucketEntity> = emptyMap(),
    ) = BucketNormalizer
        .normalize(raw.map { RawInterval(it.startEpochSecond, it.endEpochSecond, it.steps) })
        .map { minute ->
            val existing = existingByMinute[minute.startEpochSecond]
            val bucketZoneId = existing?.zoneId ?: zone.id
            val bucketLocalDate = existing?.localDate
                ?: Instant.ofEpochSecond(minute.startEpochSecond).atZone(zone).toLocalDate().toString()
            StepBucketEntity(
                source = source,
                startEpochSecond = minute.startEpochSecond,
                endEpochSecond = minute.startEpochSecond + 60,
                steps = minute.steps,
                zoneId = bucketZoneId,
                localDate = bucketLocalDate,
                importedAtEpochSecond = importedAt.epochSecond,
            )
        }

    private fun alignToMinute(epochSecond: Long): Long = epochSecond - Math.floorMod(epochSecond, 60L)

    /** Reruns the classifier over the full raw history and atomically replaces the AUTO cache. */
    private suspend fun recomputeClassification() {
        val thresholds = settingsRepository.settings.first().thresholds
        val buckets = database.stepBucketDao().getAllActive()
        val minuteBuckets = buckets.map { MinuteBucket(it.startEpochSecond, it.steps) }
        val classified = WalkClassifier.classify(minuteBuckets, thresholds)
        val computedAt = clock.instant().epochSecond

        val entities = classified.map { it.toEntity(computedAt) }
        database.withTransaction {
            database.walkBoutDao().clearAll()
            database.walkBoutDao().insertAll(entities)
        }
    }

    suspend fun reclassify(anchorEpochSecond: Long, classification: BoutClassification) {
        database.sessionOverrideDao().upsert(
            SessionOverrideEntity(
                boutStartEpochSecond = anchorEpochSecond,
                classification = classification.name,
                overriddenAtEpochSecond = clock.instant().epochSecond,
            ),
        )
    }

    /**
     * Persists new classification thresholds and immediately reruns the classifier against them,
     * rather than leaving existing sessions showing stale classifications until the next sync.
     * Shares [syncMutex] with [syncNowLocked] so a threshold change can never interleave with (or
     * be clobbered by) a concurrent sync's own classify-and-replace.
     */
    suspend fun applyThresholds(thresholds: ClassificationThresholds): Boolean = syncMutex.withLock {
        val accepted = settingsRepository.setThresholds(thresholds)
        if (accepted) recomputeClassification()
        accepted
    }

    suspend fun resetThresholds() = syncMutex.withLock {
        settingsRepository.resetThresholds()
        recomputeClassification()
    }

    // ---- Manual "Start walk / Finish walk" flow ----

    suspend fun startManualWalk(): Boolean {
        if (database.manualWalkDao().getOngoing() != null) return false
        val now = clock.instant().epochSecond
        database.manualWalkDao().insert(
            ManualWalkEntity(startEpochSecond = now, endEpochSecond = null, steps = null, createdAtEpochSecond = now),
        )
        return true
    }

    /**
     * Finishing a manual walk needs an up-to-date step read for the covered window - finalizing
     * it against stale or missing data would silently record a wrong step count. If the sync
     * fails or the source is unavailable, the walk is left ongoing (its `endEpochSecond` stays
     * null) so the user can retry rather than getting a silently inaccurate result.
     *
     * The sync this triggers may itself auto-complete the walk for inactivity (see
     * [maybeAutoCompleteOngoingManualWalk]) before this function gets a chance to finalize it
     * manually - in that case the row is re-read by id and left as the (more accurate)
     * auto-determined result rather than being overwritten with a `now`-based end time.
     */
    suspend fun finishManualWalk(): Boolean = syncMutex.withLock {
        val ongoingBeforeSync = database.manualWalkDao().getOngoing() ?: return@withLock false
        val syncResult = syncNowLocked()
        if (syncResult !is SyncResult.Success) return@withLock false

        val current = database.manualWalkDao().getById(ongoingBeforeSync.id) ?: return@withLock false
        if (current.endEpochSecond != null) {
            // Already finished - auto-completed for inactivity during the sync just above.
            return@withLock true
        }

        val end = clock.instant().epochSecond
        val steps = database.stepBucketDao().getAllActive()
            .filter { it.startEpochSecond >= current.startEpochSecond && it.startEpochSecond < end }
            .sumOf { it.steps }
        database.manualWalkDao().update(current.copy(endEpochSecond = end, steps = steps, autoCompleted = false))
        true
    }

    /**
     * Examines imported step buckets on every successful sync and, if the ongoing manual walk has
     * at least one active minute since it started and [MANUAL_WALK_INACTIVITY_TIMEOUT] worth of
     * fully-elapsed minutes have since passed with no further steps, finishes it automatically -
     * anchored to the end of the *last active minute*, not the moment this check runs, so later
     * (possibly much later, since the Recording API is not a real-time stream) syncs don't inflate
     * the workout with dead time. A walk with zero recorded steps is never touched here - see
     * [observeOngoingManualWalkStatus] for the separate, UI-driven stale/zero-step recovery path.
     */
    private suspend fun maybeAutoCompleteOngoingManualWalk(now: Instant) {
        val ongoing = database.manualWalkDao().getOngoing() ?: return
        val activeSinceStart = database.stepBucketDao().getAllActive()
            .filter { it.startEpochSecond >= ongoing.startEpochSecond }
        val lastActiveMinuteStart = activeSinceStart.maxOfOrNull { it.startEpochSecond } ?: return
        val lastActiveMinuteEnd = lastActiveMinuteStart + 60
        val idleSeconds = now.epochSecond - lastActiveMinuteEnd
        if (idleSeconds < MANUAL_WALK_INACTIVITY_TIMEOUT.seconds) return

        database.manualWalkDao().update(
            ongoing.copy(
                endEpochSecond = lastActiveMinuteEnd,
                steps = activeSinceStart.sumOf { it.steps },
                autoCompleted = true,
                autoCompletionMessageShown = false,
            ),
        )
    }

    /** Deletes the ongoing walk outright - used for the "Cancel" stale-walk recovery action, never for a walk with any recorded steps. */
    suspend fun cancelOngoingManualWalk(): Boolean = syncMutex.withLock {
        val ongoing = database.manualWalkDao().getOngoing() ?: return@withLock false
        database.manualWalkDao().deleteById(ongoing.id)
        true
    }

    /** Finishes the ongoing walk at a user-chosen time - used for the "Finish at a selected time" stale-walk recovery action. */
    suspend fun finishOngoingManualWalkAt(endEpochSecond: Long): Boolean = syncMutex.withLock {
        val ongoing = database.manualWalkDao().getOngoing() ?: return@withLock false
        if (endEpochSecond <= ongoing.startEpochSecond) return@withLock false
        val steps = database.stepBucketDao().getAllActive()
            .filter { it.startEpochSecond >= ongoing.startEpochSecond && it.startEpochSecond < endEpochSecond }
            .sumOf { it.steps }
        database.manualWalkDao().update(
            ongoing.copy(endEpochSecond = endEpochSecond, steps = steps, autoCompleted = false),
        )
        true
    }

    fun observeOngoingManualWalk(): Flow<ManualWalkEntity?> = database.manualWalkDao().observeOngoing()

    fun observeOngoingManualWalkStatus(): Flow<OngoingManualWalkStatus?> = combine(
        database.manualWalkDao().observeOngoing(),
        database.stepBucketDao().observeAllActive(),
    ) { ongoing, activeBuckets ->
        ongoing?.let { walk ->
            OngoingManualWalkStatus(
                startEpochSecond = walk.startEpochSecond,
                hasRecordedSteps = activeBuckets.any { it.startEpochSecond >= walk.startEpochSecond },
            )
        }
    }

    fun observeUnacknowledgedAutoCompletions(): Flow<List<AutoCompletedWalk>> =
        database.manualWalkDao().observeUnacknowledgedAutoCompletions().map { walks ->
            walks.map { AutoCompletedWalk(it.id, it.startEpochSecond, requireNotNull(it.endEpochSecond)) }
        }

    suspend fun acknowledgeAutoCompletion(walkId: Long) {
        val walk = database.manualWalkDao().getById(walkId) ?: return
        if (walk.autoCompletionMessageShown) return
        database.manualWalkDao().update(walk.copy(autoCompletionMessageShown = true))
    }

    /**
     * Debug-only entry point (see [com.example.stepsplit.debug.DebugDataSeeder]): seeds the
     * database from arbitrary raw intervals through the same normalize -> upsert -> reclassify
     * pipeline as a real sync, without touching the configured [stepSource]. Never invoked from
     * release code paths.
     */
    suspend fun debugImportRawIntervals(sourceId: String, intervals: List<RawStepInterval>) {
        syncMutex.withLock {
            val now = clock.instant()
            val entities = normalizeToEntities(intervals, sourceId, clock.zone, now)
            database.withTransaction { database.stepBucketDao().upsertAll(entities) }
            recomputeClassification()
        }
    }

    // ---- UI-facing reads ----

    fun observeSessions(): Flow<List<WalkSession>> = combine(
        database.walkBoutDao().observeAll(),
        database.sessionOverrideDao().observeAll(),
        database.manualWalkDao().observeFinished(),
    ) { bouts, overrides, manualWalks ->
        val overrideMap = overrides.associate { it.boutStartEpochSecond to BoutClassification.valueOf(it.classification) }
        val autoSessions = SessionMerger.fromAutoBouts(bouts.map { it.toDomain() }, overrideMap)
        val manualSessions = manualWalks.mapNotNull { walk ->
            val end = walk.endEpochSecond ?: return@mapNotNull null
            SessionMerger.manualWalkSession(walk.id, walk.startEpochSecond, end, walk.steps ?: 0L)
        }
        (autoSessions + manualSessions).sortedByDescending { it.startEpochSecond }
    }

    fun observeDailyBreakdowns(dates: List<LocalDate>): Flow<Map<LocalDate, DateStepBreakdown>> {
        val dateStrings = dates.map { it.toString() }
        return combine(
            database.stepBucketDao().observeForDates(dateStrings),
            observeSessions(),
        ) { buckets, sessions ->
            val dated = buckets.map { DatedBucket(LocalDate.parse(it.localDate), it.startEpochSecond, it.steps) }
            val workoutIntervals = SessionMerger.workoutIntervals(sessions)
            val breakdowns = StepAggregator.aggregateByDate(dated, workoutIntervals)
            dates.associateWith { date -> breakdowns[date] ?: DateStepBreakdown(date, 0L, 0L, 0L) }
        }
    }

    fun observeLastSuccessfulSync(): Flow<Instant?> = settingsRepository.settings.map { it.lastSuccessfulSync }

    companion object {
        val RETENTION_WINDOW: Duration = Duration.ofDays(7)
        val SYNC_OVERLAP: Duration = Duration.ofHours(6)

        /** How long a manual walk can go without any recorded steps before it's auto-finished. */
        val MANUAL_WALK_INACTIVITY_TIMEOUT: Duration = Duration.ofMinutes(7)

        /**
         * How long a manual walk with *zero* recorded steps since Start is left alone before the
         * UI offers the stale-walk recovery choice (Cancel / Finish at a time / Keep ongoing).
         * Deliberately longer than [MANUAL_WALK_INACTIVITY_TIMEOUT]: a slow start (tying shoes,
         * walking to a trailhead with the phone still in a pocket) is normal and should not be
         * flagged, whereas a walk that never recorded a single step in an hour most likely means
         * the user simply forgot it was running.
         */
        val ZERO_STEP_STALE_THRESHOLD: Duration = Duration.ofMinutes(60)
    }
}

private fun ClassifiedBout.toEntity(computedAtEpochSecond: Long) = WalkBoutEntity(
    startEpochSecond = startEpochSecond,
    endEpochSecond = endEpochSecond,
    steps = steps,
    activeMinutes = activeMinutes,
    elapsedMinutes = elapsedMinutes,
    cadence = cadence,
    autoClassification = classification.name,
    autoConfidence = confidence,
    autoReasonCode = reasonCode.name,
    classifierVersion = CLASSIFIER_VERSION,
    computedAtEpochSecond = computedAtEpochSecond,
)

private fun WalkBoutEntity.toDomain() = ClassifiedBout(
    startEpochSecond = startEpochSecond,
    endEpochSecond = endEpochSecond,
    steps = steps,
    activeMinutes = activeMinutes,
    elapsedMinutes = elapsedMinutes,
    cadence = cadence,
    classification = BoutClassification.valueOf(autoClassification),
    confidence = autoConfidence,
    reasonCode = ClassificationReasonCode.valueOf(autoReasonCode),
)
