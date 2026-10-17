package com.example.stepsplit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.stepsource.RawStepInterval
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.stepsource.StepSourceHealthStore
import com.example.stepsplit.domain.classification.ClassificationThresholds
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Leaving Settings with Back closes its ViewModel's scope. A change the owner already saved must
 * still be applied: new thresholds wait for any sync that is running (they share its lock), which
 * can take seconds on a 30-day catch-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneOffset.UTC)
    private lateinit var database: StepSplitDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        settings = SettingsRepository(context)
    }

    @After
    fun tearDown() = runTest {
        // Preferences DataStore keeps its file between tests.
        settings.resetThresholds()
        Dispatchers.resetMain()
        database.close()
    }

    /** A step source whose read waits for [release] - a sync still reading from Health Connect. */
    private class SlowStepSource(private val delegate: FakeStepSource = FakeStepSource()) : StepSource by delegate {
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> {
            reading.complete(Unit)
            release.await()
            return delegate.readSteps(fromInclusive, toExclusive)
        }
    }

    @Test
    fun `new thresholds are applied when Settings is left while a sync is still running`() = runTest {
        // Main runs at once, as Dispatchers.Main.immediate does on the phone.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val source = SlowStepSource()
        val steps = StepRepository(database, source, settings, clock, backgroundScope)
        val sync = launch(Dispatchers.Default) { steps.syncNow() }
        source.reading.await()
        val store = ViewModelStore()
        val viewModel = viewModelIn(store) { SettingsViewModel(settings, steps, StepSourceHealthStore(context), null, clock) }
        val changed = ClassificationThresholds.DEFAULT.copy(minSteps = 900)

        viewModel.setThresholds(changed)
        store.clear()
        source.release.complete(Unit)
        sync.join()

        withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { while (settings.settings.first().thresholds != changed) delay(10) } }
        assertEquals(changed, settings.settings.first().thresholds)
    }

    private fun <T : ViewModel> viewModelIn(store: ViewModelStore, create: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
        }
        @Suppress("UNCHECKED_CAST")
        return ViewModelProvider(store, factory)[ViewModel::class.java] as T
    }
}
