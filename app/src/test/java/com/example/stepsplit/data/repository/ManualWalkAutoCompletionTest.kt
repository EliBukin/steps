package com.example.stepsplit.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.model.SessionOrigin
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the manual-walk inactivity auto-completion feature: finishing a forgotten walk once
 * seven fully-elapsed minutes pass with no steps, protecting zero-step walks from being silently
 * finalized, and making sure steps before/after a walk's end land in exactly the right place.
 */
@RunWith(RobolectricTestRunner::class)
class ManualWalkAutoCompletionTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var fakeSource: FakeStepSource
    private lateinit var settingsRepository: SettingsRepository

    /** An arbitrary minute-aligned morning start, far from any day/DST boundary. */
    private val start = Instant.parse("2026-03-10T08:00:00Z").epochSecond

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        fakeSource = FakeStepSource()
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() = runTest {
        // See StepRepositoryTest: Preferences DataStore isn't guaranteed fresh per test method.
        settingsRepository.resetThresholds()
    }

    private fun repoAt(epochSecond: Long): StepRepository =
        StepRepository(database, fakeSource, settingsRepository, Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC))

    @Test
    fun `a short water break under the inactivity threshold keeps the same manual walk`() = runTest {
        assertTrue(repoAt(start).startManualWalk())

        fakeSource.addInterval(start, start + 60, 50)
        fakeSource.addInterval(start + 60, start + 120, 50)
        // Three-minute water break: start+120 .. start+300 has no steps at all.
        fakeSource.addInterval(start + 300, start + 360, 50)
        fakeSource.addInterval(start + 360, start + 420, 50)

        // A sync shortly after walking resumes - well under 7 minutes since the latest active
        // minute (start+360) - must not auto-complete anything.
        repoAt(start + 420 + 90).syncNow()
        assertTrue("the walk must still be ongoing after a short break", database.manualWalkDao().getOngoing() != null)

        // The user finishes manually a few minutes later, still well under the inactivity timeout.
        assertTrue(repoAt(start + 600).finishManualWalk())

        val finished = database.manualWalkDao().observeFinished().first().single()
        assertEquals(start, finished.startEpochSecond)
        assertEquals(start + 600, finished.endEpochSecond)
        assertEquals(200L, finished.steps) // all four active minutes, not split by the break
        assertFalse(finished.autoCompleted)
    }

    @Test
    fun `seven complete inactive minutes automatically finish the walk at the end of its last active minute`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        fakeSource.addInterval(start, start + 60, 50)
        fakeSource.addInterval(start + 60, start + 120, 50)

        val autoCompleteInstant = start + 120 + StepRepository.MANUAL_WALK_INACTIVITY_TIMEOUT.seconds
        val result = repoAt(autoCompleteInstant).syncNow()

        assertTrue(result is SyncResult.Success)
        assertNull(database.manualWalkDao().getOngoing())
        val finished = database.manualWalkDao().observeFinished().first().single()
        assertEquals(start, finished.startEpochSecond)
        // Anchored to the end of the last active minute, not the moment inactivity was detected.
        assertEquals(start + 120, finished.endEpochSecond)
        assertEquals(100L, finished.steps)
        assertTrue(finished.autoCompleted)
        assertFalse(finished.autoCompletionMessageShown)
    }

    @Test
    fun `does not auto-complete a single second before the inactivity threshold`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        fakeSource.addInterval(start, start + 60, 50)

        val justBefore = start + 60 + StepRepository.MANUAL_WALK_INACTIVITY_TIMEOUT.seconds - 1
        repoAt(justBefore).syncNow()

        assertTrue(database.manualWalkDao().getOngoing() != null)
    }

    @Test
    fun `steps recorded after the automatic end are included in the daily total but excluded from the manual workout`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        fakeSource.addInterval(start, start + 60, 50)
        fakeSource.addInterval(start + 60, start + 120, 50)

        repoAt(start + 120 + StepRepository.MANUAL_WALK_INACTIVITY_TIMEOUT.seconds).syncNow()
        val autoCompleted = database.manualWalkDao().observeFinished().first().single()
        assertEquals(100L, autoCompleted.steps)
        assertTrue(autoCompleted.autoCompleted)

        // A small, unrelated later burst - well after the auto-determined end.
        val laterMinute = start + 900
        fakeSource.addInterval(laterMinute, laterMinute + 60, 30)
        val laterRepo = repoAt(laterMinute + 90)
        laterRepo.syncNow()

        val stillFinished = database.manualWalkDao().observeFinished().first().single()
        assertEquals(100L, stillFinished.steps)
        assertEquals(autoCompleted.endEpochSecond, stillFinished.endEpochSecond)

        val date = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC).toLocalDate()
        val breakdown = laterRepo.observeDailyBreakdowns(listOf(date)).first().getValue(date)
        assertEquals(130L, breakdown.totalSteps)
        assertEquals(100L, breakdown.workoutSteps)
        assertEquals(30L, breakdown.incidentalSteps)
    }

    @Test
    fun `a later qualifying walk after the manual session ends is classified as a separate automatic workout`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        fakeSource.addInterval(start, start + 60, 80)
        fakeSource.addInterval(start + 60, start + 120, 80)

        // The manual walk auto-completes before the later burst even begins.
        repoAt(start + 120 + StepRepository.MANUAL_WALK_INACTIVITY_TIMEOUT.seconds).syncNow()
        assertNull(database.manualWalkDao().getOngoing())

        // A separate, later 20-minute burst that independently satisfies the default workout thresholds.
        val laterStart = start + 900
        for (i in 0 until 20) fakeSource.addInterval(laterStart + i * 60L, laterStart + i * 60L + 60L, 80)
        val finalRepo = repoAt(laterStart + 20 * 60L + 60)
        finalRepo.syncNow()

        val sessions = finalRepo.observeSessions().first()
        val manualSession = sessions.single { it.origin == SessionOrigin.MANUAL }
        val laterWorkout = sessions.single { it.origin == SessionOrigin.AUTO && it.startEpochSecond == laterStart }

        assertEquals(160L, manualSession.steps)
        assertEquals(BoutClassification.WORKOUT, laterWorkout.classification)
        assertEquals(1600L, laterWorkout.steps)

        val date = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC).toLocalDate()
        val breakdown = finalRepo.observeDailyBreakdowns(listOf(date)).first().getValue(date)
        assertEquals(1760L, breakdown.totalSteps)
        assertEquals(1760L, breakdown.workoutSteps)
    }

    @Test
    fun `a later 30-step or 300-step burst remains incidental, not a workout`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        fakeSource.addInterval(start, start + 60, 50)
        repoAt(start + 60 + StepRepository.MANUAL_WALK_INACTIVITY_TIMEOUT.seconds).syncNow()

        val burst1 = start + 900
        fakeSource.addInterval(burst1, burst1 + 60, 30)
        val burst2 = start + 1800
        fakeSource.addInterval(burst2, burst2 + 60, 300)

        val finalRepo = repoAt(burst2 + 90)
        finalRepo.syncNow()

        val sessions = finalRepo.observeSessions().first()
        val burst1Session = sessions.single { it.origin == SessionOrigin.AUTO && it.startEpochSecond == burst1 }
        val burst2Session = sessions.single { it.origin == SessionOrigin.AUTO && it.startEpochSecond == burst2 }
        assertEquals(BoutClassification.INCIDENTAL, burst1Session.classification)
        assertEquals(BoutClassification.INCIDENTAL, burst2Session.classification)
    }

    @Test
    fun `daily totals do not double-count steps where the manual walk and its own auto bout overlap`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        // 12 minutes at 80 steps/min - satisfies the default auto-workout thresholds too, so this
        // minute range independently qualifies as both the manual session and its own auto bout.
        for (i in 0 until 12) fakeSource.addInterval(start + i * 60L, start + i * 60L + 60L, 80)

        // Finished well under the inactivity timeout - a plain manual finish, not auto-completion.
        val finishRepo = repoAt(start + 12 * 60L + 60)
        assertTrue(finishRepo.finishManualWalk())

        val sessions = finishRepo.observeSessions().first()
        assertTrue(sessions.any { it.origin == SessionOrigin.MANUAL })
        assertTrue(sessions.any { it.origin == SessionOrigin.AUTO && it.classification == BoutClassification.WORKOUT })

        val date = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC).toLocalDate()
        val breakdown = finishRepo.observeDailyBreakdowns(listOf(date)).first().getValue(date)
        assertEquals(960L, breakdown.totalSteps)
        assertEquals(960L, breakdown.workoutSteps)
        assertEquals(0L, breakdown.incidentalSteps)
    }

    @Test
    fun `a manual walk with no recorded steps is never silently finalized`() = runTest {
        assertTrue(repoAt(start).startManualWalk())

        // No steps ever added. Sync well past both the inactivity timeout and the (much longer)
        // zero-step staleness threshold.
        val laterRepo = repoAt(start + StepRepository.ZERO_STEP_STALE_THRESHOLD.seconds * 3)
        laterRepo.syncNow()

        val ongoing = database.manualWalkDao().getOngoing()
        assertTrue("a zero-step walk must never be auto-finalized", ongoing != null)
        assertNull(ongoing?.endEpochSecond)

        val status = laterRepo.observeOngoingManualWalkStatus().first()
        assertEquals(false, status?.hasRecordedSteps)
    }

    @Test
    fun `cancelling a stale zero-step walk removes it without creating a session`() = runTest {
        assertTrue(repoAt(start).startManualWalk())

        assertTrue(repoAt(start + 3600).cancelOngoingManualWalk())

        assertNull(database.manualWalkDao().getOngoing())
        assertTrue(database.manualWalkDao().observeFinished().first().isEmpty())
    }

    @Test
    fun `finishing a stale walk at a chosen time records it with steps up to that time`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        fakeSource.addInterval(start, start + 60, 40)

        val midRepo = repoAt(start + 90)
        midRepo.syncNow()

        val chosenEnd = start + 3600
        assertTrue(midRepo.finishOngoingManualWalkAt(chosenEnd))

        val finished = database.manualWalkDao().observeFinished().first().single()
        assertEquals(chosenEnd, finished.endEpochSecond)
        assertEquals(40L, finished.steps)
        assertFalse(finished.autoCompleted)
    }

    @Test
    fun `unacknowledged auto-completions are exposed and cleared once acknowledged`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        fakeSource.addInterval(start, start + 60, 50)
        val repo = repoAt(start + 60 + StepRepository.MANUAL_WALK_INACTIVITY_TIMEOUT.seconds)
        repo.syncNow()

        val unacknowledged = repo.observeUnacknowledgedAutoCompletions().first()
        assertEquals(1, unacknowledged.size)
        val walk = unacknowledged.single()
        assertEquals(start, walk.startEpochSecond)
        assertEquals(start + 60, walk.endEpochSecond)

        repo.acknowledgeAutoCompletion(walk.id)

        assertTrue(repo.observeUnacknowledgedAutoCompletions().first().isEmpty())
    }

    // Kept short deliberately: Robolectric embeds the full test method name into its temp
    // directory path, and combined with the nested ".../databases/<name>" suffix a long,
    // descriptive backtick name can push the total path past Windows' MAX_PATH, which surfaces as
    // a generic SQLiteCantOpenDatabaseException that has nothing to do with the test's logic.
    @Test
    fun `auto-completion persists across reopen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "reopen.db"
        context.deleteDatabase(dbName)
        try {
            val db = Room.databaseBuilder(context, StepSplitDatabase::class.java, dbName).build()
            val source = FakeStepSource()
            val settings = SettingsRepository(context)

            StepRepository(db, source, settings, Clock.fixed(Instant.ofEpochSecond(start), ZoneOffset.UTC))
                .startManualWalk()
            source.addInterval(start, start + 60, 50)

            val autoCompleteInstant = Instant.ofEpochSecond(start + 60 + StepRepository.MANUAL_WALK_INACTIVITY_TIMEOUT.seconds)
            StepRepository(db, source, settings, Clock.fixed(autoCompleteInstant, ZoneOffset.UTC)).syncNow()

            db.close() // simulate process death / device restart

            val reopened = Room.databaseBuilder(context, StepSplitDatabase::class.java, dbName).build()
            val finished = reopened.manualWalkDao().observeFinished().first().single()
            assertTrue(finished.autoCompleted)
            assertEquals(start, finished.startEpochSecond)
            assertEquals(start + 60, finished.endEpochSecond)
            assertEquals(50L, finished.steps)
            assertNull(reopened.manualWalkDao().getOngoing())
            reopened.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `concurrent sync and manual finish do not finalize the same walk inconsistently`() = runTest {
        assertTrue(repoAt(start).startManualWalk())
        fakeSource.addInterval(start, start + 60, 50)

        val autoCompleteInstant = start + 60 + StepRepository.MANUAL_WALK_INACTIVITY_TIMEOUT.seconds
        val racingRepo = repoAt(autoCompleteInstant)

        // Both share racingRepo's single syncMutex - whichever actually acquires it first runs to
        // completion before the other can start, so this exercises the serialized interleaving
        // rather than a true data race.
        val syncDeferred = async { racingRepo.syncNow() }
        val finishDeferred = async { racingRepo.finishManualWalk() }
        syncDeferred.await()
        finishDeferred.await()

        val finishedWalks = database.manualWalkDao().observeFinished().first()
        assertEquals(1, finishedWalks.size)
        val finished = finishedWalks.single()
        assertEquals(start + 60, finished.endEpochSecond)
        assertTrue(finished.autoCompleted)
        assertNull(database.manualWalkDao().getOngoing())
    }
}
