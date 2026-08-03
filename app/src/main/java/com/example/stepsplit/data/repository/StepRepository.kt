package com.example.stepsplit.data.repository

import androidx.room.withTransaction
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bout.WalkBoutEntity
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.local.override.SessionOverrideEntity
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.RawStepInterval
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceReadException
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
import com.example.stepsplit.domain.model.SyncFailure
import com.example.stepsplit.domain.model.SyncFailureCategory
import com.example.stepsplit.domain.model.WalkSession
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
 * one-minute buckets, running the classifier, merging manual overrides, and exposing UI-ready
 * flows. A single [syncMutex] serializes import+classify pipelines so a WorkManager sync racing
 * an app-start sync can never interleave writes - combined with Room's own transactions, this
 * makes concurrent triggers safe by construction rather than by luck.
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
        // never performs a source read itself, so it can never be blocked by one.
        ensureClassificationFreshLocked()

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

            recomputeClassification()
            // A genuinely successful sync is the only thing allowed to clear a previously
            // recorded failure - it must never look cleared just because the UI happened to poll
            // again, and it must never be replaced with a fake "zero steps" success. Recorded as
            // one atomic DataStore transaction (see SettingsRepository.recordSuccessfulSync) so
            // no observer can ever see the new timestamp alongside the old failure.
            settingsRepository.recordSuccessfulSync(now)
            SyncResult.Success(bucketEntities.size)
        } catch (e: CancellationException) {
            throw e
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

    /**
     * Guarantees the cached classification (and, transitively, the pending finalization timer -
     * see [rescheduleFinalizationJob]) reflect the current local raw data before this call
     * returns. Recomputes straight from the raw [StepBucketEntity] history already stored in
     * Room, so this works even when [stepSource] is unavailable, unsubscribed, or failing; it
     * never performs a source read. [observeSessions] also filters stale-version rows directly,
     * so nothing stale is ever shown even in the brief window before a recompute completes.
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
     * Reruns the classifier over the full raw history and atomically replaces the AUTO cache.
     * Passes the current instant explicitly so the classifier can decide whether the most recent
     * bout is finalized yet (see [WalkClassifier]'s own doc comment) without reading a clock
     * itself - it stays a pure function of its inputs.
     */
    private suspend fun recomputeClassification() {
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

        rescheduleFinalizationJob(minuteBuckets, thresholds, computedAt)
    }

    /**
     * Schedules a single cancellable, one-shot local timer so a trailing bout that
     * [WalkClassifier] withheld (still waiting out [ClassificationThresholds.idleFinalizeMinutes])
     * gets reclassified - and, once idle long enough, appears as a session - the moment its own
     * deadline passes, rather than only on the next sync (periodic syncs are hours apart). The
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
    ) { bouts, overrides ->
        // A row computed by an older CLASSIFIER_VERSION must never be treated as current - see
        // ensureClassificationFreshLocked. Filtered here too (not just relying on that recompute
        // having already run) so a stale row is never shown even in the brief window before it does.
        val current = bouts.filter { it.classifierVersion == CLASSIFIER_VERSION }
        val overrideMap = overrides.associate { it.boutStartEpochSecond to BoutClassification.valueOf(it.classification) }
        SessionMerger.fromAutoBouts(current.map { it.toDomain() }, overrideMap).sortedByDescending { it.startEpochSecond }
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

    companion object {
        /**
         * The Local Recording API documents successfully subscribed local data as retained for
         * 10 days. Clamping the recovery/read window any tighter than that would permanently
         * miss still-recoverable data after a long sync gap (roughly 8-10 days with no
         * successful sync) even though the API itself still has it. This only bounds how far
         * back a *read request* reaches on a first-ever or long-overdue sync - it has no effect
         * on how long data already imported into Room is kept; that's permanent.
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
