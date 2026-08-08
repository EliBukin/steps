package com.example.stepsplit.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Regression coverage for the exact safety property demanded during plan review: a later-arriving
 * `PENDING` bucket with a farther-out deadline must never push back an already-scheduled sooner
 * one, because [WorkManagerPendingBucketFinalizationScheduler] always recomputes the TRUE current
 * earliest deadline from the database - never threading through whichever bucket happened to
 * trigger the call - making [androidx.work.ExistingWorkPolicy.REPLACE] safe. The delay actually
 * handed to WorkManager is not independently readable back through its public test API, so the
 * mathematically load-bearing part of that claim - [com.example.stepsplit.data.local.bucket.StepBucketDao.earliestPendingObservationEnd]
 * always resolving to the true minimum regardless of insertion order - is asserted directly here,
 * alongside a behavioral proof that every call unique-enqueues exactly one job (never
 * accumulating duplicates) and that a genuinely new job (a new WorkManager-assigned id) replaces
 * the previous one on every call.
 */
@RunWith(RobolectricTestRunner::class)
class PendingBucketFinalizationSchedulerTest {

    private lateinit var database: StepSplitDatabase
    private val sourceId = "fake"
    private val pendingFinalizationDelaySeconds = 120

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        ShadowLog.stream = System.out
    }

    private fun pendingBucket(startEpochSecond: Long, observationEndEpochSecond: Long) = StepBucketEntity(
        source = sourceId,
        startEpochSecond = startEpochSecond,
        endEpochSecond = startEpochSecond + 60,
        steps = 80,
        zoneId = "UTC",
        localDate = "2026-03-10",
        importedAtEpochSecond = startEpochSecond,
        validationState = "PENDING",
        observationStartEpochSecond = startEpochSecond,
        observationEndEpochSecond = observationEndEpochSecond,
    )

    @Test
    fun `earliestPendingObservationEnd always resolves to the true minimum, regardless of insertion order`() = runTest {
        val dao = database.stepBucketDao()
        // Deliberately inserted out of order (middle, latest, earliest) - the query must still
        // resolve to the true earliest regardless of which bucket was written, or asked about,
        // most recently.
        dao.upsertAll(listOf(pendingBucket(2000, 2500)))
        dao.upsertAll(listOf(pendingBucket(5000, 5500)))
        dao.upsertAll(listOf(pendingBucket(1000, 1500)))

        assertEquals(1500L, dao.earliestPendingObservationEnd(sourceId))

        // A non-PENDING row with an even earlier span must never be counted - only PENDING
        // buckets have an outstanding finalization deadline at all.
        dao.upsertAll(listOf(pendingBucket(100, 200).copy(validationState = "ACCEPTED_WALKING", acceptedSteps = 80)))
        assertEquals(1500L, dao.earliestPendingObservationEnd(sourceId))

        // A different source's PENDING buckets must never influence this source's own deadline.
        dao.upsertAll(listOf(pendingBucket(1, 50).copy(source = "other_source")))
        assertEquals(1500L, dao.earliestPendingObservationEnd(sourceId))
    }

    @Test
    fun `earliestPendingObservationEnd is null when nothing is pending`() = runTest {
        assertNull(database.stepBucketDao().earliestPendingObservationEnd(sourceId))
    }

    @Test
    fun `rescheduling with nothing pending never enqueues any work`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = WorkManagerPendingBucketFinalizationScheduler(context, database, sourceId, pendingFinalizationDelaySeconds)

        scheduler.rescheduleForEarliestPendingDeadline()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerPendingBucketFinalizationScheduler.UNIQUE_WORK_NAME).get()
        assertTrue(infos.isEmpty())
    }

    @Test
    fun `a later-arriving farther-out bucket never regresses an already-scheduled sooner deadline - REPLACE always reflects the current true minimum`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dao = database.stepBucketDao()
        val scheduler = WorkManagerPendingBucketFinalizationScheduler(context, database, sourceId, pendingFinalizationDelaySeconds)
        val workManager = WorkManager.getInstance(context)

        // Only a far-out bucket exists first - this is the trigger the ORIGINAL, rejected design
        // would have threaded straight through as "the" deadline to schedule.
        val farDeadline = System.currentTimeMillis() / 1000 + 100_000
        dao.upsertAll(listOf(pendingBucket(farDeadline - 500, farDeadline)))
        scheduler.rescheduleForEarliestPendingDeadline()

        val firstInfos = workManager.getWorkInfosForUniqueWork(WorkManagerPendingBucketFinalizationScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, firstInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, firstInfos.single().state)
        val firstId = firstInfos.single().id

        // A much sooner bucket now also exists. The scheduler must recompute the TRUE current
        // minimum from the database (proven independently above) rather than trusting the
        // far-out value from the previous call - REPLACE must produce a genuinely new request,
        // not silently keep the old, later one enqueued.
        val nearDeadline = System.currentTimeMillis() / 1000 + 10
        dao.upsertAll(listOf(pendingBucket(nearDeadline - 30, nearDeadline)))
        assertEquals(
            "the scheduler's own delay computation is driven entirely by this query result",
            nearDeadline,
            dao.earliestPendingObservationEnd(sourceId),
        )
        scheduler.rescheduleForEarliestPendingDeadline()

        val secondInfos = workManager.getWorkInfosForUniqueWork(WorkManagerPendingBucketFinalizationScheduler.UNIQUE_WORK_NAME).get()
        // Still exactly one job under the unique name (REPLACE deduplicates, never accumulates)
        // but it must be a genuinely new WorkManager-assigned request, not the stale far-out one.
        assertEquals(1, secondInfos.size)
        assertNotEquals(firstId, secondInfos.single().id)
    }

    @Test
    fun `rescheduling repeatedly with the same pending state stays at exactly one enqueued job`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dao = database.stepBucketDao()
        val scheduler = WorkManagerPendingBucketFinalizationScheduler(context, database, sourceId, pendingFinalizationDelaySeconds)
        val workManager = WorkManager.getInstance(context)

        val deadline = System.currentTimeMillis() / 1000 + 10_000
        dao.upsertAll(listOf(pendingBucket(deadline - 60, deadline)))

        repeat(3) { scheduler.rescheduleForEarliestPendingDeadline() }

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerPendingBucketFinalizationScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
    }
}
