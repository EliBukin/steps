package com.example.stepsplit.ui.today

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.stepsource.RawStepInterval
import com.example.stepsplit.data.stepsource.StepSource
import android.os.Looper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodayViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fixedNow = Instant.parse("2026-03-10T20:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private lateinit var database: StepSplitDatabase
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Synchronous executors: Room's default query/transaction executors run on real
        // background threads, which race against the virtual TestDispatcher used below (a single
        // runCurrent() can return before that real thread has posted its result back). Running
        // everything on the calling thread makes DB access deterministic under the test scheduler.
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Drains both the coroutine test scheduler and Robolectric's real Android main looper - Room's coroutine Flow support touches the latter independently of the former. */
    private fun advance() {
        testDispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a failed finish-walk sync surfaces a one-shot error without ending the walk`() = runTest(testDispatcher) {
        val fakeSource = FakeStepSource()
        val failingSource = object : StepSource by fakeSource {
            override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> =
                throw IllegalStateException("simulated transient failure")
        }
        val repository = StepRepository(database, failingSource, settingsRepository, clock)
        repository.startManualWalk()

        val viewModel = TodayViewModel(repository, settingsRepository, clock)
        val collectorJob = launch { viewModel.uiState.collect {} }
        advance()

        // Not shown before any Finish attempt has actually failed.
        assertFalse(viewModel.uiState.value.finishWalkFailed)

        viewModel.finishManualWalk()
        advance()

        assertTrue(viewModel.uiState.value.finishWalkFailed)
        // The walk itself must still be ongoing - the failure must not silently finalize it.
        assertTrue(viewModel.uiState.value.hasOngoingManualWalk)

        // The UI "consumes" the message once it's actually been shown.
        viewModel.consumeFinishWalkFailure()
        advance()

        assertFalse(viewModel.uiState.value.finishWalkFailed)
        // Consuming the message must not touch the walk's own ongoing state.
        assertTrue(viewModel.uiState.value.hasOngoingManualWalk)

        collectorJob.cancel()
    }
}
