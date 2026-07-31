package com.example.stepsplit.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.classification.ClassificationThresholds
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        fakeSource = FakeStepSource()
        settingsRepository = SettingsRepository(context)
        repository = StepRepository(database, fakeSource, settingsRepository, clock)
    }

    @After
    fun tearDown() = runTest {
        // Preferences DataStore persists to a real file, and Robolectric does not guarantee a
        // fresh one per test method - without this, a threshold change made by one test (e.g.
        // "changing thresholds...") can leak into a sibling test that assumes defaults.
        settingsRepository.resetThresholds()
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
        val failingRepository = StepRepository(database, failingSource, settingsRepository, clock)

        val result = failingRepository.syncNow()

        assertTrue(result is SyncResult.Failed)
        assertEquals(0, database.stepBucketDao().count())
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
        val dstRepository = StepRepository(dstDatabase, dstSource, SettingsRepository(dstContext), dstClock)

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
        val midMinuteRepository = StepRepository(database, fakeSource, settingsRepository, midMinuteClock)
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
        val repo1 = StepRepository(db, source, settings, clock1)

        source.addInterval(baseEpoch, baseEpoch + 60, 50)
        repo1.syncNow()
        val original = db.stepBucketDao().getAllActive().single()
        assertEquals("America/New_York", original.zoneId)

        // Simulate a device timezone change, then a re-sync that re-reads the same overlap
        // window (the source still reports the exact same interval as before).
        val zone2 = ZoneId.of("Asia/Tokyo")
        val clock2 = Clock.fixed(fixedNow.plusSeconds(60), zone2)
        val repo2 = StepRepository(db, source, settings, clock2)
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
        val dynamicRepository = StepRepository(database, fakeSource, settingsRepository, mutableClock)

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
