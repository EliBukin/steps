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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
     * The scheduled timer's recompute crosses onto Room's own real background executor,
     * independent of this test's virtual coroutine time - advancing virtual time resolves the
     * `delay()` itself, but the resulting Room work can still be mid-flight on that real thread
     * when [kotlinx.coroutines.test.TestScope.advanceTimeBy] returns. Repeatedly draining the
     * scheduler with brief real pauses (bounded, and typically resolving in well under a
     * millisecond of real time) lets any in-flight cross-thread hop actually catch up before an
     * assertion checks state - without advancing virtual time any further than what was requested.
     */
    private fun TestScope.settle() {
        repeat(200) {
            runCurrent()
            Thread.sleep(1)
        }
    }

    /** Like [settle], but polls until [repo] actually shows a finalized session instead of a fixed iteration count - more robust against real-thread scheduling variance under load. */
    private suspend fun TestScope.awaitFinalizedSessions(repo: StepRepository): List<WalkSession> {
        repeat(1000) {
            runCurrent()
            val sessions = repo.observeSessions().first()
            if (sessions.isNotEmpty()) return sessions
            Thread.sleep(1)
        }
        return repo.observeSessions().first()
    }

    @Test
    fun `a trailing bout finalizes on its own once idleFinalizeMinutes passes, with no second source read`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        val lastActiveMinuteEnd = baseEpoch + 20 * 60L
        val today = Instant.ofEpochSecond(baseEpoch).atZone(ZoneOffset.UTC).toLocalDate()

        var readCount = 0
        val countingSource = object : com.example.stepsplit.data.stepsource.StepSource by fakeSource {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant) =
                fakeSource.readSteps(fromInclusive, toExclusive).also { readCount++ }
        }
        // "Now" starts 1 minute after the final active bucket - well under the default 3-minute
        // idleFinalizeMinutes, so this first sync must not finalize a session yet.
        val mutableClock = MutableClock(Instant.ofEpochSecond(lastActiveMinuteEnd + 60), ZoneOffset.UTC)
        val timedRepository = StepRepository(database, countingSource, settingsRepository, mutableClock, backgroundScope)

        timedRepository.syncNow()
        assertEquals(1, readCount)
        assertTrue("must not appear as a session yet", timedRepository.observeSessions().first().isEmpty())
        val earlyBreakdown = timedRepository.observeDailyBreakdowns(listOf(today)).first().getValue(today)
        assertEquals(0L, earlyBreakdown.workoutSteps)
        assertEquals(1600L, earlyBreakdown.incidentalSteps)

        // Advance the clock to exactly the idle-finalize deadline and drive the scheduled local
        // timer - no second call into syncNow(), so no second source read either.
        advanceTimeBy(3 * 60_000L)
        mutableClock.instant = Instant.ofEpochSecond(lastActiveMinuteEnd + 3 * 60L)
        settle()

        val sessions = awaitFinalizedSessions(timedRepository)
        assertEquals("the local timer must never read from the step source", 1, readCount)
        assertEquals(1, sessions.size)
        assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
        val laterBreakdown = timedRepository.observeDailyBreakdowns(listOf(today)).first().getValue(today)
        assertEquals(1600L, laterBreakdown.workoutSteps)
        assertEquals(0L, laterBreakdown.incidentalSteps)
    }

    @Test
    fun `new activity before the deadline reschedules finalization to reflect the newest data`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        val lastActiveMinuteEnd = baseEpoch + 20 * 60L
        val mutableClock = MutableClock(Instant.ofEpochSecond(lastActiveMinuteEnd + 60), ZoneOffset.UTC)
        val timedRepository = StepRepository(database, fakeSource, settingsRepository, mutableClock, backgroundScope)

        // First sync: schedules a deadline 3 minutes (the default idleFinalizeMinutes) after the
        // last active minute, i.e. at lastActiveMinuteEnd + 180s.
        timedRepository.syncNow()

        // 60 (virtual) seconds later - still well before that deadline - new activity arrives and
        // a sync picks it up, extending the bout by one more active minute.
        advanceTimeBy(60_000)
        mutableClock.instant = mutableClock.instant.plusSeconds(60)
        settle()
        fakeSource.addInterval(lastActiveMinuteEnd + 60, lastActiveMinuteEnd + 120, 80)
        timedRepository.syncNow()

        // Advance to exactly the ORIGINAL (now-superseded) deadline - if new activity had not
        // rescheduled it, this is when the bout would have finalized with the old, incomplete data.
        advanceTimeBy(61_000)
        mutableClock.instant = mutableClock.instant.plusSeconds(61)
        settle()
        assertTrue(
            "finalization must wait for the rescheduled deadline, not the original one",
            timedRepository.observeSessions().first().isEmpty(),
        )

        // Advance the rest of the way to the actual (rescheduled) deadline: idleFinalizeMinutes
        // after the newest active minute, i.e. lastActiveMinuteEnd + 120 + 180.
        val target = lastActiveMinuteEnd + 120 + 180
        val remainingSeconds = target - mutableClock.instant.epochSecond
        advanceTimeBy(remainingSeconds * 1000)
        mutableClock.instant = mutableClock.instant.plusSeconds(remainingSeconds)

        val sessions = awaitFinalizedSessions(timedRepository)
        assertEquals(1, sessions.size)
        assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
        // The original 20 minutes (1600 steps) plus the one extra minute that arrived before the
        // original deadline (80 steps) - proof the newest data, not a stale snapshot, was used.
        assertEquals(1680L, sessions.single().steps)
    }

    @Test
    fun `changing idleFinalizeMinutes reschedules the pending finalization to the new duration`() = runTest {
        for (i in 0 until 20) fakeSource.addInterval(baseEpoch + i * 60L, baseEpoch + i * 60L + 60L, 80)
        val lastActiveMinuteEnd = baseEpoch + 20 * 60L
        val mutableClock = MutableClock(Instant.ofEpochSecond(lastActiveMinuteEnd + 60), ZoneOffset.UTC)
        val timedRepository = StepRepository(database, fakeSource, settingsRepository, mutableClock, backgroundScope)

        // First sync: schedules a deadline using the default 3-minute idleFinalizeMinutes.
        timedRepository.syncNow()

        // Before that deadline, the user relaxes idleFinalizeMinutes to 10 minutes - finalization
        // must now wait longer, not fire on the old 3-minute schedule.
        advanceTimeBy(60_000)
        mutableClock.instant = mutableClock.instant.plusSeconds(60)
        settle()
        val longer = ClassificationThresholds.DEFAULT.copy(idleFinalizeMinutes = 10)
        timedRepository.applyThresholds(longer)

        // Advance to just past the OLD 3-minute deadline - must still be withheld now that
        // idleFinalizeMinutes is 10 minutes.
        advanceTimeBy(61_000)
        mutableClock.instant = mutableClock.instant.plusSeconds(61)
        settle()
        assertTrue(timedRepository.observeSessions().first().isEmpty())

        // Advance the rest of the way to the actual new deadline: 10 minutes after the last
        // active minute.
        val target = lastActiveMinuteEnd + 10 * 60
        val remainingSeconds = target - mutableClock.instant.epochSecond
        advanceTimeBy(remainingSeconds * 1000)
        mutableClock.instant = mutableClock.instant.plusSeconds(remainingSeconds)

        val sessions = awaitFinalizedSessions(timedRepository)
        assertEquals(1, sessions.size)
        assertEquals(BoutClassification.WORKOUT, sessions.single().classification)
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
