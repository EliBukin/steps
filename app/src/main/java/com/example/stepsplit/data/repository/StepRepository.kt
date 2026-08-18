package com.example.stepsplit.data.repository

import androidx.room.withTransaction
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bout.WalkBoutEntity
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.RawStepInterval
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceReadException
import com.example.stepsplit.data.stepsource.StepSourceUnavailableException
import com.example.stepsplit.domain.aggregation.BucketNormalizer
import com.example.stepsplit.domain.aggregation.DatedBucket
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.aggregation.EpochInterval
import com.example.stepsplit.domain.aggregation.RawInterval
import com.example.stepsplit.domain.aggregation.StepAggregator
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.classification.CLASSIFIER_VERSION
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.classification.ClassifiedBout
import com.example.stepsplit.domain.classification.MinuteBucket
import com.example.stepsplit.domain.classification.WalkClassifier
import com.example.stepsplit.domain.model.SyncFailure
import com.example.stepsplit.domain.model.SyncFailureCategory
import com.example.stepsplit.domain.stats.LifetimeStepTotals
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every read/write path over step data: importing from [stepSource], normalizing into
 * one-minute buckets, and running [WalkClassifier] over the resulting raw history. A single
 * [syncMutex] serializes import+classify pipelines so two foreground triggers racing each other
 * (e.g. an app-resume refresh and the Today screen's periodic refresh, or a just-granted-permission
 * refresh and a resume refresh - see [com.example.stepsplit.ui.today.TodayViewModel.refresh]'s own
 * in-flight-job coalescing) can never interleave writes - combined with Room's own transactions,
 * this makes concurrent triggers safe by construction rather than by luck.
 *
 * There is no validation gate between import and a bucket's canonical [StepBucketEntity.steps] -
 * every positive step [stepSource] reports counts, always. [WalkClassifier] only ever labels
 * already-counted raw history as a likely workout or incidental movement; a classification
 * failure, an unfinished bout, or a threshold change can change that label but can never change
 * how many steps are counted (see [recomputeClassificationWithinTransaction]).
 *
 * [repositoryScope] owns the one-shot local timer used to finalize a withheld trailing bout once
 * [ClassificationThresholds.idleFinalizeMinutes] actually elapses (see [rescheduleFinalizationJob])
 * without polling or a background service. It is expected to live exactly as long as this
 * repository does - the process lifetime in production ([com.example.stepsplit.di.AppContainer]),
 * or a test's own scope in tests - so the timer can never outlive its owner.
 */
class StepRepository(
    private val database: StepSplitDatabase,
    private val stepSource: StepSource,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
    private val repositoryScope: CoroutineScope,
) {
    private val syncMutex = Mutex()

    /** The single pending trailing-bout finalization timer, if any - see [rescheduleFinalizationJob]. */
    private var finalizationJob: Job? = null

    /**
     * Set once the first [ensureClassificationFreshLocked] call this process has run its
     * unconditional recovery recompute - see that function for why this is needed on top of the
     * ordinary [CLASSIFIER_VERSION] staleness check.
     */
    private var recoveryPerformed = false

    suspend fun checkAvailability(): StepSourceAvailability = stepSource.checkAvailability()

    suspend fun syncNow(): SyncResult = syncMutex.withLock { syncNowLocked() }

    private suspend fun syncNowLocked(): SyncResult {
        // Runs first and unconditionally - a cached classification produced by an older
        // CLASSIFIER_VERSION must be recomputed from the raw data already in Room regardless of
        // whether the source below turns out to be unavailable, unsubscribed, or failing. It
        // never performs a source read itself, so it can never be blocked by one. Any failure here
        // is converted into an ordinary SyncResult.Failed by ensureClassificationFreshOrFail rather
        // than being allowed to propagate - see that function's own doc comment.
        ensureClassificationFreshOrFail()?.let { return it }

        val availability = stepSource.checkAvailability()
        if (availability !is StepSourceAvailability.Available) {
            // Source availability/permission state is a separate axis from sync/collection
            // health below - deliberately left untouched here rather than recorded as a sync
            // failure, so the two concerns never get conflated in the UI.
            return SyncResult.Unavailable(availability)
        }
        if (!stepSource.ensureSubscribed()) {
            // Without a live subscription a "successful" read could just be an empty/stale one -
            // report this as a failure rather than silently recording an empty sync as success.
            return recordFailure(SyncFailureCategory.SUBSCRIPTION_FAILED, "Unable to subscribe to the step data source")
        }

        return try {
            val now = clock.instant()
            val zone = clock.zone
            val latestBucketEnd = database.stepBucketDao().latestBucketEnd(stepSource.id)
            val windowStart = computeWindowStart(latestBucketEnd, now)

            val rawIntervals = stepSource.readSteps(windowStart, now)

            // Wide on purpose - only used to look up each touched minute's PRIOR zoneId/localDate
            // (see normalizeToEntities) so a re-import never lets a later timezone change
            // retroactively move a past minute to a different calendar day. Reading a superset is
            // harmless.
            val existingByMinute = database.stepBucketDao()
                .getFrom(stepSource.id, alignToMinute(windowStart.epochSecond))
                .associateBy { it.startEpochSecond }

            val bucketEntities = normalizeToEntities(rawIntervals, stepSource.id, zone, now, existingByMinute)

            // Deliberately upsert-only - this sync path never deletes a previously stored bucket.
            // A minute this read doesn't return a positive value for cannot be told apart from
            // "genuinely zero" versus "simply outside what this particular read happened to
            // cover" - preserving existing history is strictly safer than a speculative
            // correction to zero. A previously stored bucket can therefore only ever be corrected
            // by a later positive value for that exact minute (via OnConflictStrategy.REPLACE in
            // upsertAll), never removed outright by this sync path.
            //
            // The raw-bucket upsert and the classifier's derived walk_bouts replacement
            // (recomputeClassificationWithinTransaction, called from inside this same block) must
            // still commit as one atomic unit. Room's withTransaction is reentrant within the same
            // coroutine - a nested call reuses the already-open transaction instead of starting a
            // second one - so nesting them here means either both commit together, or (if either
            // throws, or this coroutine is cancelled anywhere in here) both roll back together.
            val recomputeResult = database.withTransaction {
                database.stepBucketDao().upsertAll(bucketEntities)
                recomputeClassificationWithinTransaction()
            }
            rescheduleFinalizationJob(recomputeResult.minuteBuckets, recomputeResult.thresholds, recomputeResult.computedAtEpochSecond)

            // A genuinely successful sync is the only thing allowed to clear a previously
            // recorded failure - it must never look cleared just because the UI happened to poll
            // again, and it must never be replaced with a fake "zero steps" success. Recorded as
            // one atomic DataStore transaction (see SettingsRepository.recordSuccessfulSync) so
            // no observer can ever see the new timestamp alongside the old failure.
            settingsRepository.recordSuccessfulSync(now)
            SyncResult.Success(bucketEntities.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepSourceUnavailableException) {
            // Availability was lost between the earlier upfront checkAvailability() (above) and
            // this actual read, or mid-read - must never be treated as a successful empty read
            // (see StepSource.readSteps's own doc comment). Thrown from stepSource.readSteps(),
            // strictly before the transaction above ever runs, so no stored bucket, walk_bouts
            // row, or the last-successful-sync timestamp is touched.
            SyncResult.Unavailable(e.availability)
        } catch (e: StepSourceReadException) {
            recordFailure(SyncFailureCategory.READ_FAILED, e.message ?: "Unknown read failure")
        } catch (e: Exception) {
            recordFailure(SyncFailureCategory.UNKNOWN, e.message ?: "Unknown sync failure")
        }
    }

    /** Persists a structured failure category/time (see [SyncFailure]) and returns the matching [SyncResult.Failed]. */
    private suspend fun recordFailure(category: SyncFailureCategory, message: String): SyncResult.Failed {
        settingsRepository.recordSyncFailure(SyncFailure(category, clock.instant().epochSecond))
        return SyncResult.Failed(category, message)
    }

    /**
     * First import ever: read the full retained history. Later imports: re-read a rolling
     * overlap window ending at the last known bucket so late/corrected data reconciles, clamped
     * to a conservative retention floor so a long-overdue sync never requests an unbounded window.
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

    // ---- Classification ----

    /**
     * Guarantees the cached classification (and, transitively, the pending finalization timer -
     * see [rescheduleFinalizationJob]) reflect the current local raw data before this call
     * returns. Recomputes straight from the raw [StepBucketEntity] history already stored in
     * Room, so this works even when [stepSource] is unavailable, unsubscribed, or failing; it
     * never performs a source read. [observeDailyBreakdowns] also filters stale-version rows
     * directly, so nothing stale is ever shown even in the brief window before a recompute
     * completes.
     *
     * Two independent reasons a recompute can be needed:
     *
     * 1. **Stale [CLASSIFIER_VERSION].** A cached row was produced by an older algorithm version,
     *    which could now classify the same raw history differently.
     * 2. **Lost in-memory recovery state.** [finalizationJob] and [recoveryPerformed] live only in
     *    memory - if the process is killed while a trailing bout is still withheld and waiting out
     *    [ClassificationThresholds.idleFinalizeMinutes], a fresh process has no memory of that
     *    pending deadline at all, yet every cached row can already be stamped with the current
     *    [CLASSIFIER_VERSION] (or there may be no cached row for that bout at all, since it was
     *    correctly withheld) - so the version check alone would never catch this. Recomputing
     *    unconditionally on the very first call this process makes re-derives the correct current
     *    state regardless: if the deadline already passed while the process was dead, the
     *    classifier finalizes it immediately as part of this recompute; otherwise
     *    [rescheduleFinalizationJob] schedules a fresh timer for whatever delay actually remains.
     */
    private suspend fun ensureClassificationFreshLocked() {
        if (!recoveryPerformed) {
            // Set only after recomputeClassification() actually completes: if it throws or is
            // cancelled, recovery has NOT happened, and a later call in this same process must
            // retry it rather than silently skipping straight to the cheap version-only check
            // below.
            recomputeClassification()
            recoveryPerformed = true
            return
        }
        if (database.walkBoutDao().hasRowsWithOtherClassifierVersion(CLASSIFIER_VERSION)) {
            recomputeClassification()
        }
    }

    /**
     * Runs [ensureClassificationFreshLocked], converting any failure into a structured
     * [SyncResult.Failed] (category [SyncFailureCategory.UNKNOWN]) instead of letting it escape
     * [syncNow]. This matters because [syncNow] is called from ViewModels' own coroutine scopes
     * (e.g. `viewModelScope.launch`) with no exception handling of their own - an uncaught
     * exception here would crash the app rather than surface as ordinary, structured sync-failure
     * state the UI already knows how to show. [CancellationException] is rethrown unchanged, never
     * treated as a failure. A genuine failure here does NOT mark recovery as done - see
     * [ensureClassificationFreshLocked]'s own guard - so the next [syncNow] call retries it, the
     * same as any other local-only condition that failed to complete. Returns null when recovery
     * succeeded (including the common case where nothing needed recomputing), signalling
     * [syncNowLocked] to continue with the rest of the sync.
     */
    private suspend fun ensureClassificationFreshOrFail(): SyncResult.Failed? = try {
        ensureClassificationFreshLocked()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        recordFailure(SyncFailureCategory.UNKNOWN, e.message ?: "Unknown recovery failure")
    }

    /** What [rescheduleFinalizationJob] needs, returned by [recomputeClassificationWithinTransaction] so its caller can reschedule only once the transaction that produced it has actually committed. */
    private data class ClassificationRecomputeResult(
        val minuteBuckets: List<MinuteBucket>,
        val thresholds: ClassificationThresholds,
        val computedAtEpochSecond: Long,
    )

    /**
     * The transactional half of classification recompute: reruns the classifier over the full raw
     * history (every positive-step minute - see [com.example.stepsplit.data.local.bucket.StepBucketDao.getAllActive])
     * and atomically replaces the classification cache. Passes the current instant explicitly so
     * the classifier can decide whether the most recent bout is finished yet (see [WalkClassifier]'s
     * own doc comment) without reading a clock itself - it stays a pure function of its inputs.
     *
     * Deliberately does NOT touch [finalizationJob] or call [rescheduleFinalizationJob] - that
     * must only ever happen once the transaction this function opens (or joins, if already inside
     * one - Room's `withTransaction` is reentrant within the same coroutine) has actually
     * committed. Calling it from inside a still-open transaction would let a rollback or
     * cancellation leave the in-memory timer scheduled from data that turns out not to be durably
     * committed after all - Room would roll the database back while the timer mutation (and
     * whatever coroutine it launched) stayed applied, since neither lives inside the transaction
     * itself.
     */
    private suspend fun recomputeClassificationWithinTransaction(): ClassificationRecomputeResult {
        val thresholds = settingsRepository.settings.first().thresholds
        val buckets = database.stepBucketDao().getAllActive()
        val minuteBuckets = buckets.map { MinuteBucket(it.startEpochSecond, it.steps) }
        val computedAt = clock.instant().epochSecond
        val classified = WalkClassifier.classify(minuteBuckets, thresholds, computedAt)

        val entities = classified.map { it.toEntity(computedAt) }
        database.withTransaction {
            database.walkBoutDao().clearAll()
            database.walkBoutDao().insertAll(entities)
        }

        return ClassificationRecomputeResult(minuteBuckets, thresholds, computedAt)
    }

    /**
     * Standalone entry point used by every caller that is NOT already inside its own outer
     * transaction (recovery, threshold changes, the finalization timer's own rerun, debug
     * import) - [recomputeClassificationWithinTransaction] here owns its own outermost
     * transaction, so rescheduling immediately after it returns is always safe: it only runs once
     * that transaction has actually committed. [syncNowLocked] does NOT call this - see
     * [recomputeClassificationWithinTransaction]'s own doc comment for why.
     */
    private suspend fun recomputeClassification() {
        val result = recomputeClassificationWithinTransaction()
        rescheduleFinalizationJob(result.minuteBuckets, result.thresholds, result.computedAtEpochSecond)
    }

    /**
     * Schedules a single cancellable, one-shot local timer so a trailing bout that
     * [WalkClassifier] withheld (still waiting out [ClassificationThresholds.idleFinalizeMinutes])
     * gets reclassified - and, once idle long enough, finalizes into a workout/incidental result -
     * the moment its own deadline passes, rather than only on the next sync (periodic syncs are
     * hours apart). The
     * timer only ever reruns the classifier over data already in Room; it never touches
     * [stepSource]. Any previously scheduled timer is replaced here every time this function runs
     * - i.e. on every sync, threshold change, or debug import - so new raw data or a threshold
     * change always reschedules rather than leaving a stale deadline pending, and at most one
     * timer is ever outstanding.
     */
    private suspend fun rescheduleFinalizationJob(
        minuteBuckets: List<MinuteBucket>,
        thresholds: ClassificationThresholds,
        computedAtEpochSecond: Long,
    ) {
        // Guard against a job cancelling itself: when this function runs because the timer below
        // just fired, `finalizationJob` still refers to the coroutine currently executing this
        // very code - cancelling it here would abort this recompute partway through.
        finalizationJob?.let { pending -> if (pending !== coroutineContext[Job]) pending.cancel() }
        finalizationJob = null

        val lastActiveMinuteStart = minuteBuckets.filter { it.steps > 0 }.maxOfOrNull { it.startEpochSecond } ?: return
        val deadlineEpochSecond = lastActiveMinuteStart + 60L + thresholds.idleFinalizeMinutes * 60L
        val delaySeconds = deadlineEpochSecond - computedAtEpochSecond
        if (delaySeconds <= 0) return // already finalized as of this very recompute - nothing to wait for

        finalizationJob = repositoryScope.launch {
            delay(delaySeconds * 1000)
            syncMutex.withLock { recomputeClassification() }
        }
    }

    /**
     * Persists new classification thresholds and immediately reruns the classifier against them,
     * rather than leaving existing bouts showing stale classifications until the next sync.
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

    /**
     * Debug-only entry point (see [com.example.stepsplit.debug.DebugDataSeeder]): seeds the
     * database from arbitrary raw intervals through the same normalize -> upsert -> classify
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

    /**
     * Debug-only: raw bucket row count for the real, production [stepSource] specifically - never
     * including rows written by the debug sample-data seeder
     * ([com.example.stepsplit.debug.DebugDataSeeder], which imports under its own, different
     * source id) - see the Settings debug diagnostics panel. Mixing the two in would let seeded
     * sample data masquerade as evidence of real production acquisition.
     */
    suspend fun debugStoredBucketCount(): Int = database.stepBucketDao().countBySource(stepSource.id)

    // ---- UI-facing reads ----

    /**
     * Splits raw step buckets into workout/incidental totals per local calendar day, combining
     * [com.example.stepsplit.data.local.bucket.StepBucketEntity] rows directly with the current
     * automatic [WalkBoutEntity] cache - there is no manual-override layer to merge in. A row
     * computed by an older [CLASSIFIER_VERSION] must never be treated as current - see
     * [ensureClassificationFreshLocked] - and is filtered here too (not just relying on that
     * recompute having already run) so a stale row is never attributed to a day's workout total,
     * even in the brief window before a recompute completes. Only bouts finalized as
     * [BoutClassification.WORKOUT] contribute a workout interval; a still-withheld trailing bout
     * (see [WalkClassifier]) has no current-version row at all yet, so its raw steps fall through
     * to incidental until it finalizes - preserving `totalSteps == workoutSteps + incidentalSteps`
     * at every point in time.
     */
    fun observeDailyBreakdowns(dates: List<LocalDate>): Flow<Map<LocalDate, DateStepBreakdown>> {
        val dateStrings = dates.map { it.toString() }
        return combine(
            database.stepBucketDao().observeForDates(dateStrings),
            database.walkBoutDao().observeAll(),
        ) { buckets, bouts ->
            val dated = buckets.map { DatedBucket(LocalDate.parse(it.localDate), it.startEpochSecond, it.steps) }
            val workoutIntervals = bouts
                .filter { it.classifierVersion == CLASSIFIER_VERSION && BoutClassification.valueOf(it.autoClassification) == BoutClassification.WORKOUT }
                .map { EpochInterval(it.startEpochSecond, it.endEpochSecond) }
            val breakdowns = StepAggregator.aggregateByDate(dated, workoutIntervals)
            dates.associateWith { date -> breakdowns[date] ?: DateStepBreakdown(date, 0L, 0L, 0L) }
        }
    }

    /**
     * Lifetime totals across the real production step source's ENTIRE stored raw history - never
     * date-bounded, never limited by any UI-visible date range (e.g. History's rolling 7 days).
     * Filtered to [stepSource]'s own id so debug/sample-data sources (imported under their own
     * distinct source id - see [debugImportRawIntervals]) never pollute real lifetime statistics.
     * Observed as a [Flow] (not a one-shot read) so Stats updates live as new buckets are imported.
     */
    fun observeLifetimeStats(): Flow<LifetimeStepTotals> = combine(
        database.stepBucketDao().observeLifetimeAggregate(stepSource.id),
        database.stepBucketDao().observeBestDay(stepSource.id),
    ) { aggregate, bestDay ->
        LifetimeStepTotals(
            lifetimeSteps = aggregate.totalSteps,
            activeDays = aggregate.activeDays,
            bestDayDate = bestDay?.localDate?.let(LocalDate::parse),
            bestDaySteps = bestDay?.totalSteps ?: 0L,
        )
    }

    companion object {
        /**
         * How far back a first-ever (or long-overdue) sync's read request reaches. This only
         * bounds the read *request* window - it has no effect on how long data already imported
         * into Room is kept; that's permanent.
         */
        val RETENTION_WINDOW: Duration = Duration.ofDays(10)
        val SYNC_OVERLAP: Duration = Duration.ofHours(6)
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
