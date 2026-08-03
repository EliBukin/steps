package com.example.stepsplit.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bout.WalkBoutEntity
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.classification.CLASSIFIER_VERSION
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.SyncFailureCategory
import com.example.stepsplit.domain.model.WalkSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A [Clock] whose [instant] and [currentZone] can each change mid-test, standing in for a live device timezone change while the process (and this same Clock instance) stays alive. */
private class MutableClock(var instant: Instant, var currentZone: ZoneId) : Clock() {
    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = currentZone
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
}

/**
 * A [Clock] whose [instant] is derived directly from [scheduler]'s own virtual time, rather than
 * a separately tracked field a test has to remember to bump in lockstep. Advancing the test
 * scheduler (`advanceTimeBy`, `runCurrent`, ...) therefore advances this clock automatically and
 * atomically with the coroutines it drives - there is no way for the two to drift out of sync,
 * unlike [MutableClock], which a scheduled continuation could observe mid-update if a test forgot
 * (or raced) a manual bump.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class TestSchedulerClock(
    private val scheduler: TestCoroutineScheduler,
    private val base: Instant,
    private val zoneId: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = TestSchedulerClock(scheduler, base, zone)
    override fun instant(): Instant = base.plusMillis(scheduler.currentTime)
}

/**
 * A [Clock] that throws on its very first [instant] call and behaves like [TestSchedulerClock] on
 * every call after that. Used only to prove
 * [StepRepository.ensureClassificationFreshLocked] leaves its recovery flag unset when the first
 * recomputation attempt fails, so a later call retries recovery rather than treating a failed
 * attempt as done - a plain test-side fake `Clock`, not a production hook.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class ThrowOnceClock(
    private val scheduler: TestCoroutineScheduler,
    private val base: Instant,
) : Clock() {
    private var calls = 0
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant {
        calls++
        if (calls == 1) throw RuntimeException("simulated recovery failure")
        return base.plusMillis(scheduler.currentTime)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StepRepositoryTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var fakeSource: FakeStepSource
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var repository: StepRepository
    private val fixedNow = Instant.parse("2026-03-10T20:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    /** One hour before "now" - safely inside the sync read window used by every test below. */
    private val baseEpoch = fixedNow.epochSecond - 3600

    /**
     * A throwaway [CoroutineScope] for the shared [repository] below, backed by a
     * [StandardTestDispatcher] that nothing in this file ever advances - so any trailing-bout
     * finalization timer it schedules (see StepRepository.rescheduleFinalizationJob) simply never
     * fires. Fine for every test here that isn't specifically about that timer; tests that are
     * construct their own repository against `backgroundScope` (see runTest's own TestScope)
     * instead, so its virtual clock is actually driven by that test's advanceTimeBy/advanceUntilIdle.
     */
    private val noOpTimerScope: CoroutineScope = CoroutineScope(StandardTestDispatcher())

    /** Iteration budget for [settle]/[awaitFinalizedSessions] below - see their doc comments. */
    private val SETTLE_ITERATIONS = 50

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        fakeSource = FakeStepSource()
        settingsRepository = SettingsRepository(context)
        repository = StepRepository(database, fakeSource, settingsRepository, clock, noOpTimerScope)
    }

    @After
    fun tearDown() = runTest {
        // Preferences DataStore persists to a real file, and Robolectric does not guarantee a
        // fresh one per test method - without this, a threshold change made by one test (e.g.
        // "changing thresholds...") can leak into a sibling test that assumes defaults.
        settingsRepository.resetThresholds()
        settingsRepository.clearSyncFailure()
    }

    @Test
    fun `importing the same interval twice does not duplicate steps`() = runTest {
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)

        repository.syncNow()
        val firstCount = database.stepBucketDao().count()
        repository.syncNow()
        val secondCount = database.stepBucketDao().count()

        assertEquals(firstCount, secondCount)
        assertEquals(50L, database.stepBucketDao().getAllActive().sumOf { it.steps })
    }

    @Test
    fun `a later upsert for an already-stored minute replaces rather than adds to it`() = runTest {
        val dao = database.stepBucketDao()
        val bucket = StepBucketEntity(
            source = "local_recording_api",
            startEpochSecond = baseEpoch,
            endEpochSecond = baseEpoch + 60,
            steps = 50,
            zoneId = "UTC",
            localDate = "2026-03-10",
            importedAtEpochSecond = baseEpoch,
        )

        dao.upsertAll(listOf(bucket))
        // A later read reconciles the same minute with a corrected value (e.g. a late-arriving
        // sensor reading), not an additional value to sum on top.
        dao.upsertAll(listOf(bucket.copy(steps = 999)))

        val stored = dao.getAllActive()
        assertEquals(1, stored.size)
        assertEquals(999L, stored.single().steps)
    }

    @Test
    fun `an unexpected failure while reading steps is reported, not thrown`() = runTest {
        val failingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<com.example.stepsplit.data.stepsource.RawStepInterval> {
                throw IllegalStateException("simulated transient failure")
            }
        }
        val failingRepository = StepRepository(database, failingSource, settingsRepository, clock, noOpTimerScope)

        val result = failingRepository.syncNow()

        assertTrue(result is SyncResult.Failed)
        assertEquals(0, database.stepBucketDao().count())
    }

    @Test
    fun `a failed read after data already exists leaves the existing buckets untouched`() = runTest {
        // A successful sync first - this is the data a failed read must never wipe out via
        // reconciliation deletion (see LocalRecordingStepSource.toRawIntervalsOrThrow).
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)
        assertTrue(repository.syncNow() is SyncResult.Success)
        assertEquals(50L, database.stepBucketDao().getAllActive().sumOf { it.steps })
        val syncTimeAfterSuccess = settingsRepository.settings.first().lastSuccessfulSync

        val failingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<com.example.stepsplit.data.stepsource.RawStepInterval> {
                throw com.example.stepsplit.data.stepsource.StepSourceReadException("simulated non-success status")
            }
        }
        val laterClock = Clock.fixed(fixedNow.plusSeconds(120), ZoneOffset.UTC)
        val laterRepository = StepRepository(database, failingSource, settingsRepository, laterClock, noOpTimerScope)

        val result = laterRepository.syncNow()

        assertTrue(result is SyncResult.Failed)
        // The reconciliation delete-then-upsert transaction must never have run for this failed
        // read - the previously stored bucket is exactly as it was, not deleted and not replaced.
        assertEquals(50L, database.stepBucketDao().getAllActive().sumOf { it.steps })
        assertEquals(1, database.stepBucketDao().count())
        assertEquals(syncTimeAfterSuccess, settingsRepository.settings.first().lastSuccessfulSync)
    }

    // ---- Classifier cache versioning (CLASSIFIER_VERSION) ----

    /** Builds a stale cached row as if written by an older classifier version, deliberately misclassified so a passing test proves it was actually replaced rather than coincidentally matching. */
    private fun staleWalkBoutRow() = WalkBoutEntity(
        startEpochSecond = baseEpoch,
        endEpochSecond = baseEpoch + 20 * 60L,
        steps = 1600,
        activeMinutes = 20,
        elapsedMinutes = 20,
        cadence = 80.0,
        autoClassification = "INCIDENTAL",
        autoConfidence = 0.9,
        autoReasonCode = "DURATION_TOO_SHORT",
        classifierVersion = 1,
        computedAtEpochSecond = baseEpoch,
    )

    private suspend fun seedRawWorkoutBuckets() {
        for (i in 0 until 20) {
            database.stepBucketDao().upsertAll(
                listOf(
                    StepBucketEntity(
                        source = fakeSource.id,
                        startEpochSecond = baseEpoch + i * 60L,
                        endEpochSecond = baseEpoch + i * 60L + 60,
                        steps = 80,
                        zoneId = "UTC",
                        localDate = "2026-03-10",
                        importedAtEpochSecond = baseEpoch,
                    ),
                ),
            )
        }
    }

    @Test
    fun `observeSessions never returns a row computed by an older classifier version`() = runTest {
        // No sync has run at all - this asserts the read-side filter directly, independent of
        // whether anything has triggered a recompute yet.
        database.walkBoutDao().insertAll(listOf(staleWalkBoutRow()))

        assertTrue(repository.observeSessions().first().isEmpty())
    }

    @Test
    fun `a stale cached classification is recomputed from local raw data when the step source is unavailable`() = runTest {
        seedRawWorkoutBuckets()
        database.walkBoutDao().insertAll(listOf(staleWalkBoutRow()))
        fakeSource.setAvailability(StepSourceAvailability.ApiUnavailable)

        val result = repository.syncNow()

        assertTrue("an unavailable source must not block recovering from a stale cache", result is SyncResult.Unavailable)
        val sessions = repository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
    }

    @Test
    fun `a stale cached classification is recomputed from local raw data when the source read fails`() = runTest {
        seedRawWorkoutBuckets()
        database.walkBoutDao().insertAll(listOf(staleWalkBoutRow()))
        val failingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<com.example.stepsplit.data.stepsource.RawStepInterval> {
                throw com.example.stepsplit.data.stepsource.StepSourceReadException("simulated non-success status")
            }
        }
        val failingRepository = StepRepository(database, failingSource, settingsRepository, clock, noOpTimerScope)

        val result = failingRepository.syncNow()

        assertTrue("a failing read must not block recovering from a stale cache", result is SyncResult.Failed)
        val sessions = failingRepository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
    }

    @Test
    fun `a freshly computed row is stamped with the current classifier version`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)

        repository.syncNow()

        assertEquals(CLASSIFIER_VERSION, database.walkBoutDao().getAll().single().classifierVersion)
    }

    @Test
    fun `an interval roughly nine days old is still recovered on a first-ever sync`() = runTest {
        // The Local Recording API documents a 10-day retention window - a first-ever (or
        // long-overdue) sync must reach back that far, not just 7 days, or it permanently misses
        // data the API still actually has.
        val nineDaysAgo = fixedNow.epochSecond - Duration.ofDays(9).seconds
        fakeSource.addInterval(nineDaysAgo, nineDaysAgo + 60, 40)

        val result = repository.syncNow()

        assertTrue(result is SyncResult.Success)
        assertEquals(40L, database.stepBucketDao().getAllActive().sumOf { it.steps })
    }

    @Test
    fun `syncing without permission reports unavailable and writes nothing`() = runTest {
        fakeSource.setAvailability(StepSourceAvailability.PermissionNotGranted)
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)

        val result = repository.syncNow()

        assertTrue(result is SyncResult.Unavailable)
        assertEquals(0, database.stepBucketDao().count())
    }

    @Test
    fun `manual reclassification overrides the automatic result and survives a rerun`() = runTest {
        // 20 minutes at 80 steps/min: a clear automatic WORKOUT, safely finished before "now".
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()

        val autoSession = repository.observeSessions().first().single()
        assertEquals(BoutClassification.WORKOUT, autoSession.classification)

        repository.reclassify(autoSession.anchorEpochSecond!!, BoutClassification.INCIDENTAL)
        assertEquals(BoutClassification.INCIDENTAL, repository.observeSessions().first().single().classification)

        // Rerunning classification (another sync) must not discard the manual override.
        repository.syncNow()
        assertEquals(BoutClassification.INCIDENTAL, repository.observeSessions().first().single().classification)
    }

    @Test
    fun `daily totals maintain the total equals workout plus incidental invariant`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80) // workout bout
        fakeSource.addInterval(baseEpoch - 1800, baseEpoch - 1800 + 60, 30) // isolated incidental minute

        repository.syncNow()

        val today = fixedNow.atZone(ZoneOffset.UTC).toLocalDate()
        val breakdown = repository.observeDailyBreakdowns(listOf(today)).first().getValue(today)

        assertEquals(breakdown.totalSteps, breakdown.workoutSteps + breakdown.incidentalSteps)
        assertTrue(breakdown.workoutSteps > 0)
        assertTrue(breakdown.incidentalSteps > 0)
    }

    @Test
    fun `a trailing bout counts as incidental until it finalizes, then reclassifies as a workout on a later sync`() = runTest {
        // 20 minutes of brisk walking that would clearly qualify as a workout once finalized.
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        val lastActiveMinuteEnd = baseEpoch + 20 * 60L
        val today = Instant.ofEpochSecond(baseEpoch).atZone(ZoneOffset.UTC).toLocalDate()

        // First sync: only 1 minute after the last active minute - well under the default
        // 3-minute idleFinalizeMinutes, so the bout must not be finalized into a session yet.
        val soonClock = Clock.fixed(Instant.ofEpochSecond(lastActiveMinuteEnd + 60), ZoneOffset.UTC)
        val soonRepo = StepRepository(database, fakeSource, settingsRepository, soonClock, noOpTimerScope)
        soonRepo.syncNow()

        assertTrue("an un-finalized trailing bout must not appear as a session", soonRepo.observeSessions().first().isEmpty())
        val earlyBreakdown = soonRepo.observeDailyBreakdowns(listOf(today)).first().getValue(today)
        assertEquals(1600L, earlyBreakdown.totalSteps)
        assertEquals(0L, earlyBreakdown.workoutSteps)
        assertEquals(1600L, earlyBreakdown.incidentalSteps)

        // A later sync, once the idle-finalize window has actually elapsed - same raw data,
        // nothing new arrived, but the bout now finalizes into a workout session.
        val laterClock = Clock.fixed(Instant.ofEpochSecond(lastActiveMinuteEnd + 3 * 60L), ZoneOffset.UTC)
        val laterRepo = StepRepository(database, fakeSource, settingsRepository, laterClock, noOpTimerScope)
        laterRepo.syncNow()

        val finalizedSession = laterRepo.observeSessions().first().single()
        assertEquals(BoutClassification.WORKOUT, finalizedSession.classification)
        val laterBreakdown = laterRepo.observeDailyBreakdowns(listOf(today)).first().getValue(today)
        assertEquals(1600L, laterBreakdown.totalSteps)
        assertEquals(1600L, laterBreakdown.workoutSteps)
        assertEquals(0L, laterBreakdown.incidentalSteps)
    }

    // ---- Local trailing-bout finalization timer (rescheduleFinalizationJob) ----

    /**
     * Bundles a timer test's per-test-only resources - a dedicated in-memory Room database backed
     * by a single real background thread for BOTH query and transaction work (so
     * [awaitRoomQuiescence]'s "one sentinel task proves every earlier task is done" reasoning
     * actually holds - Room's default executor is an unordered multi-thread pool), and a
     * dedicated [TestCoroutineScheduler] independent of this test's own `runTest` scheduler (see
     * [newScope]'s doc comment for why that independence matters) - together with every
     * [CoroutineScope] created against that scheduler via [newScope]. `use { }` guarantees [close]
     * runs even when an assertion throws mid-test, so no test leaks the executor's thread or
     * leaves the database open.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class TimerTestHarness : AutoCloseable {
        val roomExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        val database: StepSplitDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            StepSplitDatabase::class.java,
        )
            .setQueryExecutor(roomExecutor)
            .setTransactionExecutor(roomExecutor)
            .build()
        val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()

        private val scopes = mutableListOf<CoroutineScope>()

        /**
         * A fresh [CoroutineScope] on [scheduler], tracked here so [close] can cancel it too.
         * [scheduler] is deliberately independent of this test's own `runTest` scheduler - sharing
         * `runTest`'s own scheduler for a [StepRepository]'s finalization-timer scope was tried
         * first and is unsafe: whenever the main test coroutine blocks waiting on Room's real
         * executor thread, `runTest`'s own event loop can respond to the apparent idleness by
         * auto-advancing virtual time to the next scheduled delay - here, the very finalization
         * job under test - so it can start running *while the original, still-in-flight sync call
         * has not returned yet*, corrupting the "not yet finalized" checkpoint the test is trying
         * to observe. This was confirmed empirically: instrumenting `testScheduler.currentTime`
         * immediately after a plain `syncNow()` call (with no `advanceTimeBy` in sight) showed it
         * already sitting at the scheduled job's exact delay. A completely separate scheduler has
         * no `runTest`/`runBlocking` event loop watching it at all, so it only ever advances via
         * this test's own explicit [TestCoroutineScheduler.advanceTimeBy] /
         * [TestCoroutineScheduler.runCurrent] calls.
         */
        fun newScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(scheduler)).also { scopes += it }

        override fun close() {
            scopes.forEach { it.cancel() }
            database.close()
            roomExecutor.shutdown()
        }
    }

    /**
     * The scheduled timer's recompute crosses onto Room's own executor thread, independent of the
     * harness's virtual time - advancing virtual time resolves the `delay()` call itself, but the
     * Room work it then triggers can still be mid-flight on that real thread when
     * [TestCoroutineScheduler.advanceTimeBy]/[TestCoroutineScheduler.runCurrent] return. Because
     * [roomExecutor] is single-threaded (see [TimerTestHarness]) and therefore processes submitted
     * tasks strictly in the order they were submitted, a sentinel task's completion proves every
     * task queued before it - including whichever Room call the timer's coroutine is currently
     * suspended on - has already run and already signalled its continuation. This is a real
     * synchronization barrier tied to an actual completion signal, not a timing guess.
     */
    private fun awaitRoomQuiescence(roomExecutor: ExecutorService) {
        val latch = CountDownLatch(1)
        roomExecutor.execute { latch.countDown() }
        assertTrue("Room's background executor did not quiesce in time", latch.await(5, TimeUnit.SECONDS))
    }

    /**
     * Repeatedly drains whatever [TimerTestHarness.scheduler] has ready (`runCurrent`) and then
     * blocks on [awaitRoomQuiescence] before draining again, so any cross-thread Room round trip a
     * drained continuation just triggered gets a chance to complete and re-enqueue its own
     * continuation before the next `runCurrent`. Used at "must still be withheld" checkpoints,
     * where there is no positive condition to await instead.
     */
    private fun settle(harness: TimerTestHarness) {
        repeat(SETTLE_ITERATIONS) {
            harness.scheduler.runCurrent()
            awaitRoomQuiescence(harness.roomExecutor)
        }
        harness.scheduler.runCurrent()
    }

    /** Like [settle], but stops as soon as [repo] actually shows a finalized session rather than always spending the full iteration budget. */
    private suspend fun awaitFinalizedSessions(repo: StepRepository, harness: TimerTestHarness): List<WalkSession> {
        repeat(SETTLE_ITERATIONS) {
            harness.scheduler.runCurrent()
            awaitRoomQuiescence(harness.roomExecutor)
            val sessions = repo.observeSessions().first()
            if (sessions.isNotEmpty()) return sessions
        }
        return repo.observeSessions().first()
    }

    @Test
    fun `a trailing bout finalizes on its own once idleFinalizeMinutes passes, with no second source read`() = runTest {
        TimerTestHarness().use { harness ->
            for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            val today = Instant.ofEpochSecond(baseEpoch).atZone(ZoneOffset.UTC).toLocalDate()

            var readCount = 0
            val countingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
                override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant) =
                    fakeSource.readSteps(fromInclusive, toExclusive).also { readCount++ }
            }
            // "Now" starts 1 minute after the final active bucket - well under the default
            // 3-minute idleFinalizeMinutes, so this first sync must not finalize a session yet.
            // Deriving the clock straight from the harness scheduler's currentTime means every
            // advanceTimeBy below moves it automatically, with no separate manual bump that could
            // ever drift out of sync.
            val clock = TestSchedulerClock(harness.scheduler, Instant.ofEpochSecond(lastActiveMinuteEnd + 60))
            val timedRepository = StepRepository(harness.database, countingSource, settingsRepository, clock, harness.newScope())

            timedRepository.syncNow()
            assertEquals(1, readCount)
            assertTrue("must not appear as a session yet", timedRepository.observeSessions().first().isEmpty())
            val earlyBreakdown = timedRepository.observeDailyBreakdowns(listOf(today)).first().getValue(today)
            assertEquals(0L, earlyBreakdown.workoutSteps)
            assertEquals(1600L, earlyBreakdown.incidentalSteps)

            // Advance to exactly the idle-finalize deadline and drive the scheduled local timer -
            // the job's own computed delay is 2 minutes (idleFinalizeMinutes=3 minus the 1 minute
            // "now" already sits past the last active minute) - and no second call into syncNow(),
            // so no second source read either.
            harness.scheduler.advanceTimeBy(2 * 60_000L)
            settle(harness)

            val sessions = awaitFinalizedSessions(timedRepository, harness)
            assertEquals("the local timer must never read from the step source", 1, readCount)
            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
            val laterBreakdown = timedRepository.observeDailyBreakdowns(listOf(today)).first().getValue(today)
            assertEquals(1600L, laterBreakdown.workoutSteps)
            assertEquals(0L, laterBreakdown.incidentalSteps)
        }
    }

    @Test
    fun `new activity before the deadline reschedules finalization to reflect the newest data`() = runTest {
        TimerTestHarness().use { harness ->
            for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            val clock = TestSchedulerClock(harness.scheduler, Instant.ofEpochSecond(lastActiveMinuteEnd + 60))
            val timedRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())

            // First sync: schedules a deadline 2 minutes out (idleFinalizeMinutes=3 minus the 1
            // minute "now" already sits past the last active minute), i.e. at relative t=120s.
            timedRepository.syncNow()

            // 60 (virtual) seconds later - still well before that deadline - new activity arrives
            // and a sync picks it up, extending the bout by one more active minute.
            harness.scheduler.advanceTimeBy(60_000)
            settle(harness)
            fakeSource.addInterval(lastActiveMinuteEnd + 60, lastActiveMinuteEnd + 120, 80)
            timedRepository.syncNow()

            // Advance to exactly the ORIGINAL (now-superseded) deadline at relative t=120s - if
            // new activity had not rescheduled it, this is when the bout would have finalized with
            // the old, incomplete data. (WalkClassifier's own idle check is inclusive of this exact
            // boundary, but nothing is scheduled to run at it any more since the timer was already
            // replaced above, so landing exactly on it is still safely withheld.)
            harness.scheduler.advanceTimeBy(60_000)
            settle(harness)
            assertTrue(
                "finalization must wait for the rescheduled deadline, not the original one",
                timedRepository.observeSessions().first().isEmpty(),
            )

            // Advance the rest of the way to the actual (rescheduled) deadline: idleFinalizeMinutes
            // after the newest active minute, i.e. lastActiveMinuteEnd + 120 + 180.
            val target = lastActiveMinuteEnd + 120 + 180
            val remainingSeconds = target - clock.instant().epochSecond
            harness.scheduler.advanceTimeBy(remainingSeconds * 1000)

            val sessions = awaitFinalizedSessions(timedRepository, harness)
            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
            // The original 20 minutes (1600 steps) plus the one extra minute that arrived before
            // the original deadline (80 steps) - proof the newest data, not a stale snapshot, was
            // used.
            assertEquals(1680L, sessions.single().steps)
        }
    }

    @Test
    fun `changing idleFinalizeMinutes reschedules the pending finalization to the new duration`() = runTest {
        TimerTestHarness().use { harness ->
            for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            val clock = TestSchedulerClock(harness.scheduler, Instant.ofEpochSecond(lastActiveMinuteEnd + 60))
            val timedRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())

            // First sync: schedules a deadline using the default 3-minute idleFinalizeMinutes.
            timedRepository.syncNow()

            // Before that deadline, the user relaxes idleFinalizeMinutes to 10 minutes -
            // finalization must now wait longer, not fire on the old 3-minute schedule.
            harness.scheduler.advanceTimeBy(60_000)
            settle(harness)
            val longer = ClassificationThresholds.DEFAULT.copy(idleFinalizeMinutes = 10)
            timedRepository.applyThresholds(longer)

            // Advance to exactly the OLD 3-minute deadline (relative t=120s) - must still be
            // withheld now that idleFinalizeMinutes is 10 minutes and nothing is scheduled to run
            // at this superseded time any more.
            harness.scheduler.advanceTimeBy(60_000)
            settle(harness)
            assertTrue(timedRepository.observeSessions().first().isEmpty())

            // Advance the rest of the way to the actual new deadline: 10 minutes after the last
            // active minute.
            val target = lastActiveMinuteEnd + 10 * 60
            val remainingSeconds = target - clock.instant().epochSecond
            harness.scheduler.advanceTimeBy(remainingSeconds * 1000)

            val sessions = awaitFinalizedSessions(timedRepository, harness)
            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
        }
    }

    // ---- Startup recovery of a pending finalization after simulated process death ----

    @Test
    fun `a pending finalization timer is restored after the repository is recreated, and finalizes without another source read`() = runTest {
        TimerTestHarness().use { harness ->
            for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            val baseInstant = Instant.ofEpochSecond(lastActiveMinuteEnd + 60)
            // Both simulated "processes" below share this one scheduler/clock - virtual time keeps
            // advancing continuously across the simulated restart, exactly like a real device
            // clock that never stops just because the app's process did.
            val clock = TestSchedulerClock(harness.scheduler, baseInstant)

            // The "first process": a real sync schedules a finalization timer 2 minutes out
            // (idleFinalizeMinutes=3 minus the 1 minute "now" already sits past the last active
            // minute), on its own scope.
            val firstRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())
            firstRepository.syncNow()
            assertTrue(firstRepository.observeSessions().first().isEmpty())

            // Simulate the process being killed: its scope (and whatever timer it scheduled) is
            // cancelled by the harness's close() at the end of this test, same as every other
            // scope here - but we don't need to wait until then, since a fresh StepRepository
            // below never touches it.

            // The "second process": a brand-new StepRepository over the SAME Room database, on its
            // own new scope, with the step source now unavailable - modeling the app being
            // reopened with no network/permission state recovered yet.
            var readCount = 0
            val countingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
                override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant) =
                    fakeSource.readSteps(fromInclusive, toExclusive).also { readCount++ }
            }
            fakeSource.setAvailability(StepSourceAvailability.ApiUnavailable)
            val secondRepository = StepRepository(harness.database, countingSource, settingsRepository, clock, harness.newScope())

            val result = secondRepository.syncNow()

            assertTrue("an unavailable source must not block recovering the pending deadline", result is SyncResult.Unavailable)
            assertEquals("recovery must never read from the step source", 0, readCount)
            assertTrue(
                "the deadline has not passed yet - recovery must not finalize early",
                secondRepository.observeSessions().first().isEmpty(),
            )

            // Advance to the ORIGINAL deadline (2 minutes remaining: 3 minutes total minus the 1
            // already elapsed before the simulated restart) - restored by the new process's own
            // recovery, not by anything left over from the killed one.
            harness.scheduler.advanceTimeBy(2 * 60_000L)
            val sessions = awaitFinalizedSessions(secondRepository, harness)

            assertEquals("the restored timer must never read from the step source either", 0, readCount)
            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
            assertEquals(1600L, sessions.single().steps)
        }
    }

    @Test
    fun `recovery reclassifies immediately when the finalization deadline already elapsed while the process was dead`() = runTest {
        TimerTestHarness().use { harness ->
            for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            val baseInstant = Instant.ofEpochSecond(lastActiveMinuteEnd + 60)
            val clock = TestSchedulerClock(harness.scheduler, baseInstant)

            val firstProcessScope = harness.newScope()
            val firstRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, firstProcessScope)
            firstRepository.syncNow()
            firstProcessScope.cancel()

            // Time passes well beyond the original 3-minute deadline while no repository exists at
            // all - modeling a long-dead process, not just a quick restart. Nothing fires during
            // this advance since the only timer that existed was just cancelled above.
            harness.scheduler.advanceTimeBy(10 * 60_000L)

            fakeSource.setAvailability(StepSourceAvailability.ApiUnavailable)
            val secondRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())

            val result = secondRepository.syncNow()

            assertTrue(result is SyncResult.Unavailable)
            // No further time advance needed - the deadline was already in the past the moment
            // recovery ran, so the classifier must finalize it as part of that very recompute.
            val sessions = secondRepository.observeSessions().first()
            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
        }
    }

    @Test
    fun `recovery still restores the pending timer when the source read fails, and the read failure remains correctly reported`() = runTest {
        TimerTestHarness().use { harness ->
            for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            val baseInstant = Instant.ofEpochSecond(lastActiveMinuteEnd + 60)
            val clock = TestSchedulerClock(harness.scheduler, baseInstant)

            val firstRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())
            firstRepository.syncNow()

            var readCount = 0
            val failingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
                override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<com.example.stepsplit.data.stepsource.RawStepInterval> {
                    readCount++
                    throw com.example.stepsplit.data.stepsource.StepSourceReadException("simulated non-success status")
                }
            }
            val secondRepository = StepRepository(harness.database, failingSource, settingsRepository, clock, harness.newScope())

            val result = secondRepository.syncNow()

            assertTrue("a failing read must not block recovering the pending deadline", result is SyncResult.Failed)
            assertEquals(SyncFailureCategory.READ_FAILED, (result as SyncResult.Failed).category)
            assertEquals(1, readCount)
            assertEquals(
                SyncFailureCategory.READ_FAILED,
                settingsRepository.settings.first().lastSyncFailure?.category,
            )
            assertTrue(secondRepository.observeSessions().first().isEmpty())

            harness.scheduler.advanceTimeBy(2 * 60_000L)
            val sessions = awaitFinalizedSessions(secondRepository, harness)

            assertEquals("the restored timer must never read from the step source", 1, readCount)
            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
            // The recorded failure is orthogonal to local classification recovery - the timer's
            // purely-local recompute must not have silently cleared or altered it.
            assertEquals(
                SyncFailureCategory.READ_FAILED,
                settingsRepository.settings.first().lastSyncFailure?.category,
            )
        }
    }

    @Test
    fun `new activity after recovery reschedules the restored finalization to reflect the newest data`() = runTest {
        TimerTestHarness().use { harness ->
            for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            val baseInstant = Instant.ofEpochSecond(lastActiveMinuteEnd + 60)
            val clock = TestSchedulerClock(harness.scheduler, baseInstant)

            val firstRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())
            firstRepository.syncNow()

            // The "second process" recovers the pending deadline (2 minutes remaining), but this
            // time the source is available again, so a normal sync can pick up new activity.
            val secondRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())
            secondRepository.syncNow()

            // Before the restored deadline, one more minute of activity arrives.
            harness.scheduler.advanceTimeBy(30_000)
            settle(harness)
            fakeSource.addInterval(lastActiveMinuteEnd + 60, lastActiveMinuteEnd + 120, 80)
            secondRepository.syncNow()

            // Advance to exactly the ORIGINAL restored deadline (relative t=120s) - must still be
            // withheld, since the new activity must have rescheduled it rather than leaving a
            // duplicate original timer; nothing is scheduled to run at this superseded time any
            // more.
            harness.scheduler.advanceTimeBy(90_000)
            settle(harness)
            assertTrue(
                "finalization must wait for the rescheduled deadline, not the recovered-but-now-stale one",
                secondRepository.observeSessions().first().isEmpty(),
            )

            // Advance the rest of the way to the actual rescheduled deadline: idleFinalizeMinutes
            // after the newest active minute.
            val target = lastActiveMinuteEnd + 120 + 180
            val remainingSeconds = target - clock.instant().epochSecond
            harness.scheduler.advanceTimeBy(remainingSeconds * 1000)
            val sessions = awaitFinalizedSessions(secondRepository, harness)

            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
            assertEquals(1680L, sessions.single().steps)
        }
    }

    // ---- Recovery failure/retry ordering (ensureClassificationFreshLocked) ----

    @Test
    fun `a failure during the first recovery attempt leaves it unmarked, so a later sync retries recovery`() = runTest {
        TimerTestHarness().use { harness ->
            // Raw buckets are seeded directly into Room, bypassing fakeSource/sync entirely - the
            // point is to prove RECOVERY itself (not the normal end-of-sync recompute, which runs
            // unconditionally and would mask a recovery-flag bug) is what schedules the
            // finalization timer, so the source is kept unavailable for every syncNow() call
            // below - a successful sync would call the normal recompute regardless of the flag.
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            for (i in 0 until 20) {
                harness.database.stepBucketDao().upsertAll(
                    listOf(
                        StepBucketEntity(
                            source = fakeSource.id,
                            startEpochSecond = baseEpoch + i * 60L,
                            endEpochSecond = baseEpoch + i * 60L + 60,
                            steps = 80,
                            zoneId = "UTC",
                            localDate = "2026-03-10",
                            importedAtEpochSecond = baseEpoch,
                        ),
                    ),
                )
            }
            fakeSource.setAvailability(StepSourceAvailability.ApiUnavailable)
            val clock = ThrowOnceClock(harness.scheduler, Instant.ofEpochSecond(lastActiveMinuteEnd + 60))
            val repo = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())

            // First sync: recovery's recomputeClassification() throws while reading "now" - the
            // failure must propagate (not be silently absorbed) and must NOT mark recovery done.
            try {
                repo.syncNow()
                fail("expected the simulated recovery failure to propagate")
            } catch (e: RuntimeException) {
                assertEquals("simulated recovery failure", e.message)
            }

            // Second sync: if the first failed attempt had incorrectly marked recovery as done,
            // this would skip straight to the version-only check - which finds nothing stale in
            // the still-empty walk_bouts table - and never schedule a finalization timer at all,
            // so the bout would never finalize no matter how much time passes. A correct retry
            // schedules the timer here instead.
            val result = repo.syncNow()
            assertTrue("still unavailable, but recovery must have retried and succeeded", result is SyncResult.Unavailable)
            assertTrue(repo.observeSessions().first().isEmpty())

            harness.scheduler.advanceTimeBy(2 * 60_000L)
            val sessions = awaitFinalizedSessions(repo, harness)

            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
        }
    }

    @Test
    fun `a leftover manual_walks row from an earlier install is never surfaced or counted`() = runTest {
        // Simulates an installation that upgraded from before the manual-walk feature was
        // removed and still has a real row in the deprecated `manual_walks` table (see
        // StepSplitDatabase's doc comment). There is deliberately no DAO for it anymore, so this
        // writes directly through Room's low-level statement API, the same way an old app version
        // would have left the row behind. Off the test's main thread, same as Room's own suspend
        // DAO methods would be, since compileStatement() is a direct/synchronous call that Room
        // otherwise refuses to run there.
        withContext(Dispatchers.IO) {
            database.compileStatement(
                "INSERT INTO manual_walks " +
                    "(startEpochSecond, endEpochSecond, steps, createdAtEpochSecond, autoCompleted, autoCompletionMessageShown) " +
                    "VALUES ($baseEpoch, ${baseEpoch + 3600}, 5000, $baseEpoch, 0, 0)",
            ).executeInsert()
        }

        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)
        repository.syncNow()

        val sessions = repository.observeSessions().first()
        assertTrue("no session may originate from the deprecated manual_walks table", sessions.none { it.id.startsWith("manual:") })

        val today = fixedNow.atZone(ZoneOffset.UTC).toLocalDate()
        val breakdown = repository.observeDailyBreakdowns(listOf(today)).first().getValue(today)
        // Only the 50 steps actually imported through the sync pipeline are counted - the
        // leftover row's 5000 steps must not leak into the total, workout, or incidental figures.
        assertEquals(50L, breakdown.totalSteps)
    }

    @Test
    fun `buckets around a DST spring-forward transition are attributed to the correct local day`() = runTest {
        // 2026-03-08 02:00 America/New_York springs forward to 03:00 - the local 02:00-03:00 hour
        // does not exist that day. Instant-to-LocalDate conversion is unambiguous regardless, but
        // this pins down the behavior for the exact night it matters.
        val dstZone = java.time.ZoneId.of("America/New_York")
        val nightOf = Instant.parse("2026-03-08T12:00:00Z") // safely after the transition, same UTC day
        val dstClock = Clock.fixed(nightOf, dstZone)
        val dstContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dstDatabase = Room.inMemoryDatabaseBuilder(dstContext, StepSplitDatabase::class.java).build()
        val dstSource = FakeStepSource()
        val dstRepository = StepRepository(dstDatabase, dstSource, SettingsRepository(dstContext), dstClock, noOpTimerScope)

        // One minute at 01:30 local (EST, before the gap) and one at 03:30 local (EDT, right after it).
        val localDay = java.time.LocalDate.of(2026, 3, 8)
        val beforeGap = localDay.atTime(1, 30).atZone(dstZone).toInstant()
        val afterGap = localDay.atTime(3, 30).atZone(dstZone).toInstant()
        dstSource.addInterval(beforeGap.epochSecond, beforeGap.epochSecond + 60, 40)
        dstSource.addInterval(afterGap.epochSecond, afterGap.epochSecond + 60, 40)

        dstRepository.syncNow()

        val expectedLocalDate = beforeGap.atZone(dstZone).toLocalDate()
        val storedDates = dstDatabase.stepBucketDao().getAllActive().map { it.localDate }.distinct()

        assertEquals(listOf(expectedLocalDate.toString()), storedDates)
        assertEquals(80L, dstDatabase.stepBucketDao().getAllActive().sumOf { it.steps })
    }

    @Test
    fun `a minute that disappears from a later read is removed rather than left stale`() = runTest {
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)
        repository.syncNow()
        assertEquals(50L, database.stepBucketDao().getAllActive().sumOf { it.steps })

        // The source corrects itself on the next read: that minute no longer has any steps at
        // all (equivalent to it reporting zero, which the normalizer also treats as absent).
        fakeSource.clearIntervals()
        repository.syncNow()

        assertEquals(0, database.stepBucketDao().count())
    }

    @Test
    fun `the in-progress current minute is not deleted merely for being absent from a read`() = runTest {
        // 30 seconds into a still-in-progress minute - "now" deliberately does not land exactly
        // on a minute boundary, so the read window's exclusive upper bound doesn't itself hide
        // the minute under test.
        val midMinuteNow = fixedNow.plusSeconds(30)
        val midMinuteClock = Clock.fixed(midMinuteNow, ZoneOffset.UTC)
        val midMinuteRepository = StepRepository(database, fakeSource, settingsRepository, midMinuteClock, noOpTimerScope)
        val currentMinuteStart = midMinuteNow.epochSecond - Math.floorMod(midMinuteNow.epochSecond, 60L)

        fakeSource.addInterval(currentMinuteStart, currentMinuteStart + 60, 5)
        midMinuteRepository.syncNow()
        assertEquals(5L, database.stepBucketDao().getAllActive().sumOf { it.steps })

        // A later sync within the same still-in-progress minute reports nothing new for it yet -
        // that must not be treated as proof the minute is actually zero.
        fakeSource.clearIntervals()
        midMinuteRepository.syncNow()

        assertEquals(5L, database.stepBucketDao().getAllActive().sumOf { it.steps })
    }

    @Test
    fun `a subscription failure is reported as a failed sync and does not update the last sync time`() = runTest {
        // Compared against its own before/after value (not an assumed-null starting state) since
        // Preferences DataStore's on-disk file is not guaranteed to be isolated between test
        // methods under Robolectric.
        val before = settingsRepository.settings.first().lastSuccessfulSync
        fakeSource.setSubscribeSucceeds(false)
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)

        val result = repository.syncNow()

        assertTrue(result is SyncResult.Failed)
        assertEquals(0, database.stepBucketDao().count())
        assertEquals(before, settingsRepository.settings.first().lastSuccessfulSync)
    }

    @Test
    fun `a subscription failure records a structured SUBSCRIPTION_FAILED sync failure`() = runTest {
        fakeSource.setSubscribeSucceeds(false)

        val result = repository.syncNow()

        assertTrue(result is SyncResult.Failed)
        assertEquals(SyncFailureCategory.SUBSCRIPTION_FAILED, (result as SyncResult.Failed).category)
        val recorded = settingsRepository.settings.first().lastSyncFailure
        assertEquals(SyncFailureCategory.SUBSCRIPTION_FAILED, recorded?.category)
        assertEquals(fixedNow.epochSecond, recorded?.atEpochSecond)
    }

    @Test
    fun `a read failure records a structured READ_FAILED sync failure, distinct from a subscription failure`() = runTest {
        val failingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<com.example.stepsplit.data.stepsource.RawStepInterval> {
                throw com.example.stepsplit.data.stepsource.StepSourceReadException("simulated non-success status")
            }
        }
        val failingRepository = StepRepository(database, failingSource, settingsRepository, clock, noOpTimerScope)

        val result = failingRepository.syncNow()

        assertTrue(result is SyncResult.Failed)
        assertEquals(SyncFailureCategory.READ_FAILED, (result as SyncResult.Failed).category)
        assertEquals(SyncFailureCategory.READ_FAILED, settingsRepository.settings.first().lastSyncFailure?.category)
    }

    @Test
    fun `a genuinely successful sync clears a previously recorded failure`() = runTest {
        fakeSource.setSubscribeSucceeds(false)
        repository.syncNow()
        assertEquals(SyncFailureCategory.SUBSCRIPTION_FAILED, settingsRepository.settings.first().lastSyncFailure?.category)

        fakeSource.setSubscribeSucceeds(true)
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 20)
        val result = repository.syncNow()

        assertTrue(result is SyncResult.Success)
        assertEquals(null, settingsRepository.settings.first().lastSyncFailure)
    }

    @Test
    fun `an available source with no recorded failure has no lingering sync failure after a successful sync`() = runTest {
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 20)
        assertTrue(repository.syncNow() is SyncResult.Success)

        assertEquals(null, settingsRepository.settings.first().lastSyncFailure)
    }

    @Test
    fun `changing thresholds immediately reclassifies existing sessions without waiting for a sync`() = runTest {
        // 12 minutes at 80 steps per minute is a WORKOUT under the default thresholds
        // (minBoutDurationMinutes = 10).
        for (i in 0 until 12) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()
        assertEquals(BoutClassification.WORKOUT, repository.observeSessions().first().single().classification)

        val stricterThresholds = ClassificationThresholds.DEFAULT.copy(minBoutDurationMinutes = 30)
        val accepted = repository.applyThresholds(stricterThresholds)

        assertTrue(accepted)
        // No repository.syncNow() here - the reclassification must already be reflected.
        assertEquals(BoutClassification.INCIDENTAL, repository.observeSessions().first().single().classification)
    }

    @Test
    fun `an invalid threshold change is rejected and does not touch existing classifications`() = runTest {
        for (i in 0 until 12) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()

        val invalidThresholds = ClassificationThresholds.DEFAULT.copy(minBoutDurationMinutes = -1)
        val accepted = repository.applyThresholds(invalidThresholds)

        assertFalse(accepted)
        assertEquals(BoutClassification.WORKOUT, repository.observeSessions().first().single().classification)
    }

    @Test
    fun `re-importing an existing minute after a timezone change preserves its original zone and local date`() = runTest {
        val zone1 = ZoneId.of("America/New_York")
        val clock1 = Clock.fixed(fixedNow, zone1)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        val source = FakeStepSource()
        val settings = SettingsRepository(context)
        val repo1 = StepRepository(db, source, settings, clock1, noOpTimerScope)

        source.addInterval(baseEpoch, baseEpoch + 60, 50)
        repo1.syncNow()
        val original = db.stepBucketDao().getAllActive().single()
        assertEquals("America/New_York", original.zoneId)

        // Simulate a device timezone change, then a re-sync that re-reads the same overlap
        // window (the source still reports the exact same interval as before).
        val zone2 = ZoneId.of("Asia/Tokyo")
        val clock2 = Clock.fixed(fixedNow.plusSeconds(60), zone2)
        val repo2 = StepRepository(db, source, settings, clock2, noOpTimerScope)
        repo2.syncNow()

        val reimported = db.stepBucketDao().getAllActive().single { it.startEpochSecond == baseEpoch }
        assertEquals("America/New_York", reimported.zoneId)
        assertEquals(original.localDate, reimported.localDate)
    }

    @Test
    fun `a live device timezone change is picked up for new buckets while existing buckets keep their original zone`() = runTest {
        // Unlike the fixed-zone clocks used elsewhere in this file, this is the SAME repository
        // and SAME Clock instance throughout - modeling AppContainer's clock living for the
        // whole process, with the device's timezone changing underneath it mid-life.
        val zoneA = ZoneId.of("America/New_York")
        val mutableClock = MutableClock(fixedNow, zoneA)
        val dynamicRepository = StepRepository(database, fakeSource, settingsRepository, mutableClock, noOpTimerScope)

        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)
        dynamicRepository.syncNow()
        val original = database.stepBucketDao().getAllActive().single { it.startEpochSecond == baseEpoch }
        assertEquals("America/New_York", original.zoneId)

        // The device's timezone changes while the process (and this repository/clock) stays alive.
        mutableClock.currentZone = ZoneId.of("Asia/Tokyo")
        mutableClock.instant = fixedNow.plusSeconds(120)
        val newMinuteStart = baseEpoch + 120
        fakeSource.addInterval(newMinuteStart, newMinuteStart + 60, 20)
        dynamicRepository.syncNow()

        val stillOriginal = database.stepBucketDao().getAllActive().single { it.startEpochSecond == baseEpoch }
        assertEquals("America/New_York", stillOriginal.zoneId)
        assertEquals(original.localDate, stillOriginal.localDate)

        val newBucket = database.stepBucketDao().getAllActive().single { it.startEpochSecond == newMinuteStart }
        assertEquals("Asia/Tokyo", newBucket.zoneId)
    }
}
