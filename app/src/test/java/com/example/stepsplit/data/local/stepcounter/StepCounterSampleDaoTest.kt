package com.example.stepsplit.data.local.stepcounter

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepCounterSampleDaoTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var dao: StepCounterSampleDao

    private fun sample(cumulativeSteps: Long, wallClockEpochMilli: Long, elapsedRealtimeMillis: Long = wallClockEpochMilli, bootSessionId: Long = 1) =
        StepCounterSampleEntity(
            cumulativeSteps = cumulativeSteps,
            elapsedRealtimeMillisAtSample = elapsedRealtimeMillis,
            wallClockEpochMilli = wallClockEpochMilli,
            bootSessionId = bootSessionId,
            receivedAtEpochMilli = wallClockEpochMilli,
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        dao = database.stepCounterSampleDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insertIgnoringDuplicate is idempotent on the same boot session and elapsed-realtime timestamp`() = runTest {
        val first = dao.insertIgnoringDuplicate(sample(cumulativeSteps = 10, wallClockEpochMilli = 1_000, elapsedRealtimeMillis = 5_000))
        val duplicate = dao.insertIgnoringDuplicate(sample(cumulativeSteps = 10, wallClockEpochMilli = 1_000, elapsedRealtimeMillis = 5_000))

        assertNotEquals(-1L, first)
        assertEquals(-1L, duplicate)
        assertEquals(1, dao.count())
    }

    @Test
    fun `getEarliestWallClockEpochMilli reports the true minimum across all stored samples`() = runTest {
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 10, wallClockEpochMilli = 5_000, elapsedRealtimeMillis = 1))
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 20, wallClockEpochMilli = 1_000, elapsedRealtimeMillis = 2))
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 30, wallClockEpochMilli = 9_000, elapsedRealtimeMillis = 3))

        assertEquals(1_000L, dao.getEarliestWallClockEpochMilli())
    }

    @Test
    fun `deleteOlderThan always protects the single earliest and single latest row, regardless of age`() = runTest {
        // All four rows are "old" relative to the cutoff below - without protection, all would be deleted.
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 0, wallClockEpochMilli = 1_000, elapsedRealtimeMillis = 1))
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 10, wallClockEpochMilli = 2_000, elapsedRealtimeMillis = 2))
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 20, wallClockEpochMilli = 3_000, elapsedRealtimeMillis = 3))
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 30, wallClockEpochMilli = 4_000, elapsedRealtimeMillis = 4))

        dao.deleteOlderThan(cutoffEpochMilli = 100_000)

        val remaining = dao.getAllOrdered().map { it.wallClockEpochMilli }.toSet()
        assertEquals("only the earliest (cutover anchor) and latest (next-delta baseline) rows survive", setOf(1_000L, 4_000L), remaining)
    }

    @Test
    fun `deleteOlderThan removes ordinary rows in between, keeping only what's newer than the cutoff plus the protected pair`() = runTest {
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 0, wallClockEpochMilli = 1_000, elapsedRealtimeMillis = 1))
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 10, wallClockEpochMilli = 2_000, elapsedRealtimeMillis = 2))
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 20, wallClockEpochMilli = 50_000, elapsedRealtimeMillis = 3))
        dao.insertIgnoringDuplicate(sample(cumulativeSteps = 30, wallClockEpochMilli = 60_000, elapsedRealtimeMillis = 4))

        dao.deleteOlderThan(cutoffEpochMilli = 10_000)

        val remaining = dao.getAllOrdered().map { it.wallClockEpochMilli }.toSet()
        // 1_000 survives only because it's the protected earliest row; 2_000 (also older than the
        // cutoff, but neither earliest nor latest) is genuinely deleted.
        assertEquals(setOf(1_000L, 50_000L, 60_000L), remaining)
    }

    @Test
    fun `an empty table's earliest sample is null, not an error`() = runTest {
        assertNull(dao.getEarliestWallClockEpochMilli())
    }
}
