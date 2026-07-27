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
        stepSource.ensureSubscribed()

        return try {
            val now = clock.instant()
            val zone = clock.zone
            val latestBucketEnd = database.stepBucketDao().latestBucketEnd(stepSource.id)
            val windowStart = computeWindowStart(latestBucketEnd, now)

            val rawIntervals = stepSource.readSteps(windowStart, now)
            val bucketEntities = normalizeToEntities(rawIntervals, stepSource.id, zone, now)

            database.withTransaction {
                database.stepBucketDao().upsertAll(bucketEntities)
            }

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

    private fun normalizeToEntities(
        raw: List<RawStepInterval>,
        source: String,
        zone: ZoneId,
        importedAt: Instant,
    ) = BucketNormalizer
        .normalize(raw.map { RawInterval(it.startEpochSecond, it.endEpochSecond, it.steps) })
        .map { minute ->
            val localDate = Instant.ofEpochSecond(minute.startEpochSecond).atZone(zone).toLocalDate()
            StepBucketEntity(
                source = source,
                startEpochSecond = minute.startEpochSecond,
                endEpochSecond = minute.startEpochSecond + 60,
                steps = minute.steps,
                zoneId = zone.id,
                localDate = localDate.toString(),
                importedAtEpochSecond = importedAt.epochSecond,
            )
        }

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

    // ---- Manual "Start walk / Finish walk" flow ----

    suspend fun startManualWalk(): Boolean {
        if (database.manualWalkDao().getOngoing() != null) return false
        val now = clock.instant().epochSecond
        database.manualWalkDao().insert(
            ManualWalkEntity(startEpochSecond = now, endEpochSecond = null, steps = null, createdAtEpochSecond = now),
        )
        return true
    }

    suspend fun finishManualWalk(): Boolean = syncMutex.withLock {
        val ongoing = database.manualWalkDao().getOngoing() ?: return@withLock false
        syncNowLocked()
        val end = clock.instant().epochSecond
        val steps = database.stepBucketDao().getAllActive()
            .filter { it.startEpochSecond >= ongoing.startEpochSecond && it.startEpochSecond < end }
            .sumOf { it.steps }
        database.manualWalkDao().update(ongoing.copy(endEpochSecond = end, steps = steps))
        true
    }

    fun observeOngoingManualWalk(): Flow<ManualWalkEntity?> = database.manualWalkDao().observeOngoing()

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
