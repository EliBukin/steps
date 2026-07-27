package com.example.stepsplit.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.classification.BoutClassification
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    fun `start and finish walk records a manual session with steps from the covered window`() = runTest {
        assertTrue(repository.startManualWalk())
        assertFalse("only one ongoing manual walk is allowed at a time", repository.startManualWalk())

        fakeSource.addInterval(fixedNow.epochSecond, fixedNow.epochSecond + 60, 120)

        // Finish two minutes "later" - a separate repository sharing the same database, backed by
        // a clock two minutes ahead, models time actually elapsing between start and finish.
        val laterClock = Clock.fixed(fixedNow.plusSeconds(120), ZoneOffset.UTC)
        val laterRepository = StepRepository(database, fakeSource, settingsRepository, laterClock)

        assertTrue(laterRepository.finishManualWalk())
        // The same minute is also picked up as its own (incidental, too-short) auto-detected
        // bout - by design, a manual walk never hides or replaces the raw automatic analysis.
        val session = laterRepository.observeSessions().first()
            .single { it.origin == com.example.stepsplit.domain.model.SessionOrigin.MANUAL }
        assertEquals(120L, session.steps)
        assertEquals(BoutClassification.WORKOUT, session.classification)
        assertFalse(session.isReclassifiable)
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
}
