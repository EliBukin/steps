package com.example.stepsplit.data.repository

import androidx.room.withTransaction
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bout.WalkBoutEntity
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.local.bucket.ValidationStateCount
import com.example.stepsplit.data.local.motion.ActivityIntervalEntity
import com.example.stepsplit.data.local.motion.MotionEvidenceEntity
import com.example.stepsplit.data.local.motion.TemporalContinuityStateEntity
import com.example.stepsplit.data.motion.ConvertedSampledBatch
import com.example.stepsplit.data.motion.ConvertedTransitionEvent
import com.example.stepsplit.data.motion.MotionDiagnosticsHealthSink
import com.example.stepsplit.data.motion.MotionEvidenceIngestor
import com.example.stepsplit.data.motion.NoOpMotionDiagnosticsHealthSink
import com.example.stepsplit.data.local.override.SessionOverrideEntity
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.RawStepInterval
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceReadException
import com.example.stepsplit.data.stepsource.StepSourceUnavailableException
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
import com.example.stepsplit.domain.stats.LifetimeStepTotals
import com.example.stepsplit.domain.validation.IntervalMutationSet
import com.example.stepsplit.domain.validation.IntervalReconstructor
import com.example.stepsplit.domain.validation.MaterializedInterval
import com.example.stepsplit.domain.validation.MotionActivityType
import com.example.stepsplit.domain.validation.MotionEvidenceEvent
import com.example.stepsplit.domain.validation.MotionEvidenceKind
import com.example.stepsplit.domain.validation.PersistedInterval
import com.example.stepsplit.domain.validation.RawObservation
import com.example.stepsplit.domain.validation.ReconciliationSignal
import com.example.stepsplit.domain.validation.StrictStepValidationPolicy
import com.example.stepsplit.domain.validation.ValidationConstants
import com.example.stepsplit.domain.validation.ValidationDecision
import com.example.stepsplit.domain.validation.ValidationState
import com.example.stepsplit.sync.PendingBucketFinalizationScheduler
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every read/write path over step data: importing from [stepSource], normalizing into
 * one-minute buckets, running strict vehicle-aware validation, running the classifier over
 * *accepted* buckets only, merging manual overrides, and exposing UI-ready flows. A single
 * [syncMutex] serializes import+classify+validate pipelines so a WorkManager sync racing an
 * app-start sync (or a motion-evidence broadcast racing either) can never interleave writes -
 * combined with Room's own transactions, this makes concurrent triggers safe by construction
 * rather than by luck.
 *
 * **Strict validation** (see [com.example.stepsplit.domain.validation.StrictStepValidationPolicy]
 * for the full rule set) sits between raw import and every user-facing read: a freshly-imported
 * minute starts `PENDING`, is evaluated against [ActivityIntervalEntity]/[MotionEvidenceEntity]
 * evidence already in Room, and only `ACCEPTED_WALKING`/`ACCEPTED_RUNNING` buckets ever reach
 * [observeDailyBreakdowns]/[observeSessions]/[observeLifetimeStats]. This repository also
 * implements [MotionEvidenceIngestor] - the receiver-facing entry point for new Activity
 * Recognition evidence - so every write path that can affect a validation decision (a sync, a
 * transition/sampled event, a finalization deadline, a policy-version bump) funnels through this
 * one class and the same [syncMutex].
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
    private val motionDiagnosticsHealthSink: MotionDiagnosticsHealthSink = NoOpMotionDiagnosticsHealthSink,
    private val pendingBucketFinalizationScheduler: PendingBucketFinalizationScheduler? = null,
    private val validationConstants: ValidationConstants = ValidationConstants.DEFAULT,
) : MotionEvidenceIngestor {
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

            // Wide on purpose - only used to look up each touched minute's PRIOR zoneId/localDate/
            // validation state (see normalizeToEntities) so a re-import never lets a later
            // timezone change retroactively move a past minute to a different calendar day, and
            // never silently discards an already-made validation decision - see
            // [mergeValidationColumns]. Reading a superset is harmless.
            val existingByMinute = database.stepBucketDao()
                .getFrom(stepSource.id, alignToMinute(windowStart.epochSecond))
                .associateBy { it.startEpochSecond }

            val bucketEntities = normalizeToEntities(rawIntervals, stepSource.id, zone, now, existingByMinute)

            // Deliberately upsert-only - this sync path never deletes a previously stored bucket.
            // An earlier version bounded a delete-by-envelope reconciliation to exactly the
            // minute-range this read's own positive results spanned, on the assumption that two
            // positively-returned minutes prove everything between them is confirmed zero. The
            // Local Recording API documentation makes no such coverage guarantee: a minute this
            // read doesn't return a positive value for cannot be told apart from "genuinely zero"
            // versus "simply outside what this particular read happened to cover" (see
            // toRawIntervalsOrThrow - a zero-step DataPoint is filtered out identically to one that
            // was never returned at all, so there is no signal here to distinguish the two). Absent
            // an explicit, trustworthy per-minute coverage signal from the source (which no current
            // StepSource implementation provides), preserving existing history is strictly safer
            // than a speculative correction-to-zero. A previously stored bucket can therefore only
            // ever be corrected by a later positive value for that exact minute (via
            // OnConflictStrategy.REPLACE in upsertAll), never removed outright by this sync path.
            //
            // The raw-bucket upsert, strict validation, and the classifier's derived walk_bouts
            // replacement (recomputeClassificationWithinTransaction, called from inside this same
            // block) must still commit as one atomic unit. Room's withTransaction is reentrant
            // within the same coroutine - a nested call reuses the already-open transaction instead
            // of starting a second one - so nesting them here means either all three commit
            // together, or (if any of them throws, or this coroutine is cancelled anywhere in here)
            // ALL roll back together.
            val recomputeResult = database.withTransaction {
                database.stepBucketDao().upsertAll(bucketEntities)
                validateNewlyUpsertedBucketsWithinTransaction(bucketEntities, now.epochSecond)
                recomputeClassificationWithinTransaction()
            }
            rescheduleFinalizationJob(recomputeResult.minuteBuckets, recomputeResult.thresholds, recomputeResult.computedAtEpochSecond)
            pendingBucketFinalizationScheduler?.rescheduleForEarliestPendingDeadline()

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
     * minutes with no prior row get zoneId/localDate computed fresh from [zone]. Validation-state
     * columns are carried forward (or reset) per [mergeValidationColumns] - see that function's own
     * doc comment for the exact per-state rules.
     *
     * Observation bounds are set equal to each minute's own `[start, end)` - correct and sufficient
     * for the current production source, which already reports 1-minute-aligned intervals (see
     * [com.example.stepsplit.domain.validation.StrictStepValidationPolicy]'s own doc comment on
     * `observationStartEpochSecond`/`observationEndEpochSecond` for why a future, genuinely
     * multi-minute raw source would need this computed differently).
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
            val observationEnd = minute.startEpochSecond + 60
            val merged = mergeValidationColumns(existing, minute.steps, minute.startEpochSecond, observationEnd)
            StepBucketEntity(
                source = source,
                startEpochSecond = minute.startEpochSecond,
                endEpochSecond = observationEnd,
                steps = minute.steps,
                zoneId = bucketZoneId,
                localDate = bucketLocalDate,
                importedAtEpochSecond = importedAt.epochSecond,
                validationState = merged.state.name,
                acceptedSteps = merged.acceptedSteps,
                rejectionReason = merged.rejectionReason,
                policyVersion = merged.policyVersion,
                validatedAtEpochSecond = merged.validatedAtEpochSecond,
                observationStartEpochSecond = minute.startEpochSecond,
                observationEndEpochSecond = observationEnd,
            )
        }

    private data class MergedValidationColumns(
        val state: ValidationState,
        val acceptedSteps: Long,
        val rejectionReason: String?,
        val policyVersion: Int?,
        val validatedAtEpochSecond: Long?,
    )

    /**
     * Governs exactly what a re-import (the same `(source, startEpochSecond)` minute seen again) is
     * allowed to change about an already-validated bucket. Three independent axes:
     *
     * - **Unchanged** (same raw steps, same observation span): the existing validation decision is
     *   preserved verbatim, whatever it is.
     * - **`LEGACY_UNVERIFIED`**: preserved *regardless* of what changed - historical motion evidence
     *   for that period will never exist (this app wasn't collecting any yet), so resetting it to
     *   `PENDING` would only let it silently time out to `REJECTED_UNVERIFIED` and vanish from the
     *   Stats legacy card, discarding genuinely real historical step data for no benefit. Only the
     *   raw `steps`/observation-span columns themselves are updated, for audit accuracy.
     * - **`REJECTED_VEHICLE`/`REJECTED_BICYCLE`**: also preserved regardless of what changed - see
     *   [StrictStepValidationPolicy]'s own state-machine doc comment for why these are terminal.
     *   The minute's own wall-clock time never changes even if its observation-grouping metadata
     *   does, so a motion veto for that fixed time is never reconsidered by a later correction.
     * - **`ACCEPTED_WALKING`/`ACCEPTED_RUNNING`**: if only the step *count* changed (same span),
     *   the acceptance is preserved and `acceptedSteps` is updated to match - the time-coverage
     *   justification for accepting didn't change. If the *span* changed, the row resets to
     *   `PENDING` and is revalidated - an accepted decision must never be silently extended to
     *   cover time it was never actually validated against.
     * - **`PENDING`/`REJECTED_UNVERIFIED`**: reset to `PENDING` with the new values - naturally
     *   re-validated by the normal pipeline.
     */
    private fun mergeValidationColumns(
        existing: StepBucketEntity?,
        newSteps: Long,
        newObservationStart: Long,
        newObservationEnd: Long,
    ): MergedValidationColumns {
        if (existing == null) return MergedValidationColumns(ValidationState.PENDING, 0L, null, null, null)

        val spanUnchanged = existing.observationStartEpochSecond == newObservationStart && existing.observationEndEpochSecond == newObservationEnd
        val stepsUnchanged = existing.steps == newSteps
        fun preserved() = MergedValidationColumns(
            ValidationState.valueOf(existing.validationState), existing.acceptedSteps, existing.rejectionReason,
            existing.policyVersion, existing.validatedAtEpochSecond,
        )

        if (spanUnchanged && stepsUnchanged) return preserved()

        return when (ValidationState.valueOf(existing.validationState)) {
            ValidationState.LEGACY_UNVERIFIED -> preserved()
            ValidationState.REJECTED_VEHICLE, ValidationState.REJECTED_BICYCLE ->
                MergedValidationColumns(ValidationState.valueOf(existing.validationState), 0L, existing.rejectionReason, existing.policyVersion, existing.validatedAtEpochSecond)
            ValidationState.ACCEPTED_WALKING, ValidationState.ACCEPTED_RUNNING ->
                if (spanUnchanged) {
                    MergedValidationColumns(ValidationState.valueOf(existing.validationState), newSteps, existing.rejectionReason, existing.policyVersion, existing.validatedAtEpochSecond)
                } else {
                    MergedValidationColumns(ValidationState.PENDING, 0L, null, null, null)
                }
            ValidationState.PENDING, ValidationState.REJECTED_UNVERIFIED ->
                MergedValidationColumns(ValidationState.PENDING, 0L, null, null, null)
        }
    }

    private fun alignToMinute(epochSecond: Long): Long = epochSecond - Math.floorMod(epochSecond, 60L)

    // ---- Strict vehicle-aware validation ----

    /** Runs [StrictStepValidationPolicy.evaluate] for every distinct observation group among the just-upserted rows that is (still) `PENDING` - already-terminal/preserved rows from [mergeValidationColumns] are skipped entirely. Must run inside the same transaction as the upsert it follows. */
    private suspend fun validateNewlyUpsertedBucketsWithinTransaction(justUpserted: List<StepBucketEntity>, nowEpochSecond: Long) {
        if (justUpserted.isEmpty()) return
        val minStart = justUpserted.minOf { it.startEpochSecond }
        val touchedStarts = justUpserted.map { it.startEpochSecond }.toSet()
        val currentRows = database.stepBucketDao().getFrom(stepSource.id, minStart)
            .filter { it.startEpochSecond in touchedStarts }
        validateGroupsWithinTransaction(currentRows.filter { it.validationState == ValidationState.PENDING.name }, nowEpochSecond)
    }

    /** Groups [rows] by observation span and re-validates each group as one unit - the concrete implementation of "reject/accept the entire observation, never guess per-minute." */
    private suspend fun validateGroupsWithinTransaction(rows: List<StepBucketEntity>, nowEpochSecond: Long): Boolean {
        if (rows.isEmpty()) return false
        var anyChanged = false
        for ((_, groupRows) in rows.groupBy { it.observationStartEpochSecond to it.observationEndEpochSecond }) {
            val first = groupRows.first()
            val previousState = ValidationState.valueOf(first.validationState)
            val observation = RawObservation(first.observationStartEpochSecond, first.observationEndEpochSecond, groupRows.sumOf { it.steps })
            val decision = evaluateObservation(observation, previousState, nowEpochSecond)
            if (decision.state.name == first.validationState && decision.rejectionReason?.name == first.rejectionReason) continue
            val updated = groupRows.map { row ->
                row.copy(
                    validationState = decision.state.name,
                    acceptedSteps = if (decision.state == ValidationState.ACCEPTED_WALKING || decision.state == ValidationState.ACCEPTED_RUNNING) row.steps else 0L,
                    rejectionReason = decision.rejectionReason?.name,
                    policyVersion = StrictStepValidationPolicy.POLICY_VERSION,
                    validatedAtEpochSecond = nowEpochSecond,
                )
            }
            database.stepBucketDao().updateAll(updated)
            anyChanged = true
        }
        return anyChanged
    }

    /** The suspend half of [StrictStepValidationPolicy.evaluate] - fetches exactly the evidence that call needs and nothing more. */
    private suspend fun evaluateObservation(observation: RawObservation, previousState: ValidationState, nowEpochSecond: Long): ValidationDecision {
        if (previousState == ValidationState.REJECTED_VEHICLE || previousState == ValidationState.REJECTED_BICYCLE || previousState == ValidationState.LEGACY_UNVERIFIED) {
            return ValidationDecision(previousState, 0L, null)
        }
        val continuity = database.temporalContinuityStateDao().get()
        val currentEpoch = continuity?.temporalContinuityEpoch ?: 0L

        val guardMillis = maxOf(validationConstants.guardBeforeVehicleSeconds, validationConstants.guardAfterVehicleSeconds).toLong() * 1000L
        val afterMillis = observation.startEpochSecond * 1000L - guardMillis
        val beforeMillis = observation.endEpochSecondExclusive * 1000L + guardMillis

        val vehicleIntervals = database.activityIntervalDao()
            .getOverlapping(listOf(MotionActivityType.IN_VEHICLE.name, MotionActivityType.ON_BICYCLE.name), afterMillis, beforeMillis)
            .map { it.toMaterialized() }
        val positiveIntervals = database.activityIntervalDao()
            .getOverlapping(listOf(MotionActivityType.WALKING.name, MotionActivityType.RUNNING.name), afterMillis, beforeMillis)
            .map { it.toMaterialized() }

        val sampledFrom = observation.startEpochSecond * 1000L - validationConstants.reconciliationWindowSeconds * 1000L
        val sampledTo = observation.endEpochSecondExclusive * 1000L + guardMillis
        val sampledEvidence = database.motionEvidenceDao().getSampledWithin(sampledFrom, sampledTo).map { it.toMotionEvidenceEvent() }

        val decision = StrictStepValidationPolicy.evaluate(
            observation, vehicleIntervals, positiveIntervals, sampledEvidence, currentEpoch, nowEpochSecond, previousState, validationConstants,
        )
        if (decision.state == ValidationState.ACCEPTED_WALKING || decision.state == ValidationState.ACCEPTED_RUNNING) {
            motionDiagnosticsHealthSink.recordSuccessfulValidation(nowEpochSecond)
        }
        return decision
    }

    private fun ActivityIntervalEntity.toMaterialized() = MaterializedInterval(
        MotionActivityType.valueOf(activityType), startWallClockEpochMilli, endWallClockEpochMilli, temporalContinuityEpoch,
    )

    private fun MotionEvidenceEntity.toMotionEvidenceEvent() = MotionEvidenceEvent(
        kind = MotionEvidenceKind.valueOf(kind),
        activityType = MotionActivityType.valueOf(activityType),
        confidence = confidence,
        wallClockEpochMilli = derivedWallClockEpochMilli,
        temporalContinuityEpoch = temporalContinuityEpoch,
        batchId = batchId,
    )

    /** One [IntervalReconstructor] interrupt-group's configuration - see that object's own doc comment. */
    private enum class Group(val trackedTypes: Set<MotionActivityType>, val interrupterTypes: Set<MotionActivityType>, val failClosedIfLate: Boolean) {
        VEHICLE(setOf(MotionActivityType.IN_VEHICLE, MotionActivityType.ON_BICYCLE), emptySet(), false),
        POSITIVE(
            setOf(MotionActivityType.WALKING, MotionActivityType.RUNNING),
            setOf(MotionActivityType.STILL, MotionActivityType.IN_VEHICLE, MotionActivityType.ON_BICYCLE),
            true,
        ),
    }

    private fun groupsFor(activityType: MotionActivityType): List<Group> = when (activityType) {
        MotionActivityType.WALKING, MotionActivityType.RUNNING, MotionActivityType.STILL -> listOf(Group.POSITIVE)
        MotionActivityType.IN_VEHICLE, MotionActivityType.ON_BICYCLE -> listOf(Group.VEHICLE, Group.POSITIVE)
        MotionActivityType.ON_FOOT, MotionActivityType.TILTING, MotionActivityType.UNKNOWN -> emptyList()
    }

    /**
     * The exact wall-clock range one [IntervalReconstructor] mutation could have affected - the
     * union, across every close/insert it produced, of each touched interval's own true
     * `[start, end]` (an insert with no resolved end yet extends to "now", the furthest any
     * already-imported bucket could be). This - never a fixed radius around the triggering event's
     * own timestamp - is what [revalidateSpanWithinTransactionLocked] revalidates against, so a
     * multi-hour ride reconstructed from a delayed `ENTER`/`EXIT` pair gets every bucket in the
     * middle re-checked, not just the buckets near either endpoint.
     */
    private data class AffectedSpan(val startEpochSecond: Long, val endEpochSecond: Long)

    private fun mergeAffectedSpans(a: AffectedSpan?, b: AffectedSpan?): AffectedSpan? = when {
        a == null -> b
        b == null -> a
        else -> AffectedSpan(minOf(a.startEpochSecond, b.startEpochSecond), maxOf(a.endEpochSecond, b.endEpochSecond))
    }

    private fun IntervalMutationSet.affectedSpan(preMutationOpenByType: Map<MotionActivityType, PersistedInterval>, nowEpochSecond: Long): AffectedSpan? {
        if (closes.isEmpty() && inserts.isEmpty()) return null
        val originalById = preMutationOpenByType.values.associateBy { it.id }
        var span: AffectedSpan? = null
        for (close in closes) {
            // The row being closed was, by construction, present in preMutationOpenByType (that is
            // where its id came from) - its own persisted start is the true beginning of the
            // affected range, however long ago that was.
            val startMillis = originalById[close.intervalId]?.startWallClockEpochMilli ?: close.endWallClockEpochMilli
            span = mergeAffectedSpans(span, AffectedSpan(startMillis / 1000, close.endWallClockEpochMilli / 1000))
        }
        for (insert in inserts) {
            val endMillis = insert.endWallClockEpochMilli ?: (nowEpochSecond * 1000)
            span = mergeAffectedSpans(span, AffectedSpan(insert.startWallClockEpochMilli / 1000, endMillis / 1000))
        }
        return span
    }

    // ---- MotionEvidenceIngestor ----

    override suspend fun ingestTransitionEvents(events: List<ConvertedTransitionEvent>) {
        if (events.isEmpty()) return
        syncMutex.withLock {
            for (event in events) ingestOneTransitionEventLocked(event)
        }
    }

    /**
     * Evidence insertion, interval reconstruction, affected-bucket revalidation, and the
     * classifier's derived walk-bout replacement all commit as ONE Room transaction - see
     * [revalidateSpanWithinTransactionLocked]'s own doc comment for why. A process death (or any
     * thrown exception) anywhere in here rolls back everything together: it can never leave
     * confirmed vehicle evidence durably committed while the steps it should have revoked remain
     * `ACCEPTED_*`. [rescheduleFinalizationJob]/[pendingBucketFinalizationScheduler] are the only
     * things deliberately left outside the transaction - both are in-memory/WorkManager scheduling
     * side effects that must only ever run once the transaction has actually committed (see
     * [recomputeClassificationWithinTransaction]'s own doc comment).
     */
    private suspend fun ingestOneTransitionEventLocked(event: ConvertedTransitionEvent) {
        val now = clock.instant().epochSecond
        var recomputeResult: ClassificationRecomputeResult? = null
        var anyValidationChanged = false
        database.withTransaction {
            val epoch = ensureEpochCurrentLocked(event.bootSessionId, event.bootEpochOffsetMillis)
            val insertedId = database.motionEvidenceDao().insertOneIgnoringDuplicate(
                MotionEvidenceEntity(
                    kind = if (event.isEnter) MotionEvidenceKind.TRANSITION_ENTER.name else MotionEvidenceKind.TRANSITION_EXIT.name,
                    activityType = event.activityType.name,
                    confidence = null,
                    eventElapsedRealtimeMillis = event.eventElapsedRealtimeMillis,
                    bootSessionId = event.bootSessionId,
                    derivedWallClockEpochMilli = event.derivedWallClockEpochMilli,
                    temporalContinuityEpoch = epoch,
                    receivedAtEpochMilli = event.receivedAtEpochMilli,
                    dedupeKey = event.dedupeKey,
                    batchId = null,
                ),
            )
            if (insertedId == -1L) return@withTransaction // true duplicate replay - no further side effects (idempotency)

            var affectedSpan: AffectedSpan? = null
            for (group in groupsFor(event.activityType)) {
                val signal = ReconciliationSignal.Transition(event.activityType, event.isEnter, event.derivedWallClockEpochMilli, epoch)
                affectedSpan = mergeAffectedSpans(affectedSpan, applyReconciliationSignalLocked(group, signal, now))
            }
            if (affectedSpan != null) {
                anyValidationChanged = revalidateSpanWithinTransactionLocked(affectedSpan, now)
                if (anyValidationChanged) recomputeResult = recomputeClassificationWithinTransaction()
            }
        }
        recomputeResult?.let { rescheduleFinalizationJob(it.minuteBuckets, it.thresholds, it.computedAtEpochSecond) }
        if (anyValidationChanged) pendingBucketFinalizationScheduler?.rescheduleForEarliestPendingDeadline()
    }

    /** Same atomicity/affected-span reasoning as [ingestOneTransitionEventLocked] - see that function's own doc comment. */
    override suspend fun ingestSampledBatch(batch: ConvertedSampledBatch) {
        syncMutex.withLock {
            val now = clock.instant().epochSecond
            var recomputeResult: ClassificationRecomputeResult? = null
            var anyValidationChanged = false
            database.withTransaction {
                val epoch = ensureEpochCurrentLocked(batch.bootSessionId, batch.bootEpochOffsetMillis)
                var anyInserted = false
                for (activity in batch.activities) {
                    val id = database.motionEvidenceDao().insertOneIgnoringDuplicate(
                        MotionEvidenceEntity(
                            kind = MotionEvidenceKind.SAMPLED.name,
                            activityType = activity.activityType.name,
                            confidence = activity.confidence,
                            eventElapsedRealtimeMillis = batch.eventElapsedRealtimeMillis,
                            bootSessionId = batch.bootSessionId,
                            derivedWallClockEpochMilli = batch.derivedWallClockEpochMilli,
                            temporalContinuityEpoch = epoch,
                            receivedAtEpochMilli = batch.receivedAtEpochMilli,
                            dedupeKey = "${batch.batchId}:${activity.activityType.name}",
                            batchId = batch.batchId,
                        ),
                    )
                    if (id != -1L) anyInserted = true
                }
                if (!anyInserted) return@withTransaction // a full replay of an already-seen batch - idempotent no-op

                // The batch's own top activity, computed exactly once for the whole batch - never
                // once per flattened DetectedActivity row (see the product requirement to persist
                // and process one ActivityRecognitionResult as one atomic unit).
                val topActivity = batch.activities.maxByOrNull { it.confidence }?.activityType
                var affectedSpan: AffectedSpan? = null
                if (topActivity != null && topActivity != MotionActivityType.WALKING && topActivity != MotionActivityType.RUNNING) {
                    // Sampled evidence can only ever CLOSE an open positive interval, never open one
                    // (that would bypass the >=2-consecutive-sample rule) - see IntervalReconstructor's
                    // own doc comment. Never offered to the vehicle group either.
                    val signal = ReconciliationSignal.SampledInterrupt(batch.derivedWallClockEpochMilli, epoch)
                    affectedSpan = applyReconciliationSignalLocked(Group.POSITIVE, signal, now)
                }

                // Sampled evidence is ALSO direct StrictStepValidationPolicy input, entirely
                // independent of whatever IntervalReconstructor did (or didn't) mutate above -
                // evaluateObservation reads raw sampled MotionEvidenceEvent rows straight from the
                // database for both the sampled-vehicle-veto rule (confidence>=50 OR top-of-batch)
                // and the >=2-consecutive-sample positive-run rule, never through activity_intervals
                // at all. A batch can therefore change a validation decision with ZERO interval
                // mutation: a qualifying IN_VEHICLE/ON_BICYCLE confidence with no currently-open
                // positive interval to interrupt (affectedSpan stays null above), or a co-present
                // qualifying vehicle signal ranked below a top WALKING/RUNNING (which never even
                // builds a SampledInterrupt, so affectedSpan is never touched at all). The
                // revalidation reach must therefore always include this batch's own point in time,
                // never only whatever IntervalReconstructor happened to touch - a one-second span
                // around the sample's own derived event second, merged with the interval-mutation
                // span when one exists.
                val eventSecond = batch.derivedWallClockEpochMilli / 1000L
                val directEvidenceSpan = AffectedSpan(eventSecond, eventSecond + 1)
                val revalidationSpan = affectedSpan?.let { mergeAffectedSpans(it, directEvidenceSpan) } ?: directEvidenceSpan

                anyValidationChanged = revalidateSpanWithinTransactionLocked(revalidationSpan, now)
                if (anyValidationChanged) recomputeResult = recomputeClassificationWithinTransaction()
            }
            recomputeResult?.let { rescheduleFinalizationJob(it.minuteBuckets, it.thresholds, it.computedAtEpochSecond) }
            if (anyValidationChanged) pendingBucketFinalizationScheduler?.rescheduleForEarliestPendingDeadline()
        }
    }

    override suspend fun handleTemporalDiscontinuity(newBootSessionId: Long) {
        syncMutex.withLock {
            database.withTransaction {
                val stored = database.temporalContinuityStateDao().get()
                if (stored == null || newBootSessionId > stored.bootSessionId) {
                    val newEpoch = (stored?.temporalContinuityEpoch ?: 0L) + 1
                    database.activityIntervalDao().forceCloseAllOpen(clock.instant().toEpochMilli())
                    database.temporalContinuityStateDao().upsert(
                        TemporalContinuityStateEntity(0, newBootSessionId, stored?.bootEpochOffsetMillis ?: 0L, newEpoch),
                    )
                }
            }
        }
    }

    /** Detects a reboot (`bootSessionId` increased) or a mid-boot clock discontinuity (the wall-clock/elapsed-realtime offset shifted beyond tolerance) and force-closes every open interval when either occurs - see `temporal_continuity_state`'s own doc comment. Returns the epoch new rows should be stamped with. Must run inside an already-open transaction. */
    private suspend fun ensureEpochCurrentLocked(bootSessionId: Long, bootEpochOffsetMillis: Long): Long {
        val stored = database.temporalContinuityStateDao().get()
        if (stored == null) {
            database.temporalContinuityStateDao().upsert(TemporalContinuityStateEntity(0, bootSessionId, bootEpochOffsetMillis, 1))
            return 1
        }
        val rebooted = bootSessionId > stored.bootSessionId
        val clockJumped = !rebooted &&
            kotlin.math.abs(bootEpochOffsetMillis - stored.bootEpochOffsetMillis) > validationConstants.clockDiscontinuityToleranceMillis
        if (rebooted || clockJumped) {
            val newEpoch = stored.temporalContinuityEpoch + 1
            database.activityIntervalDao().forceCloseAllOpen(clock.instant().toEpochMilli())
            database.temporalContinuityStateDao().upsert(TemporalContinuityStateEntity(0, bootSessionId, bootEpochOffsetMillis, newEpoch))
            return newEpoch
        }
        if (bootSessionId != stored.bootSessionId || bootEpochOffsetMillis != stored.bootEpochOffsetMillis) {
            database.temporalContinuityStateDao().upsert(stored.copy(bootSessionId = bootSessionId, bootEpochOffsetMillis = bootEpochOffsetMillis))
        }
        return stored.temporalContinuityEpoch
    }

    /**
     * Applies one signal against [group]'s already-persisted interval state - seeded from the
     * unbounded open-row lookup (never a time-bounded query, see [IntervalReconstructor]'s own doc
     * comment), reconciled against nearby signals for short-range reordering, widened once if the
     * signal turns out to be unusually late relative to what's already known for this group. Must
     * run inside an already-open transaction. Returns the exact [AffectedSpan] the mutation touched
     * (or null if nothing was mutated), for [revalidateSpanWithinTransactionLocked] to use.
     */
    private suspend fun applyReconciliationSignalLocked(group: Group, signal: ReconciliationSignal, nowEpochSecond: Long): AffectedSpan? {
        val openRows = database.activityIntervalDao().getOpen(group.trackedTypes.map { it.name })
        val currentOpenByType = openRows.associate {
            MotionActivityType.valueOf(it.activityType) to PersistedInterval(
                it.id, MotionActivityType.valueOf(it.activityType), it.startWallClockEpochMilli, it.endWallClockEpochMilli, it.temporalContinuityEpoch, it.closedReason,
            )
        }

        val windowMillis = validationConstants.reconciliationWindowSeconds * 1000L
        var nearby = fetchNearbySignals(group, signal.wallClockEpochMilli - windowMillis, signal.wallClockEpochMilli + windowMillis, signal)
        // Compares against the group's TRUE last-known signal (an unbounded MAX query), never a
        // value drawn from the very narrow window this check exists to widen past - that was the
        // bug: nearby's own max can, by construction, never exceed signal+windowMillis, so comparing
        // against it made this branch unreachable regardless of how late a signal actually was.
        val lastKnownMillis = latestKnownSignalMillis(group)
        if (lastKnownMillis != null && kotlin.math.abs(lastKnownMillis - signal.wallClockEpochMilli) > windowMillis) {
            val from = minOf(signal.wallClockEpochMilli, lastKnownMillis)
            val to = maxOf(signal.wallClockEpochMilli, lastKnownMillis) + 1000
            nearby = fetchNearbySignals(group, from, to, signal)
        }

        val mutationSet = IntervalReconstructor.applySignal(
            group.trackedTypes, group.interrupterTypes, signal, currentOpenByType, nearby, group.failClosedIfLate, validationConstants,
        )
        applyMutationSetLocked(mutationSet)
        return mutationSet.affectedSpan(currentOpenByType, nowEpochSecond)
    }

    /** The group's true most-recent known signal timestamp - an unbounded query, see [applyReconciliationSignalLocked]'s own doc comment for why this must never be derived from a bounded window. */
    private suspend fun latestKnownSignalMillis(group: Group): Long? {
        val relevantTypes = (group.trackedTypes + group.interrupterTypes).map { it.name }
        val latestTransition = database.motionEvidenceDao().getLatestTransitionTimestamp(relevantTypes)
        val latestSampled = if (group == Group.POSITIVE) database.motionEvidenceDao().getLatestSampledTimestamp() else null
        return listOfNotNull(latestTransition, latestSampled).maxOrNull()
    }

    private suspend fun fetchNearbySignals(group: Group, fromMillis: Long, toMillis: Long, excludeSelf: ReconciliationSignal): List<ReconciliationSignal> {
        val relevantTypes = (group.trackedTypes + group.interrupterTypes).map { it.name }
        val transitions: List<ReconciliationSignal> = database.motionEvidenceDao()
            .getTransitionsWithin(relevantTypes, fromMillis, toMillis)
            .map {
                ReconciliationSignal.Transition(
                    MotionActivityType.valueOf(it.activityType), it.kind == MotionEvidenceKind.TRANSITION_ENTER.name,
                    it.derivedWallClockEpochMilli, it.temporalContinuityEpoch,
                )
            }

        val sampledInterrupts: List<ReconciliationSignal> = if (group == Group.POSITIVE) {
            database.motionEvidenceDao().getSampledWithin(fromMillis, toMillis)
                .groupBy { it.batchId }
                .mapNotNull { (_, rows) ->
                    val top = rows.maxByOrNull { it.confidence ?: 0 } ?: return@mapNotNull null
                    val topType = MotionActivityType.valueOf(top.activityType)
                    if (topType == MotionActivityType.WALKING || topType == MotionActivityType.RUNNING) null
                    else ReconciliationSignal.SampledInterrupt(top.derivedWallClockEpochMilli, top.temporalContinuityEpoch)
                }
        } else {
            emptyList()
        }

        return (transitions + sampledInterrupts).filterNot { it == excludeSelf }
    }

    private suspend fun applyMutationSetLocked(mutationSet: IntervalMutationSet) {
        for (close in mutationSet.closes) {
            database.activityIntervalDao().close(close.intervalId, close.endWallClockEpochMilli, close.closedReason)
        }
        for (insert in mutationSet.inserts) {
            database.activityIntervalDao().insert(
                ActivityIntervalEntity(
                    activityType = insert.activityType.name,
                    startWallClockEpochMilli = insert.startWallClockEpochMilli,
                    endWallClockEpochMilli = insert.endWallClockEpochMilli,
                    temporalContinuityEpoch = insert.temporalContinuityEpoch,
                    closedReason = insert.closedReason,
                ),
            )
        }
    }

    /**
     * Re-runs validation for every `PENDING`/`ACCEPTED_WALKING`/`ACCEPTED_RUNNING` bucket whose
     * observation overlaps [span] (guard-expanded by the same before/after margin the vehicle veto
     * itself uses - a bucket just outside the strict interval but within the guard window can also
     * flip). [span] is the *exact* range [applyReconciliationSignalLocked] reported as touched by
     * this mutation - however long the underlying interval actually turned out to be, including a
     * multi-hour ride reconstructed from a single delayed `ENTER`/`EXIT` pair - never a fixed
     * +/-45-minute radius around the triggering event's own point in time. That fixed-radius
     * version could leave the middle of a long ride's buckets untouched by either endpoint's own
     * revalidation call; this cannot, since it always covers the interval's true full extent.
     *
     * This is what makes rule 10 (a delayed vehicle veto revoking an already-accepted bucket) and
     * the analogous "delayed evidence shortens positive coverage" case (see
     * [StrictStepValidationPolicy]'s own state-machine doc comment) both work correctly regardless
     * of how long the affected interval is: any bucket whose accepted status depended on evidence
     * that just changed gets a full fresh [evaluateObservation] call, not just a narrow veto-only
     * check.
     *
     * Must run inside an already-open transaction - callers do not wrap this in its own
     * [androidx.room.withTransaction], unlike the equivalent [finalizeDuePendingBuckets]/
     * [reevaluateForPolicyVersionChange] entry points, specifically so evidence insertion, interval
     * mutation, this revalidation, and the classifier's derived walk-bout replacement all commit as
     * one atomic unit (see [ingestOneTransitionEventLocked]'s own doc comment for why that
     * atomicity matters).
     */
    private suspend fun revalidateSpanWithinTransactionLocked(span: AffectedSpan, nowEpochSecond: Long): Boolean {
        val guard = maxOf(validationConstants.guardBeforeVehicleSeconds, validationConstants.guardAfterVehicleSeconds).toLong()
        val candidates = database.stepBucketDao().getRevalidationCandidates(
            stepSource.id, span.startEpochSecond - guard, span.endEpochSecond + guard,
        )
        if (candidates.isEmpty()) return false
        return validateGroupsWithinTransaction(candidates, nowEpochSecond)
    }

    /**
     * Resolves every `PENDING` bucket whose finalization deadline (`observationEnd +
     * pendingFinalizationDelaySeconds`) has already passed - the durable half of the "2-minute
     * pending delay" (see [PendingBucketFinalizationScheduler]'s own doc comment for why a purely
     * in-process timer alone is insufficient: it does not survive process death, but a bucket must
     * still eventually resolve to `REJECTED_UNVERIFIED` rather than staying `PENDING` forever if no
     * evidence ever arrives - never silently becoming `ACCEPTED` merely because time passed).
     */
    suspend fun finalizeDuePendingBuckets(now: Instant = clock.instant()) = syncMutex.withLock {
        val due = database.stepBucketDao().getDuePending(stepSource.id, now.epochSecond, validationConstants.pendingFinalizationDelaySeconds.toLong())
        if (due.isEmpty()) return@withLock
        var anyChanged = false
        val recomputeResult = database.withTransaction {
            anyChanged = validateGroupsWithinTransaction(due, now.epochSecond)
            if (anyChanged) recomputeClassificationWithinTransaction() else null
        }
        if (recomputeResult != null) {
            rescheduleFinalizationJob(recomputeResult.minuteBuckets, recomputeResult.thresholds, recomputeResult.computedAtEpochSecond)
        }
        pendingBucketFinalizationScheduler?.rescheduleForEarliestPendingDeadline()
    }

    /**
     * The rare, explicit maintenance pass a [StrictStepValidationPolicy.POLICY_VERSION] bump
     * requires - re-evaluates every `PENDING`/`REJECTED_UNVERIFIED` row not already on the current
     * version (mirroring the codebase's own existing [CLASSIFIER_VERSION] precedent). Deliberately
     * never touches `ACCEPTED_*`/`REJECTED_VEHICLE`/`REJECTED_BICYCLE`/`LEGACY_UNVERIFIED` rows -
     * see [StrictStepValidationPolicy]'s own state-machine doc comment for why a policy retune must
     * never be able to silently strip already-verified real steps or un-reject a real vehicle
     * detection.
     */
    suspend fun reevaluateForPolicyVersionChange() = syncMutex.withLock {
        val stale = database.stepBucketDao().getStaleForPolicyVersion(stepSource.id, StrictStepValidationPolicy.POLICY_VERSION)
        if (stale.isEmpty()) return@withLock
        var anyChanged = false
        val recomputeResult = database.withTransaction {
            anyChanged = validateGroupsWithinTransaction(stale, clock.instant().epochSecond)
            if (anyChanged) recomputeClassificationWithinTransaction() else null
        }
        if (recomputeResult != null) {
            rescheduleFinalizationJob(recomputeResult.minuteBuckets, recomputeResult.thresholds, recomputeResult.computedAtEpochSecond)
        }
    }

    // ---- Classification ----

    /**
     * Guarantees the cached classification (and, transitively, the pending finalization timer -
     * see [rescheduleFinalizationJob]) reflect the current local raw data before this call
     * returns. Recomputes straight from the accepted [StepBucketEntity] history already stored in
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
     * The transactional half of classification recompute: reruns the classifier over the full
     * *accepted* raw history - never `PENDING`/`REJECTED_*`/`LEGACY_UNVERIFIED` minutes, see
     * [com.example.stepsplit.data.local.bucket.StepBucketDao.getAllAccepted] - and atomically
     * replaces the AUTO cache (and reconciles override anchors - see [reconcileOverrideAnchors]).
     * Passes the current instant explicitly so the classifier can decide whether the most recent
     * bout is finished yet (see [WalkClassifier]'s own doc comment) without reading a clock itself -
     * it stays a pure function of its inputs.
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
        val buckets = database.stepBucketDao().getAllAccepted()
        val minuteBuckets = buckets.map { MinuteBucket(it.startEpochSecond, it.acceptedSteps) }
        val computedAt = clock.instant().epochSecond
        val classified = WalkClassifier.classify(minuteBuckets, thresholds, computedAt)

        val entities = classified.map { it.toEntity(computedAt) }
        database.withTransaction {
            // Snapshotted before clearAll() below removes it - reconcileOverrideAnchors needs each
            // override's PREVIOUS bout interval to decide whether a new bout is clearly the same
            // walking session.
            val previousBoutsByStart = database.walkBoutDao().getAll().associateBy { it.startEpochSecond }
            database.walkBoutDao().clearAll()
            database.walkBoutDao().insertAll(entities)
            reconcileOverrideAnchors(previousBoutsByStart, classified)
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
     * Manual overrides are keyed by a bout's [SessionOverrideEntity.boutStartEpochSecond] anchor,
     * but a classifier rerun can shift what that anchor actually represents for the very same
     * walking session - e.g. an earlier active minute extends it backward, or a corrected/removed
     * minute shortens it - or can even leave an override's anchor numerically unchanged while the
     * bout at that anchor is no longer the same session at all (a split whose first fragment keeps
     * the original start, or a merge whose combined bout keeps the first original bout's start).
     * *Every* override is reconsidered here - never fast-pathed as "fine" purely because its exact
     * anchor still exists in [newBouts] - specifically because that coincidence is exactly what the
     * two bugs above hinge on.
     *
     * For every override, this looks for a single newly computed bout that overlaps the override's
     * PREVIOUS bout interval (from [previousBoutsByStart], snapshotted before the replace above) by
     * a strong majority in both directions (see [isStrongOneToOneOverlap]) - i.e. clearly the same
     * walking session, not a coincidental adjacency:
     * - If that single match is the override's own current anchor, it is *self-consistent* - left
     *   untouched, and its anchor is reserved: no other override may reattach there, no matter what
     *   the claim-count arithmetic below would otherwise suggest, since this override isn't going
     *   anywhere.
     * - If that single match is a *different* anchor, and no other override independently resolved
     *   the same target (a claim-count of exactly one) and that target isn't reserved by a
     *   self-consistent override, it is a genuine, unambiguous reattachment: the override is moved
     *   (old row deleted, new one inserted at the new anchor).
     * - Otherwise (zero or multiple candidates, or a target that lost the claim-count/reservation
     *   arbitration) the match is ambiguous. If the override's own current anchor still coincides
     *   with a live bout - which the overlap check just proved is NOT the same session anymore, or
     *   which is legitimately owned by a different, self-consistent override - leaving it in place
     *   would silently misapply it, so the row is deleted rather than risking that. If its current
     *   anchor matches no live bout at all, it is genuinely, harmlessly orphaned (can never
     *   accidentally reapply) and is left exactly as it was - preserved, never deleted, simply
     *   inactive (see the README).
     *
     * All deletes (both moves' old anchors and evictions) run before any insert, so a mover's
     * insert can never race against, or be clobbered by (via `OnConflictStrategy.REPLACE`),
     * another row's own delete of that same target key - every target above is unique among movers
     * and never a reserved anchor, so by the time inserts run each target is guaranteed free. This
     * always runs from inside [recomputeClassificationWithinTransaction]'s own transaction, so a
     * concurrent [observeSessions] can never see a transient, half-applied state.
     */
    private suspend fun reconcileOverrideAnchors(
        previousBoutsByStart: Map<Long, WalkBoutEntity>,
        newBouts: List<ClassifiedBout>,
    ) {
        val overrides = database.sessionOverrideDao().getAll()
        if (overrides.isEmpty()) return
        val newStarts = newBouts.map { it.startEpochSecond }.toSet()

        val desiredTarget: Map<SessionOverrideEntity, Long?> = overrides.associateWith { override ->
            val previous = previousBoutsByStart[override.boutStartEpochSecond] ?: return@associateWith null
            newBouts.filter { candidate -> isStrongOneToOneOverlap(previous, candidate) }
                .singleOrNull()
                ?.startEpochSecond
        }

        val selfConsistent = overrides.filter { desiredTarget.getValue(it) == it.boutStartEpochSecond }
        val reservedAnchors = selfConsistent.map { it.boutStartEpochSecond }.toSet()

        val candidateMovers = overrides.filter { override ->
            val target = desiredTarget.getValue(override)
            target != null && target != override.boutStartEpochSecond
        }
        // A target claimed by more than one candidate mover is itself ambiguous (a merge target,
        // or two unrelated overrides both resolving to the same destination) - neither claimant
        // may take it.
        val claimCounts = candidateMovers.groupingBy { desiredTarget.getValue(it) }.eachCount()
        val movers = candidateMovers.filter { override ->
            val target = desiredTarget.getValue(override)
            claimCounts[target] == 1 && target !in reservedAnchors
        }

        val moverSet = movers.toSet()
        val remaining = overrides - moverSet - selfConsistent.toSet()

        for (override in movers) database.sessionOverrideDao().deleteByAnchor(override.boutStartEpochSecond)
        for (override in remaining) {
            if (override.boutStartEpochSecond in newStarts) {
                database.sessionOverrideDao().deleteByAnchor(override.boutStartEpochSecond)
            }
        }
        for (override in movers) {
            val target = desiredTarget.getValue(override)!!
            database.sessionOverrideDao().upsert(override.copy(boutStartEpochSecond = target))
        }
    }

    /**
     * True when [candidate] overlaps [previous] by at least [OVERLAP_MAJORITY_FRACTION] of BOTH
     * intervals' own durations - i.e. neither interval is mostly something else. This is what
     * keeps a split (each fragment covers only a minority of the original) or a merge (the
     * combined bout is mostly *not* either original interval) from ever counting as a match; only
     * a bout that is clearly, substantially the same walking session as before qualifies.
     */
    private fun isStrongOneToOneOverlap(previous: WalkBoutEntity, candidate: ClassifiedBout): Boolean {
        val overlapStart = maxOf(previous.startEpochSecond, candidate.startEpochSecond)
        val overlapEnd = minOf(previous.endEpochSecond, candidate.endEpochSecond)
        val overlapSeconds = overlapEnd - overlapStart
        if (overlapSeconds <= 0) return false
        val previousDuration = previous.endEpochSecond - previous.startEpochSecond
        val candidateDuration = candidate.endEpochSecond - candidate.startEpochSecond
        if (previousDuration <= 0 || candidateDuration <= 0) return false
        return overlapSeconds >= previousDuration * OVERLAP_MAJORITY_FRACTION &&
            overlapSeconds >= candidateDuration * OVERLAP_MAJORITY_FRACTION
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
     * pipeline as a real sync, without touching the configured [stepSource]. Synthetic data is
     * pre-stamped `ACCEPTED_WALKING` directly, **never** routed through
     * [StrictStepValidationPolicy.evaluate] - it exists specifically to exercise the UI without
     * real motion evidence, and under strict validation it would otherwise always end up
     * `PENDING`/`REJECTED_UNVERIFIED` (no real evidence exists for fabricated data), making this
     * debug feature useless. Never invoked from release code paths.
     */
    suspend fun debugImportRawIntervals(sourceId: String, intervals: List<RawStepInterval>) {
        syncMutex.withLock {
            val now = clock.instant()
            val entities = BucketNormalizer
                .normalize(intervals.map { RawInterval(it.startEpochSecond, it.endEpochSecond, it.steps) })
                .map { minute ->
                    val observationEnd = minute.startEpochSecond + 60
                    StepBucketEntity(
                        source = sourceId,
                        startEpochSecond = minute.startEpochSecond,
                        endEpochSecond = observationEnd,
                        steps = minute.steps,
                        zoneId = clock.zone.id,
                        localDate = Instant.ofEpochSecond(minute.startEpochSecond).atZone(clock.zone).toLocalDate().toString(),
                        importedAtEpochSecond = now.epochSecond,
                        validationState = ValidationState.ACCEPTED_WALKING.name,
                        acceptedSteps = minute.steps,
                        rejectionReason = null,
                        policyVersion = StrictStepValidationPolicy.POLICY_VERSION,
                        validatedAtEpochSecond = now.epochSecond,
                        observationStartEpochSecond = minute.startEpochSecond,
                        observationEndEpochSecond = observationEnd,
                    )
                }
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
            val dated = buckets.map { DatedBucket(LocalDate.parse(it.localDate), it.startEpochSecond, it.acceptedSteps) }
            val workoutIntervals = SessionMerger.workoutIntervals(sessions)
            val breakdowns = StepAggregator.aggregateByDate(dated, workoutIntervals)
            dates.associateWith { date -> breakdowns[date] ?: DateStepBreakdown(date, 0L, 0L, 0L) }
        }
    }

    /**
     * Lifetime totals across the real production step source's ENTIRE stored *accepted* history -
     * never date-bounded, never limited by the Local Recording API's retention window
     * ([RETENTION_WINDOW]) or by any UI-visible date range (e.g. History's rolling 7 days). See
     * [com.example.stepsplit.data.local.bucket.StepBucketDao.observeLifetimeAggregate] for why
     * this is safe as a plain unfiltered aggregate query rather than a separately maintained
     * counter. Filtered to [stepSource]'s own id so debug/sample-data sources (imported under
     * their own distinct source id - see [debugImportRawIntervals]) never pollute real lifetime
     * statistics, and to `ACCEPTED_WALKING`/`ACCEPTED_RUNNING` states so pending/rejected/legacy
     * minutes never contribute. Observed as a [Flow] (not a one-shot read) so Stats updates live
     * as new buckets are imported and validated.
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

    /**
     * Lifetime totals still carrying `LEGACY_UNVERIFIED` for the real production source - existing
     * history imported before strict validation existed, permanently preserved but never merged
     * into [observeLifetimeStats]'s verified total. Feeds the Stats tab's own separate "earlier
     * recorded steps" card.
     */
    fun observeLegacyStats(): Flow<LifetimeStepTotals> = database.stepBucketDao().observeLegacyAggregate(stepSource.id)
        .map { LifetimeStepTotals(lifetimeSteps = it.totalSteps, activeDays = it.activeDays, bestDayDate = null, bestDaySteps = 0L) }

    /** Live pending-bucket count for the real production source - see `ValidationStatusBanner`'s "Checking recent steps" state. */
    fun observePendingCount(): Flow<Int> = database.stepBucketDao().observePendingCount(stepSource.id)

    /** Live per-[com.example.stepsplit.domain.validation.ValidationState] row counts for the real production source - debug diagnostics panel only. */
    fun observeValidationStateCounts(): Flow<List<ValidationStateCount>> = database.stepBucketDao().observeValidationStateCounts(stepSource.id)

    /** Debug-only: the most recently received raw motion-evidence rows (any kind) - see [com.example.stepsplit.data.local.motion.MotionEvidenceDao.getRecent]. Never invoked from a production/UI read path outside the debug diagnostics panel. */
    suspend fun debugRecentMotionEvidence(limit: Int = 5): List<MotionEvidenceEntity> = database.motionEvidenceDao().getRecent(limit)

    /**
     * Deletes raw `motion_evidence` rows older than the more conservative of (a) a flat retention
     * window ([ValidationConstants.motionEvidenceRetentionDays], default 7 days - generous relative
     * to the 2-minute finalization delay and any realistic Activity Recognition delivery delay) and
     * (b) whatever the oldest still-`PENDING` bucket's own evidence-lookback window needs, so
     * compaction can never delete evidence a genuinely-pending bucket might still be revalidated
     * against. Never touches `activity_intervals` (permanent, already materialized independently of
     * the raw log - see that table's own doc comment) or `step_buckets` (permanent). Folded into
     * the existing periodic [com.example.stepsplit.sync.StepSyncWorker] run rather than a new job.
     */
    suspend fun compactMotionEvidence(now: Instant = clock.instant()) {
        val flatCutoffMillis = now.minusSeconds(validationConstants.motionEvidenceRetentionDays * 24L * 3600L).toEpochMilli()
        val oldestPendingStart = database.stepBucketDao().oldestPendingObservationStart(stepSource.id)
        val pendingSafeCutoffMillis = oldestPendingStart?.let { (it - validationConstants.reconciliationWindowSeconds) * 1000L }
        val cutoffMillis = if (pendingSafeCutoffMillis != null) minOf(flatCutoffMillis, pendingSafeCutoffMillis) else flatCutoffMillis
        database.motionEvidenceDao().deleteOlderThan(cutoffMillis)
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

        /** See [reconcileOverrideAnchors]/[isStrongOneToOneOverlap]: how much of BOTH the old and new bout interval must overlap for a manual override to be reattached to the new bout. */
        private const val OVERLAP_MAJORITY_FRACTION = 0.5
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
