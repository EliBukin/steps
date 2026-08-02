package com.example.stepsplit.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.stepsource.RawStepInterval
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceReadException
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepSyncWorkerTest {

    private fun buildWorker(stepSource: StepSource): StepSyncWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        val repository = StepRepository(database, stepSource, SettingsRepository(context), Clock.systemUTC())
        return TestListenableWorkerBuilder<StepSyncWorker>(context)
            .setWorkerFactory(StepSyncWorkerFactory(repository))
            .build()
    }

    @Test
    fun `worker succeeds when a sync completes normally`() = runTest {
        val worker = buildWorker(FakeStepSource())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun `worker reports success rather than retry when permission is missing`() = runTest {
        val fakeSource = FakeStepSource().apply { setAvailability(StepSourceAvailability.PermissionNotGranted) }
        val worker = buildWorker(fakeSource)

        // A missing permission is not a transient failure - retrying will not fix it, so the
        // worker should not keep rescheduling itself with backoff for this case.
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun `worker requests a retry for a transient read failure`() = runTest {
        val failingSource = object : StepSource by FakeStepSource() {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> {
                // Models the Local Recording API delivering a non-success response status - a
                // transient condition worth retrying, unlike a missing permission or unavailable API.
                throw StepSourceReadException("simulated non-success status")
            }
        }
        val worker = buildWorker(failingSource)

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }
}
