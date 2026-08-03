package com.example.stepsplit.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bout.WalkBoutEntity
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.local.override.SessionOverrideEntity
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
import kotlinx.coroutines.CancellationException
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

/**
 * Throws exactly once, on the [throwOnCall]-th call to [instant] - a plain [RuntimeException] by
 * default, or [CancellationException] when [cancellation] is true - then returns [base] on every
 * other call. Used to inject a failure or a cancellation at a precise, deterministic point inside
 * a sync without needing to hook Room internals directly: [StepRepository.syncNowLocked] and
 * [StepRepository.recomputeClassification] each read the clock exactly once, so counting calls
 * pins down exactly which read is targeted - see this file's atomicity tests for why call 3 lands
 * between raw-bucket reconciliation and classification finishing on a fresh [StepRepository].
 * Throwing [CancellationException] directly (rather than actually cancelling some enclosing
 * [kotlinx.coroutines.Job]) is the standard, deterministic way to represent "this coroutine was
 * cancelled at this exact suspension point" without depending on dispatcher/scheduler timing to
 * deliver a real cancellation - Room's transaction rollback path (see
 * [androidx.room.RoomDatabase.withTransaction]) treats any thrown [Throwable] the same way,
 * [CancellationException] included, so this still exercises the identical rollback behavior.
 */
private class ThrowOnNthCallClock(
    private val base: Instant,
    private val throwOnCall: Int,
    private val cancellation: Boolean = false,
) : Clock() {
    private var calls = 0
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant {
        calls++
        if (calls == throwOnCall) {
            if (cancellation) {
                throw CancellationException("simulated cancellation between raw reconciliation and classification finishing")
            }
            throw RuntimeException("simulated failure between raw reconciliation and classification finishing")
        }
        return base
    }
}

/**
 * Behaves exactly like [TestSchedulerClock] (time derived from the scheduler's own virtual clock)
 * except that after [armToThrowOnCall] is called, the [n]-th [instant] call counting from that
 * point throws once (a plain [RuntimeException]) instead of returning the time; every call before
 * and after still behaves normally. Unlike [ThrowOnNthCallClock], this can be reused across
 * multiple [StepRepository.syncNow] calls on the SAME repository instance - needed for tests that
 * must observe in-memory state (like the pending finalization timer) that only persists on a
 * single, reused [StepRepository], not across fresh instances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class ArmableThrowOnceClock(
    private val scheduler: TestCoroutineScheduler,
    private val base: Instant,
) : Clock() {
    private var callsUntilThrow = -1
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this

    fun armToThrowOnCall(n: Int) {
        callsUntilThrow = n
    }

    override fun instant(): Instant {
        if (callsUntilThrow > 0) {
            callsUntilThrow--
            if (callsUntilThrow == 0) throw RuntimeException("simulated failure inside the merged transaction")
        }
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

    @Test
    fun `losing availability between the initial check and the actual read produces Unavailable, not a false empty success`() = runTest {
        // A successful sync first - this is the data a mid-read availability loss must never wipe
        // out via reconciliation deletion, and the timestamp it must never look like it refreshed.
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)
        assertTrue(repository.syncNow() is SyncResult.Success)
        assertEquals(50L, database.stepBucketDao().getAllActive().sumOf { it.steps })
        val syncTimeAfterSuccess = settingsRepository.settings.first().lastSuccessfulSync

        // syncNowLocked's own upfront checkAvailability() still sees Available - only readSteps()
        // itself discovers the source has gone unavailable, exactly like a permission revocation
        // or a Play services drop occurring in the narrow window between that check and the read
        // actually completing. FakeStepSource.readSteps throws StepSourceUnavailableException in
        // this situation (mirroring LocalRecordingStepSource), never emptyList().
        val flakySource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<com.example.stepsplit.data.stepsource.RawStepInterval> {
                fakeSource.setAvailability(StepSourceAvailability.ApiUnavailable)
                return fakeSource.readSteps(fromInclusive, toExclusive)
            }
        }
        val laterClock = Clock.fixed(fixedNow.plusSeconds(120), ZoneOffset.UTC)
        val flakyRepository = StepRepository(database, flakySource, settingsRepository, laterClock, noOpTimerScope)

        val result = flakyRepository.syncNow()

        assertTrue("a mid-read availability loss must never look like a successful empty read", result !is SyncResult.Success)
        assertTrue(result is SyncResult.Unavailable)
        // The reconciliation delete-then-upsert transaction (and the classifier replace nested in
        // it) must never have run for this lost-mid-read case - the previously stored bucket is
        // exactly as it was.
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

    // ---- Manual override anchor reconciliation (reconcileOverrideAnchors) ----

    @Test
    fun `an override survives when the first minute of its bout is later removed`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()
        val original = repository.observeSessions().first().single()
        repository.reclassify(original.anchorEpochSecond!!, BoutClassification.INCIDENTAL)
        assertEquals(BoutClassification.INCIDENTAL, repository.observeSessions().first().single().classification)

        // The source corrects itself: the very first minute of the bout no longer has any steps,
        // shifting the recomputed bout's start one minute later - the same walking session, just
        // missing its first minute.
        fakeSource.clearIntervals()
        for (i in 1 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()

        val sessions = repository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(
            "the override must follow the same session even though its start shifted",
            BoutClassification.INCIDENTAL,
            sessions.single().classification,
        )
        assertEquals(baseEpoch + 60L, sessions.single().anchorEpochSecond)
    }

    @Test
    fun `an override survives when an earlier active minute extends the same bout backward`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()
        val original = repository.observeSessions().first().single()
        repository.reclassify(original.anchorEpochSecond!!, BoutClassification.INCIDENTAL)

        // A late-arriving correction reveals one more active minute immediately before the bout -
        // the same walking session, now starting a minute earlier.
        fakeSource.addInterval(baseEpoch - 60L, baseEpoch, 80)
        repository.syncNow()

        val sessions = repository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(BoutClassification.INCIDENTAL, sessions.single().classification)
        assertEquals(baseEpoch - 60L, sessions.single().anchorEpochSecond)
    }

    @Test
    fun `an override does not reattach to either half when its session ambiguously splits`() = runTest {
        // One long 40-minute bout, clearly a workout.
        for (i in 0 until 40) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()
        val original = repository.observeSessions().first().single()
        repository.reclassify(original.anchorEpochSecond!!, BoutClassification.INCIDENTAL)

        // A correction removes the first two minutes AND a wide middle chunk, splitting it into
        // two much shorter bouts - deliberately so NEITHER fragment starts where the original
        // override's anchor did (that would be a coincidental exact-key match, not a test of
        // reattachment logic at all). Neither fragment overlaps the original 40-minute interval by
        // a strong majority, so neither is clearly "the same session" as the override's original
        // bout.
        fakeSource.clearIntervals()
        for (i in 2 until 12) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        for (i in 30 until 40) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()

        val sessions = repository.observeSessions().first()
        assertEquals(2, sessions.size)
        assertTrue(
            "an ambiguous split must not silently reattach the override to either half - both must show their natural auto result",
            sessions.all { it.classification == BoutClassification.WORKOUT },
        )
    }

    @Test
    fun `two overrides do not both reattach to a single bout formed by merging their sessions`() = runTest {
        // Two separate 10-minute bouts (idle gap of 3 minutes between them - well past the
        // default maxGapMinutes=2), each individually reclassified by the user.
        for (i in 0 until 10) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        val secondBoutStart = baseEpoch + 13 * 60L
        for (i in 0 until 10) fakeSource.addInterval(secondBoutStart + i * 60L, secondBoutStart + i * 60L + 60L, 80)
        repository.syncNow()

        val firstSessions = repository.observeSessions().first().sortedBy { it.startEpochSecond }
        assertEquals(2, firstSessions.size)
        repository.reclassify(firstSessions[0].anchorEpochSecond!!, BoutClassification.INCIDENTAL)
        repository.reclassify(firstSessions[1].anchorEpochSecond!!, BoutClassification.INCIDENTAL)

        // A correction both fills the gap between the two bouts AND extends the very start
        // backward by one more minute - merging them into one much longer bout that starts
        // earlier than EITHER original bout, so neither override's anchor could ever
        // coincidentally still exact-match the merged bout. It is not clear which of the two
        // conflicting manual classifications (if either) should apply to the merged result.
        fakeSource.addInterval(baseEpoch - 60L, baseEpoch, 80)
        for (i in 10 until 13) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()

        val sessions = repository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(
            "a merge must not let either conflicting override silently win - the auto result must show",
            BoutClassification.WORKOUT,
            sessions.single().classification,
        )
    }

    @Test
    fun `an override is removed, not silently kept, when a split leaves the first fragment at the original start`() = runTest {
        // One long 40-minute bout, clearly a workout.
        for (i in 0 until 40) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()
        val original = repository.observeSessions().first().single()
        repository.reclassify(original.anchorEpochSecond!!, BoutClassification.INCIDENTAL)

        // A correction removes ONLY a middle chunk - unlike the split test above, the first
        // fragment here coincidentally keeps the EXACT original start. The old (pre-fix) code
        // treated an unchanged exact anchor as automatically still valid and kept applying the
        // override to this fragment, even though it now covers only a quarter of the original
        // bout - the overlap check below proves this is NOT the same session anymore.
        fakeSource.clearIntervals()
        for (i in 0 until 10) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        for (i in 30 until 40) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()

        val sessions = repository.observeSessions().first()
        assertEquals(2, sessions.size)
        assertTrue(
            "the first fragment keeps the original anchor, but must show its natural auto result, not the stale override",
            sessions.all { it.classification == BoutClassification.WORKOUT },
        )
        assertTrue(
            "the override must be removed rather than left silently attached to an unrelated fragment",
            database.sessionOverrideDao().getAll().isEmpty(),
        )
    }

    @Test
    fun `an override is removed, not silently kept, when a merge leaves the combined bout at the first bout's start`() = runTest {
        // Two separate 10-minute bouts (idle gap of 3 minutes - well past the default
        // maxGapMinutes=2), each individually reclassified by the user.
        for (i in 0 until 10) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        val secondBoutStart = baseEpoch + 13 * 60L
        for (i in 0 until 10) fakeSource.addInterval(secondBoutStart + i * 60L, secondBoutStart + i * 60L + 60L, 80)
        repository.syncNow()

        val firstSessions = repository.observeSessions().first().sortedBy { it.startEpochSecond }
        assertEquals(2, firstSessions.size)
        repository.reclassify(firstSessions[0].anchorEpochSecond!!, BoutClassification.INCIDENTAL)
        repository.reclassify(firstSessions[1].anchorEpochSecond!!, BoutClassification.INCIDENTAL)

        // A correction fills ONLY the gap between them - unlike the merge test above, the merged
        // bout here keeps the FIRST original bout's exact start (no leading extension). The old
        // (pre-fix) code treated that unchanged exact anchor as automatically still valid and kept
        // applying the first bout's override to the whole merged session, even though it now spans
        // more than twice as long and includes the second bout's steps too.
        for (i in 10 until 13) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()

        val sessions = repository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(
            "neither conflicting override may silently win - the merged bout must show its natural auto result",
            BoutClassification.WORKOUT,
            sessions.single().classification,
        )

        val remainingOverrides = database.sessionOverrideDao().getAll()
        assertEquals(
            "the first bout's override (whose anchor coincides with the merged bout) must be removed",
            0,
            remainingOverrides.count { it.boutStartEpochSecond == baseEpoch },
        )
        assertEquals(
            "the second bout's override is genuinely orphaned (no collision) and must simply be left inactive, not deleted",
            1,
            remainingOverrides.count { it.boutStartEpochSecond == secondBoutStart },
        )
    }

    @Test
    fun `a reattachment candidate must not overwrite an existing override already at its target anchor`() = runTest {
        // A synthetic, adversarial DB state seeded directly (not derived from a real sync) to
        // exercise reconcileOverrideAnchors's conflict-arbitration path directly: two DIFFERENT
        // overrides whose previous bouts both plausibly relate to the SAME upcoming new bout -
        // overrideB legitimately (its previous bout is EXACTLY what the new bout will be, so it is
        // self-consistent), overrideA only because its own, now-stale bout happens to strongly
        // overlap the same new bout too.
        val currentBoutStart = baseEpoch + 60L
        val currentBoutEnd = baseEpoch + 20 * 60L
        for (i in 1 until 20) {
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
        database.walkBoutDao().insertAll(
            listOf(
                // overrideA's stale previous bout - strongly overlaps the upcoming new bout, but
                // is not what it actually was computed from.
                WalkBoutEntity(
                    startEpochSecond = baseEpoch,
                    endEpochSecond = currentBoutEnd,
                    steps = 1600,
                    activeMinutes = 20,
                    elapsedMinutes = 20,
                    cadence = 80.0,
                    autoClassification = "WORKOUT",
                    autoConfidence = 1.0,
                    autoReasonCode = "MEETS_ALL_THRESHOLDS",
                    classifierVersion = CLASSIFIER_VERSION,
                    computedAtEpochSecond = baseEpoch,
                ),
                // overrideB's previous bout - EXACTLY what the new bout (from the raw data seeded
                // above) will be, so overrideB is legitimately self-consistent.
                WalkBoutEntity(
                    startEpochSecond = currentBoutStart,
                    endEpochSecond = currentBoutEnd,
                    steps = 1520,
                    activeMinutes = 19,
                    elapsedMinutes = 19,
                    cadence = 80.0,
                    autoClassification = "WORKOUT",
                    autoConfidence = 1.0,
                    autoReasonCode = "MEETS_ALL_THRESHOLDS",
                    classifierVersion = CLASSIFIER_VERSION,
                    computedAtEpochSecond = baseEpoch,
                ),
            ),
        )
        database.sessionOverrideDao().upsert(SessionOverrideEntity(baseEpoch, BoutClassification.WORKOUT.name, baseEpoch))
        database.sessionOverrideDao().upsert(SessionOverrideEntity(currentBoutStart, BoutClassification.INCIDENTAL.name, baseEpoch))

        // Triggers recomputeClassification purely from the raw data already in Room (the recovery
        // path, unconditional on a fresh repository's first call) without going through a real
        // read - the source is kept unavailable so the sync's own read/reconcile step never runs
        // and can never touch (or delete) the raw buckets seeded above.
        fakeSource.setAvailability(StepSourceAvailability.ApiUnavailable)
        val result = repository.syncNow()
        assertTrue(result is SyncResult.Unavailable)

        val sessions = repository.observeSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(
            "overrideB is the legitimate, self-consistent match - it must survive and keep applying",
            BoutClassification.INCIDENTAL,
            sessions.single().classification,
        )

        val atTarget = database.sessionOverrideDao().getAll().singleOrNull { it.boutStartEpochSecond == currentBoutStart }
        assertEquals(
            "overrideA's conflicting reattachment must never overwrite overrideB's own row at the target anchor",
            BoutClassification.INCIDENTAL.name,
            atTarget?.classification,
        )
    }

    @Test
    fun `a reattached override remains applied after another classifier recomputation`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow()
        val original = repository.observeSessions().first().single()
        repository.reclassify(original.anchorEpochSecond!!, BoutClassification.INCIDENTAL)

        fakeSource.clearIntervals()
        for (i in 1 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        repository.syncNow() // triggers reattachment to the new, one-minute-later anchor
        assertEquals(BoutClassification.INCIDENTAL, repository.observeSessions().first().single().classification)

        // Rerunning classification again with no further raw-data changes must not lose the
        // now-reattached override a second time - it should already be an exact-key match by now.
        repository.syncNow()
        assertEquals(
            "the reattached override must remain stable across a further recompute",
            BoutClassification.INCIDENTAL,
            repository.observeSessions().first().single().classification,
        )
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
            val firstProcessScope = harness.newScope()
            val firstRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, firstProcessScope)
            firstRepository.syncNow()
            assertTrue(firstRepository.observeSessions().first().isEmpty())

            // Simulate the process being killed: cancel its scope (and whatever timer it
            // scheduled) and drive the scheduler so the cancellation is fully observed BEFORE the
            // second repository is created below. Without this, the first process's timer stays
            // live (it would otherwise only be torn down by the harness's close() at the very end
            // of this test) and could itself update walk_bouts when time is advanced later,
            // letting this test pass even if the second repository's own recovery were broken.
            firstProcessScope.cancel()
            harness.scheduler.runCurrent()

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

            // Simulate the process being killed: cancel the first process's scope (and whatever
            // timer it scheduled) and drive the scheduler so the cancellation is fully observed
            // before the second repository is created - otherwise the first process's still-live
            // timer could satisfy the assertions below on its own, masking a broken recovery in
            // the second repository.
            val firstProcessScope = harness.newScope()
            val firstRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, firstProcessScope)
            firstRepository.syncNow()
            firstProcessScope.cancel()
            harness.scheduler.runCurrent()

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

            // Simulate the process being killed: cancel the first process's scope (and whatever
            // timer it scheduled) and drive the scheduler so the cancellation is fully observed
            // before the second repository is created - otherwise the first process's still-live
            // timer could itself finalize the bout later in this test, masking a broken recovery
            // in the second repository.
            val firstProcessScope = harness.newScope()
            val firstRepository = StepRepository(harness.database, fakeSource, settingsRepository, clock, firstProcessScope)
            firstRepository.syncNow()
            firstProcessScope.cancel()
            harness.scheduler.runCurrent()

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
    fun `a failure during the first recovery attempt is reported as a structured failure, not thrown, and a later sync retries recovery`() = runTest {
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
            var readCount = 0
            val countingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
                override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant) =
                    fakeSource.readSteps(fromInclusive, toExclusive).also { readCount++ }
            }
            val clock = ThrowOnceClock(harness.scheduler, Instant.ofEpochSecond(lastActiveMinuteEnd + 60))
            val repo = StepRepository(harness.database, countingSource, settingsRepository, clock, harness.newScope())

            // First sync: recovery's recomputeClassification() throws while reading "now" -
            // StepRepository.ensureClassificationFreshOrFail must convert that into an ordinary
            // structured SyncResult.Failed rather than letting it escape syncNow() (which real
            // callers invoke from their own coroutine scope with no exception handling), and must
            // NOT mark recovery done.
            val firstResult = repo.syncNow()
            assertTrue("a recovery failure must be reported, not thrown", firstResult is SyncResult.Failed)
            assertEquals(SyncFailureCategory.UNKNOWN, (firstResult as SyncResult.Failed).category)
            assertEquals(
                SyncFailureCategory.UNKNOWN,
                settingsRepository.settings.first().lastSyncFailure?.category,
            )
            assertEquals("recovery must never read from the step source, failed or not", 0, readCount)

            // Second sync: if the first failed attempt had incorrectly marked recovery as done,
            // this would skip straight to the version-only check - which finds nothing stale in
            // the still-empty walk_bouts table - and never schedule a finalization timer at all,
            // so the bout would never finalize no matter how much time passes. A correct retry
            // schedules the timer here instead.
            val secondResult = repo.syncNow()
            assertTrue("still unavailable, but recovery must have retried and succeeded", secondResult is SyncResult.Unavailable)
            assertTrue(repo.observeSessions().first().isEmpty())
            assertEquals(0, readCount)

            harness.scheduler.advanceTimeBy(2 * 60_000L)
            val sessions = awaitFinalizedSessions(repo, harness)

            assertEquals("the restored timer must never read from the step source either", 0, readCount)
            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
        }
    }

    // ---- Atomic raw-bucket + classification commit (syncNowLocked) ----

    @Test
    fun `a failure between raw reconciliation and classification finishing rolls back atomically, leaving nothing stale`() = runTest {
        // Baseline: a real successful sync establishes a consistent (raw buckets, walk_bouts)
        // pair - a single finalized WORKOUT session, well before "now" so it isn't the still-open
        // trailing bout.
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        assertTrue(repository.syncNow() is SyncResult.Success)
        val baseline = repository.observeSessions().first().single()
        assertEquals(BoutClassification.WORKOUT, baseline.classification)
        assertEquals(1600L, baseline.steps)
        val syncTimeAfterBaseline = settingsRepository.settings.first().lastSuccessfulSync
        assertEquals(20, database.stepBucketDao().count())

        // New activity arrives that would change the classified result (more steps) if the
        // recompute below actually completed - it must not, since the clock is about to throw
        // exactly when recomputeClassification reads "now" for its own computedAt, deep inside
        // the SAME transaction as the raw-bucket reconciliation (see StepRepository.syncNowLocked's
        // own doc comment on why the two are nested into one atomic unit).
        fakeSource.addInterval(baseEpoch + 20 * 60L, baseEpoch + 21 * 60L, 80)

        // A fresh repository instance over the SAME database: call #1 is its own one-time recovery
        // recompute (over the still-baseline raw data - nothing new has been read into Room yet),
        // call #2 is syncNowLocked's own "now", call #3 is recomputeClassification's computedAt
        // read inside the merged transaction - exactly where the injected failure lands.
        val throwingClock = ThrowOnNthCallClock(fixedNow, throwOnCall = 3)
        val flakyRepository = StepRepository(database, fakeSource, settingsRepository, throwingClock, noOpTimerScope)

        val result = flakyRepository.syncNow()

        assertTrue("a failure mid-recompute must be reported, not thrown", result is SyncResult.Failed)
        assertEquals(
            "the new raw data must have rolled back along with the incomplete bout replacement",
            20,
            database.stepBucketDao().count(),
        )
        assertEquals(1600L, database.stepBucketDao().getAllActive().sumOf { it.steps })
        assertEquals(syncTimeAfterBaseline, settingsRepository.settings.first().lastSuccessfulSync)

        val sessionsAfterFailure = flakyRepository.observeSessions().first()
        assertEquals(1, sessionsAfterFailure.size)
        assertEquals(
            "the original, still-consistent session must be exactly as it was",
            1600L,
            sessionsAfterFailure.single().steps,
        )

        // A later sync, with the source now unavailable, must not need to repair anything (nothing
        // was ever left inconsistent) and must not perform a second source read to stay correct.
        var readCount = 0
        val countingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant) =
                fakeSource.readSteps(fromInclusive, toExclusive).also { readCount++ }
        }
        fakeSource.setAvailability(StepSourceAvailability.ApiUnavailable)
        val recoveryClock = Clock.fixed(fixedNow.plusSeconds(60), ZoneOffset.UTC)
        val recoveryRepository = StepRepository(database, countingSource, settingsRepository, recoveryClock, noOpTimerScope)

        val recoveryResult = recoveryRepository.syncNow()

        assertTrue(recoveryResult is SyncResult.Unavailable)
        assertEquals("no source read was needed to stay consistent - nothing was ever corrupted", 0, readCount)
        val sessionsAfterRecovery = recoveryRepository.observeSessions().first()
        assertEquals("no stale session must be exposed", 1, sessionsAfterRecovery.size)
        assertEquals(1600L, sessionsAfterRecovery.single().steps)
    }

    @Test
    fun `a cancellation between raw reconciliation and classification finishing rolls back atomically and propagates`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        assertTrue(repository.syncNow() is SyncResult.Success)
        assertEquals(1600L, repository.observeSessions().first().single().steps)
        val syncTimeAfterBaseline = settingsRepository.settings.first().lastSuccessfulSync
        assertEquals(20, database.stepBucketDao().count())

        fakeSource.addInterval(baseEpoch + 20 * 60L, baseEpoch + 21 * 60L, 80)

        // Same call-count reasoning as the failure-injection test above: call #3 lands between
        // this sync's own raw-bucket reconciliation (already executed earlier in the very same
        // transaction) and classification finishing.
        val cancellingClock = ThrowOnNthCallClock(fixedNow, throwOnCall = 3, cancellation = true)
        val flakyRepository = StepRepository(database, fakeSource, settingsRepository, cancellingClock, noOpTimerScope)

        var propagated = false
        try {
            flakyRepository.syncNow()
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("cancellation must propagate to the caller, not be swallowed as a sync failure", propagated)

        assertEquals(
            "the new raw data must roll back along with the incomplete reclassification",
            20,
            database.stepBucketDao().count(),
        )
        assertEquals(1600L, database.stepBucketDao().getAllActive().sumOf { it.steps })
        assertEquals(syncTimeAfterBaseline, settingsRepository.settings.first().lastSuccessfulSync)
        assertEquals(1600L, flakyRepository.observeSessions().first().single().steps)
    }

    @Test
    fun `a failure inside the merged transaction never reschedules the finalization timer from uncommitted data`() = runTest {
        TimerTestHarness().use { harness ->
            for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
            val lastActiveMinuteEnd = baseEpoch + 20 * 60L
            val clock = ArmableThrowOnceClock(harness.scheduler, Instant.ofEpochSecond(lastActiveMinuteEnd + 60))
            val repo = StepRepository(harness.database, fakeSource, settingsRepository, clock, harness.newScope())

            // Baseline: a real successful sync schedules a real finalization timer 2 minutes out
            // (idleFinalizeMinutes=3 minus the 1 minute "now" already sits past the last active
            // minute) - this is the ONLY thing that must ever schedule/mutate the timer below.
            repo.syncNow()
            assertTrue(repo.observeSessions().first().isEmpty())

            // New activity arrives that would push the trailing bout's last active minute a
            // full minute later - and, if a sync committed it and (incorrectly) rescheduled the
            // timer from data that turns out not to be durably committed, would replace the
            // correct 2-minute-out deadline above with a later, wrong one.
            fakeSource.addInterval(lastActiveMinuteEnd, lastActiveMinuteEnd + 60, 80)

            // Same repository instance as the baseline sync (so any interference with its
            // finalizationJob field is actually observable), with recoveryPerformed already true
            // from that first sync - this sync's own clock calls are just #1 ("now") and #2
            // (recomputeClassificationWithinTransaction's computedAt, deep inside the merged
            // transaction, after the new minute's raw bucket has already been upserted within it).
            // Armed to throw on that #2 call.
            clock.armToThrowOnCall(2)
            val result = repo.syncNow()
            assertTrue(result is SyncResult.Failed)

            // Advance to EXACTLY the ORIGINAL, still-correct 2-minute deadline. If the failed sync
            // had rescheduled the timer from its own (uncommitted, rolled-back) data - as the old,
            // unfixed code did, since it called rescheduleFinalizationJob while still inside its
            // own open outer transaction - this bout would still be withheld here, because the new
            // minute would have pushed the real deadline a minute later. It must finalize right on
            // the original schedule instead, using only the 20 minutes that were actually, durably
            // committed - proof the failed attempt never touched the timer at all.
            harness.scheduler.advanceTimeBy(2 * 60_000L)
            val sessions = awaitFinalizedSessions(repo, harness)

            assertEquals(1, sessions.size)
            assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
            assertEquals(
                "only the durably committed 20 minutes - the new minute from the failed sync must never have counted",
                1600L,
                sessions.single().steps,
            )
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
